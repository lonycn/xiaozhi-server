package xiaozhi.modules.supportsummary;

import static org.junit.jupiter.api.Assertions.*;
import static xiaozhi.modules.supportsummary.SummaryModels.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import liquibase.*;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import xiaozhi.modules.conversation.*;
import static xiaozhi.modules.conversation.ConversationModels.*;
import xiaozhi.modules.support.*;
import static xiaozhi.modules.support.SupportModels.*;

/** Creates only new fixtures in a fresh schema; no cleanup, resets or deletion. */
@EnabledIfEnvironmentVariable(named="SUMMARY_TEST_JDBC_URL",matches=".+")
class SupportSummaryPersistenceTest {
    static JdbcTemplate jdbc;
    static ConversationService conversations;
    static SupportService support;
    static SupportSummaryService summaries;
    @BeforeAll static void initialize()throws Exception {
        String url=System.getenv("SUMMARY_TEST_JDBC_URL");
        if(!url.matches("jdbc:mysql://127\\.0\\.0\\.1:34776/xiaozhi_summary_test_[a-z0-9_]+(\\?.*)?"))throw new IllegalArgumentException("Fresh isolated loopback schema required");
        var source=new DriverManagerDataSource(url,"root",System.getenv("SUMMARY_TEST_DB_PASSWORD")); jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE sys_user(id BIGINT PRIMARY KEY,status TINYINT NOT NULL,super_admin TINYINT NOT NULL DEFAULT 0,username VARCHAR(80))");
        jdbc.execute("CREATE TABLE ai_agent(id VARCHAR(32) PRIMARY KEY,user_id BIGINT)");
        jdbc.execute("CREATE TABLE ai_agent_chat_history(id BIGINT PRIMARY KEY,content VARCHAR(100))");
        jdbc.update("INSERT INTO ai_agent_chat_history VALUES(1,'preserve-summary-legacy')");
        for(long id=1;id<=8;id++)jdbc.update("INSERT INTO sys_user VALUES(?,1,?,?)",id,id==1?1:0,"summary-test-"+id);
        for(String path:List.of("conversation","support","attachments","support-summary")) {
            try(var connection=source.getConnection();var migration=new Liquibase("db/changelog/"+path+".changelog.yaml",new ClassLoaderResourceAccessor(),new JdbcConnection(connection))) {
                migration.update(new Contexts()); migration.update(new Contexts());
            }
        }
        assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM DATABASECHANGELOG",Long.class));
        var json=JsonMapper.builder().addModule(new JavaTimeModule()).build();var tx=new DataSourceTransactionManager(source);var dao=new ConversationDao(jdbc);
        conversations=new ConversationService(dao,json,tx);summaries=new SupportSummaryService(jdbc,json,new ConversationAccessService(dao),tx);support=new SupportService(jdbc,json,tx,conversations);support.setSummaries(summaries);
        support.upsertAgent(1L,3L,new AgentUpdate("support_agent",true,20));support.upsertAgent(1L,4L,new AgentUpdate("expert",true,20));
    }
    String conversation(){return conversations.create(2L,new CreateConversation("summary-test",null)).id();}
    Message user(String id,String text){return conversations.send(2L,id,new SendMessage(UUID.randomUUID().toString(),List.of(new MessagePart("text",text,null,null,null,null,null,null))));}
    String handoff(String id){return support.handoff(2L,id,new Handoff(UUID.randomUUID().toString(),"text",null)).requestId();}
    void claim(long actor,String request){support.presence(actor,new PresenceUpdate(true));support.claim(actor,request);}
    Revision latest(long actor,String id){return summaries.history(actor,id,0,20).getFirst();}
    Map<String,List<Item>> fields(Revision revision){return new LinkedHashMap<>(revision.fields());}
    int error(Runnable action){return assertThrows(ConversationProblem.class,action::run).status();}
    String raw(String conversation,String sender,String text,String attachment)throws Exception {
        long seq=jdbc.queryForObject("SELECT last_seq+1 FROM ai_conversation WHERE id=?",Long.class,conversation);String id=UUID.randomUUID().toString().replace("-","");
        var parts=attachment==null?List.of(Map.of("type","text","text",text)):List.of(Map.of("type","image","attachment_id",attachment));
        String encoded=JsonMapper.builder().build().writeValueAsString(parts);
        jdbc.update("INSERT INTO ai_message(id,conversation_id,seq,client_message_id,sender_type,sender_id,parts_json,request_hash,status) VALUES(?,?,?,?,?,?,?,?,'completed')",id,conversation,seq,id,sender,"summary-fixture",encoded,"a".repeat(64));
        jdbc.update("UPDATE ai_conversation SET last_seq=? WHERE id=?",seq,conversation);
        if(attachment!=null)jdbc.update("INSERT INTO ai_attachment(id,conversation_id,uploader_user_id,client_upload_id,filename,mime_type,kind,size,sha256,status,linked_message_id,linked_seq,metadata_json,expires_at) VALUES(?,?,2,?,'evidence.jpg','image/jpeg','image',10,?,'ready',?,?,'{}',DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 1 DAY))",attachment,conversation,id,"b".repeat(64),id,seq);
        return id;
    }
    @Test void handoffPersistsNineFieldsAndAuthorizedOriginalEvidenceWithoutPublishing()throws Exception {
        String id=conversation();var user=user(id,"用户说：冷库温度升高，设备型号尚不清楚。");String ai=raw(id,"assistant","AI猜测：可能缺制冷剂",null);String image=raw(id,"user",null,"81001");
        long messages=jdbc.queryForObject("SELECT COUNT(*) FROM ai_message WHERE conversation_id=?",Long.class,id);
        String req=handoff(id);
        assertEquals(404,error(()->summaries.history(2L,id,0,20))); // owner/external cannot read internal data
        assertEquals(404,error(()->summaries.history(5L,id,0,20))); // unrelated
        assertEquals(404,error(()->summaries.history(3L,id,0,20))); // queued staff has no grant
        claim(3L,req);var draft=latest(3,id);
        assertEquals(new HashSet<>(FIELDS),draft.fields().keySet());assertEquals("deterministic_excerpt",draft.origin());assertNull(draft.editedBy());
        assertEquals(user.id(),draft.fields().get("user_goal").getFirst().evidence().getFirst().messageId());
        assertEquals("user_statement",draft.fields().get("user_goal").getFirst().attribution());
        assertEquals("unknown",draft.fields().get("project/equipment").getFirst().attribution());
        assertEquals("unknown",draft.fields().get("verified_facts").getFirst().attribution());
        assertEquals(ai,draft.fields().get("hypotheses").getFirst().evidence().getFirst().messageId());
        assertEquals("ai_inference",draft.fields().get("hypotheses").getFirst().attribution());
        var evidence=draft.fields().get("evidence_attachments").getFirst().evidence().getFirst();assertEquals(image,evidence.messageId());assertEquals("81001",evidence.attachmentId());assertEquals(3,evidence.seq());
        assertEquals(messages,jdbc.queryForObject("SELECT COUNT(*) FROM ai_message WHERE conversation_id=?",Long.class,id));
        assertTrue(conversations.events(2L,id,0,100).items().stream().noneMatch(event->event.type().contains("summary")));
        assertEquals(req,support.handoff(2L,id,new Handoff("another-key","text",null)).requestId());assertEquals(1,latest(3,id).version());
    }
    @Test void factsRequireNonAiEvidenceAndExplicitStaffVerificationAndCannotSpoofSources()throws Exception {
        String id=conversation();var original=user(id,"用户说已检查电源，电源正常");String ai=raw(id,"assistant","AI断言压缩机已损坏",null);String req=handoff(id);claim(3,req);var draft=latest(3,id);
        var edited=fields(draft);var userRef=new Evidence(original.id(),original.seq(),null,"已检查电源");
        edited.put("verified_facts",List.of(new Item("电源已检查","verified_fact",List.of(new Evidence(ai,2,null,"压缩机已损坏")),"复核")));
        assertEquals(400,error(()->summaries.edit(3L,id,new Edit(1,edited))));
        edited.put("verified_facts",List.of(new Item("电源已检查","verified_fact",List.of(userRef),null)));
        assertEquals(400,error(()->summaries.edit(3L,id,new Edit(1,edited))));
        edited.put("verified_facts",List.of(new Item("用户已确认检查电源","verified_fact",List.of(userRef),"本客服复核用户确认记录；未获取设备遥测")));
        var revision=summaries.edit(3L,id,new Edit(1,edited));assertEquals(2,revision.version());assertEquals(1,revision.parentVersion());assertEquals(3,revision.editedBy());assertEquals("staff_edit",revision.origin());
        assertEquals(409,error(()->summaries.edit(3L,id,new Edit(1,edited))));assertEquals(404,error(()->summaries.edit(4L,id,new Edit(2,edited))));
        String other=conversation();var foreign=user(other,"外部证据");edited.put("symptoms",List.of(new Item("跨会话伪造","user_statement",List.of(new Evidence(foreign.id(),foreign.seq(),null,"外部证据")),null)));
        assertEquals(400,error(()->summaries.edit(3L,id,new Edit(2,edited))));
        edited.put("symptoms",List.of(new Item("伪造摘录","user_statement",List.of(new Evidence(original.id(),original.seq(),null,"不存在的引用")),null)));
        assertEquals(400,error(()->summaries.edit(3L,id,new Edit(2,edited))));
        edited.put("symptoms",List.of(new Item("伪造seq","user_statement",List.of(new Evidence(original.id(),99,null,"电源正常")),null)));
        assertEquals(400,error(()->summaries.edit(3L,id,new Edit(2,edited))));
        edited.put("symptoms",List.of(new Item("AI当用户","user_statement",List.of(new Evidence(ai,2,null,"压缩机已损坏")),null)));
        assertEquals(400,error(()->summaries.edit(3L,id,new Edit(2,edited))));
        assertEquals(2,summaries.history(3L,id,0,20).size());
    }
    @Test void releasedAssignmentsCannotSeeLaterVersionsEvenWithoutNewMessagesAndTargetSeesHistory() {
        String id=conversation();user(id,"原始咨询");String req=handoff(id);claim(3,req);var first=latest(3,id);
        support.requeue(3L,req,new EndRequest("转专家"),false);claim(4,req);
        assertEquals(first,latest(4,id));
        var changed=fields(first);changed.put("missing_information",List.of(new Item("专家后续内部追问","unknown",List.of(),null)));
        var second=summaries.edit(4L,id,new Edit(1,changed));assertEquals(first.sourceThroughSeq(),second.sourceThroughSeq());
        assertEquals(List.of(first),summaries.history(3L,id,0,20));assertEquals(second,latest(4,id));assertEquals(404,error(()->summaries.edit(3L,id,new Edit(2,changed))));
        user(id,"转派后的消息");changed.put("missing_information",List.of(new Item("更新内部信息","unknown",List.of(),null)));summaries.edit(4L,id,new Edit(2,changed));
        support.resolve(4L,req,new EndRequest("结束"));assertEquals(3,latest(4,id).version());assertEquals(1,latest(3,id).version());
        conversations.send(2L,id,new SendMessage("after-release",List.of(new MessagePart("text","恢复前用户消息",null,null,null,null,null,null))));
        assertEquals(1,latest(3,id).version());assertEquals(404,error(()->summaries.history(2L,id,0,20)));
        jdbc.update("UPDATE sys_user SET status=0 WHERE id=4");assertEquals(404,error(()->summaries.history(4L,id,0,20)));jdbc.update("UPDATE sys_user SET status=1 WHERE id=4");
    }
    @Test void concurrentEditsHaveOneWinnerAndAttachmentReferencesAreBound()throws Exception {
        String id=conversation();user(id,"带图的原文");String image=raw(id,"user",null,"81002");String req=handoff(id);claim(3,req);var first=latest(3,id);var changed=fields(first);
        changed.put("evidence_attachments",List.of(new Item("伪造附件","evidence_reference",List.of(new Evidence(image,2,"81001",null)),null)));
        assertEquals(400,error(()->summaries.edit(3L,id,new Edit(1,changed))));
        changed.put("evidence_attachments",first.fields().get("evidence_attachments"));
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Integer> edit=()->{start.await();try{summaries.edit(3L,id,new Edit(1,changed));return 200;}catch(ConversationProblem p){return p.status();}};
            var a=executor.submit(edit);var b=executor.submit(edit);start.countDown();assertEquals(Set.of(200,409),Set.of(a.get(),b.get()));
        }
        assertEquals(2,latest(3,id).version());
        support.resolve(3L,req,new EndRequest("并发验证结束"));
    }
    @Test void activeRequestsFromBeforeMigrationCanCreateOneDraftWithoutBackfill() {
        String id=conversation();user(id,"升级前正在接待的原文");
        var legacySupport=new SupportService(jdbc,JsonMapper.builder().addModule(new JavaTimeModule()).build(),new DataSourceTransactionManager(jdbc.getDataSource()),conversations);
        String req=legacySupport.handoff(2L,id,new Handoff("legacy-active","text",null)).requestId();claim(3,req);
        assertTrue(summaries.history(3L,id,0,20).isEmpty());assertEquals(404,error(()->summaries.prepare(2L,id)));assertEquals(404,error(()->summaries.prepare(4L,id)));
        var first=summaries.prepare(3L,id);assertEquals(first,summaries.prepare(3L,id));assertEquals(1,first.version());
        support.resolve(3L,req,new EndRequest("升级兼容验证结束"));assertEquals(404,error(()->summaries.prepare(3L,id)));
    }
    /** Establish a real old RR read view before another connection commits the newer state. */
    <T> T afterOldSnapshot(Runnable commitNewState,java.util.concurrent.Callable<T> operation)throws Exception {
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var snapshot=new java.util.concurrent.CountDownLatch(1);var committed=new java.util.concurrent.CountDownLatch(1);
            var future=executor.submit(()->{
                var outer=new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
                outer.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
                return outer.execute(status->{
                    assertEquals("REPEATABLE-READ",jdbc.queryForObject("SELECT @@transaction_isolation",String.class));
                    jdbc.queryForObject("SELECT COUNT(*) FROM support_assignment",Long.class); // consistent read creates the old snapshot
                    snapshot.countDown();
                    try {assertTrue(committed.await(10,java.util.concurrent.TimeUnit.SECONDS));T result=operation.call();
                        // An asserted inner 409 marks the joined transaction rollback-only; explicitly acknowledge that rollback.
                        if(status.isRollbackOnly())status.setRollbackOnly();return result;}
                    catch(Exception failure){throw new RuntimeException(failure);}
                });
            });
            assertTrue(snapshot.await(10,java.util.concurrent.TimeUnit.SECONDS));
            try {commitNewState.run();}finally{committed.countDown();}
            return future.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
    }
    @Test void oldRrSnapshotCannotMissCommittedReleaseWhenSavingSameSeqRevision()throws Exception {
        String id=conversation();user(id,"同seq转派前原文");String req=handoff(id);claim(3,req);var first=latest(3,id);var edited=fields(first);
        edited.put("missing_information",List.of(new Item("新专家内部补充","unknown",List.of(),null)));
        var second=afterOldSnapshot(()->{support.requeue(3L,req,new EndRequest("旧RR回归转派"),false);claim(4,req);},()->summaries.edit(4L,id,new Edit(1,edited)));
        assertEquals(first.sourceThroughSeq(),second.sourceThroughSeq());assertEquals(2,second.version());
        assertEquals(List.of(first),summaries.history(3L,id,0,20));assertEquals(second,latest(4,id));
        support.resolve(4L,req,new EndRequest("旧RR释放回归结束"));
    }
    @Test void oldRrSnapshotVersionConflictReturns409InsteadOfUniqueInsertFailure()throws Exception {
        String id=conversation();user(id,"版本并发原文");String req=handoff(id);claim(3,req);var first=latest(3,id);var edited=fields(first);
        int code=afterOldSnapshot(()->summaries.edit(3L,id,new Edit(1,edited)),()->error(()->summaries.edit(3L,id,new Edit(1,edited))));
        assertEquals(409,code);assertEquals(2,latest(3,id).version());support.resolve(3L,req,new EndRequest("旧RR版本回归结束"));
    }
    @Test void oldRrHistoryUsesCommittedAssignmentAndRevisionRatherThanStaleGrant()throws Exception {
        String id=conversation();user(id,"历史授权原文");String req=handoff(id);claim(3,req);var first=latest(3,id);var edited=fields(first);
        var targetHistory=afterOldSnapshot(()->{support.requeue(3L,req,new EndRequest("RR历史转派"),false);claim(4,req);summaries.edit(4L,id,new Edit(1,edited));},()->summaries.history(4L,id,0,20));
        assertEquals(List.of(2L,1L),targetHistory.stream().map(Revision::version).toList());
        var retiredHistory=afterOldSnapshot(()->summaries.edit(4L,id,new Edit(2,edited)),()->summaries.history(3L,id,0,20));
        assertEquals(List.of(first),retiredHistory);support.resolve(4L,req,new EndRequest("旧RR历史回归结束"));
    }
    @Test void oldRrSnapshotCanValidateNewMessageAndAttachmentAndBuildCurrentDraft()throws Exception {
        String id=conversation();user(id,"旧RR证据原文");String req=handoff(id);claim(3,req);var first=latest(3,id);
        var newText=new java.util.concurrent.atomic.AtomicReference<Message>();var newImage=new java.util.concurrent.atomic.AtomicReference<String>();
        var updated=afterOldSnapshot(()->{
            newText.set(user(id,"另一连接刚提交的用户新证据"));
            try {newImage.set(raw(id,"user",null,"81003"));}catch(Exception failure){throw new RuntimeException(failure);}
        },()->{
            var edited=fields(first);edited.put("symptoms",List.of(new Item("用户新证据","user_statement",List.of(new Evidence(newText.get().id(),2,null,"刚提交的用户新证据")),null)));
            edited.put("evidence_attachments",List.of(new Item("新绑定原图","evidence_reference",List.of(new Evidence(newImage.get(),3,"81003",null)),null)));
            var revision=summaries.edit(3L,id,new Edit(1,edited));assertEquals(3,revision.sourceThroughSeq());
            summaries.createForHandoff(id);return latest(3,id);
        });
        assertEquals(3,updated.version());assertEquals(3,updated.sourceThroughSeq());
        assertTrue(updated.fields().get("user_goal").stream().anyMatch(item->item.text().contains("另一连接刚提交")));
        assertEquals("81003",updated.fields().get("evidence_attachments").getFirst().evidence().getFirst().attachmentId());
        support.resolve(3L,req,new EndRequest("旧RR证据回归结束"));
    }
    @Test void draftsDeclareTruncationAndRetainLegacyData() {
        String id=conversation();for(int i=0;i<82;i++)user(id,"msg-"+i+":"+"长".repeat(501));String req=handoff(id);claim(3,req);var draft=latest(3,id);
        assertTrue(draft.sourceTruncated());assertEquals(82,draft.sourceThroughSeq());assertEquals(20,draft.fields().get("user_goal").size());
        assertTrue(draft.fields().get("user_goal").stream().allMatch(item->item.text().length()<=500));
        assertEquals("preserve-summary-legacy",jdbc.queryForObject("SELECT content FROM ai_agent_chat_history WHERE id=1",String.class));
        assertTrue(summaries.history(3L,id,1,20).isEmpty());
    }
}
