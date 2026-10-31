package xiaozhi.modules.supportmedia;

import static org.junit.jupiter.api.Assertions.*;
import static xiaozhi.modules.supportmedia.MediaStore.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import org.mockito.Mockito;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import xiaozhi.modules.annotation.*;
import xiaozhi.modules.attachment.*;
import xiaozhi.modules.conversation.*;
import xiaozhi.modules.conversation.ConversationModels.*;
import xiaozhi.modules.support.*;
import xiaozhi.modules.supportmedia.MediaModels.*;
import xiaozhi.modules.supportmedia.livekit.*;
import xiaozhi.modules.supportmedia.livekit.LiveKitAdapter.*;

/** Real MySQL locking, constraints and recovery. All external transport and RTP evidence are explicit test fixtures. */
@EnabledIfEnvironmentVariable(named="SUPPORT_MEDIA_TEST_JDBC_URL",matches=".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MediaPersistenceTest {
    static final ObjectMapper JSON=JsonMapper.builder().addModule(new JavaTimeModule()).build();
    static final Consent AUDIO=new Consent(true,false,false),VIDEO=new Consent(true,true,true);
    static final String API_KEY="b5-isolated-key",API_SECRET="b5-isolated-synthetic-secret-32-characters";
    static JdbcTemplate db;static MediaStore store;static MediaPolicy policy;static MediaService media;static MediaMaintenance maintenance;static FakeNetwork network;static LiveKitAdapter livekit;static ConversationService conversations;static SupportService support;static AttachmentService attachments;static AnnotationService annotations;static MediaWebhookController webhooks;static byte[] JPEG;static MockEnvironment environment;
    static class FakeNetwork implements Transport {
        final Map<String,Map<String,Object>> rooms=new ConcurrentHashMap<>();final List<String> operations=new CopyOnWriteArrayList<>();volatile String fail;
        @Override public synchronized RpcResponse send(URI uri,String authorization,byte[] raw,Duration timeout)throws IOException {
            JsonNode body=JSON.readTree(raw);String op=uri.getPath().substring(uri.getPath().lastIndexOf('/')+1);operations.add(op);if(op.equals(fail)){fail=null;return response(503,Map.of("code","unavailable","msg","synthetic failure"));}
            String name=body.path("room").asText(body.path("name").asText());
            if(op.equals("CreateRoom")){rooms.computeIfAbsent(name,k->new ConcurrentHashMap<>(Map.of("sid","RM_"+UUID.randomUUID().toString().replace("-",""),"participants",new ArrayList<>())));return response(200,Map.of("sid",rooms.get(name).get("sid"),"name",name));}
            var room=rooms.get(name);if(room==null)return response(404,Map.of("code","not_found","msg","fixture absent"));
            if(op.equals("DeleteRoom")){rooms.remove(name);return response(200,Map.of());}
            @SuppressWarnings("unchecked") List<Map<String,Object>> participants=(List<Map<String,Object>>)room.get("participants");
            if(op.equals("ListParticipants"))return response(200,Map.of("participants",participants));
            if(op.equals("RemoveParticipant")){participants.removeIf(p->Objects.equals(p.get("identity"),body.path("identity").asText()));return response(200,Map.of());}
            throw new IOException("Unexpected fixture operation");
        }
        static RpcResponse response(int status,Object body)throws IOException{return new RpcResponse(status,JSON.writeValueAsBytes(body));}
    }
    @BeforeAll static void initialize()throws Exception {
        String url=System.getenv("SUPPORT_MEDIA_TEST_JDBC_URL");if(!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/xiaozhi_support_media_test_[a-z0-9_]+(\\?.*)?"))throw new IllegalArgumentException("Requires new loopback xiaozhi_support_media_test_* schema");
        var source=new DriverManagerDataSource(url,Optional.ofNullable(System.getenv("SUPPORT_MEDIA_TEST_DB_USER")).orElse("root"),System.getenv("SUPPORT_MEDIA_TEST_DB_PASSWORD"));db=new JdbcTemplate(source);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Long.class),"Only new empty schema; no row/table cleanup");String expectedZone=Optional.ofNullable(System.getenv("SUPPORT_MEDIA_TEST_EXPECTED_ZONE")).orElse("UTC");if(!Set.of("UTC","Asia/Shanghai").contains(expectedZone))throw new IllegalArgumentException("Unsupported isolated test timezone");
        assertEquals(expectedZone.equals("UTC")?0:28800,db.queryForObject("SELECT TIMESTAMPDIFF(SECOND,UTC_TIMESTAMP(),NOW())",Integer.class),"Test session/JDBC timezone must explicitly match");
        db.execute("CREATE TABLE sys_user(id BIGINT PRIMARY KEY,status TINYINT NOT NULL,super_admin TINYINT NOT NULL DEFAULT 0,username VARCHAR(80))");db.execute("CREATE TABLE ai_agent(id VARCHAR(32) PRIMARY KEY,user_id BIGINT)");db.execute("CREATE TABLE ai_agent_chat_history(id BIGINT PRIMARY KEY,content VARCHAR(100))");db.update("INSERT INTO ai_agent_chat_history VALUES(1,'B5-legacy-unchanged')");
        for(long user:new long[]{7,8,71,72,73,74,75,76,77,78})db.update("INSERT INTO sys_user(id,status,username) VALUES(?,1,?)",user,"media-fixture-"+user);
        for(String name:List.of("conversation","support","attachments","annotations","support-media","support-media-source-binding"))try(var connection=source.getConnection();var migration=new Liquibase("db/changelog/"+name+".changelog.yaml",new ClassLoaderResourceAccessor(),new JdbcConnection(connection))){migration.update(new Contexts());migration.update(new Contexts());}
        assertEquals(6,db.queryForObject("SELECT COUNT(*) FROM DATABASECHANGELOG",Long.class));for(long user:new long[]{71,72,73,74,75,76,77,78})db.update("INSERT INTO support_agent_profile(user_id,role,capacity_text,accepting,presence_expires_at) VALUES(?,'expert',?,1,DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 1 HOUR))",user,user==75?1:20);
        var manager=new DataSourceTransactionManager(source);var dao=new ConversationDao(db);store=new MediaStore(db,manager);policy=new MediaPolicy(store,dao);network=new FakeNetwork();livekit=new LiveKitAdapter(new Config("http://fixture.invalid","ws://fixture.invalid",API_KEY,API_SECRET,"b5-test-"),JSON,policy,Clock.systemUTC(),network);
        environment=new MockEnvironment().withProperty("support-media.livekit.room-prefix","b5-test-").withProperty("features.support-media-enabled","true");media=new MediaService(store,policy,livekit,dao,JSON,environment);maintenance=new MediaMaintenance(media,store,policy,livekit);
        conversations=new ConversationService(dao,JSON,manager);support=new SupportService(db,JSON,manager,conversations);support.setMediaHooksForTests(media);webhooks=new MediaWebhookController(livekit,store,policy,media);
        attachments=new AttachmentService(db,dao,JSON,manager,new MockEnvironment().withProperty("media.ticket-key","b5-synthetic-ticket-key-at-least-32-characters"),new PrivateObjectStore.Filesystem(Files.createTempDirectory("b5-private-media-test-")));annotations=new AnnotationService(db,attachments,new ConversationAccessService(dao),JSON,manager);attachments.setGeneratedSupportForTests(annotations);conversations.setAttachments(attachments);
        @SuppressWarnings("unchecked") var provider=Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);Mockito.when(provider.getIfAvailable()).thenReturn(annotations);media.setAnnotationProvider(provider);
        try(var out=new ByteArrayOutputStream()){assertTrue(ImageIO.write(new BufferedImage(100,100,BufferedImage.TYPE_INT_RGB),"jpg",out));JPEG=out.toByteArray();}
        network.rooms.put("aimeet-existing-preserve",new ConcurrentHashMap<>(Map.of("sid","foreign","participants",new ArrayList<>())));
    }
    static String key(){return UUID.randomUUID().toString();}
    static String request(long agent,boolean active){String c=conversations.create(7L,new CreateConversation("B5 fixture",null)).id(),request=key().replace("-","");db.update("INSERT INTO support_request(id,conversation_id,client_request_id,request_hash,status,assigned_agent_user_id) VALUES(?,?,?,'fixture',?,?)",request,c,key(),active?"active":"queued",active?agent:null);if(active)db.update("INSERT INTO support_assignment(id,request_id,conversation_id,agent_user_id) VALUES(?,?,?,?)",key().replace("-",""),request,c,agent);return request;}
    static Session create(long agent,Consent consent){return media.create(7L,new Create(request(agent,true),key(),consent.camera()?"video":"audio",consent));}
    static Session accepted(Session s,long agent,Consent c){s=media.accept(agent,s.id(),new Accept(s.controlEpoch(),c));maintenance.processOutbox();return media.get(7L,s.id());}
    static void join(Session s,int generation){var r=store.generation(Long.parseLong(s.id()),generation);List<Map<String,Object>> peers=new ArrayList<>();for(var m:store.members(number(r,"id"))){List<Map<String,Object>> tracks=new ArrayList<>();if(yes(m,"consent_microphone"))tracks.add(Map.of("sid","TR_m"+m.get("id"),"source","MICROPHONE","muted",false));if(yes(m,"consent_camera"))tracks.add(Map.of("sid","TR_c"+m.get("id"),"source","CAMERA","muted",false));peers.add(Map.of("sid","PA_"+m.get("id"),"identity",m.get("identity"),"state","ACTIVE","tracks",tracks));}network.rooms.get(string(r,"room_name")).put("participants",peers);}
    static Session ready(Session s,int generation,boolean receiving){var r=store.generation(Long.parseLong(s.id()),generation);var peers=store.members(number(r,"id"));for(int i=0;i<2;i++){var own=peers.get(i);var other=peers.get(1-i);s=media.ready(number(own,"user_id"),s.id(),new Ready(s.controlEpoch(),generation,"PA_"+own.get("id"),"PA_"+other.get("id"),true,receiving,false));}return s;}
    static Session active(long agent,Consent c){Session s=accepted(create(agent,c),agent,c);join(s,1);return ready(s,1,true);}
    static void end(Session s){s=media.get(7L,s.id());media.end(7L,s.id(),new End(s.controlEpoch(),null));maintenance.processOutbox();assertEquals("ended",media.get(7L,s.id()).status());}
    @Test @Order(1) void twoConsentsRealReadyAndMediaHangupPreserveText()throws Exception {
        Session s=create(71,AUDIO);assertEquals("invited",s.status());assertThrows(ConversationProblem.class,()->media.token(7L,s.id(),new TokenRequest(s.controlEpoch(),1)));Session accepted=accepted(s,71,AUDIO);assertEquals("connecting",accepted.status());assertNotNull(media.token(7L,s.id(),new TokenRequest(accepted.controlEpoch(),1)).participantToken());assertNotNull(media.token(71L,s.id(),new TokenRequest(accepted.controlEpoch(),1)).participantToken());join(accepted,1);
        Session baseline=ready(accepted,1,false);assertEquals("connecting",baseline.status());Session live=ready(baseline,1,true);assertEquals("active",live.status());end(live);
        assertEquals("active",db.queryForObject("SELECT status FROM support_request WHERE id=?",String.class,s.requestId()));assertNull(db.queryForObject("SELECT released_at FROM support_assignment WHERE id=?",java.sql.Timestamp.class,s.assignmentId()));
        var reply=support.send(71L,s.requestId(),new SendMessage(key(),List.of(new MessagePart("text","媒体结束继续文字",null,null,null,null,null,null))));assertEquals("expert",reply.senderType());assertTrue(network.rooms.containsKey("aimeet-existing-preserve"));
    }
    @Test @Order(2) void unauthorizedTokensConsentChangesAndStaleSidCannotMakeActive(){Session s=create(71,AUDIO);assertThrows(ConversationProblem.class,()->media.accept(7L,s.id(),new Accept(s.controlEpoch(),AUDIO)));Session accepted=accepted(s,71,AUDIO);assertThrows(ConversationProblem.class,()->media.get(8L,s.id()));assertThrows(ConversationProblem.class,()->media.accept(71L,s.id(),new Accept(accepted.controlEpoch(),VIDEO)));assertThrows(ConversationProblem.class,()->media.token(7L,s.id(),new TokenRequest(accepted.controlEpoch()-1,1)));join(accepted,1);assertThrows(ConversationProblem.class,()->media.ready(7L,s.id(),new Ready(accepted.controlEpoch(),1,"PA_wrong","PA_other",true,true,false)));assertEquals("connecting",media.get(7L,s.id()).status());end(accepted);}
    @Test @Order(3) void generationTransferWaitsForReadySurvivesRemovalFailureAndCutsOffOldStaff()throws Exception {
        Session s=active(71,AUDIO);support.send(71L,s.requestId(),new SendMessage("old-before",List.of(new MessagePart("text","old",null,null,null,null,null,null))));CreateTransfer transferRequest=new CreateTransfer(s.controlEpoch(),key(),72L);Transfer transfer=media.createTransfer(7L,s.id(),transferRequest);assertEquals(transfer.id(),media.createTransfer(7L,s.id(),transferRequest).id());s=media.get(7L,s.id());String sessionId=s.id();assertEquals(1,s.currentGeneration());assertThrows(ConversationProblem.class,()->media.token(71L,sessionId,new TokenRequest(transfer.controlEpoch(),transfer.roomGeneration())));
        media.approveTransfer(7L,transfer.id(),new Accept(transfer.controlEpoch(),AUDIO),false);media.approveTransfer(72L,transfer.id(),new Accept(transfer.controlEpoch(),AUDIO),true);maintenance.processOutbox();s=media.get(7L,s.id());join(s,transfer.roomGeneration());s=ready(s,transfer.roomGeneration(),true);
        var preparedRoom=store.generation(Long.parseLong(s.id()),transfer.roomGeneration());
        @SuppressWarnings("unchecked") var preparedPeers=(List<Map<String,Object>>)network.rooms.get(string(preparedRoom,"room_name")).get("participants");
        Map<String,Object> temporarilyMuted=new HashMap<>(preparedPeers.getFirst());temporarilyMuted.put("tracks",List.of(Map.of("sid","TR_muted","source","MICROPHONE","muted",true)));preparedPeers.set(0,temporarilyMuted);
        maintenance.advanceTransfers();assertEquals("ready",media.getTransfer(7L,transfer.id()).status());assertEquals(1,media.get(7L,s.id()).currentGeneration());
        join(s,transfer.roomGeneration());maintenance.advanceTransfers();assertEquals("committing",media.getTransfer(7L,transfer.id()).status());assertEquals(71,db.queryForObject("SELECT assigned_agent_user_id FROM support_request WHERE id=?",Long.class,s.requestId()));
        network.fail="RemoveParticipant";maintenance.processOutbox();assertEquals("committing",media.getTransfer(7L,transfer.id()).status());assertEquals(1,media.get(7L,s.id()).currentGeneration());db.update("UPDATE support_media_outbox SET available_at=CURRENT_TIMESTAMP(3) WHERE session_id=? AND status='queued'",s.id());maintenance.processOutbox();maintenance.advanceTransfers();Session switched=media.get(7L,s.id());assertEquals("completed",media.getTransfer(7L,transfer.id()).status());assertEquals(2,switched.currentGeneration());assertEquals("72",switched.agentUserId());assertThrows(ConversationProblem.class,()->media.token(71L,switched.id(),new TokenRequest(switched.controlEpoch(),1)));assertThrows(ConversationProblem.class,()->media.get(71L,switched.id()));
        support.send(72L,s.requestId(),new SendMessage("new-after",List.of(new MessagePart("text","new",null,null,null,null,null,null))));assertEquals(1,conversations.messages(71L,s.conversationId(),0,50).items().size());assertEquals(2,conversations.messages(72L,s.conversationId(),0,50).items().size());maintenance.processOutbox();
        Transfer next=media.createTransfer(7L,switched.id(),new CreateTransfer(switched.controlEpoch(),key(),76L));media.approveTransfer(7L,next.id(),new Accept(next.controlEpoch(),AUDIO),false);media.approveTransfer(76L,next.id(),new Accept(next.controlEpoch(),AUDIO),true);maintenance.processOutbox();Session nextSession=media.get(7L,switched.id());join(nextSession,next.roomGeneration());ready(nextSession,next.roomGeneration(),true);maintenance.advanceTransfers();maintenance.processOutbox();maintenance.advanceTransfers();Session third=media.get(7L,switched.id());assertEquals(3,third.currentGeneration());
        assertThrows(ConversationProblem.class,()->media.getTransfer(72L,transfer.id()));assertThrows(ConversationProblem.class,()->media.get(72L,third.id()));support.send(76L,third.requestId(),new SendMessage("third-generation",List.of(new MessagePart("text","third",null,null,null,null,null,null))));assertEquals(2,conversations.messages(72L,third.conversationId(),0,50).items().size());assertEquals(3,conversations.messages(76L,third.conversationId(),0,50).items().size());maintenance.processOutbox();end(third);
    }
    @Test @Order(4) void pendingTargetReservesTextAndMediaCapacityAndCancelKeepsOriginal(){Session one=active(73,AUDIO),two=active(74,AUDIO);Transfer reserved=media.createTransfer(7L,one.id(),new CreateTransfer(one.controlEpoch(),key(),75L));assertThrows(ConversationProblem.class,()->media.createTransfer(7L,two.id(),new CreateTransfer(two.controlEpoch(),key(),75L)));String queued=request(75,false);assertThrows(ConversationProblem.class,()->support.claim(75L,queued));media.rejectTransfer(7L,reserved.id(),new Epoch(reserved.controlEpoch()),true);maintenance.processOutbox();assertEquals("active",support.claim(75L,queued).status());assertEquals("73",media.get(7L,one.id()).agentUserId());assertEquals(1,media.get(7L,one.id()).currentGeneration());end(one);end(two);}
    @Test @Order(5) void expiredOutboxLeaseAndResponsibilityRevocationRecoverWithoutTouchingForeignRooms(){Session s=accepted(create(76,AUDIO),76,AUDIO);join(s,1);s=ready(s,1,true);Session before=s;support.requeue(76L,s.requestId(),null,false);assertEquals("ending",media.get(7L,s.id()).status());assertThrows(ConversationProblem.class,()->media.token(76L,before.id(),new TokenRequest(before.controlEpoch(),1)));db.update("UPDATE support_media_outbox SET status='running',lease_token='abandoned-attempt',lease_expires_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE session_id=? AND kind='delete_room'",s.id());maintenance.processOutbox();assertEquals("ended",media.get(7L,s.id()).status());assertTrue(network.rooms.containsKey("aimeet-existing-preserve"));assertEquals("B5-legacy-unchanged",db.queryForObject("SELECT content FROM ai_agent_chat_history WHERE id=1",String.class));}
    static String sign(byte[] body)throws Exception{String head=Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));String digest=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(body));String payload=Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(Map.of("iss",API_KEY,"exp",Instant.now().getEpochSecond()+60,"nbf",Instant.now().getEpochSecond()-1,"sha256",digest)));Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(API_SECRET.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return head+"."+payload+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal((head+"."+payload).getBytes(StandardCharsets.US_ASCII)));}
    @Test @Order(6) void signedLateWebhookIsIdempotentAndCannotReviveEndedGeneration()throws Exception {Session s=active(77,AUDIO);var r=store.generation(Long.parseLong(s.id()),1);var m=store.members(number(r,"id")).getFirst();end(s);byte[] body=JSON.writeValueAsBytes(Map.of("id",key(),"event","participant_joined","room",Map.of("name",r.get("room_name"),"sid",r.get("livekit_sid")),"participant",Map.of("identity",m.get("identity"),"sid","PA_late")));String signed=sign(body);assertThrows(LiveKitFailure.class,()->webhooks.webhook((new String(body,StandardCharsets.UTF_8)+" ").getBytes(StandardCharsets.UTF_8),signed));try(var executor=Executors.newFixedThreadPool(3)){List<Callable<Object>> calls=List.of(()->webhooks.webhook(body,signed),()->webhooks.webhook(body,signed),()->webhooks.webhook(body,signed));for(var done:executor.invokeAll(calls))assertNotNull(done.get());}assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM support_media_webhook WHERE session_id=?",Long.class,s.id()));maintenance.processOutbox();assertEquals("ended",media.get(7L,s.id()).status());}
    static String uploadedImage(long actor,String conversation)throws Exception {
        String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JPEG));
        var a=attachments.init(actor,new xiaozhi.modules.attachment.AttachmentModels.Init(conversation,key(),"browser-capture.jpg","image/jpeg",(long)JPEG.length,sha),"");attachments.upload(actor,a.id(),"image/jpeg",JPEG.length,new ByteArrayInputStream(JPEG));attachments.completeUpload(actor,a.id());
        var task=attachments.claim(new xiaozhi.modules.attachment.AttachmentModels.Claim("b5-snapshot-fixture-worker"));assertEquals(a.id(),task.attachmentId());completeImage(task);return a.id();
    }
    static void completeImage(xiaozhi.modules.attachment.AttachmentModels.Task task)throws Exception {
        for(String kind:List.of("display","thumbnail"))attachments.artifact(task.jobId(),kind,task.leaseToken(),"image/jpeg",new ByteArrayInputStream(JPEG));
        attachments.completeJob(task.jobId(),new xiaozhi.modules.attachment.AttachmentModels.Complete(task.leaseToken(),Map.of("mime_type","image/jpeg","width",100,"height",100)));
    }
    @Test @Order(7) void liveCaptureRequiresExplicitConsentAndPreservesDeclaredProvenanceThroughB4Publication()throws Exception {
        Session s=active(78,VIDEO);var r=store.generation(Long.parseLong(s.id()),1);var peers=store.members(number(r,"id"));for(int i=0;i<2;i++){var own=peers.get(i);var peer=peers.get(1-i);s=media.ready(number(own,"user_id"),s.id(),new Ready(s.controlEpoch(),1,"PA_"+own.get("id"),"PA_"+peer.get("id"),true,true,true));}
        String source=uploadedImage(78,s.conversationId());Instant captured=Instant.now();Capture request=new Capture(s.controlEpoch(),1,"same-browser-frame",source,captured);Session snapshot=s;
        assertThrows(ConversationProblem.class,()->media.capture(7L,snapshot.id(),request));
        db.update("UPDATE support_media_member SET consent_snapshots=0 WHERE room_id=? AND user_id=7",r.get("id"));assertThrows(ConversationProblem.class,()->media.capture(78L,snapshot.id(),request));db.update("UPDATE support_media_member SET consent_snapshots=1 WHERE room_id=? AND user_id=7",r.get("id"));
        var frame=media.capture(78L,s.id(),request);assertEquals(frame.id(),media.capture(78L,s.id(),request).id());assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ai_conversation_event WHERE conversation_id=? AND event_type='media.snapshot.saved'",Long.class,s.conversationId()));
        assertThrows(ConversationProblem.class,()->annotations.getFrame(7L,frame.id()));end(s);
        // Ending media preserves the explicit snapshot evidence and the still-current text assignment.
        var capture=attachments.claim(new xiaozhi.modules.attachment.AttachmentModels.Claim("b5-snapshot-fixture-worker"));assertEquals(frame.frameAttachmentId(),capture.attachmentId());completeImage(capture);
        var scene=JSON.readTree("{\"format\":\"annotation-v1\",\"frame_id\":\""+frame.id()+"\",\"width\":100,\"height\":100,\"elements\":[]}");
        var annotation=annotations.createAnnotation(78L,new AnnotationModels.CreateAnnotation(s.conversationId(),key(),frame.id(),"浏览器抓帧标注",scene));var render=attachments.claim(new xiaozhi.modules.attachment.AttachmentModels.Claim("b5-snapshot-fixture-worker"));assertEquals(annotation.renderAttachmentId(),render.attachmentId());completeImage(render);
        var message=support.send(78L,s.requestId(),new SendMessage("send-live-capture",List.of(new MessagePart("image",null,null,null,null,null,null,null,annotation.renderAttachmentId()))));
        for(String attachment:List.of(source,frame.frameAttachmentId(),annotation.renderAttachmentId()))assertEquals(message.id(),db.queryForObject("SELECT linked_message_id FROM ai_attachment WHERE id=?",String.class,attachment));
        var metadata=attachments.get(7L,annotation.renderAttachmentId()).metadata();assertEquals("client_live_capture",metadata.get("source_type"));assertEquals("client_declared",metadata.get("verification"));assertEquals(s.id(),metadata.get("media_session_id"));assertEquals(frame.id(),annotations.getFrame(7L,frame.id()).id());
    }
    @Test @Order(8) void concurrentInvitationsExpiryAndAccountDisableUsePersistentCurrentState()throws Exception {
        String request=request(71,true),client=key();Create body=new Create(request,client,"audio",AUDIO);Session s;
        try(var executor=Executors.newFixedThreadPool(4)){List<Callable<Session>> calls=new ArrayList<>();for(int i=0;i<8;i++)calls.add(()->media.create(7L,body));var results=executor.invokeAll(calls);Set<String> ids=new HashSet<>();for(var result:results)ids.add(result.get().id());assertEquals(1,ids.size());s=media.get(7L,ids.iterator().next());}
        db.update("UPDATE support_media_session SET expires_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",s.id());maintenance.expire();maintenance.processOutbox();assertEquals("expired",media.get(7L,s.id()).status());
        Session active=active(71,AUDIO);db.update("UPDATE sys_user SET status=0 WHERE id=71");try{assertThrows(ConversationProblem.class,()->media.token(7L,active.id(),new TokenRequest(active.controlEpoch(),1)));maintenance.expire();maintenance.processOutbox();assertEquals("ended",media.get(7L,active.id()).status());}finally{db.update("UPDATE sys_user SET status=1 WHERE id=71");}
    }

    @Test @Order(9) void authorizationUsesCurrentReadAfterRepeatableReadSnapshotWasEstablished()throws Exception {
        try(var executor=Executors.newSingleThreadExecutor()){
            try {assertThrows(ConversationProblem.class,()->store.tx(()->{
                assertEquals(1,db.queryForObject("SELECT status FROM sys_user WHERE id=78",Integer.class));
                try{executor.submit(()->db.update("UPDATE sys_user SET status=0 WHERE id=78")).get(5,TimeUnit.SECONDS);}catch(Exception ex){throw new AssertionError(ex);}
                policy.lockUsers(List.of(78L));policy.account(78L);return null;
            }));}finally{db.update("UPDATE sys_user SET status=1 WHERE id=78");}
        }
    }

    @Test @Order(10) void featureOffDrainsExistingRoomsAndRevokesNewTokensWithoutEndingText(){Session s=active(78,AUDIO);environment.withProperty("features.support-media-enabled","false");try{assertThrows(ConversationProblem.class,()->media.token(7L,s.id(),new TokenRequest(s.controlEpoch(),1)));assertFalse((Boolean)media.drainStatus(7L,s.conversationId()).get("cleanup_complete"));maintenance.tick();assertEquals("ended",media.get(7L,s.id()).status());var status=media.drainStatus(7L,s.conversationId());assertFalse((Boolean)status.get("enabled"));assertTrue((Boolean)status.get("cleanup_complete"));assertEquals("active",db.queryForObject("SELECT status FROM support_request WHERE id=?",String.class,s.requestId()));}finally{environment.withProperty("features.support-media-enabled","true");}}

    @Test @Order(11) void aggregateDrainIsAdminCountsOnlyAndNeverGrantsConversationAccess(){assertThrows(ConversationProblem.class,()->media.drainStatus(7L,null));db.update("UPDATE sys_user SET super_admin=1 WHERE id=8");try{var status=media.drainStatus(8L,null);assertEquals(Set.of("enabled","pending_sessions","pending_operations","cleanup_complete"),status.keySet());assertTrue((Boolean)status.get("cleanup_complete"));String privateConversation=conversations.create(7L,new CreateConversation("private",null)).id();assertThrows(ConversationProblem.class,()->media.drainStatus(8L,privateConversation));}finally{db.update("UPDATE sys_user SET super_admin=0 WHERE id=8");}}

    @Test @Order(12) void datetimeMeasurementsStayRealAcrossMatchingJdbcAndMysqlTimezones(){Session s=create(78,AUDIO);Instant now=Instant.now();assertTrue(Duration.between(now,s.createdAt()).abs().toSeconds()<10);long remaining=Duration.between(now,s.expiresAt()).toSeconds();assertTrue(remaining>=30 && remaining<=50,"invitation should be seconds away, never eight hours");db.update("UPDATE support_media_session SET expires_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",s.id());maintenance.expire();maintenance.processOutbox();assertEquals("expired",media.get(7L,s.id()).status());}

    @Test @Order(13) void tokenOwnershipReadsLatestAfterRoomTransitionDespiteRepeatableReadSnapshot() {
        Session invited=create(71,AUDIO);
        Session accepted=media.accept(71L,invited.id(),new Accept(invited.controlEpoch(),AUDIO));
        var room=store.generation(Long.parseLong(accepted.id()),1);
        String identity=string(store.member(number(room,"id"),7),"identity");
        store.tx(()->{
            assertEquals("preparing",string(store.room(number(room,"id"),false),"status"));
            // Another connection commits the actual asynchronous room transition.
            CompletableFuture.runAsync(()->db.update("UPDATE support_media_room SET status='ready',livekit_sid='RM_rr_fixture' WHERE id=?",room.get("id"))).join();
            assertEquals("ready",string(store.generation(Long.parseLong(accepted.id()),1),"status"));
            assertTrue(policy.allowed(MediaService.owned(room),Operation.TOKEN,identity),
                    "Current locked state must not be denied using an older consistent-read snapshot");
            return null;
        });
        end(accepted);
    }

}
