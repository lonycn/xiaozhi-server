package xiaozhi.modules.realtime;

import static org.junit.jupiter.api.Assertions.*;
import static xiaozhi.modules.realtime.RealtimeStore.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import liquibase.*;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import xiaozhi.modules.ai.*;
import xiaozhi.modules.conversation.*;
import xiaozhi.modules.conversation.ConversationModels.*;
import xiaozhi.modules.support.*;
import xiaozhi.modules.support.SupportModels.*;
import xiaozhi.modules.realtime.RealtimeModels.*;

/** MySQL constraints/locking/expiry, append-only fixtures, and synthetic provider events. Never contacts a provider. */
@EnabledIfEnvironmentVariable(named="REALTIME_TEST_JDBC_URL",matches=".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RealtimePersistenceTest {
    static DriverManagerDataSource source;static JdbcTemplate db;static DataSourceTransactionManager manager;static ObjectMapper json;
    RealtimeStore store;RealtimeService realtime;ConversationService conversations;AiExecutionService ai;SupportService support;MockEnvironment environment;
    final Set<String> created=new HashSet<>();
    static <T>ObjectProvider<T> provider(Class<T> type,T instance){var factory=new DefaultListableBeanFactory();factory.registerSingleton("fixture",instance);return factory.getBeanProvider(type);}
    @BeforeAll static void initialize()throws Exception{
        String url=System.getenv("REALTIME_TEST_JDBC_URL");guard(url);source=new DriverManagerDataSource(url,"root",System.getenv("REALTIME_TEST_DB_PASSWORD"));db=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);json=new xiaozhi.modules.security.config.WebMvcConfig().jackson2HttpMessageConverter().getObjectMapper();
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Long.class),"Requires brand new empty schema; no cleanup/reuse");
        String zone=Optional.ofNullable(System.getenv("REALTIME_TEST_EXPECTED_ZONE")).orElse("UTC");assertTrue(Set.of("UTC","Asia/Shanghai").contains(zone));assertEquals(zone.equals("UTC")?0:28800,db.queryForObject("SELECT TIMESTAMPDIFF(SECOND,UTC_TIMESTAMP(),NOW())",Integer.class));
        db.execute("CREATE TABLE sys_user(id BIGINT PRIMARY KEY,status TINYINT NOT NULL,super_admin TINYINT NOT NULL DEFAULT 0,username VARCHAR(80))");db.execute("CREATE TABLE ai_agent(id VARCHAR(32) PRIMARY KEY,user_id BIGINT)");db.execute("CREATE TABLE ai_agent_chat_history(id BIGINT PRIMARY KEY,content VARCHAR(100))");db.update("INSERT INTO ai_agent_chat_history VALUES(1,'P3-legacy-unchanged')");for(long id:new long[]{1,7,8,71})db.update("INSERT INTO sys_user VALUES(?,1,?,?)",id,id==1?1:0,"rt-fixture-"+id);
        for(String migration:List.of("conversation","support","ai-execution","realtime-ai"))try(var connection=source.getConnection();var liq=new Liquibase("db/changelog/"+migration+".changelog.yaml",new ClassLoaderResourceAccessor(),new JdbcConnection(connection))){liq.update(new Contexts());liq.update(new Contexts());}
        assertEquals(4,db.queryForObject("SELECT COUNT(*) FROM DATABASECHANGELOG",Long.class));db.update("INSERT INTO support_agent_profile(user_id,role,enabled,capacity_text,accepting,presence_expires_at) VALUES(71,'expert',1,20,1,DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 1 HOUR))");
    }
    static void guard(String url){if(url==null||!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/xiaozhi_realtime_test_[a-z0-9_]+(\\?.*)?"))throw new IllegalArgumentException("Only a new loopback xiaozhi_realtime_test_* schema is allowed");}
    @BeforeEach void services(){
        store=new RealtimeStore(db,manager);environment=new MockEnvironment().withProperty("features.realtime-ai-enabled","true").withProperty("realtime.worker.key","p3-isolated-synthetic-worker-key-not-production").withProperty("realtime.worker.public-url","ws://fixture.invalid/realtime");realtime=new RealtimeService(store,json,environment);
        conversations=new ConversationService(new ConversationDao(db),json,manager);ai=new AiExecutionService(db,json,manager);ai.setEnabled(true);ai.setModel("test-b2");ai.setRealtime(provider(RealtimeHooks.class,realtime));conversations.setAiExecution(ai);conversations.setRealtime(realtime);realtime.setConversations(provider(ConversationService.class,conversations));realtime.setAi(provider(AiExecutionService.class,ai));support=new SupportService(db,json,manager,conversations);support.setAiExecution(ai);support.setRealtime(provider(RealtimeHooks.class,realtime));
    }
    @AfterEach void closeOwnFixtureSessionsWithoutDeleting(){
        db.update("UPDATE sys_user SET status=1 WHERE id=7");environment.withProperty("features.realtime-ai-enabled","true");
        for(String conv:created)store.tx(()->{store.conversation(conv);realtime.cancelInConversation(conv,"FIXTURE_FINISHED");ai.cancelActive(conv);return null;});assertEquals("P3-legacy-unchanged",db.queryForObject("SELECT content FROM ai_agent_chat_history WHERE id=1",String.class));
    }
    String conversation(){String c=conversations.create(7L,new CreateConversation("P3-isolated",null)).id();created.add(c);return c;}
    Session create(){return create(conversation());}
    Session create(String c){return realtime.create(7L,new Create(c,key(),new Consent(true,false)));}
    Task connect(Session s){var ticket=realtime.ticket(7L,s.id(),new Epoch(s.controlEpoch()));return realtime.consume(new Consume(ticket.ticket(),"fixture-worker","fixture-server"));}
    ProviderEvent event(Task task,long seq,String type,String item,String response,String text,String finish,Usage usage,String injection,String command,String error){return new ProviderEvent(task.leaseToken(),seq,"event-"+seq,type,type.equals("session.ready")?"provider-session":null,item,response,injection,command,text,finish,usage,error);}
    Receipt emit(Task task,long seq,String type,String response,String text){return realtime.providerEvent(task.sessionId(),event(task,seq,type,null,response,text,type.equals("response.completed")?"stop":null,null,null,null,null));}
    Task active(){var task=connect(create());emit(task,1,"session.ready",null,null);return task;}
    SendMessage text(String id,String value){return new SendMessage(id,List.of(new MessagePart("text",value,null,null,null,null,null,null)));}
    String key(){return UUID.randomUUID().toString();}
    long count(String table,String session){return db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE session_id=?",Long.class,session);}
    int error(Runnable action){return assertThrows(ConversationProblem.class,action::run).status();}
    @Test @Order(1) void migrationSentinelAndEnvironmentGuardRemainAppendOnly(){assertThrows(IllegalArgumentException.class,()->guard("jdbc:mysql://prod:3306/xiaozhi"));assertThrows(IllegalArgumentException.class,()->guard("jdbc:mysql://127.0.0.1:34776/xiaozhi_dev"));assertEquals(4,db.queryForObject("SELECT COUNT(*) FROM DATABASECHANGELOG",Long.class));assertEquals("P3-legacy-unchanged",db.queryForObject("SELECT content FROM ai_agent_chat_history WHERE id=1",String.class));}
    @Test @Order(2) void explicitOwnerConsentCancelsB2ButConnectionIsNotReady(){
        String c=conversation();var old=conversations.send(7L,c,text("before","真实已有文字"));var b2=ai.list(7L,c,0,10).items().getFirst();var lease=ai.claim(new AiModels.Claim("b2-fixture"));assertNotNull(lease);var session=create(c);assertEquals("canceled",ai.get(7L,c,b2.runId()).status());assertEquals("pending",session.status());assertEquals(409,error(()->ai.delta(lease.runId(),new AiModels.Delta(lease.leaseToken(),1L,"late"))));
        var task=connect(session);assertEquals("connecting",realtime.get(7L,session.id()).status());assertTrue(task.context().stream().anyMatch(m->m.content().contains(old.parts().getFirst().text())));assertEquals(16000,task.inputAudio().sampleRate());assertEquals(24000,task.outputAudio().sampleRate());assertEquals(190000,task.limits().maxFrameBytes());emit(task,1,"session.ready",null,null);assertEquals("active",realtime.get(7L,session.id()).status());
        assertEquals(404,error(()->realtime.get(8L,session.id())));assertEquals(400,error(()->realtime.create(7L,new Create(c,key(),new Consent(false,true)))));assertEquals(409,error(()->realtime.create(7L,new Create(c,key(),new Consent(true,false)))));
    }
    @Test @Order(3) void singleUseTicketAndConcurrentDuplicateCreationHaveOneWinner()throws Exception{
        String c=conversation();Create request=new Create(c,key(),new Consent(true,false));Session s;
        try(var pool=Executors.newFixedThreadPool(6)){List<Callable<Session>> calls=new ArrayList<>();for(int i=0;i<12;i++)calls.add(()->realtime.create(7L,request));Set<String> ids=new HashSet<>();for(var answer:pool.invokeAll(calls))ids.add(answer.get().id());assertEquals(1,ids.size());s=realtime.get(7L,ids.iterator().next());}
        assertEquals(409,error(()->realtime.create(7L,new Create(c,request.clientSessionId(),new Consent(true,true)))));
        var old=realtime.ticket(7L,s.id(),new Epoch(s.controlEpoch()));var ticket=realtime.ticket(7L,s.id(),new Epoch(s.controlEpoch()));assertEquals(409,error(()->realtime.consume(new Consume(old.ticket(),"worker","server"))));assertEquals(hash(ticket.ticket()),db.queryForObject("SELECT ticket_hash FROM ai_realtime_ticket WHERE ticket_hash=?",String.class,hash(ticket.ticket())));
        try(var pool=Executors.newFixedThreadPool(6)){List<Callable<Boolean>> calls=new ArrayList<>();for(int i=0;i<12;i++)calls.add(()->{try{realtime.consume(new Consume(ticket.ticket(),"worker","server"));return true;}catch(ConversationProblem expected){assertEquals(409,expected.status());return false;}});int winners=0;for(var answer:pool.invokeAll(calls))if(answer.get())winners++;assertEquals(1,winners);}
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ai_realtime_ticket WHERE session_id=? AND status='consumed'",Long.class,s.id()));
    }
    @Test @Order(4) void canonicalEventsAreOrderedDeduplicatedAndPreserveLiteralTranscriptAndRealUsage(){
        var task=active();var started=emit(task,2,"response.started","response-one",null);assertNotNull(started.messageId());var delta=event(task,3,"response.delta",null,"response-one","真实 <>& 中文",null,null,null,null,null);var accepted=realtime.providerEvent(task.sessionId(),delta);assertEquals(accepted,realtime.providerEvent(task.sessionId(),delta));
        assertEquals(409,error(()->emit(task,5,"response.delta","response-one","gap")));assertEquals(409,error(()->emit(task,3,"response.delta","response-one","changed")));assertEquals(1,count("ai_realtime_turn",task.sessionId()));
        var input=event(task,4,"input.transcript","provider-audio-item",null,"客户 <>& 转写",null,null,null,null,null);Receipt user=realtime.providerEvent(task.sessionId(),input);assertNotNull(user.messageId());
        var done=event(task,5,"response.completed",null,"response-one","真实 <>& 中文","stop",new Usage(452L,95L,547L),null,null,null);var result=realtime.providerEvent(task.sessionId(),done);realtime.providerEvent(task.sessionId(),done);assertNull(result.session().activeResponseId());assertEquals(started.messageId(),result.messageId());
        assertEquals(5,count("ai_realtime_event",task.sessionId()));var messages=conversations.messages(7L,task.conversationId(),0,50).items();assertEquals(2,messages.size());assertEquals("真实 <>& 中文",messages.getFirst().parts().getFirst().text());assertEquals("客户 <>& 转写",messages.getLast().parts().getFirst().text());assertTrue(store.one("SELECT usage_json FROM ai_realtime_turn WHERE session_id=? AND kind='assistant'",task.sessionId()).get("usage_json").toString().contains("547"));
        assertEquals(409,error(()->emit(task,6,"response.delta","response-one","late")));
    }
    @Test @Order(5) void textIsSavedOnceAndOnlyRealProviderItemAcknowledgesInjection(){
        var task=active();var body=new Send(task.controlEpoch(),"same-message","保留客户文字");var sent=realtime.send(7L,task.sessionId(),body);assertEquals(sent,realtime.send(7L,task.sessionId(),body));assertEquals("queued",sent.injection().status());assertEquals(0,realtime.heartbeat(task.sessionId(),new Heartbeat(task.leaseToken(),true)).injections().size());
        var pulse=realtime.heartbeat(task.sessionId(),new Heartbeat(task.leaseToken(),false));assertEquals(1,pulse.injections().size());assertEquals(0,realtime.heartbeat(task.sessionId(),new Heartbeat(task.leaseToken(),false)).injections().size());var dispatched=pulse.injections().getFirst();assertEquals(sent.message().id(),dispatched.messageId());
        assertEquals(409,error(()->realtime.providerEvent(task.sessionId(),event(task,2,"injection.ack","wrong-item",null,null,null,null,dispatched.id(),null,null))));
        realtime.providerEvent(task.sessionId(),event(task,2,"injection.ack",dispatched.providerItemId(),null,null,null,null,dispatched.id(),null,null));assertEquals("acknowledged",realtime.injections(7L,task.sessionId(),0,50).items().getFirst().status());assertEquals(1,conversations.messages(7L,task.conversationId(),0,50).items().size());assertTrue(ai.list(7L,task.conversationId(),0,50).items().isEmpty());
        var second=realtime.send(7L,task.sessionId(),new Send(task.controlEpoch(),"not-accepted","未注入也保留"));realtime.heartbeat(task.sessionId(),new Heartbeat(task.leaseToken(),false));db.update("UPDATE ai_realtime_injection SET dispatched_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 16 SECOND) WHERE id=?",second.injection().id());realtime.maintain();var list=realtime.injections(7L,task.sessionId(),0,50).items();assertEquals("failed",list.getLast().status());assertEquals(2,conversations.messages(7L,task.conversationId(),0,50).items().size());assertEquals(409,error(()->realtime.providerEvent(task.sessionId(),event(task,3,"injection.ack",second.injection().providerItemId(),null,null,null,null,second.injection().id(),null,null))));
    }
    @Test @Order(6) void contextSnapshotDoesNotDuplicateQueuedTextAndNormalSendUsesSameTransaction(){
        String c=conversation();conversations.send(7L,c,text("before","before realtime"));var s=create(c);var queued=conversations.send(7L,c,text("during","only inject once"));var task=connect(s);assertTrue(task.context().stream().anyMatch(x->x.content().contains("before realtime")));assertTrue(task.context().stream().noneMatch(x->x.content().contains("only inject once")));emit(task,1,"session.ready",null,null);assertEquals(queued.id(),realtime.heartbeat(task.sessionId(),new Heartbeat(task.leaseToken(),false)).injections().getFirst().messageId());
        long count=db.queryForObject("SELECT COUNT(*) FROM ai_message WHERE conversation_id=?",Long.class,c);var location=new MessagePart("location",null,30d,120d,"WGS84",10d,"user_shared",Instant.now());assertEquals(409,error(()->conversations.send(7L,c,new SendMessage("unsupported-location",List.of(location)))));assertEquals(count,db.queryForObject("SELECT COUNT(*) FROM ai_message WHERE conversation_id=?",Long.class,c));
    }
    @Test @Order(7) void interruptFreezesPartialAndRequiresAuthoritativeTerminationBeforeCommandAck(){
        var task=active();emit(task,2,"response.started","response-cancel",null);emit(task,3,"response.delta","response-cancel","已经说出部分");var body=new Interrupt(task.controlEpoch(),"stop-once","response-cancel");var command=realtime.interrupt(7L,task.sessionId(),body);assertEquals(command,realtime.interrupt(7L,task.sessionId(),body));assertEquals(409,error(()->emit(task,4,"response.delta","response-cancel","不得追加")));
        var pulse=realtime.heartbeat(task.sessionId(),new Heartbeat(task.leaseToken(),true));assertEquals(command.id(),pulse.commands().getFirst().id());assertEquals(409,error(()->realtime.providerEvent(task.sessionId(),event(task,4,"command.ack",null,"response-cancel",null,null,null,null,command.id(),null))));
        emit(task,4,"response.completed","response-cancel","不得覆盖已取消部分");realtime.providerEvent(task.sessionId(),event(task,5,"command.ack",null,"response-cancel",null,null,null,null,command.id(),null));var message=conversations.messages(7L,task.conversationId(),0,50).items().getLast();assertEquals("canceled",message.status());assertEquals("已经说出部分",message.parts().getFirst().text());assertEquals("acknowledged",db.queryForObject("SELECT status FROM ai_realtime_command WHERE id=?",String.class,command.id()));
    }
    @Test @Order(8) void humanTakeoverTerminatesRealtimeAndRejectsLateCallbacksWithoutLosingHistory(){
        var task=active();realtime.providerEvent(task.sessionId(),event(task,2,"input.transcript","audio-handoff",null,"保留转写",null,null,null,null,null));emit(task,3,"response.started","human-race",null);emit(task,4,"response.delta","human-race","partial before handoff");
        var request=support.handoff(7L,task.conversationId(),new Handoff("human-handoff","text",null));assertEquals("canceled",realtime.get(7L,task.sessionId()).status());assertEquals(409,error(()->emit(task,5,"response.completed","human-race","late fake completion")));assertEquals(409,error(()->realtime.heartbeat(task.sessionId(),new Heartbeat(task.leaseToken(),false))));
        support.presence(71L,new PresenceUpdate(true));support.claim(71L,request.requestId());support.send(71L,request.requestId(),text("human-advice","人工仍在同一会话"));support.resolve(71L,request.requestId(),null);support.resumeAi(7L,task.conversationId());conversations.send(7L,task.conversationId(),text("after-rt","恢复普通文本"));assertFalse(ai.list(7L,task.conversationId(),0,50).items().isEmpty());assertTrue(conversations.messages(7L,task.conversationId(),0,50).items().stream().anyMatch(x->x.parts().stream().anyMatch(p->"保留转写".equals(p.text()))));
    }
    @Test @Order(9) void disabledAccountCurrentReadDefeatsRepeatableReadSnapshotAndCannotCallback()throws Exception{
        var task=active();try(var executor=Executors.newSingleThreadExecutor()){
            try{assertEquals(401,error(()->store.tx(()->{assertEquals(1,db.queryForObject("SELECT status FROM sys_user WHERE id=7",Integer.class));try{executor.submit(()->db.update("UPDATE sys_user SET status=0 WHERE id=7")).get(5,TimeUnit.SECONDS);}catch(Exception ex){throw new AssertionError(ex);}return realtime.heartbeat(task.sessionId(),new Heartbeat(task.leaseToken(),false));})));assertEquals(401,error(()->emit(task,2,"response.started","disabled-response",null)));realtime.maintain();assertEquals("expired",db.queryForObject("SELECT status FROM ai_realtime_session WHERE id=?",String.class,task.sessionId()));assertEquals(0,count("ai_realtime_turn",task.sessionId()));}
            finally{db.update("UPDATE sys_user SET status=1 WHERE id=7");}
        }
    }
    @Test @Order(10) void b2RetryAndClaimCannotRunAlongsideRealtimeAndOldEpochNeverResurrects(){
        String c=conversation();conversations.send(7L,c,text("old-b2","question"));var run=ai.list(7L,c,0,50).items().getFirst();var s=create(c);assertEquals(409,error(()->ai.retry(7L,c,run.runId())));
        // Explicit stale-queue fixture: even a persisted B2 item at the current epoch cannot claim during RT.
        db.update("UPDATE ai_run SET status='queued',control_epoch=? WHERE id=?",s.controlEpoch(),run.runId());assertNull(ai.claim(new AiModels.Claim("cannot-steal-realtime")));assertEquals("canceled",ai.get(7L,c,run.runId()).status());
        var task=connect(s);emit(task,1,"session.ready",null,null);realtime.end(7L,s.id(),new Epoch(s.controlEpoch()));Session next=create(c);assertTrue(next.controlEpoch()>s.controlEpoch());assertEquals(409,error(()->emit(task,2,"response.started","old-epoch",null)));assertEquals("AI_ACTIVE",conversations.get(7L,c).state());conversations.send(7L,c,text("new-rt","new session only"));assertEquals(1,count("ai_realtime_injection",next.id()));
    }
    @Test @Order(11) void workerCrashRecoveryExpiresLeaseAndPreservesPartialWithoutReclaim(){
        var task=active();emit(task,2,"response.started","crashed",null);emit(task,3,"response.delta","crashed","保留实际部分");var sent=realtime.send(7L,task.sessionId(),new Send(task.controlEpoch(),"queued-crash","已存但未注入"));db.update("UPDATE ai_realtime_session SET lease_expires_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",task.sessionId());assertEquals(409,error(()->realtime.heartbeat(task.sessionId(),new Heartbeat(task.leaseToken(),false))));
        var restarted=new RealtimeService(new RealtimeStore(db,manager),json,environment);restarted.maintain();restarted.maintain();assertEquals("expired",realtime.get(7L,task.sessionId()).status());assertEquals("failed",realtime.injections(7L,task.sessionId(),0,50).items().getFirst().status());assertEquals("canceled",conversations.messages(7L,task.conversationId(),0,50).items().getFirst().status());assertEquals("保留实际部分",conversations.messages(7L,task.conversationId(),0,50).items().getFirst().parts().getFirst().text());assertEquals(sent.message().id(),conversations.messages(7L,task.conversationId(),0,50).items().getLast().id());assertEquals(409,error(()->emit(task,4,"response.completed","crashed","late")));assertEquals(409,error(()->realtime.ticket(7L,task.sessionId(),new Epoch(task.controlEpoch()))));
    }
    @Test @Order(12) void lostInterruptConfirmationTerminatesVisiblyAndDoesNotFakeAck(){
        var task=active();emit(task,2,"response.started","unconfirmed-cancel",null);emit(task,3,"response.delta","unconfirmed-cancel","said");var command=realtime.interrupt(7L,task.sessionId(),new Interrupt(task.controlEpoch(),key(),"unconfirmed-cancel"));realtime.heartbeat(task.sessionId(),new Heartbeat(task.leaseToken(),true));db.update("UPDATE ai_realtime_command SET dispatched_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 16 SECOND) WHERE id=?",command.id());var pulse=realtime.heartbeat(task.sessionId(),new Heartbeat(task.leaseToken(),false));assertFalse(pulse.active());assertEquals("COMMAND_UNCONFIRMED",realtime.get(7L,task.sessionId()).errorCode());assertEquals("failed",db.queryForObject("SELECT status FROM ai_realtime_command WHERE id=?",String.class,command.id()));assertEquals(409,error(()->emit(task,4,"response.canceled","unconfirmed-cancel",null)));
    }
    @Test @Order(13) void missingUsageStaysNullAndUnsupportedToolsNeverGetFabricatedResults(){
        var task=active();emit(task,2,"response.started","no-usage",null);emit(task,3,"response.completed","no-usage","真实回复无usage");assertNull(store.one("SELECT usage_json FROM ai_realtime_turn WHERE session_id=?",task.sessionId()).get("usage_json"));
        emit(task,4,"response.started","tool-request",null);realtime.providerEvent(task.sessionId(),event(task,5,"response.completed",null,"tool-request","","tool_calls",null,null,null,null));assertEquals("failed",realtime.get(7L,task.sessionId()).status());assertEquals("UNSUPPORTED_TOOL",realtime.get(7L,task.sessionId()).errorCode());assertEquals(2,count("ai_realtime_turn",task.sessionId()));
    }
    @Test @Order(14) void featureOffAndExpiredPendingTicketAreRecoverableWithoutClearingMessages(){
        var s=create();var ticket=realtime.ticket(7L,s.id(),new Epoch(s.controlEpoch()));db.update("UPDATE ai_realtime_ticket SET expires_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE ticket_hash=?",hash(ticket.ticket()));assertEquals(409,error(()->realtime.consume(new Consume(ticket.ticket(),"w","s"))));db.update("UPDATE ai_realtime_session SET created_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 61 SECOND) WHERE id=?",s.id());realtime.maintain();assertEquals("expired",realtime.get(7L,s.id()).status());
        var task=active();realtime.send(7L,task.sessionId(),new Send(task.controlEpoch(),key(),"切开关也保留"));environment.withProperty("features.realtime-ai-enabled","false");assertEquals(503,error(()->realtime.heartbeat(task.sessionId(),new Heartbeat(task.leaseToken(),false))));realtime.maintain();assertEquals("expired",realtime.get(7L,task.sessionId()).status());assertEquals(1,conversations.messages(7L,task.conversationId(),0,50).items().size());
    }
    @Test @Order(15) void jdbcAndSessionTimezonesMatchRealShortTicketAndLeaseDeadlines(){
        var s=create();Instant now=Instant.now();assertTrue(Duration.between(now,s.createdAt()).abs().toSeconds()<10);var ticket=realtime.ticket(7L,s.id(),new Epoch(s.controlEpoch()));long ticketSeconds=Duration.between(now,ticket.expiresAt()).toSeconds();assertTrue(ticketSeconds>=25&&ticketSeconds<=35);var task=realtime.consume(new Consume(ticket.ticket(),"timezone","test"));long lease=Duration.between(Instant.now(),instant(store.session(s.id(),false),"lease_expires_at")).toSeconds();assertTrue(lease>=8&&lease<=12,"lease is seconds, never eight hours");emit(task,1,"session.ready",null,null);assertTrue(Duration.between(Instant.now(),realtime.get(7L,s.id()).connectedAt()).abs().toSeconds()<10);db.update("UPDATE ai_realtime_session SET lease_expires_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",s.id());assertEquals(409,error(()->realtime.heartbeat(s.id(),new Heartbeat(task.leaseToken(),false))));realtime.maintain();assertEquals("expired",realtime.get(7L,s.id()).status());
    }
    @Test @Order(16) void explicitEndRestoresB2OnSameConversationWithoutChangingHumanModeOrHistory(){
        var task=active();realtime.providerEvent(task.sessionId(),event(task,2,"input.transcript","final-audio",null,"此前真实语音内容",null,null,null,null,null));emit(task,3,"response.started","final-answer",null);emit(task,4,"response.completed","final-answer","此前真实模型回答");var s=realtime.get(7L,task.sessionId());assertEquals("ended",realtime.end(7L,s.id(),new Epoch(s.controlEpoch())).status());assertEquals("ended",realtime.end(7L,s.id(),new Epoch(s.controlEpoch())).status());assertEquals("AI_ACTIVE",conversations.get(7L,task.conversationId()).state());
        conversations.send(7L,task.conversationId(),text("ordinary-again","继续普通文本"));var run=ai.claim(new AiModels.Claim("normal-text-worker"));assertNotNull(run);assertEquals(task.conversationId(),run.conversationId());assertTrue(run.messages().stream().anyMatch(m->m.content().contains("此前真实模型回答")));assertTrue(run.messages().stream().anyMatch(m->m.content().contains("此前真实语音内容")));assertEquals(4,conversations.messages(7L,task.conversationId(),0,50).items().size());
    }

    @Test @Order(17) void committedAudioReservesUserSequenceAndLateAsrUpdatesOneMessageWithNoFakeTranscript(){
        var task=active();var committed=realtime.providerEvent(task.sessionId(),event(task,2,"input.committed","audio-late-asr",null,null,null,null,null,null,null));assertEquals("transcribing",committed.messageStatus());assertNotNull(committed.messageId());emit(task,3,"response.started","answer-before-asr",null);realtime.providerEvent(task.sessionId(),event(task,4,"input.transcript","audio-late-asr",null,"实际迟到转写",null,null,null,null,null));emit(task,5,"response.completed","answer-before-asr","实际回答");
        var messages=conversations.messages(7L,task.conversationId(),0,50).items();assertEquals(2,messages.size());assertEquals("user",messages.getFirst().senderType());assertEquals("实际迟到转写",messages.getFirst().parts().getFirst().text());assertEquals(committed.messageId(),messages.getFirst().id());assertEquals("assistant",messages.getLast().senderType());
        realtime.providerEvent(task.sessionId(),event(task,6,"input.transcript","asr-first",null,"ASR先到",null,null,null,null,null));var reverse=realtime.providerEvent(task.sessionId(),event(task,7,"input.committed","asr-first",null,null,null,null,null,null,null));assertEquals("completed",reverse.messageStatus());assertEquals(3,conversations.messages(7L,task.conversationId(),0,50).items().size());
        var lost=realtime.providerEvent(task.sessionId(),event(task,8,"input.committed","asr-lost",null,null,null,null,null,null,null));realtime.end(7L,task.sessionId(),new Epoch(task.controlEpoch()));var last=conversations.messages(7L,task.conversationId(),0,50).items().getLast();assertEquals(lost.messageId(),last.id());assertEquals("failed",last.status());assertTrue(last.parts().isEmpty());assertEquals(409,error(()->realtime.providerEvent(task.sessionId(),event(task,9,"input.transcript","asr-lost",null,"too late",null,null,null,null,null))));
        assertTrue(conversations.events(7L,task.conversationId(),0,100).items().stream().filter(x->x.type().startsWith("realtime.")).allMatch(x->x.data().path("control_epoch").isIntegralNumber()));
    }
    @Test @Order(18) void invalidCallbackFieldsOrderAndForgedLeaseHaveNoSideEffects(){
        var task=active();assertEquals(409,error(()->emit(task,2,"response.delta","not-started","fake")));assertEquals(400,error(()->realtime.providerEvent(task.sessionId(),event(task,2,"session.ready",null,"forged-extra-response",null,null,null,null,null,null))));
        var forged=new ProviderEvent("f".repeat(64),2L,"wrong-lease","response.started",null,null,"fake-response",null,null,null,null,null,null);assertEquals(409,error(()->realtime.providerEvent(task.sessionId(),forged)));assertEquals(0,count("ai_realtime_turn",task.sessionId()));assertEquals(1,count("ai_realtime_event",task.sessionId()));
        emit(task,2,"response.started","real-response",null);assertEquals(400,error(()->realtime.providerEvent(task.sessionId(),event(task,3,"response.completed",null,"real-response","x","stop",new Usage(-1L,1L,0L),null,null,null))));assertEquals("streaming",conversations.messages(7L,task.conversationId(),0,50).items().getLast().status());
        realtime.providerEvent(task.sessionId(),event(task,3,"response.canceled",null,"real-response",null,null,new Usage(10L,2L,12L),null,null,null));assertTrue(store.one("SELECT usage_json FROM ai_realtime_turn WHERE session_id=?",task.sessionId()).get("usage_json").toString().contains("12"));
    }

    @Test @Order(19) void ackDeadlineAndConnectingDeadlineDoNotDependOnSweepTiming(){
        var task=active();var sent=realtime.send(7L,task.sessionId(),new Send(task.controlEpoch(),key(),"延迟接受"));realtime.heartbeat(task.sessionId(),new Heartbeat(task.leaseToken(),false));db.update("UPDATE ai_realtime_injection SET dispatched_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 16 SECOND) WHERE id=?",sent.injection().id());assertEquals(409,error(()->realtime.providerEvent(task.sessionId(),event(task,2,"injection.ack",sent.injection().providerItemId(),null,null,null,null,sent.injection().id(),null,null))));assertEquals("dispatched",db.queryForObject("SELECT status FROM ai_realtime_injection WHERE id=?",String.class,sent.injection().id()));realtime.maintain();assertEquals("failed",realtime.injections(7L,task.sessionId(),0,50).items().getFirst().status());
        var connecting=connect(create());db.update("UPDATE ai_realtime_session SET created_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 61 SECOND) WHERE id=?",connecting.sessionId());assertEquals(409,error(()->emit(connecting,1,"session.ready",null,null)));realtime.maintain();assertEquals("expired",realtime.get(7L,connecting.sessionId()).status());
    }

    @Test @Order(20) void realSpringGraphConnectsConversationB2SupportAndProxiedRealtimeHooksWithoutCycles(){
        try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()){
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("p3-real-graph",Map.of("features.conversation-enabled","true","features.realtime-ai-enabled","true","features.ai-text-enabled","true","realtime.worker.key","p3-synthetic-spring-graph-worker-key","realtime.worker.public-url","ws://fixture.invalid/realtime")));
            context.getBeanFactory().registerSingleton("jdbc",db);context.getBeanFactory().registerSingleton("manager",manager);context.getBeanFactory().registerSingleton("json",json);context.getBeanFactory().registerSingleton("dao",new ConversationDao(db));context.register(org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor.class,RealtimeStore.class,RealtimeService.class,ConversationService.class,AiExecutionService.class,SupportService.class);context.refresh();
            assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(context.getBean(RealtimeStore.class)));var cs=context.getBean(ConversationService.class);var rs=context.getBean(RealtimeService.class);String c=cs.create(7L,new CreateConversation("spring-proxy-graph",null)).id();created.add(c);var session=rs.create(7L,new Create(c,key(),new Consent(true,false)));var accepted=rs.send(7L,session.id(),new Send(session.controlEpoch(),key(),"actual spring hook"));assertEquals("queued",accepted.injection().status());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ai_run WHERE conversation_id=?",Long.class,c));var request=context.getBean(SupportService.class).handoff(7L,c,new Handoff(key(),"text",null));assertNotNull(request);assertEquals("canceled",rs.get(7L,session.id()).status());
        }
    }

}
