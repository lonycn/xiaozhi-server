package xiaozhi.modules.realtimetool;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import liquibase.*;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import com.fasterxml.jackson.databind.*;
import xiaozhi.modules.agenttool.AgentToolExecutionCore;
import xiaozhi.modules.conversation.*;
import xiaozhi.modules.conversation.ConversationModels.*;
import xiaozhi.modules.externalidentity.*;
import xiaozhi.modules.externalidentity.ExternalModels.*;
import xiaozhi.modules.realtime.*;
import xiaozhi.modules.realtime.RealtimeModels.*;
import xiaozhi.modules.realtimetool.RealtimeToolModels.*;

/** New schema only, retained fixtures and real MySQL transactions; synthetic provider messages are not live voice evidence. */
@EnabledIfEnvironmentVariable(named="REALTIME_TOOL_TEST_JDBC_URL",matches=".+")
class RealtimeToolPersistenceTest {
    static JdbcTemplate db;static DataSourceTransactionManager manager;static ObjectMapper json;static AtomicLong owners=new AtomicLong(100);
    RealtimeStore store;RealtimeService realtime;RealtimeToolService tools;BlockingCore core;ConversationService conversations;MockEnvironment env;
    String conversation;Session session;Task task;long owner,sequence;long generation;ExternalIdentityService identity;
    AtomicReference<Principal> current=new AtomicReference<>();AtomicBoolean revoked=new AtomicBoolean();
    static <T>ObjectProvider<T> provider(Class<T> type,T instance){var beans=new DefaultListableBeanFactory();beans.registerSingleton("fixture",instance);return beans.getBeanProvider(type);}
    @BeforeAll static void initialize()throws Exception {
        String url=System.getenv("REALTIME_TOOL_TEST_JDBC_URL");
        if(!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/xiaozhi_realtime_tool_test_[a-z0-9_]+(\\?.*)?"))throw new IllegalArgumentException("Only a NEW loopback xiaozhi_realtime_tool_test_* schema is allowed");
        var source=new DriverManagerDataSource(url,"root",System.getenv("REALTIME_TOOL_TEST_DB_PASSWORD"));db=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);
        json=new xiaozhi.modules.security.config.WebMvcConfig().jackson2HttpMessageConverter().getObjectMapper();
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Long.class),"Refuse an existing populated schema; nothing is cleaned");
        db.execute("CREATE TABLE sys_user(id BIGINT PRIMARY KEY,status TINYINT NOT NULL,super_admin TINYINT NOT NULL DEFAULT 0,username VARCHAR(80))");
        db.execute("CREATE TABLE ai_agent(id VARCHAR(32) PRIMARY KEY,user_id BIGINT)");
        for(String name:List.of("conversation","support","ai-execution","agent-tools","realtime-ai","realtime-source-binding","realtime-vad","realtime-tools"))
            try(var connection=source.getConnection();var migration=new Liquibase("db/changelog/"+name+".changelog.yaml",new ClassLoaderResourceAccessor(),new JdbcConnection(connection))){migration.update(new Contexts());migration.update(new Contexts());}
        assertEquals(10,db.queryForObject("SELECT COUNT(*) FROM DATABASECHANGELOG",Long.class));
    }
    static class BlockingCore extends AgentToolExecutionCore {
        volatile boolean blocking;final CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);final AtomicInteger executions=new AtomicInteger();
        BlockingCore(){super(db,json);setEnabled(true);setTechnicalEnabled(true);}
        @Override public JsonNode execute(String name,JsonNode arguments,Context context){
            executions.incrementAndGet();if(blocking){started.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Bounded fixture wait");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("Fixture interrupted");}}
            return super.execute(name,arguments,context);
        }
    }
    @BeforeEach void fixture(){
        owner=owners.incrementAndGet();db.update("INSERT INTO sys_user VALUES(?,1,0,?)",owner,"rt-tool-test-"+owner);
        store=new RealtimeStore(db,manager);core=new BlockingCore();
        env=new MockEnvironment().withProperty("features.realtime-ai-enabled","true").withProperty("features.realtime-tools-enabled","true").withProperty("features.agent-tools-enabled","true")
                .withProperty("agent-tools.technical-local-enabled","true").withProperty("realtime.worker.key","independent-rt-tools-test-worker-key-32").withProperty("realtime.worker.public-url","ws://fixture.invalid/realtime");
        tools=new RealtimeToolService(store,json,env,core);realtime=new RealtimeService(store,json,env);realtime.setRealtimeTools(provider(RealtimeToolService.class,tools));
        conversations=new ConversationService(new ConversationDao(db),json,manager);conversations.setRealtime(realtime);realtime.setConversations(provider(ConversationService.class,conversations));
        identity=mock(ExternalIdentityService.class);when(identity.authenticateHash(anyString())).thenAnswer(invocation->{var value=current.get();
            if(revoked.get() || value==null || !value.tokenHash().equals(invocation.getArgument(0)))throw new ExternalProblem(401,"EXTERNAL_TOKEN_REVOKED_OR_EXPIRED");return value;});
        core.setIdentity(identity);tools.setIdentity(identity);conversation=conversations.create(owner,new CreateConversation("Independent realtime tools",null)).id();
    }
    @AfterEach void retainAllFixtures(){
        core.release.countDown();revoked.set(false);env.withProperty("features.realtime-tools-enabled","true");
        if(session!=null)store.tx(()->{store.conversation(conversation);realtime.cancelInConversation(conversation,"FIXTURE_FINISHED");return null;});
        assertEquals(0,count("SELECT COUNT(*) FROM ai_run WHERE conversation_id=?",conversation),"Realtime tools must never fabricate ordinary ai_run rows");
    }
    void connect(boolean external){
        if(external)env.withProperty("features.realtime-tools-enabled","false");
        session=realtime.create(owner,new Create(conversation,"session-"+UUID.randomUUID(),new Consent(true,false)));
        if(external){
            env.withProperty("features.external-realtime-media-enabled","true");
            var p=new Principal(owner,"a".repeat(64),owner,owner,"xiaozhi-test","subject-"+owner,new Tenant("company","1"),new ExternalModels.Scope(List.of(),List.of()),Instant.now().plusSeconds(300));current.set(p);
            var authority=mock(ExternalMediaAuthority.class);when(authority.validate(anyMap(),eq("owner_user_id"))).thenAnswer(invocation->{if(revoked.get())throw new ExternalProblem(403,"EXTERNAL_MEDIA_BINDING_DENIED");return current.get().expiresAt();});realtime.setExternal(authority);
            db.update("UPDATE ai_realtime_session SET external_source_id=?,external_token_id=? WHERE id=?",p.sourceId(),p.tokenId(),session.id());
            env.withProperty("features.realtime-tools-enabled","true");store.tx(()->{store.conversation(conversation);tools.prepare(store.session(session.id(),true),p);return null;});
        }
        var ticket=realtime.ticket(owner,session.id(),new Epoch(session.controlEpoch()));task=realtime.consume(new Consume(ticket.ticket(),"fixture-worker","fixture-server"));
        generation=task.turnGeneration()==null?0:task.turnGeneration();emit("session.ready",null,null,null,null,null,null,null,null);
    }
    Receipt emit(String type,String response,String text,String finish,Usage usage,String call,String item,String request,String continuation){
        Long gen=type.startsWith("response.")||type.equals("input.started")||type.equals("tool.result.ack")?generation:null;
        var event=new ProviderEvent(task.leaseToken(),sequence+1,"event-"+(sequence+1),type,type.equals("session.ready")?"provider-session":null,item,response,null,null,text,finish,usage,null,request,gen,call,continuation);
        var receipt=realtime.providerEvent(session.id(),event);sequence++;if(receipt.turnGeneration()!=null)generation=receipt.turnGeneration();return receipt;
    }
    void waiting(boolean external){connect(external);emit("input.started",null,null,null,null,null,null,null,null);emit("response.started","response1",null,null,null,null,null,null,null);emit("response.step.completed","response1",null,"tool_calls",new Usage(2L,3L,5L),null,null,null,null);}
    Proposal proposal(String call,String arguments)throws Exception{return new Proposal(task.leaseToken(),task.controlEpoch(),generation,"provider-session","response1",1,List.of(new Call("function-item-"+call,call,"server_capabilities",json.readTree(arguments))));}
    long count(String sql,Object...args){return db.queryForObject(sql,Long.class,args);}
    @Test void ackThenAuthorizedContinuationKeepsOneLogicalAssistantAndRealStepUsage()throws Exception {
        waiting(false);String message=db.queryForObject("SELECT message_id FROM ai_realtime_turn WHERE session_id=? AND kind='assistant'",String.class,session.id());
        assertEquals("streaming",db.queryForObject("SELECT status FROM ai_message WHERE id=?",String.class,message));
        var body=proposal("call1","{}");var result=tools.proposals(session.id(),body);assertEquals("succeeded",result.results().getFirst().status());assertEquals(result,tools.proposals(session.id(),body));assertEquals(1,core.executions.get());
        assertEquals(409,assertThrows(ConversationProblem.class,()->emit("response.continue","response1",null,null,null,null,null,null,null)).status());
        var call=result.results().getFirst();emit("tool.result.ack","response1",null,null,null,call.providerCallId(),"provider-reassigned-result",call.resultItemId(),null);
        var receipt=emit("response.continue","response1",null,null,null,null,null,null,null);assertNotNull(receipt.continuationId());
        emit("response.started","response2",null,null,null,null,null,null,receipt.continuationId());
        emit("response.delta","response2","Technical result",null,null,null,null,null,null);
        emit("response.completed","response2","Technical result","stop",new Usage(7L,8L,15L),null,null,null,null);
        assertEquals(1,count("SELECT COUNT(*) FROM ai_realtime_turn WHERE session_id=? AND kind='assistant'",session.id()));
        assertEquals(2,count("SELECT COUNT(*) FROM ai_realtime_provider_response WHERE session_id=?",session.id()));
        var aggregate=json.readTree(db.queryForObject("SELECT usage_json FROM ai_realtime_turn WHERE session_id=? AND kind='assistant'",String.class,session.id()));
        assertEquals(9,aggregate.path("input_tokens").asInt());assertEquals(20,aggregate.path("total_tokens").asInt());
        assertEquals("completed",db.queryForObject("SELECT status FROM ai_message WHERE id=?",String.class,message));
        realtime.end(owner,session.id(),new Epoch(task.controlEpoch()));
        assertEquals("continued",db.queryForObject("SELECT status FROM ai_realtime_provider_response WHERE session_id=? AND response_id='response1'",String.class,session.id()),"Ending a session must preserve a step that was actually used by a continuation");
    }
    @Test void inputInvalidatesSlowToolWithoutBlockingHeartbeatAndLateResultIsDiscarded()throws Exception {
        waiting(false);core.blocking=true;var body=proposal("slow","{}");
        try(var pool=Executors.newSingleThreadExecutor()){
            var pending=pool.submit(()->tools.proposals(session.id(),body));assertTrue(core.started.await(5,TimeUnit.SECONDS));
            long start=System.nanoTime();assertTrue(realtime.heartbeat(session.id(),new Heartbeat(task.leaseToken(),true)).active());assertTrue((System.nanoTime()-start)/1000000<2000,"IO must not hold the session transaction");
            long old=generation;emit("input.started",null,null,null,null,null,null,null,null);assertEquals(old+1,generation);
            core.release.countDown();assertThrows(ExecutionException.class,()->pending.get(5,TimeUnit.SECONDS));
        }
        assertEquals("discarded",db.queryForObject("SELECT status FROM ai_realtime_tool_call WHERE session_id=?",String.class,session.id()));
        assertEquals(0,count("SELECT COUNT(*) FROM ai_realtime_tool_call WHERE session_id=? AND result_json IS NOT NULL",session.id()));
        assertEquals("canceled",db.queryForObject("SELECT status FROM ai_realtime_turn WHERE session_id=? AND kind='assistant'",String.class,session.id()));
    }
    @Test void sameExactBearerRenewalWorksButReplacementTokenCannotTakeOverOldSession()throws Exception {
        waiting(true);var p=current.get();current.set(new Principal(p.tokenId(),p.tokenHash(),p.sourceId(),p.userId(),p.issuer(),p.subject(),p.tenant(),p.scope(),p.expiresAt().plusSeconds(300)));
        var body=proposal("renewed","{}");assertEquals("succeeded",tools.proposals(session.id(),body).results().getFirst().status());
        current.set(new Principal(p.tokenId()+1,"b".repeat(64),p.sourceId(),p.userId(),p.issuer(),p.subject(),p.tenant(),p.scope(),p.expiresAt().plusSeconds(300)));
        assertThrows(ConversationProblem.class,()->tools.proposals(session.id(),body));
    }
    @Test void sourceRevokedDuringIoRetainsAttemptAndDiscardsBusinessContextResult()throws Exception {
        waiting(true);core.blocking=true;var body=proposal("source-revoked","{}");
        try(var pool=Executors.newSingleThreadExecutor()){
            var pending=pool.submit(()->tools.proposals(session.id(),body));assertTrue(core.started.await(5,TimeUnit.SECONDS));revoked.set(true);core.release.countDown();assertThrows(ExecutionException.class,()->pending.get(5,TimeUnit.SECONDS));
        }
        assertEquals(1,count("SELECT COUNT(*) FROM ai_realtime_tool_attempt a JOIN ai_realtime_tool_call c ON c.id=a.call_id WHERE c.session_id=?",session.id()));
        assertEquals("discarded",db.queryForObject("SELECT status FROM ai_realtime_tool_call WHERE session_id=?",String.class,session.id()));
        assertEquals(0,count("SELECT COUNT(*) FROM ai_realtime_tool_call WHERE session_id=? AND result_json IS NOT NULL",session.id()));
    }
    @Test void strictArgumentsConflictingCallsAndLeaseOrEpochReplaysCannotDispatch()throws Exception {
        waiting(false);assertThrows(ConversationProblem.class,()->tools.proposals(session.id(),proposal("bad","{\"user_id\":\"invented\"}")));
        var body=proposal("good","{}");tools.proposals(session.id(),body);
        var conflicting=new Proposal(body.leaseToken(),body.controlEpoch(),body.turnGeneration(),body.providerSessionId(),body.responseId(),1,List.of(new Call("different-item","good","server_capabilities",json.createObjectNode())));
        assertThrows(ConversationProblem.class,()->tools.proposals(session.id(),conflicting));
        assertThrows(ConversationProblem.class,()->tools.proposals(session.id(),new Proposal("b".repeat(64),body.controlEpoch(),body.turnGeneration(),body.providerSessionId(),body.responseId(),1,body.calls())));
        assertThrows(ConversationProblem.class,()->tools.proposals(session.id(),new Proposal(body.leaseToken(),body.controlEpoch()+1,body.turnGeneration(),body.providerSessionId(),body.responseId(),1,body.calls())));
        assertEquals(1,core.executions.get());
    }
    @Test void ackIdentityAndReplayAreBoundToJavaResultAndMatchingProviderResponse()throws Exception {
        waiting(false);var call=tools.proposals(session.id(),proposal("ack","{}")).results().getFirst();
        assertThrows(ConversationProblem.class,()->emit("tool.result.ack","response1",null,null,null,call.providerCallId(),"provider-item","wrong-result",null));
        emit("tool.result.ack","response1",null,null,null,call.providerCallId(),"provider-item",call.resultItemId(),null);
        assertThrows(ConversationProblem.class,()->emit("tool.result.ack","response1",null,null,null,call.providerCallId(),"different-provider-item",call.resultItemId(),null));
        long old=generation;emit("input.started",null,null,null,null,null,null,null,null);assertTrue(generation>old);
        assertThrows(ConversationProblem.class,()->emit("response.continue","response1",null,null,null,null,null,null,null));
    }
    @Test void sessionEndAndFeatureOffFenceFinishedResultsAndContinuation()throws Exception {
        waiting(false);var body=proposal("finished","{}");tools.proposals(session.id(),body);env.withProperty("features.realtime-tools-enabled","false");
        assertThrows(ConversationProblem.class,()->tools.proposals(session.id(),body));assertThrows(ConversationProblem.class,()->realtime.heartbeat(session.id(),new Heartbeat(task.leaseToken(),true)));
        env.withProperty("features.realtime-tools-enabled","true");realtime.end(owner,session.id(),new Epoch(task.controlEpoch()));assertThrows(ConversationProblem.class,()->tools.proposals(session.id(),body));
    }
    @Test void unknownStepUsageIsNotFilledWithZeroInLogicalAggregate()throws Exception {
        connect(false);emit("input.started",null,null,null,null,null,null,null,null);emit("response.started","response1",null,null,null,null,null,null,null);emit("response.step.completed","response1",null,"tool_calls",null,null,null,null,null);
        var call=tools.proposals(session.id(),proposal("unknown-usage","{}")).results().getFirst();emit("tool.result.ack","response1",null,null,null,call.providerCallId(),"echo-unknown",call.resultItemId(),null);
        var continuation=emit("response.continue","response1",null,null,null,null,null,null,null).continuationId();emit("response.started","response2",null,null,null,null,null,null,continuation);
        emit("response.completed","response2","Known final answer","stop",new Usage(2L,1L,3L),null,null,null,null);
        assertNull(db.queryForObject("SELECT usage_json FROM ai_realtime_turn WHERE session_id=? AND kind='assistant'",String.class,session.id()));
        assertEquals(1,count("SELECT COUNT(*) FROM ai_realtime_response_usage u JOIN ai_realtime_provider_response r ON r.id=u.response_row_id WHERE r.session_id=? AND u.total_tokens IS NULL",session.id()));
    }
    @Test void duplicateInputEventDoesNotAdvanceGenerationTwiceAndZeroToolWireStaysLegacy()throws Exception {
        connect(false);var event=new ProviderEvent(task.leaseToken(),sequence+1,"input-once","input.started",null,null,null,null,null,null,null,null,null,null,generation,null,null);
        var first=realtime.providerEvent(session.id(),event);var duplicate=realtime.providerEvent(session.id(),event);assertEquals(first.turnGeneration(),duplicate.turnGeneration());sequence++;generation=first.turnGeneration();
        env.withProperty("features.realtime-tools-enabled","false");String other=conversations.create(owner,new CreateConversation("Legacy no tools",null)).id();
        var old=realtime.create(owner,new Create(other,"zero-tools",new Consent(true,false)));var ticket=realtime.ticket(owner,old.id(),new Epoch(old.controlEpoch()));var legacy=realtime.consume(new Consume(ticket.ticket(),"fixture-legacy","fixture-server"));
        var wire=json.valueToTree(legacy);assertFalse(wire.has("tools"));assertFalse(wire.has("tool_policy"));assertFalse(wire.has("turn_generation"));
        store.tx(()->{store.conversation(other);realtime.cancelInConversation(other,"FIXTURE_FINISHED");return null;});
    }
    @Test void fourthToolStepSavesActualUsageBeforeBudgetTerminal()throws Exception {
        connect(false);emit("input.started",null,null,null,null,null,null,null,null);emit("response.started","response1",null,null,null,null,null,null,null);
        for(int round=1;round<=3;round++){
            String response="response"+round;emit("response.step.completed",response,null,"tool_calls",new Usage(1L,1L,2L),null,null,null,null);
            var body=new Proposal(task.leaseToken(),task.controlEpoch(),generation,"provider-session",response,round,List.of(new Call("item-"+round,"call-"+round,"server_capabilities",json.createObjectNode())));
            var call=tools.proposals(session.id(),body).results().getFirst();emit("tool.result.ack",response,null,null,null,call.providerCallId(),"echo-"+round,call.resultItemId(),null);
            String continuation=emit("response.continue",response,null,null,null,null,null,null,null).continuationId();emit("response.started","response"+(round+1),null,null,null,null,null,null,continuation);
        }
        var terminal=emit("response.step.completed","response4",null,"tool_calls",new Usage(100L,100L,200L),null,null,null,null);
        assertEquals("failed",terminal.session().status());assertEquals("TOOL_LIMIT_EXCEEDED",terminal.session().errorCode());
        assertEquals(4,count("SELECT COUNT(*) FROM ai_realtime_response_usage u JOIN ai_realtime_provider_response r ON r.id=u.response_row_id WHERE r.session_id=?",session.id()));
        assertEquals(206,count("SELECT SUM(u.total_tokens) FROM ai_realtime_response_usage u JOIN ai_realtime_provider_response r ON r.id=u.response_row_id WHERE r.session_id=?",session.id()));
        assertEquals(3,core.executions.get());
    }
    @Test void overBudgetFinalUsageIsRetainedAndFinalTextIsNotPublished(){
        connect(false);emit("input.started",null,null,null,null,null,null,null,null);emit("response.started","response1",null,null,null,null,null,null,null);
        var terminal=emit("response.completed","response1","must not publish over budget","stop",new Usage(60000L,50000L,110000L),null,null,null,null);
        assertEquals("failed",terminal.session().status());assertEquals(110000,count("SELECT SUM(u.total_tokens) FROM ai_realtime_response_usage u JOIN ai_realtime_provider_response r ON r.id=u.response_row_id WHERE r.session_id=?",session.id()));
        assertEquals("canceled",db.queryForObject("SELECT status FROM ai_message WHERE id=?",String.class,terminal.messageId()));
        assertFalse(db.queryForObject("SELECT parts_json FROM ai_message WHERE id=?",String.class,terminal.messageId()).contains("must not publish"));
    }
    @Test void concurrentSameProviderCallCannotDispatchTwice()throws Exception {
        waiting(false);core.blocking=true;var body=proposal("concurrent","{}");
        try(var pool=Executors.newFixedThreadPool(2)){
            var first=pool.submit(()->tools.proposals(session.id(),body));assertTrue(core.started.await(5,TimeUnit.SECONDS));
            var second=pool.submit(()->tools.proposals(session.id(),body));var denied=assertThrows(ExecutionException.class,()->second.get(3,TimeUnit.SECONDS));assertInstanceOf(ConversationProblem.class,denied.getCause());
            core.release.countDown();assertEquals("succeeded",first.get(5,TimeUnit.SECONDS).results().getFirst().status());
        }
        assertEquals(1,core.executions.get());assertEquals(1,count("SELECT COUNT(*) FROM ai_realtime_tool_attempt a JOIN ai_realtime_tool_call c ON c.id=a.call_id WHERE c.session_id=?",session.id()));
    }
    @Test void textInjectionAdvancesServerGenerationAndOldAckCannotAuthorizeContinuation()throws Exception {
        waiting(false);var call=tools.proposals(session.id(),proposal("before-text","{}")).results().getFirst();long old=generation;
        conversations.send(owner,conversation,new SendMessage("new-text",List.of(new MessagePart("text","new authoritative user input",null,null,null,null,null,null))));
        var busy=realtime.heartbeat(session.id(),new Heartbeat(task.leaseToken(),true));assertEquals(old+1,busy.turnGeneration());generation=busy.turnGeneration();
        assertTrue(busy.injections().isEmpty());assertEquals(1,realtime.heartbeat(session.id(),new Heartbeat(task.leaseToken(),false)).injections().size());
        assertThrows(ConversationProblem.class,()->emit("tool.result.ack","response1",null,null,null,call.providerCallId(),"late-echo",call.resultItemId(),null));
    }
    @Test void endingSessionDuringIoClosesPendingAttemptWithoutReleasingLateData()throws Exception {
        waiting(false);core.blocking=true;var body=proposal("end-during-io","{}");
        try(var pool=Executors.newSingleThreadExecutor()){
            var pending=pool.submit(()->tools.proposals(session.id(),body));assertTrue(core.started.await(5,TimeUnit.SECONDS));realtime.end(owner,session.id(),new Epoch(task.controlEpoch()));
            core.release.countDown();assertThrows(ExecutionException.class,()->pending.get(5,TimeUnit.SECONDS));
        }
        assertEquals("discarded",db.queryForObject("SELECT status FROM ai_realtime_tool_call WHERE session_id=?",String.class,session.id()));
        assertEquals(0,count("SELECT COUNT(*) FROM ai_realtime_tool_call WHERE session_id=? AND result_json IS NOT NULL",session.id()));
    }
    @Test void workerLeaseExpiryDuringToolIoReleasesSessionAndDiscardsLateResult()throws Exception {
        waiting(false);core.blocking=true;var body=proposal("expired-io","{}");try(var pool=Executors.newSingleThreadExecutor()){var pending=pool.submit(()->tools.proposals(session.id(),body));assertTrue(core.started.await(5,TimeUnit.SECONDS));db.update("UPDATE ai_realtime_session SET lease_expires_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",session.id());realtime.maintain();assertEquals("expired",realtime.get(owner,session.id()).status());core.release.countDown();assertThrows(ExecutionException.class,()->pending.get(5,TimeUnit.SECONDS));}
        assertEquals("discarded",db.queryForObject("SELECT status FROM ai_realtime_tool_call WHERE session_id=?",String.class,session.id()));assertEquals(0,count("SELECT COUNT(*) FROM ai_realtime_tool_call WHERE session_id=? AND result_json IS NOT NULL",session.id()));assertEquals(1,count("SELECT COUNT(*) FROM ai_realtime_tool_attempt a JOIN ai_realtime_tool_call c ON c.id=a.call_id WHERE c.session_id=?",session.id()));assertEquals(5,count("SELECT SUM(u.total_tokens) FROM ai_realtime_response_usage u JOIN ai_realtime_provider_response r ON r.id=u.response_row_id WHERE r.session_id=?",session.id()));
    }
    @Test void lateProviderResultAckCannotContinueAndDoesNotEraseActualStepUsage()throws Exception {
        waiting(false);var result=tools.proposals(session.id(),proposal("late-ack","{}")).results().getFirst();db.update("UPDATE ai_realtime_tool_call SET finished_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 31 SECOND) WHERE session_id=?",session.id());
        assertThrows(ConversationProblem.class,()->emit("tool.result.ack","response1",null,null,null,result.providerCallId(),"late-echo",result.resultItemId(),null));assertThrows(ConversationProblem.class,()->emit("response.continue","response1",null,null,null,null,null,null,null));assertEquals(0,count("SELECT COUNT(*) FROM ai_realtime_tool_call WHERE session_id=? AND acknowledged_at IS NOT NULL",session.id()));assertEquals(5,count("SELECT SUM(u.total_tokens) FROM ai_realtime_response_usage u JOIN ai_realtime_provider_response r ON r.id=u.response_row_id WHERE r.session_id=?",session.id()));
    }
    @Test void unknownUsageStillHasAFiniteProviderStepBudgetAcrossLongConversation()throws Exception {
        connect(false);for(int i=1;i<=32;i++){emit("input.started",null,null,null,null,null,null,null,null);emit("response.started","bounded-response-"+i,null,null,null,null,null,null,null);emit("response.completed","bounded-response-"+i,"Published synthetic provider turn "+i,"stop",null,null,null,null,null);}
        emit("input.started",null,null,null,null,null,null,null,null);assertThrows(ConversationProblem.class,()->emit("response.started","over-budget",null,null,null,null,null,null,null));assertEquals(32,count("SELECT COUNT(*) FROM ai_realtime_provider_response WHERE session_id=?",session.id()));assertEquals(32,count("SELECT COUNT(*) FROM ai_realtime_response_usage u JOIN ai_realtime_provider_response r ON r.id=u.response_row_id WHERE r.session_id=? AND u.total_tokens IS NULL",session.id()));assertEquals(32,count("SELECT COUNT(*) FROM ai_message WHERE conversation_id=? AND sender_type='assistant' AND status='completed'",conversation));assertEquals(0,count("SELECT COUNT(*) FROM ai_realtime_turn WHERE session_id=? AND active_slot=1",session.id()));
    }

}
