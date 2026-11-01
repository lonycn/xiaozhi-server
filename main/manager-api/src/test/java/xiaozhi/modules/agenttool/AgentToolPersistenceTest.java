package xiaozhi.modules.agenttool;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import liquibase.*;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import xiaozhi.modules.ai.*;
import xiaozhi.modules.ai.AiModels.*;
import xiaozhi.modules.agenttool.AgentToolModels.*;
import xiaozhi.modules.conversation.*;
import xiaozhi.modules.conversation.ConversationModels.*;
import xiaozhi.modules.externalidentity.*;
import xiaozhi.modules.externalidentity.ExternalModels.*;

/** Fresh schema is required. Fixtures are retained: no DROP, TRUNCATE, DELETE or production schema access. */
@EnabledIfEnvironmentVariable(named="AGENT_TOOL_TEST_JDBC_URL",matches=".+")
class AgentToolPersistenceTest {
    static JdbcTemplate db;static DataSourceTransactionManager manager;static ObjectMapper json;
    static final AtomicLong users=new AtomicLong(100);
    AgentToolService tools;AiExecutionService ai;ConversationService conversations;
    long owner;String conversation,runId;Task task;
    @BeforeAll static void initialize()throws Exception {
        String url=System.getenv("AGENT_TOOL_TEST_JDBC_URL");
        if(!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/xiaozhi_agent_tool_test_[a-z0-9_]+(\\?.*)?"))throw new IllegalArgumentException("A NEW loopback xiaozhi_agent_tool_test_* schema is mandatory");
        var source=new DriverManagerDataSource(url,"root",System.getenv("AGENT_TOOL_TEST_DB_PASSWORD"));
        db=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);json=JsonMapper.builder().addModule(new JavaTimeModule()).build();
        if(db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Long.class)!=0)throw new IllegalStateException("Test schema must be newly created and empty");
        db.execute("CREATE TABLE sys_user(id BIGINT PRIMARY KEY,status TINYINT NOT NULL,super_admin TINYINT NOT NULL DEFAULT 0,username VARCHAR(80))");
        db.execute("CREATE TABLE ai_agent(id VARCHAR(32) PRIMARY KEY,user_id BIGINT)");
        for(String name:List.of("conversation","support","ai-execution","agent-tools")) {
            try(var connection=source.getConnection();var migration=new Liquibase("db/changelog/"+name+".changelog.yaml",new ClassLoaderResourceAccessor(),new JdbcConnection(connection))) {
                migration.update(new Contexts());migration.update(new Contexts());
            }
        }
        assertEquals(4,db.queryForObject("SELECT COUNT(*) FROM DATABASECHANGELOG",Long.class));
    }
    @BeforeEach void fixture() {
        owner=users.incrementAndGet();db.update("INSERT INTO sys_user(id,status,username) VALUES(?,1,?)",owner,"agent-test-"+owner);
        ai=new AiExecutionService(db,json,manager);ai.setEnabled(true);
        conversations=new ConversationService(new ConversationDao(db),json,manager);conversations.setAiExecution(ai);
        tools=new AgentToolService(db,json,manager);tools.setEnabled(true);tools.setTechnicalEnabled(true);
        conversation=conversations.create(owner,new CreateConversation("isolated tool test",null)).id();
        conversations.send(owner,conversation,new SendMessage("question",List.of(new MessagePart("text","protocol test",null,null,null,null,null,null))));
        runId=ai.list(owner,conversation,0,10).items().getFirst().runId();
    }
    @AfterEach void retainFixturesAndCloseOwnRun() {
        ai.cancel(owner,conversation,runId);
    }
    void claim(Principal principal) {tools.prepareRun(runId,conversation,owner,principal);task=ai.claim(new Claim("agent-test-worker"));assertEquals(runId,task.runId());}
    Proposal proposal(int round,String callId,String name,String arguments)throws Exception {
        return new Proposal(task.leaseToken(),task.attempt(),task.controlEpoch(),round,List.of(new Call(callId,name,json.readTree(arguments))));
    }
    long count(String sql,Object...args){return db.queryForObject(sql,Long.class,args);}
    Principal external() {
        var principal=new Principal(owner,"a".repeat(64),owner,owner,"xiaozhi-test","external-user-"+owner,
                new Tenant("organization","tenant-"+owner),new ExternalModels.Scope(List.of("project1"),List.of("dev1")),Instant.now().plusSeconds(300));
        var identity=mock(ExternalIdentityService.class);when(identity.authenticateHash(principal.tokenHash())).thenReturn(principal);tools.setIdentity(identity);return principal;
    }
    @Test void localOptInExecutesAndDedupeDoesNotRepeatAttempt()throws Exception {
        claim(null);assertEquals(1,tools.taskTools(runId,task.attempt(),task.leaseToken(),task.controlEpoch()).tools().size());
        var request=proposal(1,"call1","server_capabilities","{}");var first=tools.submitCalls(runId,request);
        assertEquals("succeeded",first.results().getFirst().status());assertTrue(first.results().getFirst().output().path("technical_acceptance_only").asBoolean());
        assertEquals(first,tools.submitCalls(runId,request));assertEquals(1,count("SELECT COUNT(*) FROM agent_tool_attempt WHERE call_id IN(SELECT id FROM agent_tool_call WHERE run_id=?)",runId));
        assertEquals(2,tools.history(owner,conversation,0,20).items().size());
        long other=users.incrementAndGet();db.update("INSERT INTO sys_user(id,status) VALUES(?,1)",other);
        assertThrows(ConversationProblem.class,()->tools.history(other,conversation,0,20));
        tools.setTechnicalEnabled(false);assertThrows(ConversationProblem.class,()->tools.submitCalls(runId,request));
        tools.setEnabled(false);assertThrows(ConversationProblem.class,()->tools.validatePublish(runId,task.attempt(),task.leaseToken(),task.controlEpoch()));
    }
    @Test void disabledFeatureAndMissingBindingAreClosed() {
        tools.setEnabled(false);assertTrue(tools.taskTools(runId,1,"a".repeat(64),1).tools().isEmpty());
        tools.setEnabled(true);task=ai.claim(new Claim("agent-test-worker"));
        assertThrows(ConversationProblem.class,()->tools.taskTools(runId,task.attempt(),task.leaseToken(),task.controlEpoch()));
    }
    @Test void noBusinessAdapterNeverSynthesizesDeviceOrOrder()throws Exception {
        claim(null);
        var result=tools.submitCalls(runId,proposal(1,"device1","get_device","{\"device_id\":\"dev1\"}"));
        assertEquals("BUSINESS_NOT_CONFIGURED",result.results().getFirst().errorCode());
        assertFalse(result.results().getFirst().output().has("data"));
        assertThrows(ConversationProblem.class,()->tools.submitCalls(runId,proposal(1,"device1","get_device","{\"device_id\":\"dev2\"}")));
    }
    @Test void roundCallAndTimeBudgetsAreDurable()throws Exception {
        claim(null);
        assertThrows(ConversationProblem.class,()->tools.submitCalls(runId,proposal(2,"skip","server_capabilities","{}")));
        List<Call> calls=new ArrayList<>();for(int i=0;i<6;i++)calls.add(new Call("budget"+i,"server_capabilities",json.readTree("{}")));
        tools.submitCalls(runId,new Proposal(task.leaseToken(),task.attempt(),task.controlEpoch(),1,calls));
        assertThrows(ConversationProblem.class,()->tools.submitCalls(runId,proposal(2,"seventh","server_capabilities","{}")));
        assertEquals(6,count("SELECT COUNT(*) FROM agent_tool_call WHERE run_id=?",runId));
    }
    @Test void epochAttemptLeaseAndCancellationRejectLateProposals()throws Exception {
        claim(null);var request=proposal(1,"fenced","server_capabilities","{}");
        assertThrows(ConversationProblem.class,()->tools.submitCalls(runId,new Proposal("b".repeat(64),task.attempt(),task.controlEpoch(),1,request.calls())));
        assertThrows(ConversationProblem.class,()->tools.submitCalls(runId,new Proposal(task.leaseToken(),task.attempt()+1,task.controlEpoch(),1,request.calls())));
        assertThrows(ConversationProblem.class,()->tools.submitCalls(runId,new Proposal(task.leaseToken(),task.attempt(),task.controlEpoch()+1,1,request.calls())));
        ai.cancel(owner,conversation,runId);assertThrows(ConversationProblem.class,()->tools.submitCalls(runId,request));
        assertEquals(0,count("SELECT COUNT(*) FROM agent_tool_call WHERE run_id=?",runId));
    }
    @Test void exactBearerCapturedNotReplacementOrOwnerFallback()throws Exception {
        Principal principal=external();claim(principal);
        var identity=mock(ExternalIdentityService.class);
        when(identity.authenticateHash(principal.tokenHash())).thenThrow(new ExternalProblem(401,"EXTERNAL_TOKEN_REVOKED_OR_EXPIRED"));tools.setIdentity(identity);
        assertThrows(ConversationProblem.class,()->tools.validatePublish(runId,task.attempt(),task.leaseToken(),task.controlEpoch()));
        assertEquals(1,count("SELECT COUNT(*) FROM agent_tool_run_binding WHERE run_id=? AND principal_json IS NOT NULL",runId));
    }
    @Test void releasedStaffSeesOnlyImmutableAuditBeforeItsEventCutoff()throws Exception {
        claim(null);tools.submitCalls(runId,proposal(1,"first","server_capabilities","{}"));
        long cutoff=count("SELECT MIN(event_sequence) FROM agent_tool_audit WHERE conversation_id=?",conversation);
        long staff=users.incrementAndGet();db.update("INSERT INTO sys_user(id,status) VALUES(?,1)",staff);
        db.update("INSERT INTO support_agent_profile(user_id,role) VALUES(?,'support_agent')",staff);
        String request=UUID.randomUUID().toString().replace("-","");
        db.update("INSERT INTO support_request(id,conversation_id,client_request_id,request_hash,status) VALUES(?,?,? ,?,'resolved')",request,conversation,"fixture-"+staff,"a".repeat(64));
        db.update("INSERT INTO support_assignment(id,request_id,conversation_id,agent_user_id,released_at,read_through_seq,read_through_event_seq) VALUES(?,?,?,?,CURRENT_TIMESTAMP(3),2,?)",
                UUID.randomUUID().toString().replace("-",""),request,conversation,staff,cutoff);
        tools.submitCalls(runId,proposal(2,"later","server_capabilities","{}"));
        var historical=tools.history(staff,conversation,0,20);assertEquals(1,historical.items().size());
        assertEquals("running",historical.items().getFirst().status(),"A completed call must not rewrite released staff's past snapshot");
        assertEquals(4,tools.history(owner,conversation,0,20).items().size());
        assertTrue(tools.history(staff,conversation,historical.nextCursor(),20).items().isEmpty());
    }
    @Test void successfulBusinessFixtureIsWhitelistedAndSourceConfigChangesFenceReuse()throws Exception {
        Principal principal=external();var settings=mock(ExternalBusinessSettings.class);
        when(settings.source(principal.issuer())).thenReturn(new ExternalBusinessSettings.Source(java.net.URI.create("https://business.example/read"),"key",new byte[32]));tools.setSettings(settings);
        var adapter=mock(ExternalBusinessHttpAdapter.class);@SuppressWarnings("unchecked") ObjectProvider<ExternalBusinessAdapter> providers=mock(ObjectProvider.class);
        when(providers.getIfAvailable()).thenReturn(adapter);tools.setAdapters(providers);
        when(adapter.read(any(),any())).thenReturn(new ExternalBusinessAdapter.Evidence(principal.issuer(),principal.tenant().type(),principal.tenant().id(),"device","dev1",Instant.now(),
                json.readTree("{\"record\":{\"name\":\"test fixture only\",\"status\":\"ok\",\"secret\":\"must-not-escape\",\"url\":\"https://invalid.example\"}}")));
        claim(principal);var request=proposal(1,"real-adapter-fixture","get_device","{\"device_id\":\"dev1\"}");
        var result=tools.submitCalls(runId,request).results().getFirst();
        assertEquals("succeeded",result.status());assertEquals("ok",result.output().path("data").path("record").path("status").asText());
        assertFalse(result.output().toString().contains("must-not-escape"));assertFalse(result.output().toString().contains("https://invalid.example"));
        when(settings.source(principal.issuer())).thenReturn(new ExternalBusinessSettings.Source(java.net.URI.create("https://changed.example/read"),"key",new byte[32]));
        assertThrows(ConversationProblem.class,()->tools.submitCalls(runId,request));verify(adapter,times(1)).read(any(),any());
    }
    @Test void sourceRevocationWhileReceivingUpstreamDiscardsResponse()throws Exception {
        Principal principal=external();var revoked=new AtomicBoolean();var identity=mock(ExternalIdentityService.class);
        when(identity.authenticateHash(principal.tokenHash())).thenAnswer(invocation->{if(revoked.get())throw new ExternalProblem(401,"EXTERNAL_TOKEN_REVOKED_OR_EXPIRED");return principal;});tools.setIdentity(identity);
        var settings=mock(ExternalBusinessSettings.class);
        when(settings.source(principal.issuer())).thenReturn(new ExternalBusinessSettings.Source(java.net.URI.create("https://business.example/read"),"key",new byte[32]));tools.setSettings(settings);
        var adapter=mock(ExternalBusinessHttpAdapter.class);@SuppressWarnings("unchecked") ObjectProvider<ExternalBusinessAdapter> providers=mock(ObjectProvider.class);
        when(providers.getIfAvailable()).thenReturn(adapter);tools.setAdapters(providers);
        when(adapter.read(any(),any())).thenAnswer(invocation->{revoked.set(true);return new ExternalBusinessAdapter.Evidence(principal.issuer(),principal.tenant().type(),principal.tenant().id(),"device","dev1",Instant.now(),json.readTree("{\"record\":{\"status\":\"fixture\"}}"));});
        claim(principal);
        assertThrows(ConversationProblem.class,()->tools.submitCalls(runId,proposal(1,"revoked","get_device","{\"device_id\":\"dev1\"}")));
        assertEquals("discarded",db.queryForObject("SELECT status FROM agent_tool_call WHERE run_id=?",String.class,runId));
        assertEquals(0,count("SELECT COUNT(*) FROM agent_tool_call WHERE run_id=? AND result_json IS NOT NULL",runId));
    }
    @Test void exhaustedWallClockBudgetRejectsAnotherProviderRound()throws Exception {
        claim(null);tools.submitCalls(runId,proposal(1,"timed","server_capabilities","{}"));
        // Fault injection changes only this isolated fixture's clock baseline, never audit records.
        db.update("UPDATE agent_tool_call SET created_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 31 SECOND) WHERE run_id=?",runId);
        var denied=assertThrows(ConversationProblem.class,()->tools.submitCalls(runId,proposal(2,"late","server_capabilities","{}")));
        assertEquals("TOOL_TIME_BUDGET_EXCEEDED",denied.getMessage());assertEquals(1,count("SELECT COUNT(*) FROM agent_tool_call WHERE run_id=?",runId));
    }
    @Test void cancelledDuringNetworkWaitRecordsDiscardedAuditAndNoResult()throws Exception {
        Principal principal=external();var settings=mock(ExternalBusinessSettings.class);
        when(settings.source(principal.issuer())).thenReturn(new ExternalBusinessSettings.Source(java.net.URI.create("https://business.example/read"),"key",new byte[32]));tools.setSettings(settings);
        var adapter=mock(ExternalBusinessHttpAdapter.class);@SuppressWarnings("unchecked") ObjectProvider<ExternalBusinessAdapter> providers=mock(ObjectProvider.class);
        when(providers.getIfAvailable()).thenReturn(adapter);tools.setAdapters(providers);
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(adapter.read(any(),any())).thenAnswer(invocation->{started.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));
            return new ExternalBusinessAdapter.Evidence(principal.issuer(),principal.tenant().type(),principal.tenant().id(),"device","dev1",Instant.now(),json.readTree("{\"record\":{\"name\":\"real-source-fixture\",\"secret\":\"must-not-escape\"}}"));});
        claim(principal);var request=proposal(1,"slow","get_device","{\"device_id\":\"dev1\"}");
        try(var executor=Executors.newSingleThreadExecutor()) {
            var pending=executor.submit(()->tools.submitCalls(runId,request));assertTrue(started.await(10,TimeUnit.SECONDS));
            ai.cancel(owner,conversation,runId);release.countDown();assertThrows(ExecutionException.class,()->pending.get(10,TimeUnit.SECONDS));
        } finally {release.countDown();}
        assertEquals("discarded",db.queryForObject("SELECT status FROM agent_tool_call WHERE run_id=?",String.class,runId));
        assertEquals(0,count("SELECT COUNT(*) FROM agent_tool_call WHERE run_id=? AND result_json IS NOT NULL",runId));
        assertEquals(1,count("SELECT COUNT(*) FROM agent_tool_audit a JOIN agent_tool_call c ON c.id=a.call_id WHERE c.run_id=? AND a.state='discarded'",runId));
    }
}
