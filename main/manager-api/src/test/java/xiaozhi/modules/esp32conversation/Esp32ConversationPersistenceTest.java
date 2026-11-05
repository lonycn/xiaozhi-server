package xiaozhi.modules.esp32conversation;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import com.fasterxml.jackson.databind.json.JsonMapper;
import liquibase.*;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import xiaozhi.modules.conversation.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="ESP32_SESSION_TEST_JDBC_URL",matches=".+")
class Esp32ConversationPersistenceTest {
    static JdbcTemplate db;
    static Esp32ConversationService service;
    static Esp32ConversationService.TrustedContext current;
    static int executions;
    static boolean failWorker;
    @BeforeEach void resetExecutions(){executions=0;failWorker=false;}
    @BeforeAll static void setup() throws Exception {
        String url=System.getenv("ESP32_SESSION_TEST_JDBC_URL");
        if(!url.matches("jdbc:mysql://127\\.0\\.0\\.1:34776/xiaozhi_esp32session_test_[a-z0-9_]+(\\?.*)?"))throw new IllegalArgumentException("isolated fresh loopback schema required");
        var source=new DriverManagerDataSource(url,"root",System.getenv("ESP32_SESSION_TEST_DB_PASSWORD"));db=new JdbcTemplate(source);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class));
        db.execute("CREATE TABLE ai_agent(id VARCHAR(32) PRIMARY KEY,user_id BIGINT)");db.execute("INSERT INTO ai_agent VALUES('agent',7)");
        db.execute("CREATE TABLE ai_agent_chat_history(id BIGINT PRIMARY KEY,content VARCHAR(100))");db.execute("INSERT INTO ai_agent_chat_history VALUES(1,'legacy-sentinel')");
        for(String path:new String[]{"conversation","esp32-conversation","esp32-intents"})try(var c=source.getConnection();var l=new Liquibase("db/changelog/"+path+".changelog.yaml",new ClassLoaderResourceAccessor(),new JdbcConnection(c))){l.update(new Contexts());l.update(new Contexts());}
        var beans=new DefaultListableBeanFactory();
        beans.registerSingleton("guard",(Esp32ConversationService.ContextGuard)c->{if(!c.equals(current))throw new ConversationProblem(409,"stale");});
        beans.registerSingleton("worker",new Esp32AiExecutionBridge(){public void submit(Esp32ConversationService.TrustedContext c,String conversation,String message,String turn){if(failWorker)throw new IllegalStateException("queue unavailable");executions++;}});
        service=new Esp32ConversationService(db,new ConversationDao(db),JsonMapper.builder().build(),new DataSourceTransactionManager(source),beans.getBeanProvider(Esp32AiExecutionBridge.class),beans.getBeanProvider(Esp32ConversationService.ContextGuard.class));
    }
    static Esp32ConversationService.TrustedContext context(String device,long generation){return new Esp32ConversationService.TrustedContext(device,"binding",7,"agent","connection"+generation,generation,"gateway");}
    @Test void canonicalLedgerIsIdempotentAndFenced() {
        current=context("device-record",1);var session=service.connect(current,"legacy_record_only");String sid=(String)session.get("id");
        var input=service.input(current,sid,"input","hello");String turn=(String)input.get("id");
        assertEquals(turn,service.input(current,sid,"input","hello").get("id"));
        assertThrows(ConversationProblem.class,()->service.input(current,sid,"input","changed"));
        var output=service.output(current,sid,turn,"answer");assertEquals(output.get("output_message_id"),service.output(current,sid,turn,"answer").get("output_message_id"));
        assertEquals(0,executions);assertTrue(service.isAiOnly((String)session.get("conversation_id")));
        assertThrows(ConversationProblem.class,()->service.requireNoHuman((String)session.get("conversation_id")));
        assertThrows(ConversationProblem.class,()->service.turns(8,sid,0,50));
        var report=service.report(current,sid,turn,"tool-one","tool_result","volume succeeded");
        assertEquals(report.get("id"),service.report(current,sid,turn,"tool-one","tool_result","volume succeeded").get("id"));
        assertThrows(ConversationProblem.class,()->service.report(current,sid,turn,"tool-one","tool_result","different"));
        service.input(current,sid,"second","new input");assertFalse(service.canPublish(turn));assertThrows(ConversationProblem.class,()->service.output(current,sid,turn,"late"));
        current=context("device-record",2);var reconnect=service.connect(current,"legacy_record_only");assertEquals(sid,reconnect.get("id"));assertEquals(2,((Number)reconnect.get("last_turn_seq")).intValue());
        assertThrows(ConversationProblem.class,()->service.connect(current,"ai_worker"));
        assertEquals("legacy-sentinel",db.queryForObject("SELECT content FROM ai_agent_chat_history WHERE id=1",String.class));
    }
    @Test void concurrentInputRetriesRemainSingleCanonicalMessage() throws Exception {
        current=context("device-concurrent",1);var session=service.connect(current,"legacy_record_only");String sid=(String)session.get("id");
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(6)) {
            java.util.List<java.util.concurrent.Callable<String>> calls=new java.util.ArrayList<>();
            for(int i=0;i<18;i++)calls.add(()->(String)service.input(current,sid,"same","one input").get("id"));
            java.util.Set<String> ids=new java.util.HashSet<>();for(var result:pool.invokeAll(calls))ids.add(result.get());assertEquals(1,ids.size());
        }
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM esp32_voice_turn WHERE session_id=?",Integer.class,sid));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ai_message WHERE conversation_id=?",Integer.class,session.get("conversation_id")));
    }
    @Test void workerEnqueueFailureRollsBackInputAndTurn() {
        current=context("device-rollback",1);var session=service.connect(current,"ai_worker");String sid=(String)session.get("id");failWorker=true;
        assertThrows(IllegalStateException.class,()->service.input(current,sid,"retry-after-failure","hello"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM esp32_voice_turn WHERE session_id=?",Integer.class,sid));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ai_message WHERE conversation_id=?",Integer.class,session.get("conversation_id")));
        assertEquals(0,db.queryForObject("SELECT last_turn_seq FROM esp32_device_session WHERE id=?",Integer.class,sid));
        failWorker=false;service.input(current,sid,"retry-after-failure","hello");assertEquals(1,executions);
    }
    @Test void bindingChangeRevokesOldHistoryAndCreatesSeparateMapping() {
        current=context("device-rebind",1);var old=service.connect(current,"legacy_record_only");String oldId=(String)old.get("id");service.input(current,oldId,"old","old history");
        current=new Esp32ConversationService.TrustedContext("device-rebind","new-binding",7,"agent","new-connection",2,"gateway");
        var replacement=service.connect(current,"legacy_record_only");assertNotEquals(oldId,replacement.get("id"));assertNotEquals(old.get("conversation_id"),replacement.get("conversation_id"));
        assertThrows(ConversationProblem.class,()->service.turns(7,oldId,0,10));
        assertThrows(ConversationProblem.class,()->service.requireCurrentBindingForConversation((String)old.get("conversation_id"),7));
        assertEquals(1,service.list(7,"device-rebind",10).size());
    }
    @Test void legacyFailureReportIsIdempotentAndStopsPublication() {
        current=context("device-failure",1);var session=service.connect(current,"legacy_record_only");String sid=(String)session.get("id");String turn=(String)service.input(current,sid,"failed","hello").get("id");
        var report=service.report(current,sid,turn,"failure-one","failure","upstream unavailable");assertEquals(report.get("id"),service.report(current,sid,turn,"failure-one","failure","upstream unavailable").get("id"));
        assertFalse(service.canPublish(turn));assertThrows(ConversationProblem.class,()->service.output(current,sid,turn,"late success"));
    }
    @Test void workerModeRunsExactlyOnceAndRejectsGatewayAssistant() {
        current=context("device-worker",1);var s=service.connect(current,"ai_worker");String sid=(String)s.get("id");
        var input=service.input(current,sid,"worker","hello");service.input(current,sid,"worker","hello");assertEquals(1,executions);
        assertThrows(ConversationProblem.class,()->service.output(current,sid,(String)input.get("id"),"fake"));
        service.abort(current,sid);assertFalse(service.canPublish((String)input.get("id")));
    }
    @Test void delayedAbortCannotCancelNewAcceptedTurn() {
        current=context("device-delayed-abort",1);var session=service.connect(current,"ai_worker");String sid=(String)session.get("id");
        var old=service.input(current,sid,"old-abort","old input");var newer=service.input(current,sid,"new-input","new input");
        long oldVersion=((Number)old.get("control_version")).longValue(),newVersion=((Number)newer.get("control_version")).longValue();
        var failure=assertThrows(ConversationProblem.class,()->service.abortTurn(current,sid,(String)old.get("id"),oldVersion));assertEquals(409,failure.status());
        assertEquals("accepted",db.queryForObject("SELECT status FROM esp32_voice_turn WHERE id=?",String.class,newer.get("id")));assertTrue(service.canPublish((String)newer.get("id")));
        assertEquals(newVersion,db.queryForObject("SELECT control_version FROM esp32_device_session WHERE id=?",Long.class,sid));
        service.abortTurn(current,sid,(String)newer.get("id"),newVersion);assertFalse(service.canPublish((String)newer.get("id")));assertEquals("canceled",db.queryForObject("SELECT status FROM esp32_voice_turn WHERE id=?",String.class,newer.get("id")));
    }

    @Test void publishedShortcutUsesOnePersistedReplyAndRejectsPlaybackAfterAbort() {
        var intents = new Esp32IntentService(db, JsonMapper.builder().build(),
                new DataSourceTransactionManager(db.getDataSource()));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "intents", intents);
        try {
            current = context("device-intents", 1);
            String session = (String) service.connect(current, "ai_worker").get("id");
            var rule = new Esp32IntentService.Rule("sample", "sample", java.util.List.of("sample answer"),
                    "reply", "fixed output", true);
            var time = new Esp32IntentService.Rule("time", "time", java.util.List.of("现在几点了"),
                    "time", "", true);
            var policy = new Esp32IntentService.Policy("Asia/Shanghai", java.util.List.of(rule, time));
            var draft = intents.save("agent", new Esp32IntentService.Draft(0, policy));
            assertEquals(policy, draft.draft());
            assertTrue(draft.published().rules().isEmpty());
            assertNull(intents.resolve("agent", "sample answer"));
            assertEquals("fixed output", intents.preview(policy, "sample answer!").reply());
            assertEquals("exit", intents.preview(policy, "再见吧再见吧。").action());
            assertEquals("exit", intents.preview(policy, "好的，知道了，再见。").action());
            assertEquals("exit", intents.preview(policy, "再见呀。").action());
            assertEquals("exit", intents.preview(policy, "大冷总管，推下吧。").action());
            assertNull(intents.preview(policy, "然后我说推下吧，他没有反应。"));
            assertNull(intents.preview(policy, "大冷总管，不要退出。"));
            assertNull(intents.preview(policy, "好的，知道了，不要退出。"));
            assertNull(intents.preview(policy, "好的，知道了。"));
            assertNull(intents.preview(policy, "他说现在几点了？"));
            var active = intents.publish("agent", draft.revision(), 7, false);
            assertEquals(policy, active.published());
            assertEquals(active, intents.view("agent"));
            assertEquals("time", intents.resolve("agent", "你好，中管，现在几点了？").action());
            assertTrue(intents.resolve("agent", "大冷总管，请问现在几点了？").reply().startsWith("现在是"));
            var fixedReplyContext = current;
            current = context("device-intents-clock", 1);
            String clockSession = (String) service.connect(current, "ai_worker").get("id");
            var clockTurn = service.input(current, clockSession, "clock", "你好，中管，现在几点了？");
            assertEquals("time", clockTurn.get("intent_action"));
            assertTrue(((String) service.reply(current, clockSession, (String) clockTurn.get("id"))
                    .get("text")).startsWith("现在是"));
            current = fixedReplyContext;
            var turn = service.input(current, session, "quick", "sample answer!");
            String id = (String) turn.get("id");
            assertEquals(id, service.input(current, session, "quick", "sample answer!").get("id"));
            assertEquals(0, executions);
            assertEquals(1L, ((Number) turn.get("intent_version")).longValue());
            assertEquals(turn.get("output_message_id"), service.reply(current, session, id).get("replyId"));
            assertEquals("fixed output", service.reply(current, session, id).get("text"));
            intents.publish("agent", active.revision(), 7, true);
            assertNull(intents.resolve("agent", "sample answer"));
            assertEquals("fixed output", service.reply(current, session, id).get("text"));
            service.abortTurn(current, session, id, ((Number) turn.get("control_version")).longValue());
            assertThrows(ConversationProblem.class, () -> service.reply(current, session, id));
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(service, "intents", null);
        }
    }

}
