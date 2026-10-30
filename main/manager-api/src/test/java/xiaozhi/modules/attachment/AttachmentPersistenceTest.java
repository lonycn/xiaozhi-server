package xiaozhi.modules.attachment;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import xiaozhi.modules.attachment.AttachmentModels.*;
import xiaozhi.modules.conversation.*;
import xiaozhi.modules.conversation.ConversationModels.*;

/** Explicit opt-in, loopback-only, brand-new disposable schema. No application datasource is read. */
@EnabledIfEnvironmentVariable(named="ATTACHMENT_TEST_JDBC_URL",matches=".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AttachmentPersistenceTest {
    static JdbcTemplate db;
    static AttachmentService media;
    static ConversationService conversations;
    static Path root;
    static final ObjectMapper JSON=JsonMapper.builder().addModule(new JavaTimeModule()).build();
    @BeforeAll static void initialize()throws Exception {
        String url=System.getenv("ATTACHMENT_TEST_JDBC_URL");
        if(!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/xiaozhi_attachment_test_[a-z0-9_]+(\\?.*)?"))throw new IllegalArgumentException("Requires loopback new xiaozhi_attachment_test_* database");
        var source=new DriverManagerDataSource(url,Optional.ofNullable(System.getenv("ATTACHMENT_TEST_DB_USER")).orElse("root"),System.getenv("ATTACHMENT_TEST_DB_PASSWORD"));db=new JdbcTemplate(source);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Long.class),"Tests only append fixtures to a new empty schema");
        db.execute("CREATE TABLE sys_user(id BIGINT PRIMARY KEY,status TINYINT NOT NULL)");db.update("INSERT INTO sys_user VALUES(7,1),(8,1),(71,1),(72,1)");
        db.execute("CREATE TABLE ai_agent(id VARCHAR(32) PRIMARY KEY,user_id BIGINT)");
        db.execute("CREATE TABLE ai_agent_chat_history(id BIGINT PRIMARY KEY,content VARCHAR(100))");db.update("INSERT INTO ai_agent_chat_history VALUES(1,'preserve-legacy-evidence')");
        for(String changelog:List.of("conversation","support","attachments"))try(var connection=source.getConnection();var migration=new Liquibase("db/changelog/"+changelog+".changelog.yaml",new ClassLoaderResourceAccessor(),new JdbcConnection(connection))){migration.update(new Contexts());migration.update(new Contexts());}
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM DATABASECHANGELOG",Long.class));
        var manager=new DataSourceTransactionManager(source);var dao=new ConversationDao(db);root=Files.createTempDirectory("b3-persistence-private-");
        media=new AttachmentService(db,dao,JSON,manager,new MockEnvironment().withProperty("media.ticket-key",AttachmentRulesTest.KEY),new PrivateObjectStore.Filesystem(root));
        conversations=new ConversationService(dao,JSON,manager);conversations.setAttachments(media);
    }
    static String conversation(long owner){return conversations.create(owner,new CreateConversation("attachment fixture",null)).id();}
    static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    static String hash(byte[] data)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}
    static InitView init(long actor,String conversation,String key,byte[] data)throws Exception{return media.init(actor,new Init(conversation,key,"evidence.txt","text/plain",(long)data.length,hash(data)),"/xiaozhi");}
    static InitView uploaded(long actor,String conversation)throws Exception {
        byte[] data=bytes("private evidence "+UUID.randomUUID());var a=init(actor,conversation,UUID.randomUUID().toString(),data);
        assertEquals("uploaded",media.upload(actor,a.id(),"text/plain",data.length,new ByteArrayInputStream(data)).status());return a;
    }
    static View ready(long actor,String conversation)throws Exception {
        var a=uploaded(actor,conversation);media.completeUpload(actor,a.id());Task task=media.claim(new Claim("fixture-worker"));assertNotNull(task);assertEquals(a.id(),task.attachmentId());
        media.artifact(task.jobId(),"text",task.leaseToken(),"text/plain",new ByteArrayInputStream(bytes("extracted")));
        return media.completeJob(task.jobId(),new Complete(task.leaseToken(),Map.of("mime_type","text/plain","text_available",true,"text_truncated",false)));
    }
    static MessagePart part(String id){return new MessagePart("file",null,null,null,null,null,null,null,id);}
    static String ticket(Access access){return access.url().substring(access.url().indexOf("ticket=")+7);}
    @Test @Order(1) void concurrentUploadInitIsIdempotentAndBytesAreImmutable()throws Exception {
        String c=conversation(7);byte[] data=bytes("same bytes");Init request=new Init(c,"concurrent-upload","evidence.txt","text/plain",(long)data.length,hash(data));
        try(var executor=Executors.newFixedThreadPool(6)) {
            List<Callable<String>> calls=new ArrayList<>();for(int i=0;i<12;i++)calls.add(()->media.init(7L,request,"").id());
            var ids=executor.invokeAll(calls).stream().map(f->{try{return f.get();}catch(Exception e){throw new AssertionError(e);}}).distinct().toList();assertEquals(1,ids.size());
            String id=ids.getFirst();assertEquals("uploaded",media.upload(7L,id,"text/plain",data.length,new ByteArrayInputStream(data)).status());
            assertEquals("uploaded",media.upload(7L,id,"text/plain",data.length,new ByteArrayInputStream(data)).status());
            assertEquals(409,assertThrows(ConversationProblem.class,()->media.upload(7L,id,"text/plain",data.length,new ByteArrayInputStream(bytes("diff bytes")))).status());
            assertEquals(404,assertThrows(ConversationProblem.class,()->media.get(8L,id)).status());
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ai_attachment_object WHERE attachment_id=? AND status='referenced'",Long.class,id));
        }
        assertEquals(409,assertThrows(ConversationProblem.class,()->media.init(7L,new Init(c,"concurrent-upload","different.txt","text/plain",(long)data.length,hash(data)),"")).status());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ai_conversation_event WHERE conversation_id=? AND event_type LIKE 'attachment.%'",Long.class,c));
    }
    @Test @Order(2) void blankPdfDerivedTextCanBeEmptyAndBindingRollsBackAtomically()throws Exception {
        String c=conversation(7);var pending=uploaded(7,c);var a=uploaded(7,c);media.completeUpload(7L,a.id());Task j=media.claim(new Claim("fixture-worker"));assertEquals(a.id(),j.attachmentId());
        media.artifact(j.jobId(),"text",j.leaseToken(),"text/plain",new ByteArrayInputStream(new byte[0]));
        assertEquals("ready",media.completeJob(j.jobId(),new Complete(j.leaseToken(),Map.of("mime_type","text/plain","text_available",false,"text_truncated",false))).status());
        assertThrows(ConversationProblem.class,()->conversations.send(7L,c,new SendMessage("rollback",List.of(part(a.id()),part(pending.id())))));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ai_message WHERE conversation_id=?",Long.class,c));assertNull(db.queryForObject("SELECT linked_message_id FROM ai_attachment WHERE id=?",String.class,a.id()));
        SendMessage request=new SendMessage("mixed",List.of(new MessagePart("text","检查附件",null,null,null,null,null,null),part(a.id())));
        var sent=conversations.send(7L,c,request);assertEquals(sent,conversations.send(7L,c,request));assertEquals(1,sent.seq());
        assertEquals(409,assertThrows(ConversationProblem.class,()->conversations.send(7L,c,new SendMessage("reuse",List.of(part(a.id()))))).status());
        assertEquals(404,assertThrows(ConversationProblem.class,()->conversations.send(7L,conversation(7),new SendMessage("cross-conversation",List.of(part(a.id()))))).status());
        Access access=media.access(7L,a.id(),"text","/xiaozhi");assertTrue(access.url().startsWith("/xiaozhi/api/v1/ai/attachments/"));
        try(var content=media.ticketContent(a.id(),ticket(access))){assertEquals(0,content.size());assertEquals(-1,content.input().read());}
        db.update("UPDATE sys_user SET status=0 WHERE id=7");try{assertEquals(404,assertThrows(ConversationProblem.class,()->media.ticketContent(a.id(),ticket(access))).status());}finally{db.update("UPDATE sys_user SET status=1 WHERE id=7");}
        assertEquals("preserve-legacy-evidence",db.queryForObject("SELECT content FROM ai_agent_chat_history WHERE id=1",String.class));
    }
    @Test @Order(3) void staleLeasesCannotReadWriteHeartbeatOrCommitAndClaimsStopAtThree()throws Exception {
        String c=conversation(7);var a=uploaded(7,c);media.completeUpload(7L,a.id());Task first=media.claim(new Claim("fixture-worker"));
        media.artifact(first.jobId(),"text",first.leaseToken(),"text/plain",new ByteArrayInputStream(bytes("attempt one")));
        db.update("UPDATE ai_media_job SET lease_expires_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",first.jobId());Task second=media.claim(new Claim("replacement-worker"));assertEquals(2,second.attempt());
        assertEquals(409,assertThrows(ConversationProblem.class,()->media.heartbeat(first.jobId(),new Lease(first.leaseToken()))).status());
        assertThrows(ConversationProblem.class,()->media.workerInput(first.jobId(),first.leaseToken()));assertThrows(ConversationProblem.class,()->media.artifact(first.jobId(),"text",first.leaseToken(),"text/plain",new ByteArrayInputStream(bytes("stale"))));
        assertThrows(ConversationProblem.class,()->media.completeJob(first.jobId(),new Complete(first.leaseToken(),Map.of())));assertThrows(ConversationProblem.class,()->media.failJob(first.jobId(),new Fail(first.leaseToken(),"PROCESSING_FAILED")));
        media.artifact(second.jobId(),"text",second.leaseToken(),"text/plain",new ByteArrayInputStream(bytes("attempt two")));
        media.artifact(second.jobId(),"text",second.leaseToken(),"text/plain",new ByteArrayInputStream(bytes("attempt two")));
        assertEquals(409,assertThrows(ConversationProblem.class,()->media.artifact(second.jobId(),"text",second.leaseToken(),"text/plain",new ByteArrayInputStream(bytes("changed")))).status());
        media.failJob(second.jobId(),new Fail(second.leaseToken(),"PROCESSING_FAILED"));media.retry(7L,a.id());Task third=media.claim(new Claim("third-worker"));assertEquals(3,third.attempt());media.failJob(third.jobId(),new Fail(third.leaseToken(),"PROCESSING_FAILED"));
        assertEquals(409,assertThrows(ConversationProblem.class,()->media.retry(7L,a.id())).status());assertNull(media.claim(new Claim("fourth-worker")));
        assertEquals(3,db.queryForObject("SELECT attempt FROM ai_media_job WHERE id=?",Integer.class,third.jobId()));
    }
    @Test @Order(4) void staffDraftsStayPrivateAndHistoricalContentHonorsCutoff()throws Exception {
        String c=conversation(7);String r=UUID.randomUUID().toString().replace("-","");String assignment=UUID.randomUUID().toString().replace("-","");
        db.update("INSERT INTO support_agent_profile(user_id,role) VALUES(71,'support_agent')");db.update("INSERT INTO support_request(id,conversation_id,client_request_id,request_hash,status,assigned_agent_user_id) VALUES(?,?,?,'fixture','active',71)",r,c,r);
        db.update("INSERT INTO support_assignment(id,request_id,conversation_id,agent_user_id) VALUES(?,?,?,71)",assignment,r,c);
        View old=ready(71,c);assertThrows(ConversationProblem.class,()->media.get(7L,old.id()));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ai_conversation_event WHERE conversation_id=? AND event_type LIKE 'media.%'",Long.class,c));
        var message=conversations.sendSupport(71L,c,new SendMessage("staff-with-file",List.of(part(old.id()))),"support_agent");Access ticketBeforeRelease=media.access(71L,old.id(),"text","");
        db.update("UPDATE support_assignment SET released_at=CURRENT_TIMESTAMP(3),read_through_seq=?,read_through_event_seq=999 WHERE id=?",message.seq(),assignment);db.update("UPDATE support_request SET status='resolved' WHERE id=?",r);
        View later=ready(7,c);conversations.send(7L,c,new SendMessage("later-user-file",List.of(part(later.id()))));
        try(var content=media.ticketContent(old.id(),ticket(ticketBeforeRelease))){assertTrue(content.size()>0);}
        assertEquals(404,assertThrows(ConversationProblem.class,()->media.get(71L,later.id())).status());
        assertThrows(ConversationProblem.class,()->media.init(71L,new Init(c,"released-write","x.txt","text/plain",1L,hash(bytes("x"))),""));
        assertEquals("ready",media.get(7L,old.id()).status());
    }
    @Test @Order(5) void compensationExpiresOnlyUnlinkedEvidenceAndRetainsAllRows()throws Exception {
        String c=conversation(7);View unused=ready(7,c),linked=ready(7,c);conversations.send(7L,c,new SendMessage("keep-evidence",List.of(part(linked.id()))));
        long rows=db.queryForObject("SELECT COUNT(*) FROM ai_attachment",Long.class);long objects=db.queryForObject("SELECT COUNT(*) FROM ai_attachment_object",Long.class);
        db.update("UPDATE ai_attachment SET expires_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 2 DAY) WHERE id IN (?,?)",unused.id(),linked.id());db.update("UPDATE ai_attachment_object SET expires_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 2 DAY) WHERE attachment_id IN (?,?)",unused.id(),linked.id());
        Path sentinel=root.resolve("old-application-evidence.bin");Files.writeString(sentinel,"never touch");media.cleanup();
        assertEquals(rows,db.queryForObject("SELECT COUNT(*) FROM ai_attachment",Long.class));assertEquals(objects,db.queryForObject("SELECT COUNT(*) FROM ai_attachment_object",Long.class));
        assertEquals("expired",db.queryForObject("SELECT status FROM ai_attachment WHERE id=?",String.class,unused.id()));assertEquals("ready",media.get(7L,linked.id()).status());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ai_attachment_object WHERE attachment_id=? AND status<>'deleted'",Long.class,unused.id()));
        assertTrue(db.queryForObject("SELECT COUNT(*) FROM ai_attachment_object WHERE attachment_id=? AND status='referenced'",Long.class,linked.id())>0);assertEquals("never touch",Files.readString(sentinel));
        assertEquals("preserve-legacy-evidence",db.queryForObject("SELECT content FROM ai_agent_chat_history WHERE id=1",String.class));
    }
    @Test @Order(6) void concurrentCrossConversationIdempotencyKeyReturnsOneConflict()throws Exception {
        String first=conversation(7),second=conversation(7),key="cross-race-"+UUID.randomUUID();
        byte[] data=bytes("same immutable bytes");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            List<Future<Integer>> work=new ArrayList<>();
            for(String conversation:List.of(first,second))work.add(pool.submit(()->{
                start.await();
                try {media.init(7L,new Init(conversation,key,"same.txt","text/plain",(long)data.length,hash(data)),"");return 200;}
                catch(ConversationProblem conflict){return conflict.status();}
            }));
            start.countDown();
            var results=new ArrayList<Integer>();for(var item:work)results.add(item.get());
            Collections.sort(results);assertEquals(List.of(200,409),results);
        }
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ai_attachment WHERE uploader_user_id=7 AND client_upload_id=?",Long.class,key));
    }
}
