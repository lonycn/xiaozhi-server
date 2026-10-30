package xiaozhi.modules.annotation;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import xiaozhi.modules.annotation.AnnotationModels.*;
import xiaozhi.modules.attachment.*;
import xiaozhi.modules.attachment.AttachmentModels.*;
import xiaozhi.modules.conversation.*;
import xiaozhi.modules.conversation.ConversationModels.*;

/** SQL/state-machine fixtures only. Decoder and real provider acceptance are separate worker checks. */
@EnabledIfEnvironmentVariable(named="ANNOTATION_TEST_JDBC_URL",matches=".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AnnotationPersistenceTest {
    static JdbcTemplate db;static AttachmentService media;static AnnotationService annotations;static ConversationService conversations;
    static final ObjectMapper JSON=JsonMapper.builder().addModule(new JavaTimeModule()).build();
    static final String KEY="isolated-b4-ticket-key-at-least-32-characters";
    static Path root;static byte[] JPEG;
    @BeforeAll static void initialize()throws Exception {
        String url=System.getenv("ANNOTATION_TEST_JDBC_URL");
        if(!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/xiaozhi_annotation_test_[a-z0-9_]+(\\?.*)?"))throw new IllegalArgumentException("Requires loopback new xiaozhi_annotation_test_* database");
        var source=new DriverManagerDataSource(url,Optional.ofNullable(System.getenv("ANNOTATION_TEST_DB_USER")).orElse("root"),System.getenv("ANNOTATION_TEST_DB_PASSWORD"));db=new JdbcTemplate(source);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Long.class),"Only a new empty schema is accepted; no cleanup is performed");
        db.execute("CREATE TABLE sys_user(id BIGINT PRIMARY KEY,status TINYINT NOT NULL)");db.update("INSERT INTO sys_user VALUES(7,1),(8,1),(71,1),(72,1)");
        db.execute("CREATE TABLE ai_agent(id VARCHAR(32) PRIMARY KEY,user_id BIGINT)");db.execute("CREATE TABLE ai_agent_chat_history(id BIGINT PRIMARY KEY,content VARCHAR(100))");db.update("INSERT INTO ai_agent_chat_history VALUES(1,'keep-original-evidence')");
        for(String changelog:List.of("conversation","support","attachments","annotations"))try(var connection=source.getConnection();var migration=new Liquibase("db/changelog/"+changelog+".changelog.yaml",new ClassLoaderResourceAccessor(),new JdbcConnection(connection))){migration.update(new Contexts());migration.update(new Contexts());}
        assertEquals(4,db.queryForObject("SELECT COUNT(*) FROM DATABASECHANGELOG",Long.class));
        var dao=new ConversationDao(db);var manager=new DataSourceTransactionManager(source);root=Files.createTempDirectory("b4-annotation-private-fixture-");
        media=new AttachmentService(db,dao,JSON,manager,new MockEnvironment().withProperty("media.ticket-key",KEY),new PrivateObjectStore.Filesystem(root));
        annotations=new AnnotationService(db,media,new ConversationAccessService(dao),JSON,manager);media.setGeneratedSupportForTests(annotations);
        conversations=new ConversationService(dao,JSON,manager);conversations.setAttachments(media);
        try(var bytes=new ByteArrayOutputStream()){assertTrue(ImageIO.write(new BufferedImage(100,100,BufferedImage.TYPE_INT_RGB),"jpg",bytes));JPEG=bytes.toByteArray();}
        db.update("INSERT INTO support_agent_profile(user_id,role) VALUES(71,'support_agent'),(72,'expert')");
    }
    static String key(){return UUID.randomUUID().toString();}
    static String conversation(){return conversations.create(7L,new CreateConversation("B4 isolated fixture",null)).id();}
    static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    static MessagePart part(String type,String id){return new MessagePart(type,null,null,null,null,null,null,null,id);}
    static Task claim(String output){Task task=media.claim(new Claim("annotation-fixture-worker"));assertNotNull(task);assertEquals(output,task.attachmentId());return task;}
    static void artifact(Task task,String kind)throws Exception{media.artifact(task.jobId(),kind,task.leaseToken(),"image/jpeg",new ByteArrayInputStream(JPEG));}
    static View source(String conversation,boolean send,boolean video)throws Exception {
        // Video's protocol-state fixture is intentionally not decoder evidence. Actual PTS extraction has Python tests.
        byte[] raw=JPEG;String mime=video?"video/mp4":"image/jpeg",filename=video?"fixture.mp4":"fixture.jpg";
        var upload=media.init(7L,new Init(conversation,key(),filename,mime,(long)raw.length,hash(raw)),"");media.upload(7L,upload.id(),mime,raw.length,new ByteArrayInputStream(raw));media.completeUpload(7L,upload.id());Task inspect=claim(upload.id());
        Map<String,Object> metadata;
        if(video){artifact(inspect,"poster");artifact(inspect,"frame_0");metadata=Map.of("mime_type",mime,"width",100,"height",100,"duration_ms",20000,"has_audio",false,"frames",List.of(Map.of("artifact","frame_0","time_ms",10000)));}
        else {artifact(inspect,"display");artifact(inspect,"thumbnail");metadata=Map.of("mime_type",mime,"width",100,"height",100);}
        View ready=media.completeJob(inspect.jobId(),new Complete(inspect.leaseToken(),metadata));if(send)conversations.send(7L,conversation,new SendMessage(key(),List.of(part(video?"video":"image",upload.id()))));return ready;
    }
    static Frame frame(long actor,String conversation,String source)throws Exception {
        Frame f=annotations.createFrame(actor,new CreateFrame(conversation,key(),source,null));Task task=claim(f.frameAttachmentId());assertEquals("capture_frame",task.jobType());assertEquals(source,task.metadata().get("source_attachment_id"));assertEquals(hash(JPEG),task.sha256());
        artifact(task,"display");artifact(task,"thumbnail");media.completeJob(task.jobId(),new Complete(task.leaseToken(),Map.of("mime_type","image/jpeg","width",100,"height",100)));return annotations.getFrame(actor,f.id());
    }
    static JsonNode scene(String frame)throws Exception{return JSON.readTree("{\"format\":\"annotation-v1\",\"frame_id\":\""+frame+"\",\"width\":100,\"height\":100,\"elements\":[]}");}
    static Annotation annotation(long actor,String conversation,Frame f)throws Exception {
        Annotation saved=annotations.createAnnotation(actor,new CreateAnnotation(conversation,key(),f.id(),"检查",scene(f.id())));Task task=claim(saved.renderAttachmentId());assertEquals("annotation_preview",task.jobType());assertEquals(saved.id(),task.metadata().get("annotation_id"));assertTrue(task.metadata().get("scene") instanceof JsonNode);
        artifact(task,"display");artifact(task,"thumbnail");media.completeJob(task.jobId(),new Complete(task.leaseToken(),Map.of("mime_type","image/jpeg","width",100,"height",100)));return annotations.getAnnotation(actor,saved.id());
    }
    static String assign(String conversation)throws Exception {String request=key().replace("-","");db.update("INSERT INTO support_request(id,conversation_id,client_request_id,request_hash,status,assigned_agent_user_id) VALUES(?,?,?,'fixture','active',71)",request,conversation,request);String assignment=key().replace("-","");db.update("INSERT INTO support_assignment(id,request_id,conversation_id,agent_user_id) VALUES(?,?,?,71)",assignment,request,conversation);return assignment;}
    static void release(String assignment,long cutoff){db.update("UPDATE support_assignment SET released_at=CURRENT_TIMESTAMP(3),read_through_seq=?,read_through_event_seq=99999 WHERE id=?",cutoff,assignment);db.update("UPDATE support_request SET status='resolved' WHERE id=(SELECT request_id FROM support_assignment WHERE id=?)",assignment);}
    @Test @Order(1) void readySentSourcesOnlyAndConcurrentFrameCreateIsIdempotent()throws Exception {
        String c=conversation();View unsent=source(c,false,false);assertEquals(409,assertThrows(ConversationProblem.class,()->annotations.createFrame(7L,new CreateFrame(c,key(),unsent.id(),null))).status());
        View src=source(c,true,false);assertEquals(404,assertThrows(ConversationProblem.class,()->annotations.createFrame(7L,new CreateFrame(conversation(),key(),src.id(),null))).status());
        CreateFrame body=new CreateFrame(c,key(),src.id(),null);Frame first;
        try(var executor=Executors.newFixedThreadPool(6)){List<Callable<Frame>> calls=new ArrayList<>();for(int i=0;i<12;i++)calls.add(()->annotations.createFrame(7L,body));var rows=executor.invokeAll(calls).stream().map(f->{try{return f.get();}catch(Exception ex){throw new AssertionError(ex);}}).toList();assertEquals(1,rows.stream().map(Frame::id).distinct().count());first=rows.getFirst();}
        assertEquals(409,assertThrows(ConversationProblem.class,()->annotations.createFrame(7L,new CreateFrame(c,body.clientFrameId(),unsent.id(),null))).status());
        Task task=claim(first.frameAttachmentId());artifact(task,"display");artifact(task,"thumbnail");media.completeJob(task.jobId(),new Complete(task.leaseToken(),Map.of("mime_type","image/jpeg","width",100,"height",100)));
        assertEquals(409,assertThrows(ConversationProblem.class,()->media.upload(7L,first.frameAttachmentId(),"image/jpeg",JPEG.length,new ByteArrayInputStream(JPEG))).status());
        assertEquals(src.sha256(),media.get(7L,src.id()).sha256());
    }
    @Test @Order(2) void immutableSceneAndHiddenFrameBindingsRollBackWithFailedMessageThenPublishOnce()throws Exception {
        String c=conversation();View src=source(c,true,false);Frame frame=frame(7,c,src.id());Annotation saved=annotation(7,c,frame);
        var request=new CreateAnnotation(c,"same-snapshot",frame.id(),"label",scene(frame.id()));Annotation duplicate=annotations.createAnnotation(7L,request);assertEquals(duplicate.id(),annotations.createAnnotation(7L,request).id());
        assertEquals(409,assertThrows(ConversationProblem.class,()->annotations.createAnnotation(7L,new CreateAnnotation(c,"same-snapshot",frame.id(),"changed",scene(frame.id())))).status());
        Task pending=claim(duplicate.renderAttachmentId());media.failJob(pending.jobId(),new Fail(pending.leaseToken(),"PROCESSING_FAILED"));
        long before=db.queryForObject("SELECT COUNT(*) FROM ai_message WHERE conversation_id=?",Long.class,c);
        assertThrows(ConversationProblem.class,()->conversations.send(7L,c,new SendMessage("rollback-hidden",List.of(part("image",saved.renderAttachmentId()),part("image",duplicate.renderAttachmentId())))));
        assertEquals(before,db.queryForObject("SELECT COUNT(*) FROM ai_message WHERE conversation_id=?",Long.class,c));
        assertNull(db.queryForObject("SELECT linked_message_id FROM ai_attachment WHERE id=?",String.class,frame.frameAttachmentId()));assertNull(db.queryForObject("SELECT linked_message_id FROM ai_attachment WHERE id=?",String.class,saved.renderAttachmentId()));
        SendMessage body=new SendMessage("publish-once",List.of(part("image",saved.renderAttachmentId())));var sent=conversations.send(7L,c,body);assertEquals(sent,conversations.send(7L,c,body));
        assertEquals(sent.id(),db.queryForObject("SELECT linked_message_id FROM ai_attachment WHERE id=?",String.class,frame.frameAttachmentId()));assertEquals(sent.seq(),db.queryForObject("SELECT linked_seq FROM ai_attachment WHERE id=?",Long.class,frame.frameAttachmentId()));
        assertEquals(saved.id(),media.get(7L,saved.renderAttachmentId()).metadata().get("annotation_id"));
        db.update("UPDATE ai_attachment SET expires_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 2 DAY) WHERE id IN (?,?)",frame.frameAttachmentId(),saved.renderAttachmentId());db.update("UPDATE ai_attachment_object SET expires_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 2 DAY) WHERE attachment_id IN (?,?)",frame.frameAttachmentId(),saved.renderAttachmentId());media.cleanup();
        assertEquals("ready",annotations.getFrame(7L,frame.id()).status());assertEquals(saved.scene(),annotations.getAnnotation(7L,saved.id()).scene());
    }
    @Test @Order(3) void draftsArePrivateAndReleasedStaffLeaseOperationsAndPublicationAreDenied()throws Exception {
        String c=conversation();View src=source(c,true,false);String assignment=assign(c);Frame f=frame(71,c,src.id());Annotation ready=annotation(71,c,f);
        assertEquals(404,assertThrows(ConversationProblem.class,()->annotations.getAnnotation(7L,ready.id())).status());assertThrows(ConversationProblem.class,()->annotations.getFrame(7L,f.id()));assertTrue(annotations.list(7L,c,0,50).items().isEmpty());assertEquals(1,annotations.list(71L,c,0,50).items().size());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ai_conversation_event WHERE conversation_id=? AND (event_type LIKE 'media.%' OR event_type LIKE 'attachment.%')",Long.class,c));
        Frame pending=annotations.createFrame(71L,new CreateFrame(c,key(),src.id(),null));Task lease=claim(pending.frameAttachmentId());release(assignment,1);
        assertThrows(ConversationProblem.class,()->media.heartbeat(lease.jobId(),new Lease(lease.leaseToken())));assertThrows(ConversationProblem.class,()->media.workerInput(lease.jobId(),lease.leaseToken()));assertThrows(ConversationProblem.class,()->media.artifact(lease.jobId(),"display",lease.leaseToken(),"image/jpeg",new ByteArrayInputStream(JPEG)));assertThrows(ConversationProblem.class,()->media.completeJob(lease.jobId(),new Complete(lease.leaseToken(),Map.of("mime_type","image/jpeg","width",100,"height",100))));assertThrows(ConversationProblem.class,()->media.failJob(lease.jobId(),new Fail(lease.leaseToken(),"PROCESSING_FAILED")));
        assertThrows(ConversationProblem.class,()->conversations.sendSupport(71L,c,new SendMessage("released-publish",List.of(part("image",ready.renderAttachmentId()))),"support_agent"));
        db.update("UPDATE ai_media_job SET lease_expires_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",lease.jobId());assertNull(media.claim(new Claim("after-release")));assertThrows(ConversationProblem.class,()->annotations.retryFrame(71L,pending.id()));
    }
    @Test @Order(4) void sentSceneAndFrameUseMessageCutoffAndLaterAnnotationsStayHiddenFromFormerStaff()throws Exception {
        String c=conversation();View src=source(c,true,false);String assignment=assign(c);Frame f=frame(71,c,src.id());Annotation old=annotation(71,c,f);var sent=conversations.sendSupport(71L,c,new SendMessage(key(),List.of(part("image",old.renderAttachmentId()))),"support_agent");release(assignment,sent.seq());
        Annotation later=annotation(7,c,f);conversations.send(7L,c,new SendMessage(key(),List.of(part("image",later.renderAttachmentId()))));
        assertEquals(old.id(),annotations.getAnnotation(7L,old.id()).id());assertEquals(f.id(),annotations.getFrame(7L,f.id()).id());assertEquals(old.id(),annotations.getAnnotation(71L,old.id()).id());assertEquals(404,assertThrows(ConversationProblem.class,()->annotations.getAnnotation(71L,later.id())).status());
        var list=annotations.list(71L,c,0,50);assertEquals(List.of(old.id()),list.items().stream().map(Annotation::id).toList());assertEquals(Long.parseLong(old.id()),list.nextCursor());assertFalse(list.hasMore());
        assertEquals(sent.seq(),db.queryForObject("SELECT linked_seq FROM ai_attachment WHERE id=?",Long.class,f.frameAttachmentId()));
        assertEquals("keep-original-evidence",db.queryForObject("SELECT content FROM ai_agent_chat_history WHERE id=1",String.class));
    }
    @Test @Order(5) void exactVideoCapturePersistsActualPtsAndWorkerCannotInjectProvenance()throws Exception {
        String c=conversation();View video=source(c,true,true);Frame f=annotations.createFrame(7L,new CreateFrame(c,key(),video.id(),18000L));Task task=claim(f.frameAttachmentId());assertEquals(18000L,((Number)task.metadata().get("requested_time_ms")).longValue());assertEquals("video/mp4",task.mimeType());
        artifact(task,"display");artifact(task,"thumbnail");assertThrows(ConversationProblem.class,()->media.completeJob(task.jobId(),new Complete(task.leaseToken(),Map.of("mime_type","image/jpeg","width",100,"height",100,"actual_time_ms",17999))));assertNull(db.queryForObject("SELECT actual_time_ms FROM support_frame WHERE id=?",Long.class,f.id()));
        assertThrows(ConversationProblem.class,()->media.completeJob(task.jobId(),new Complete(task.leaseToken(),Map.of("mime_type","image/jpeg","width",100,"height",100,"actual_time_ms",18033,"source_attachment_id","bad"))));
        media.completeJob(task.jobId(),new Complete(task.leaseToken(),Map.of("mime_type","image/jpeg","width",100,"height",100,"actual_time_ms",18033)));Frame result=annotations.getFrame(7L,f.id());assertEquals(18000,result.requestedTimeMs());assertEquals(18033,result.actualTimeMs());assertNotEquals(result.requestedTimeMs(),result.actualTimeMs());assertEquals(video.id(),media.get(7L,f.frameAttachmentId()).metadata().get("source_attachment_id"));
        assertThrows(ConversationProblem.class,()->media.completeJob(task.jobId(),new Complete(task.leaseToken(),Map.of("mime_type","image/jpeg","width",100,"height",100,"actual_time_ms",18000))));assertEquals(18033,annotations.getFrame(7L,f.id()).actualTimeMs());
    }
}
