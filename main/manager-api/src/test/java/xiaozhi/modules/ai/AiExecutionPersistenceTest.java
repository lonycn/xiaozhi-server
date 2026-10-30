package xiaozhi.modules.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import liquibase.*;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import xiaozhi.modules.ai.AiModels.*;
import xiaozhi.modules.conversation.*;
import xiaozhi.modules.conversation.ConversationModels.*;
import xiaozhi.modules.support.*;
import xiaozhi.modules.support.SupportModels.*;

/** Append-only fixtures in a new, explicitly named database; never resets application tables. */
@EnabledIfEnvironmentVariable(named="AI_TEST_JDBC_URL",matches=".+")
class AiExecutionPersistenceTest {
    static DriverManagerDataSource source;
    static JdbcTemplate jdbc;
    static ObjectMapper json;
    static DataSourceTransactionManager manager;
    static AtomicLong ids = new AtomicLong(100);
    AiExecutionService ai;
    ConversationService conversations;
    SupportService support;
    long owner, staff, outsider;
    Map<String,Long> created = new LinkedHashMap<>();

    @BeforeAll static void initialize() throws Exception {
        String url=System.getenv("AI_TEST_JDBC_URL");
        if (!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/xiaozhi_ai_test_[a-z0-9_]+(\\?.*)?"))
            throw new IllegalArgumentException("Use a disposable loopback xiaozhi_ai_test_* database");
        source=new DriverManagerDataSource(url,"root",System.getenv("AI_TEST_DB_PASSWORD"));
        jdbc=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);
        json=JsonMapper.builder().addModule(new JavaTimeModule()).build();
        jdbc.execute("CREATE TABLE sys_user(id BIGINT PRIMARY KEY,status TINYINT NOT NULL,super_admin TINYINT NOT NULL DEFAULT 0,username VARCHAR(80))");
        jdbc.execute("CREATE TABLE ai_agent(id VARCHAR(32) PRIMARY KEY,user_id BIGINT)");
        jdbc.execute("CREATE TABLE ai_agent_chat_history(id BIGINT PRIMARY KEY,content VARCHAR(100))");
        jdbc.update("INSERT INTO sys_user VALUES(1,1,1,'isolated-admin')");
        jdbc.update("INSERT INTO ai_agent_chat_history VALUES(1,'legacy-ai-sentinel')");
        for (String path:List.of("conversation","support","ai-execution")) {
            try(var connection=source.getConnection();var migration=new Liquibase("db/changelog/"+path+".changelog.yaml",new ClassLoaderResourceAccessor(),new JdbcConnection(connection))) {
                migration.update(new Contexts());migration.update(new Contexts());
            }
        }
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM DATABASECHANGELOG",Long.class));
    }
    @BeforeEach void services() {
        owner=ids.getAndAdd(3);staff=owner+1;outsider=owner+2;
        for(long user:List.of(owner,staff,outsider)) jdbc.update("INSERT INTO sys_user VALUES(?,1,0,?)",user,"isolated-"+user);
        ai=new AiExecutionService(jdbc,json,manager);ai.setEnabled(true);ai.setModel("test-model");
        conversations=new ConversationService(new ConversationDao(jdbc),json,manager);conversations.setAiExecution(ai);
        support=new SupportService(jdbc,json,manager,conversations);support.setAiExecution(ai);
        support.upsertAgent(1L,staff,new AgentUpdate("support_agent",true,2));
        support.presence(staff,new PresenceUpdate(true));
    }
    @AfterEach void leaveFixturesReadableAndCloseOwnPendingRuns() {
        // Terminal state changes close this test's work; records remain available for inspection.
        for(var entry:created.entrySet()) for(var run:ai.list(entry.getValue(),entry.getKey(),0,100).items())
            if(Set.of("queued","running","streaming").contains(run.status())) ai.cancel(entry.getValue(),entry.getKey(),run.runId());
        assertEquals("legacy-ai-sentinel",jdbc.queryForObject("SELECT content FROM ai_agent_chat_history WHERE id=1",String.class));
    }
    String conversation() { return conversation(owner); }
    String conversation(long actor) {
        var c=conversations.create(actor,new CreateConversation("isolated B2 "+UUID.randomUUID(),null));created.put(c.id(),actor);return c.id();
    }
    SendMessage text(String key,String value) {return new SendMessage(key,List.of(new MessagePart("text",value,null,null,null,null,null,null)));}
    Run run(String id) {return ai.list(owner,id,0,100).items().getFirst();}
    int error(Runnable work) {return assertThrows(ConversationProblem.class,work::run).status();}
    long number(String sql,Object...args) {return jdbc.queryForObject(sql,Long.class,args);}
    void expire(Task task) {jdbc.update("UPDATE ai_run SET lease_expires_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 MINUTE) WHERE id=?",task.runId());}

    @Test void streamsOneDurableMessageAndRealUsageWithIdempotentLastChunk() {
        String id=conversation();var input=conversations.send(owner,id,text("question","isolated question"));
        assertEquals(input,conversations.send(owner,id,text("question","isolated question")));
        assertEquals(1,ai.list(owner,id,0,100).items().size());assertEquals(2,conversations.messages(owner,id,0,100).items().size());
        var queued=run(id);
        assertTrue(Math.abs(java.time.Duration.between(queued.createdAt(),java.time.Instant.now()).toSeconds())<90,
                "DATETIME must use the connection time zone, not a guessed UTC offset");
        var task=ai.claim(new Claim("worker-1"));assertEquals(queued.runId(),task.runId());assertEquals("test-model",task.model());
        assertTrue(task.messages().stream().anyMatch(m->m.content().contains("isolated question")));
        ai.delta(task.runId(),new Delta(task.leaseToken(),1L,"你"));
        long events=number("SELECT last_event_seq FROM ai_conversation WHERE id=?",id);
        ai.delta(task.runId(),new Delta(task.leaseToken(),1L,"你"));assertEquals(events,number("SELECT last_event_seq FROM ai_conversation WHERE id=?",id));
        assertEquals(409,error(()->ai.delta(task.runId(),new Delta(task.leaseToken(),1L,"不同"))));
        ai.delta(task.runId(),new Delta(task.leaseToken(),2L,"好"));
        var result=ai.complete(task.runId(),new Complete(task.leaseToken(),2L,"stop",new Usage(2L,3L,5L),"test-request"));
        assertEquals("completed",result.status());
        var output=conversations.messages(owner,id,0,100).items().getLast();
        assertEquals(task.outputMessageId(),output.id());assertEquals(2,output.seq());assertEquals("你好",output.parts().getFirst().text());assertEquals("assistant",output.senderType());assertEquals("completed",output.status());
        assertEquals(1,number("SELECT COUNT(*) FROM ai_model_usage WHERE run_id=? AND input_tokens=2 AND output_tokens=3 AND total_tokens=5",task.runId()));
        assertEquals(0,number("SELECT COUNT(*) FROM ai_run WHERE id=? AND lease_token IS NOT NULL",task.runId()));
        conversations.send(owner,id,text("followup","continue"));var next=ai.claim(new Claim("worker-2"));
        assertTrue(next.messages().stream().anyMatch(m->m.role().equals("assistant")&&m.content().contains("你好")));
    }
    @Test void concurrentDuplicateInputsAndWorkerClaimsHaveOneWinner() throws Exception {
        String id=conversation();
        try(var pool=Executors.newFixedThreadPool(8)) {
            var start=new CountDownLatch(1);List<Future<String>> futures=new ArrayList<>();
            for(int i=0;i<20;i++) futures.add(pool.submit(()->{start.await();return conversations.send(owner,id,text("same","one question")).id();}));
            start.countDown();Set<String> outputs=new HashSet<>();for(var f:futures)outputs.add(f.get());assertEquals(1,outputs.size());
        }
        assertEquals(1,number("SELECT COUNT(*) FROM ai_run WHERE conversation_id=?",id));assertEquals(2,number("SELECT COUNT(*) FROM ai_message WHERE conversation_id=?",id));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);var a=pool.submit(()->{start.await();return ai.claim(new Claim("worker-a"));});var b=pool.submit(()->{start.await();return ai.claim(new Claim("worker-b"));});start.countDown();
            assertEquals(1,(a.get()==null?0:1)+(b.get()==null?0:1));
        }
    }
    @Test void humanTakeoverFencesLateOutputsAndResumedAiIncludesHumanAdvice() {
        String id=conversation();conversations.send(owner,id,text("question","before human"));var task=ai.claim(new Claim("worker"));
        ai.delta(task.runId(),new Delta(task.leaseToken(),1L,"partial"));
        var request=support.handoff(owner,id,new Handoff("handoff","text",null));
        assertEquals("canceled",run(id).status());
        assertEquals(409,error(()->ai.delta(task.runId(),new Delta(task.leaseToken(),2L,"late"))));
        assertEquals(409,error(()->ai.complete(task.runId(),new Complete(task.leaseToken(),1L,"stop",null,null))));
        support.claim(staff,request.requestId());support.send(staff,request.requestId(),text("human","请先检查固定螺栓"));
        conversations.send(owner,id,text("during-human","收到"));assertEquals(1,ai.list(owner,id,0,100).items().size());
        support.resolve(staff,request.requestId(),null);support.resumeAi(owner,id);
        conversations.send(owner,id,text("after-human","继续解释"));var resumed=ai.claim(new Claim("worker"));
        assertTrue(resumed.messages().stream().anyMatch(m->m.role().equals("assistant")&&m.content().contains("固定螺栓")));
        assertTrue(resumed.messages().stream().noneMatch(m->m.content().equals("partial")));
        assertEquals(404,error(()->ai.get(staff,id,resumed.runId())));
        assertEquals(404,error(()->conversations.get(staff,id)));
    }
    @Test void expiredLeaseHasFreshTokenResetAndFiniteRecovery() {
        String id=conversation();conversations.send(owner,id,text("question","recover"));var first=ai.claim(new Claim("first"));
        ai.delta(first.runId(),new Delta(first.leaseToken(),1L,"old partial"));expire(first);
        assertEquals(409,error(()->ai.heartbeat(first.runId(),new Lease(first.leaseToken()))));
        var second=ai.claim(new Claim("second"));assertEquals(first.runId(),second.runId());assertEquals(2,second.attempt());assertNotEquals(first.leaseToken(),second.leaseToken());
        assertTrue(conversations.messages(owner,id,0,100).items().getLast().parts().isEmpty());
        assertTrue(conversations.events(owner,id,0,100).items().stream().anyMatch(e->e.type().equals("assistant.message.reset")));
        assertEquals(409,error(()->ai.delta(first.runId(),new Delta(first.leaseToken(),2L,"stale"))));
        ai.fail(second.runId(),new Fail(second.leaseToken(),"PROVIDER_TIMEOUT"));ai.retry(owner,id,second.runId());
        var third=ai.claim(new Claim("third"));assertEquals(3,third.attempt());expire(third);assertNull(ai.claim(new Claim("fourth")));
        assertEquals("failed",run(id).status());assertEquals(409,error(()->ai.retry(owner,id,third.runId())));
    }
    @Test void newerInputCancelsOldRunAndOnlyLatestCanRetry() {
        String id=conversation();conversations.send(owner,id,text("old","old question"));var old=ai.claim(new Claim("worker"));
        conversations.send(owner,id,text("new","new question"));assertEquals("canceled",ai.get(owner,id,old.runId()).status());
        assertEquals(409,error(()->ai.retry(owner,id,old.runId())));var current=run(id);
        assertEquals(404,error(()->ai.cancel(outsider,id,current.runId())));
        String other=conversation(outsider);assertEquals(404,error(()->ai.get(outsider,other,current.runId())));
        ai.cancel(owner,id,current.runId());ai.retry(owner,id,current.runId());var task=ai.claim(new Claim("retry"));assertEquals(current.runId(),task.runId());
        assertEquals(409,error(()->ai.delta(old.runId(),new Delta(old.leaseToken(),1L,"late"))));
    }
    @Test void disabledOwnerAndChangedEpochCannotPublish() {
        String id=conversation();conversations.send(owner,id,text("question","account"));var task=ai.claim(new Claim("worker"));
        jdbc.update("UPDATE sys_user SET status=0 WHERE id=?",owner);
        assertEquals(409,error(()->ai.delta(task.runId(),new Delta(task.leaseToken(),1L,"leak"))));expire(task);assertNull(ai.claim(new Claim("recover")));
        assertEquals("canceled",run(id).status());assertTrue(conversations.messages(owner,id,0,100).items().getLast().parts().isEmpty());
        jdbc.update("UPDATE sys_user SET status=1 WHERE id=?",owner);
        conversations.send(owner,id,text("again","epoch"));var epochTask=ai.claim(new Claim("worker"));jdbc.update("UPDATE ai_conversation SET control_epoch=control_epoch+1 WHERE id=?",id);
        assertEquals(409,error(()->ai.delta(epochTask.runId(),new Delta(epochTask.leaseToken(),1L,"stale epoch"))));expire(epochTask);assertNull(ai.claim(new Claim("recover")));
        assertEquals("canceled",run(id).status());
    }
    @Test void failedLedgerWriteRollsBackRunTextAndEventTogether() {
        String id=conversation();conversations.send(owner,id,text("question","rollback"));var task=ai.claim(new Claim("worker"));
        var fault=new AtomicBoolean(true);
        var failingJdbc=new JdbcTemplate(source) {
            @Override public int update(String sql,Object...args) {
                if(fault.get()&&sql.startsWith("INSERT INTO ai_conversation_event"))throw new IllegalStateException("isolated injected event failure");
                return super.update(sql,args);
            }
        };
        var service=new AiExecutionService(failingJdbc,json,manager);service.setEnabled(true);
        long before=number("SELECT last_event_seq FROM ai_conversation WHERE id=?",id);
        assertThrows(IllegalStateException.class,()->service.delta(task.runId(),new Delta(task.leaseToken(),1L,"rolled back")));
        assertEquals(0,run(id).lastChunkSequence());assertEquals(before,number("SELECT last_event_seq FROM ai_conversation WHERE id=?",id));assertTrue(conversations.messages(owner,id,0,100).items().getLast().parts().isEmpty());
        fault.set(false);service.delta(task.runId(),new Delta(task.leaseToken(),1L,"committed"));assertEquals(1,run(id).lastChunkSequence());
    }
    @Test void disabledFeaturePreservesTheExistingLedger() {
        ai.setEnabled(false);String id=conversation();conversations.send(owner,id,text("plain","ledger only"));
        assertTrue(ai.list(owner,id,0,100).items().isEmpty());assertEquals(1,conversations.messages(owner,id,0,100).items().size());
    }
}
