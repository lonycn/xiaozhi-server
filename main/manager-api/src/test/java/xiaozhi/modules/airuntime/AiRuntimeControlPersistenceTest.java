package xiaozhi.modules.airuntime;

import static org.junit.jupiter.api.Assertions.*;
import static xiaozhi.modules.airuntime.AiRuntimeControl.Flow.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import liquibase.*;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import xiaozhi.modules.conversation.ConversationProblem;

/** Real InnoDB transactions against a NEW retained schema. No database, row or volume is reset. */
@EnabledIfEnvironmentVariable(named="AI_RUNTIME_TEST_JDBC_URL",matches=".+")
class AiRuntimeControlPersistenceTest {
    static JdbcTemplate db;static DataSource source;static DataSourceTransactionManager manager;
    static AtomicLong users=new AtomicLong(2000);
    MockEnvironment env;AiRuntimeControl control;TransactionTemplate tx;long owner;List<Long> owners=new ArrayList<>();
    @BeforeAll static void database()throws Exception{
        String url=System.getenv("AI_RUNTIME_TEST_JDBC_URL");
        if(!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/xiaozhi_ai_runtime_test_[a-z0-9_]+(\\?.*)?"))throw new IllegalArgumentException("A NEW isolated runtime-control schema is required");
        source=new DriverManagerDataSource(url,"root",System.getenv("AI_RUNTIME_TEST_DB_PASSWORD"));db=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Long.class),"Never reset a preexisting schema");
        // Exact columns consumed by the core; provider IO and business content are outside this test.
        db.execute("CREATE TABLE sys_user(id BIGINT PRIMARY KEY,status TINYINT NOT NULL,super_admin TINYINT NOT NULL DEFAULT 0)");
        db.execute("CREATE TABLE ai_conversation(id VARCHAR(32) PRIMARY KEY,owner_user_id BIGINT NOT NULL)");
        db.execute("CREATE TABLE ai_run(id VARCHAR(32) PRIMARY KEY,conversation_id VARCHAR(32),status VARCHAR(24),created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3))");
        db.execute("CREATE TABLE ai_realtime_session(id VARCHAR(32) PRIMARY KEY,owner_user_id BIGINT,status VARCHAR(24),created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3))");
        db.execute("CREATE TABLE ai_realtime_material(id VARCHAR(32) PRIMARY KEY,session_id VARCHAR(32),status VARCHAR(24),created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3))");
        db.execute("CREATE TABLE ai_answer_speech_job(id VARCHAR(32) PRIMARY KEY,owner_user_id BIGINT,status VARCHAR(24),created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3))");
        db.execute("CREATE TABLE ai_provider_request_usage(run_id VARCHAR(32),total_tokens BIGINT NULL,created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3))");
        db.execute("CREATE TABLE ai_realtime_provider_response(id VARCHAR(32) PRIMARY KEY,session_id VARCHAR(32))");
        db.execute("CREATE TABLE ai_realtime_response_usage(response_row_id VARCHAR(32),total_tokens BIGINT NULL,created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3))");
        db.execute("CREATE TABLE ai_realtime_perception_usage(material_id VARCHAR(32),total_tokens BIGINT NULL,created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3))");
        db.execute("CREATE TABLE ai_answer_speech_usage(job_id VARCHAR(32),characters BIGINT NULL,created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3))");
        // Simulate a failed DDL prefix: retry must preserve the seed and finish without cleanup.
        db.execute("CREATE TABLE ai_runtime_gate(id TINYINT PRIMARY KEY,CHECK(id=1)) ENGINE=InnoDB");db.update("INSERT INTO ai_runtime_gate(id) VALUES(1)");
        for(int i=0;i<2;i++)try(var connection=source.getConnection();var migration=new Liquibase("db/changelog/ai-runtime-control.changelog.yaml",new ClassLoaderResourceAccessor(),new JdbcConnection(connection))){migration.update(new Contexts());}
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ai_runtime_gate",Long.class));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM DATABASECHANGELOG",Long.class));
    }
    @BeforeEach void fixture(){env=new MockEnvironment().withProperty("ai.runtime-control.enabled","true").withProperty("ai.runtime-control.user-max-active","100").withProperty("ai.runtime-control.service-max-active","10000").withProperty("ai.runtime-control.user-hourly-requests","1000").withProperty("ai.runtime-control.service-hourly-requests","100000");control=new AiRuntimeControl(db,env,manager);tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);owner=user(false);}
    @AfterEach void retain(){control.close();for(long id:owners){db.update("UPDATE ai_run r JOIN ai_conversation c ON c.id=r.conversation_id SET r.status='completed' WHERE c.owner_user_id=?",id);db.update("UPDATE ai_realtime_session SET status='ended' WHERE owner_user_id=?",id);db.update("UPDATE ai_answer_speech_job SET status='completed' WHERE owner_user_id=?",id);db.update("UPDATE ai_runtime_admission SET active=0,released_at=CURRENT_TIMESTAMP(3) WHERE owner_user_id=?",id);}}
    long user(boolean admin){long id=users.incrementAndGet();db.update("INSERT INTO sys_user(id,status,super_admin) VALUES(?,1,?)",id,admin?1:0);owners.add(id);return id;}
    static String id(){return UUID.randomUUID().toString().replace("-","");}
    String resource(AiRuntimeControl.Flow flow,long actor,String state){String id=id();switch(flow){
        case AI->{String c=id();db.update("INSERT INTO ai_conversation(id,owner_user_id) VALUES(?,?)",c,actor);db.update("INSERT INTO ai_run(id,conversation_id,status) VALUES(?,?,?)",id,c,state);}
        case REALTIME->db.update("INSERT INTO ai_realtime_session(id,owner_user_id,status) VALUES(?,?,?)",id,actor,state);
        case SPEECH->db.update("INSERT INTO ai_answer_speech_job(id,owner_user_id,status) VALUES(?,?,?)",id,actor,state);
        case MATERIAL->{String session=resource(REALTIME,actor,"active");db.update("INSERT INTO ai_realtime_material(id,session_id,status) VALUES(?,?,?)",id,session,state);}
    }return id;}
    String create(AiRuntimeControl.Flow flow,long actor){return tx.execute(t->{String id=resource(flow,actor,flow==REALTIME?"pending":"queued");control.admit(flow,id,actor);return id;});}
    void finish(AiRuntimeControl.Flow flow,String id){String table=switch(flow){case AI->"ai_run";case REALTIME->"ai_realtime_session";case MATERIAL->"ai_realtime_material";case SPEECH->"ai_answer_speech_job";};tx.execute(t->{db.update("UPDATE "+table+" SET status='completed' WHERE id=?",id);control.release(flow,id);return null;});}
    @Test void defaultOffDoesNotTouchDatabaseAndEnabledRequiresAtomicResourceTransaction(){var off=new AiRuntimeControl(db,new MockEnvironment(),manager);off.admit(AI,id(),owner);assertEquals(404,assertThrows(ConversationProblem.class,()->off.snapshot(owner)).status());assertThrows(IllegalStateException.class,()->control.admit(AI,id(),owner));}
    @Test void concurrentCrossFlowAdmissionsCannotOverbookUserOrService()throws Exception{
        env.setProperty("ai.runtime-control.user-max-active","1");env.setProperty("ai.runtime-control.service-max-active","1");var pool=Executors.newFixedThreadPool(6);CountDownLatch start=new CountDownLatch(1);List<Future<Object>> jobs=new ArrayList<>();
        for(int i=0;i<6;i++){var flow=i%2==0?AI:SPEECH;jobs.add(pool.submit(()->{start.await();try{return create(flow,owner);}catch(ConversationProblem denied){return denied;}}));}
        try{start.countDown();int accepted=0,rejected=0;for(var job:jobs){Object value=job.get(10,TimeUnit.SECONDS);if(value instanceof String)accepted++;else{assertEquals(429,((ConversationProblem)value).status());rejected++;}}assertEquals(1,accepted);assertEquals(5,rejected);assertEquals(1,control.snapshot(owner).user().active());assertEquals(1,control.snapshot(owner).user().hourlyRequests());}finally{pool.shutdownNow();}
    }
    @Test void rollbackLeavesNeitherReservationNorRequestBudgetAndDenialRollsBackResource(){env.setProperty("ai.runtime-control.user-max-active","1");String[] rolled={null};assertThrows(IllegalStateException.class,()->tx.execute(t->{rolled[0]=resource(AI,owner,"queued");control.admit(AI,rolled[0],owner);throw new IllegalStateException("rollback fixture");}));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ai_run WHERE id=?",Long.class,rolled[0]));assertEquals(0,control.snapshot(owner).user().hourlyRequests());String accepted=create(SPEECH,owner);assertThrows(ConversationProblem.class,()->create(AI,owner));assertEquals(1,control.snapshot(owner).user().active());finish(SPEECH,accepted);}
    @Test void outerRepeatableReadSnapshotCannotMissACommittedAdmission()throws Exception{
        env.setProperty("ai.runtime-control.service-max-active","1");long second=user(false);var pool=Executors.newSingleThreadExecutor();CountDownLatch snapshot=new CountDownLatch(1),committed=new CountDownLatch(1);
        try{var stale=pool.submit(()->assertThrows(ConversationProblem.class,()->tx.execute(t->{db.queryForObject("SELECT COUNT(*) FROM ai_runtime_admission",Long.class);snapshot.countDown();await(committed);String id=resource(SPEECH,owner,"queued");control.admit(SPEECH,id,owner);return null;})));assertTrue(snapshot.await(5,TimeUnit.SECONDS));create(AI,second);committed.countDown();assertEquals("AI_RUNTIME_SERVICE_CONCURRENCY",stale.get(10,TimeUnit.SECONDS).getMessage());}finally{committed.countDown();pool.shutdownNow();}
    }
    @Test void terminalReleaseAndExplicitRetryAreAtomicAndRetryConsumesAnotherRequest(){env.setProperty("ai.runtime-control.user-max-active","1");String run=create(AI,owner);tx.execute(t->{db.update("UPDATE ai_run SET status='failed' WHERE id=?",run);control.release(AI,run);db.update("UPDATE ai_run SET status='queued' WHERE id=?",run);control.admitRetry(AI,run,owner);control.admit(AI,run,owner);return null;});var view=control.snapshot(owner);assertEquals(1,view.user().active());assertEquals(2,view.user().hourlyRequests());finish(AI,run);assertEquals(0,control.snapshot(owner).user().active());assertEquals(2,control.snapshot(owner).user().hourlyRequests());}
    @Test void sameTransactionReplacementDoesNotCountTheUncommittedCanceledCanonicalRow(){env.setProperty("ai.runtime-control.user-max-active","1");String old=create(AI,owner);tx.execute(t->{db.update("UPDATE ai_run SET status='canceled' WHERE id=?",old);control.release(AI,old);String next=resource(SPEECH,owner,"queued");control.admit(SPEECH,next,owner);return null;});assertEquals(1,control.snapshot(owner).user().active());}
    @Test void legacyResourcesCountWhenControlWasOffAndLateTerminalOrExpiryReleasesQuota(){env.setProperty("ai.runtime-control.user-max-active","1");String legacy=resource(SPEECH,owner,"synthesizing");assertThrows(ConversationProblem.class,()->create(AI,owner));assertEquals(1,control.snapshot(owner).user().hourlyRequests());db.update("UPDATE ai_answer_speech_job SET status='failed' WHERE id=?",legacy);String run=create(AI,owner);db.update("UPDATE ai_runtime_admission SET expires_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE resource_id=?",run);assertEquals(1,control.snapshot(owner).user().active(),"A wall clock must not pretend an active provider stopped");db.update("UPDATE ai_run SET status='failed' WHERE id=?",run);String speech=create(SPEECH,owner);assertEquals(0,db.queryForObject("SELECT active FROM ai_runtime_admission WHERE resource_id=?",Integer.class,run));assertEquals(1,control.snapshot(owner).user().active());finish(SPEECH,speech);}
    @Test void hourlyBudgetIncludesReleasedRequestsAndExpiresWithoutDeletingHistory(){env.setProperty("ai.runtime-control.user-hourly-requests","2");String one=create(AI,owner);finish(AI,one);String two=create(SPEECH,owner);finish(SPEECH,two);assertEquals("AI_RUNTIME_USER_HOURLY_BUDGET",assertThrows(ConversationProblem.class,()->create(AI,owner)).getMessage());db.update("UPDATE ai_runtime_admission SET created_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 61 MINUTE) WHERE owner_user_id=?",owner);db.update("UPDATE ai_run r JOIN ai_conversation c ON c.id=r.conversation_id SET r.created_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 61 MINUTE) WHERE c.owner_user_id=?",owner);db.update("UPDATE ai_answer_speech_job SET created_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 61 MINUTE) WHERE owner_user_id=?",owner);create(AI,owner);assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM ai_runtime_admission WHERE owner_user_id=?",Long.class,owner));}
    @Test void snapshotDoesNotWaitForCanonicalRowLocksAndDoesNotExposeOtherOwnerUsage()throws Exception{
        long other=user(false),admin=user(true);String run=create(AI,owner),secret=create(AI,other),speech=create(SPEECH,owner);db.update("INSERT INTO ai_provider_request_usage(run_id,total_tokens) VALUES(?,7),(?,NULL),(?,999)",run,run,secret);db.update("INSERT INTO ai_answer_speech_usage(job_id,characters) VALUES(?,17),(?,NULL)",speech,speech);String session=resource(REALTIME,owner,"active"),response=id(),material=resource(MATERIAL,owner,"acknowledged");db.update("INSERT INTO ai_realtime_provider_response(id,session_id) VALUES(?,?)",response,session);db.update("INSERT INTO ai_realtime_response_usage(response_row_id,total_tokens) VALUES(?,NULL)",response);db.update("INSERT INTO ai_realtime_perception_usage(material_id,total_tokens) VALUES(?,3)",material);
        var pool=Executors.newSingleThreadExecutor();CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1);
        try{var lock=pool.submit(()->tx.execute(t->{db.queryForObject("SELECT id FROM ai_run WHERE id=? FOR UPDATE",String.class,run);locked.countDown();await(release);return null;}));assertTrue(locked.await(5,TimeUnit.SECONDS));var view=control.snapshot(owner);assertNull(view.service());assertEquals(10L,view.usage().knownTokens());assertEquals(4,view.usage().tokenReports());assertEquals(2,view.usage().unknownTokenReports());assertEquals(17L,view.usage().knownSpeechCharacters());assertEquals(1,view.usage().unknownSpeechReports());assertNotNull(control.snapshot(admin).service());assertNull(control.snapshot(admin).usage().knownTokens());assertNull(control.snapshot(admin).usage().knownSpeechCharacters());release.countDown();lock.get(5,TimeUnit.SECONDS);}finally{release.countDown();pool.shutdownNow();}
    }
    @Test void everyFlowTerminalStateAndMissingExpiredReservationReleaseOnlyTheActiveSlot(){
        for(var flow:AiRuntimeControl.Flow.values()){
            String resource=create(flow,owner);long before=control.snapshot(owner).user().active();assertTrue(before>=1);
            String table=switch(flow){case AI->"ai_run";case REALTIME->"ai_realtime_session";case MATERIAL->"ai_realtime_material";case SPEECH->"ai_answer_speech_job";};
            String state=flow==REALTIME?"expired":flow==MATERIAL?"acknowledged":"failed";
            db.update("UPDATE "+table+" SET status=? WHERE id=?",state,resource);
            assertEquals(before-1,control.snapshot(owner).user().active());
            // Next admission performs durable reconciliation without requiring the terminal poller.
            String next=create(SPEECH,owner);assertEquals(0,db.queryForObject("SELECT active FROM ai_runtime_admission WHERE resource_id=?",Integer.class,resource));finish(SPEECH,next);
        }
        String missing=id();tx.execute(t->{control.admit(AI,missing,owner);return null;});
        long before=control.snapshot(owner).user().active();db.update("UPDATE ai_runtime_admission SET expires_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE resource_id=?",missing);
        assertEquals(before-1,control.snapshot(owner).user().active());String next=create(SPEECH,owner);assertEquals(0,db.queryForObject("SELECT active FROM ai_runtime_admission WHERE resource_id=?",Integer.class,missing));finish(SPEECH,next);
    }
    @Test void serviceLimitIsSharedAcrossDifferentUsersAndFlowTypes()throws Exception{
        env.setProperty("ai.runtime-control.service-max-active","1");long other=user(false);var pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try{var first=pool.submit(()->{start.await();try{return (Object)create(REALTIME,owner);}catch(ConversationProblem denied){return denied;}});var second=pool.submit(()->{start.await();try{return (Object)create(SPEECH,other);}catch(ConversationProblem denied){return denied;}});start.countDown();var result=List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));assertEquals(1,result.stream().filter(String.class::isInstance).count());var denied=(ConversationProblem)result.stream().filter(ConversationProblem.class::isInstance).findFirst().orElseThrow();assertEquals("AI_RUNTIME_SERVICE_CONCURRENCY",denied.getMessage());}finally{pool.shutdownNow();}
    }
    @Test void exhaustedMainPoolCannotStarveTheIndependentObserverAndEveryPoolIsClosed()throws Exception{
        DriverManagerDataSource fixture=(DriverManagerDataSource)source;var pool=new com.alibaba.druid.pool.DruidDataSource();
        pool.setUrl(fixture.getUrl());pool.setUsername(fixture.getUsername());pool.setPassword(fixture.getPassword());pool.setInitialSize(0);pool.setMinIdle(0);pool.setMaxActive(2);pool.setMaxWait(250);
        var pooled=new JdbcTemplate(pool);var transaction=new TransactionTemplate(new DataSourceTransactionManager(pool));transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try(var isolated=new AiRuntimeControl(pooled,env,new DataSourceTransactionManager(pool));var parked=pool.getConnection()){
            assertFalse(parked.isClosed());String conversation=id(),run=id();
            transaction.execute(t->{pooled.update("INSERT INTO ai_conversation(id,owner_user_id) VALUES(?,?)",conversation,owner);pooled.update("INSERT INTO ai_run(id,conversation_id,status) VALUES(?,?,'queued')",run,conversation);assertEquals(2,pool.getActiveCount(),"Every main-pool connection is occupied");isolated.admit(AI,run,owner);return null;});
            assertEquals(1,isolated.snapshot(owner).user().active());
            var field=AiRuntimeControl.class.getDeclaredField("observerPool");field.setAccessible(true);var observer=(com.alibaba.druid.pool.DruidDataSource)field.get(isolated);
            assertEquals(2,observer.getMaxActive());assertEquals(1000,observer.getMaxWait());assertTrue(observer.getDefaultReadOnly());assertEquals(java.sql.Connection.TRANSACTION_READ_COMMITTED,observer.getDefaultTransactionIsolation());
            isolated.close();assertTrue(observer.isClosed(),"The extra two-connection budget must be released on bean shutdown");
        }finally{pool.close();}assertTrue(pool.isClosed());
    }
    @Test void unsupportedPooledDataSourceFailsClosedWithoutBorrowingOrLeakingConnections(){
        var unknown=org.mockito.Mockito.mock(javax.sql.DataSource.class);var sql=new JdbcTemplate(unknown);try(var unsupported=new AiRuntimeControl(sql,env,new DataSourceTransactionManager(unknown))){
            assertEquals("AI_RUNTIME_OBSERVER_UNSUPPORTED",assertThrows(ConversationProblem.class,()->unsupported.snapshot(owner)).getMessage());
            org.mockito.Mockito.verifyNoInteractions(unknown);
        }
    }
    static void await(CountDownLatch latch){try{assertTrue(latch.await(8,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}}
}
