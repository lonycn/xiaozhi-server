package xiaozhi.modules.realtimeinput;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import liquibase.*;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import xiaozhi.modules.ai.*;
import xiaozhi.modules.attachment.*;
import xiaozhi.modules.agenttool.*;
import xiaozhi.modules.conversation.*;
import xiaozhi.modules.conversation.ConversationModels.*;
import xiaozhi.modules.externalidentity.*;
import xiaozhi.modules.externalidentity.ExternalModels.*;
import xiaozhi.modules.realtime.*;
import xiaozhi.modules.realtime.RealtimeModels.*;
import xiaozhi.modules.realtimetool.RealtimeToolService;
import xiaozhi.modules.realtimeinput.RealtimeInputModels.*;
import xiaozhi.modules.realtimeinput.RealtimeInputModels.Dispatch;

/** Real MySQL identity/leases and private object IO. Provider ACK/perception bodies below are explicitly synthetic fixtures. */
@EnabledIfEnvironmentVariable(named="REALTIME_INPUT_TEST_JDBC_URL",matches=".+")
class RealtimeInputPersistenceTest {
    static JdbcTemplate jdbc;static DataSourceTransactionManager manager;static ObjectMapper json;static Path objects;static AtomicLong users=new AtomicLong(800);
    RealtimeStore store;RealtimeService realtime;RealtimeToolService tools;RealtimeInputService input;ConversationService conversations;AttachmentService media;AiExecutionService ai;
    ExternalIdentityService identity;ExternalSettings settings;MockEnvironment env;byte[] signingKey;String issuer="xiaozhi-material-test",keyId="xiaozhi-material-key";
    long owner,sequence;String conversation,sourceSession="source-session",externalSubject;Session session;Task task;PauseView pause;Principal principal;String bearer;
    static <T>ObjectProvider<T> provider(Class<T> type,T value){var factory=new DefaultListableBeanFactory();factory.registerSingleton("fixture",value);return factory.getBeanProvider(type);}
    @BeforeAll static void database()throws Exception {
        String url=System.getenv("REALTIME_INPUT_TEST_JDBC_URL");if(!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/xiaozhi_realtime_input_test_[a-z0-9_]+(\\?.*)?"))throw new IllegalArgumentException("A NEW loopback xiaozhi_realtime_input_test_* schema is required");
        var source=new DriverManagerDataSource(url,"root",System.getenv("REALTIME_INPUT_TEST_DB_PASSWORD"));jdbc=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);json=new xiaozhi.modules.security.config.WebMvcConfig().jackson2HttpMessageConverter().getObjectMapper();
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Long.class),"Refuse nonempty schema; no cleanup/reset is permitted");
        jdbc.execute("CREATE TABLE sys_user(id BIGINT PRIMARY KEY,status TINYINT NOT NULL,super_admin TINYINT NOT NULL DEFAULT 0,username VARCHAR(80) UNIQUE,password VARCHAR(100),create_date DATETIME(3))");jdbc.execute("CREATE TABLE ai_agent(id VARCHAR(32) PRIMARY KEY,user_id BIGINT)");
        for(String name:List.of("conversation","support","attachments","ai-execution","ai-provider-request-usage","agent-tools","external-identity","realtime-ai","realtime-source-binding","realtime-vad","realtime-tools","realtime-inputs"))
            try(var connection=source.getConnection();var migration=new Liquibase("db/changelog/"+name+".changelog.yaml",new ClassLoaderResourceAccessor(),new JdbcConnection(connection))){migration.update(new Contexts());migration.update(new Contexts());}
        assertEquals(14,jdbc.queryForObject("SELECT COUNT(*) FROM DATABASECHANGELOG",Long.class));objects=Files.createTempDirectory("rt-input-retained-private-");
    }
    @BeforeEach void fixture(){
        owner=users.incrementAndGet();jdbc.update("INSERT INTO sys_user(id,status,super_admin,username) VALUES(?,1,0,?)",owner,"rt-input-local-"+owner);
        externalSubject="material-source-"+owner;
        store=new RealtimeStore(jdbc,manager);env=new MockEnvironment().withProperty("features.realtime-ai-enabled","true").withProperty("features.realtime-tools-enabled","true").withProperty("features.agent-tools-enabled","true")
                .withProperty("features.realtime-materials-enabled","true").withProperty("features.external-realtime-media-enabled","true").withProperty("agent-tools.technical-local-enabled","true")
                .withProperty("realtime.worker.key","material-isolated-worker-key-32-characters").withProperty("realtime.worker.public-url","ws://fixture.invalid/realtime").withProperty("realtime.materials.perception-model","qwen3.8-flash");
        signingKey=new byte[32];new java.security.SecureRandom().nextBytes(signingKey);settings=new ExternalSettings();settings.setEnabled(true);settings.setRenewalEnabled(true);settings.setKeysJson("{\""+keyId+"\":{\"issuer\":\""+issuer+"\",\"secret_b64\":\""+Base64.getEncoder().encodeToString(signingKey)+"\"}}");identity=new ExternalIdentityService(jdbc,json,manager,settings);
        var dao=new ConversationDao(jdbc);conversations=new ConversationService(dao,json,manager);media=new AttachmentService(jdbc,dao,json,manager,new MockEnvironment().withProperty("media.ticket-key","material-private-fixture-ticket-key-32-characters"),new PrivateObjectStore.Filesystem(objects));conversations.setAttachments(media);
        var core=new AgentToolExecutionCore(jdbc,json);core.setEnabled(true);core.setTechnicalEnabled(true);core.setIdentity(identity);tools=new RealtimeToolService(store,json,env,core);tools.setIdentity(identity);
        realtime=new RealtimeService(store,json,env);realtime.setExternal(new ExternalMediaAuthority(jdbc,settings,env,manager));realtime.setRealtimeTools(provider(RealtimeToolService.class,tools));realtime.setConversations(provider(ConversationService.class,conversations));
        input=new RealtimeInputService(store,json,env,provider(RealtimeService.class,realtime),provider(RealtimeToolService.class,tools),provider(ConversationService.class,conversations),provider(AttachmentService.class,media));realtime.setRealtimeInputs(provider(RealtimeInputService.class,input));conversations.setRealtime(realtime);
        ai=new AiExecutionService(jdbc,json,manager);ai.setEnabled(true);ai.setModel("qwen-flash");ai.setRealtime(provider(RealtimeHooks.class,realtime));conversations.setAiExecution(ai);realtime.setAi(provider(AiExecutionService.class,ai));
    }
    @AfterEach void retain(){if(session!=null)store.tx(()->{store.conversation(conversation);realtime.cancelInConversation(conversation,"FIXTURE_FINISHED");return null;});if(conversation!=null)ai.cancelActive(conversation);RequestContextHolder.resetRequestAttributes();}
    Map<String,List<String>> signed(String path,byte[] raw){String timestamp=Long.toString(Instant.now().getEpochSecond()),nonce=UUID.randomUUID().toString().replace("-","");return Map.of("x-external-key-id",List.of(keyId),"x-external-timestamp",List.of(timestamp),"x-external-nonce",List.of(nonce),"x-external-signature",List.of(ExternalIdentityService.signature(signingKey,path,timestamp,nonce,raw)));}
    String login()throws Exception {var node=json.createObjectNode().put("external_subject",externalSubject).put("source_session_id",sourceSession).put("source_expires_at",Long.toString(Instant.now().getEpochSecond()+3600)).put("display_name","Material fixture");node.putObject("tenant").put("type","company").put("id","1");var scope=node.putObject("scope");scope.putArray("project_ids");scope.putArray("device_ids");byte[] raw=json.writeValueAsBytes(node);var ticket=identity.issue(identity.verify(ExternalIdentityService.ISSUE,raw,signed(ExternalIdentityService.ISSUE,raw)),raw);return identity.exchange(ticket.ticket()).token();}
    void connect(boolean source)throws Exception {
        if(source){bearer=login();principal=identity.authenticate(bearer);owner=principal.userId();var request=new MockHttpServletRequest();request.setAttribute(ExternalIdentityService.ATTRIBUTE,principal);RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));}
        conversation=conversations.create(owner,new CreateConversation("Paused material fixture",null)).id();session=realtime.create(owner,new Create(conversation,"session-"+UUID.randomUUID(),new Consent(true,false)));var ticket=realtime.ticket(owner,session.id(),new Epoch(session.controlEpoch()));task=realtime.consume(new Consume(ticket.ticket(),"material-worker","material-server"));
        realtime.providerEvent(session.id(),new ProviderEvent(task.leaseToken(),++sequence,"ready","session.ready","provider-session",null,null,null,null,null,null,null,null));assertNotNull(task.materialPolicy());
    }
    Control control(){return realtime.heartbeat(session.id(),new Heartbeat(task.leaseToken(),true)).inputControl();}
    void pauseAndClear() {
        pause=input.pause(owner,session.id(),new PauseRequest(task.controlEpoch(),"pause-"+UUID.randomUUID()));assertEquals("requested",pause.status());var control=control();assertEquals("pause_clear",control.action());
        pause=input.cleared(session.id(),pause.id(),new Cleared(task.leaseToken(),task.controlEpoch(),control.turnGeneration(),"provider-session","clear-"+UUID.randomUUID(),control.clearRequestId()));assertEquals("paused",pause.status());
    }
    String ready(String kind,String text)throws Exception {
        byte[] bytes;if(kind.equals("image")){var image=new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB);image.setRGB(0,0,0x00ff00);var output=new ByteArrayOutputStream();ImageIO.write(image,"jpeg",output);bytes=output.toByteArray();}else bytes=text.getBytes(StandardCharsets.UTF_8);
        String mime=kind.equals("image")?"image/jpeg":"text/plain";String file=kind.equals("image")?"fixture.jpg":"fixture.txt";
        var init=media.init(owner,new AttachmentModels.Init(conversation,UUID.randomUUID().toString(),file,mime,(long)bytes.length,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))),"/xiaozhi");media.upload(owner,init.id(),mime,bytes.length,new ByteArrayInputStream(bytes));media.completeUpload(owner,init.id());var job=media.claim(new AttachmentModels.Claim("synthetic-material-derivative"));assertEquals(init.id(),job.attachmentId());
        if(kind.equals("image")){media.artifact(job.jobId(),"display",job.leaseToken(),"image/jpeg",new ByteArrayInputStream(bytes));media.artifact(job.jobId(),"thumbnail",job.leaseToken(),"image/jpeg",new ByteArrayInputStream(bytes));media.completeJob(job.jobId(),new AttachmentModels.Complete(job.leaseToken(),Map.of("mime_type",mime,"width",2,"height",2)));}
        else{media.artifact(job.jobId(),"text",job.leaseToken(),"text/plain",new ByteArrayInputStream(bytes));media.completeJob(job.jobId(),new AttachmentModels.Complete(job.leaseToken(),Map.of("mime_type",mime,"text_available",true,"text_truncated",false)));}return init.id();
    }
    Submitted submit(String kind,String id){return input.submit(owner,session.id(),new MaterialRequest(task.controlEpoch(),pause.id(),"material-"+UUID.randomUUID(),List.of(new MessagePart("text","Inspect this authorized material",null,null,null,null,null,null),new MessagePart(kind,null,null,null,null,null,null,null,id))));}
    Dispatch dispatch(){var value=realtime.heartbeat(session.id(),new Heartbeat(task.leaseToken(),true)).material();assertNotNull(value);return value;}
    Prepare preparation(Dispatch dispatch){List<Observation> observations=dispatch.images().stream().map(image->new Observation(image.attachmentId(),dispatch.perceptionModel(),"Synthetic perception fixture: a green pixel; not real model evidence",new Usage(3L,2L,5L),"fixture-request")).toList();return new Prepare(task.leaseToken(),task.controlEpoch(),dispatch.turnGeneration(),observations);}
    void ack(Dispatch dispatch,Prepared prepared){var result=input.acknowledge(session.id(),dispatch.id(),new ContextAck(task.leaseToken(),task.controlEpoch(),dispatch.turnGeneration(),"provider-session","context-event-"+dispatch.id(),prepared.contextItemId(),"provider-echo-"+dispatch.id()));assertEquals("acknowledged",result.status());}
    ResumePermission resume(){var requested=input.resume(owner,session.id(),pause.id(),new ResumeRequest(task.controlEpoch(),"resume-"+UUID.randomUUID()));assertEquals("resuming",requested.status());var control=control();assertEquals("resume",control.action());return input.resumed(session.id(),pause.id(),new Resumed(task.leaseToken(),task.controlEpoch(),control.turnGeneration(),control.resumeRequestId()));}
    long count(String sql,Object...args){return jdbc.queryForObject(sql,Long.class,args);}
    @Test void durableClearReadyFileSingleUserMessageContextAckAndResumeNoOrdinaryRun()throws Exception {
        connect(false);pauseAndClear();String file=ready("file","Verified private document text");var submitted=submit("file",file);assertEquals("queued",submitted.material().status());assertEquals(1,count("SELECT COUNT(*) FROM ai_message WHERE conversation_id=? AND sender_type='user'",conversation));assertEquals(0,count("SELECT COUNT(*) FROM ai_run WHERE conversation_id=?",conversation));
        assertThrows(ConversationProblem.class,()->input.resume(owner,session.id(),pause.id(),new ResumeRequest(task.controlEpoch(),"too-early")));
        var dispatch=dispatch();var body=preparation(dispatch);var prepared=input.prepared(session.id(),dispatch.id(),body);assertTrue(prepared.contextText().contains("Verified private document text"));assertEquals(prepared,input.prepared(session.id(),dispatch.id(),body));
        ack(dispatch,prepared);var permission=resume();assertTrue(permission.respond());assertEquals(dispatch.id(),permission.materialId());var state=input.state(owner,session.id());assertEquals("live",state.phase());assertEquals("resumed",state.pause().status());assertEquals("acknowledged",state.material().status());
        assertEquals(0,count("SELECT COUNT(*) FROM ai_run WHERE conversation_id=?",conversation));assertEquals(1,count("SELECT COUNT(*) FROM ai_message WHERE conversation_id=? AND sender_type='user'",conversation));
        realtime.end(owner,session.id(),new Epoch(task.controlEpoch()));conversations.send(owner,conversation,new SendMessage("after-realtime",List.of(new MessagePart("text","Continue this same conversation",null,null,null,null,null,null))));assertEquals(1,count("SELECT COUNT(*) FROM ai_run WHERE conversation_id=?",conversation));
    }
    @Test void originalAttachmentPathStillRejectsAndNoMaterialBeforeClearOrReady()throws Exception {
        connect(false);String file=ready("file","ready before pause");assertEquals("REALTIME_TEXT_ONLY",assertThrows(ConversationProblem.class,()->conversations.send(owner,conversation,new SendMessage("old-path",List.of(new MessagePart("file",null,null,null,null,null,null,null,file))))).getMessage());
        pause=input.pause(owner,session.id(),new PauseRequest(task.controlEpoch(),"uncleared"));assertThrows(ConversationProblem.class,()->submit("file",file));assertEquals(0,count("SELECT COUNT(*) FROM ai_message WHERE conversation_id=?",conversation));
        var clear=control();input.cleared(session.id(),pause.id(),new Cleared(task.leaseToken(),task.controlEpoch(),clear.turnGeneration(),"provider-session","cleared",clear.clearRequestId()));
        byte[] bytes="incomplete upload".getBytes(StandardCharsets.UTF_8);var init=media.init(owner,new AttachmentModels.Init(conversation,"pending","pending.txt","text/plain",(long)bytes.length,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))),"");assertThrows(ConversationProblem.class,()->submit("file",init.id()));assertEquals(0,count("SELECT COUNT(*) FROM ai_run WHERE conversation_id=?",conversation));
    }
    @Test void imageUsesPrivateBoundedContentAndPerceptionIsExplicitWithRealUsageLedger()throws Exception {
        connect(false);pauseAndClear();String image=ready("image","");var submitted=submit("image",image);var dispatch=dispatch();assertEquals(image,dispatch.images().getFirst().attachmentId());
        try(var content=input.content(session.id(),dispatch.id(),image,"display",task.leaseToken())){assertEquals("image/jpeg",content.mimeType());assertTrue(content.size()<=1048576);assertTrue(content.input().readAllBytes().length>0);}
        var prepared=input.prepared(session.id(),dispatch.id(),preparation(dispatch));assertTrue(prepared.contextText().contains("original image was NOT passed to realtime model"));assertEquals(5,count("SELECT SUM(total_tokens) FROM ai_realtime_perception_usage WHERE material_id=?",dispatch.id()));assertEquals(0,count("SELECT COUNT(*) FROM ai_run WHERE conversation_id=?",conversation));
        ack(dispatch,prepared);assertTrue(resume().respond());assertEquals(submitted.message().id(),input.material(owner,session.id(),dispatch.id()).messageId());
    }
    @Test void canceledPerceptionRetainsKnownUsageButNeverStoresOrInjectsStaleText()throws Exception {
        connect(false);pauseAndClear();String image=ready("image","");submit("image",image);var dispatch=dispatch();var body=preparation(dispatch);
        input.cancel(owner,session.id(),pause.id(),new CancelRequest(task.controlEpoch(),"cancel-perception"));assertThrows(ConversationProblem.class,()->input.prepared(session.id(),dispatch.id(),body));
        assertEquals(5,count("SELECT SUM(total_tokens) FROM ai_realtime_perception_usage WHERE material_id=?",dispatch.id()));assertNull(jdbc.queryForObject("SELECT observations_json FROM ai_realtime_material WHERE id=?",String.class,dispatch.id()));assertNull(jdbc.queryForObject("SELECT context_text FROM ai_realtime_material WHERE id=?",String.class,dispatch.id()));
        var clear=control();assertTrue(clear.turnGeneration()>dispatch.turnGeneration());input.cleared(session.id(),pause.id(),new Cleared(task.leaseToken(),task.controlEpoch(),clear.turnGeneration(),"provider-session","clear-after-cancel",clear.clearRequestId()));var permission=resume();assertFalse(permission.respond());assertNull(permission.materialId());
    }
    @Test void revokedExactSourceCannotPrepareButOriginalWorkerCanAuditKnownPerceptionCost()throws Exception {
        connect(true);pauseAndClear();String image=ready("image","");submit("image",image);var dispatch=dispatch();var body=preparation(dispatch);
        byte[] raw=json.writeValueAsBytes(Map.of("external_subject",principal.subject(),"source_session_id",sourceSession));identity.revoke(identity.verify(ExternalIdentityService.REVOKE,raw,signed(ExternalIdentityService.REVOKE,raw)),raw);
        assertThrows(ConversationProblem.class,()->input.prepared(session.id(),dispatch.id(),body));assertEquals(5,count("SELECT SUM(total_tokens) FROM ai_realtime_perception_usage WHERE material_id=?",dispatch.id()));assertNull(jdbc.queryForObject("SELECT observations_json FROM ai_realtime_material WHERE id=?",String.class,dispatch.id()));
        assertThrows(ConversationProblem.class,()->input.acknowledge(session.id(),dispatch.id(),new ContextAck(task.leaseToken(),task.controlEpoch(),dispatch.turnGeneration(),"provider-session","late","rt-material-"+dispatch.id(),"late-echo")));
    }
    @Test void exactBearerRenewalWorksNewBearerCannotAdoptAndAckReplaysAreImmutable()throws Exception {
        connect(true);pauseAndClear();String file=ready("file","Source-owned document");submit("file",file);var dispatch=dispatch();
        var renew=json.createObjectNode().put("external_subject",principal.subject()).put("source_session_id",sourceSession).put("token_id",Long.toString(principal.tokenId())).put("requested_lifetime_seconds",300);renew.set("tenant",json.valueToTree(principal.tenant()));renew.set("scope",json.valueToTree(principal.scope()));byte[] raw=json.writeValueAsBytes(renew);identity.renew(ExternalIdentityService.RENEW,raw,signed(ExternalIdentityService.RENEW,raw));
        var prepared=input.prepared(session.id(),dispatch.id(),preparation(dispatch));var ack=new ContextAck(task.leaseToken(),task.controlEpoch(),dispatch.turnGeneration(),"provider-session","source-ack",prepared.contextItemId(),"source-echo");var first=input.acknowledge(session.id(),dispatch.id(),ack);assertEquals(first,input.acknowledge(session.id(),dispatch.id(),ack));
        assertThrows(ConversationProblem.class,()->input.acknowledge(session.id(),dispatch.id(),new ContextAck(task.leaseToken(),task.controlEpoch(),dispatch.turnGeneration(),"provider-session","different-ack",prepared.contextItemId(),"different-echo")));
        identity.logout(principal);String replacement=login();var newPrincipal=identity.authenticate(replacement);assertEquals(owner,newPrincipal.userId());assertNotEquals(principal.tokenId(),newPrincipal.tokenId());var request=new MockHttpServletRequest();request.setAttribute(ExternalIdentityService.ATTRIBUTE,newPrincipal);RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));assertThrows(ConversationProblem.class,()->input.state(owner,session.id()));
    }
    @Test void preparedButUnconfirmedCancellationEndsSessionAndRequiresExplicitNewSession()throws Exception {
        connect(false);pauseAndClear();submit("file",ready("file","private context"));var dispatch=dispatch();input.prepared(session.id(),dispatch.id(),preparation(dispatch));var result=input.cancel(owner,session.id(),pause.id(),new CancelRequest(task.controlEpoch(),"uncertain-cancel"));assertEquals("failed",result.status());assertEquals("failed",realtime.get(owner,session.id()).status());assertEquals("MATERIAL_CONTEXT_UNCONFIRMED",realtime.get(owner,session.id()).errorCode());assertThrows(ConversationProblem.class,()->resume());
        var next=realtime.create(owner,new Create(conversation,"explicit-new-session",new Consent(true,false)));assertNotEquals(session.id(),next.id());realtime.end(owner,next.id(),new Epoch(next.controlEpoch()));
    }
    @Test void staleLeaseCannotReadContentOrAuthorizeContextAndUnknownUsageIsNotZeroFilled()throws Exception {
        connect(false);pauseAndClear();String image=ready("image","");submit("image",image);var dispatch=dispatch();var wrong=new PerceptionUsage("b".repeat(64),task.controlEpoch(),dispatch.turnGeneration(),List.of(new UsageReport(image,dispatch.perceptionModel(),new Usage(1L,1L,2L),"wrong")));assertThrows(ConversationProblem.class,()->input.reportUsage(session.id(),dispatch.id(),wrong));
        var unknown=new PerceptionUsage(task.leaseToken(),task.controlEpoch(),dispatch.turnGeneration(),List.of(new UsageReport(image,dispatch.perceptionModel(),null,"unknown")));input.reportUsage(session.id(),dispatch.id(),unknown);input.reportUsage(session.id(),dispatch.id(),unknown);assertEquals(1,count("SELECT COUNT(*) FROM ai_realtime_perception_usage WHERE material_id=? AND total_tokens IS NULL",dispatch.id()));
        jdbc.update("UPDATE ai_realtime_session SET lease_expires_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",session.id());assertThrows(ConversationProblem.class,()->input.content(session.id(),dispatch.id(),image,"display",task.leaseToken()));
    }
    @Test void cancelDuringPrivateUploadKeepsOriginalButLateReadyCannotEnterResumedPause()throws Exception {
        connect(false);pauseAndClear();byte[] bytes="retained staged upload".getBytes(StandardCharsets.UTF_8);
        var init=media.init(owner,new AttachmentModels.Init(conversation,"upload-canceled","retained.txt","text/plain",(long)bytes.length,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))),"/xiaozhi");
        media.upload(owner,init.id(),"text/plain",bytes.length,new ByteArrayInputStream(bytes));
        var canceled=input.cancel(owner,session.id(),pause.id(),new CancelRequest(task.controlEpoch(),"cancel-upload"));assertEquals("requested",canceled.status());
        var clear=control();input.cleared(session.id(),pause.id(),new Cleared(task.leaseToken(),task.controlEpoch(),clear.turnGeneration(),"provider-session","clear-upload-canceled",clear.clearRequestId()));assertFalse(resume().respond());
        media.completeUpload(owner,init.id());var job=media.claim(new AttachmentModels.Claim("late-upload-fixture"));assertEquals(init.id(),job.attachmentId());
        media.artifact(job.jobId(),"text",job.leaseToken(),"text/plain",new ByteArrayInputStream(bytes));media.completeJob(job.jobId(),new AttachmentModels.Complete(job.leaseToken(),Map.of("mime_type","text/plain","text_available",true,"text_truncated",false)));
        assertThrows(ConversationProblem.class,()->submit("file",init.id()));assertEquals("ready",media.get(owner,init.id()).status());assertNull(jdbc.queryForObject("SELECT linked_message_id FROM ai_attachment WHERE id=?",String.class,init.id()));
        assertEquals(0,count("SELECT COUNT(*) FROM ai_message WHERE conversation_id=?",conversation));assertEquals(0,count("SELECT COUNT(*) FROM ai_run WHERE conversation_id=?",conversation));
    }
    @Test void exactContextAckRetrySurvivesConcurrentResumeWithoutRepublishingOrDisconnect()throws Exception {
        connect(false);pauseAndClear();submit("file",ready("file","retry-safe committed evidence"));var dispatch=dispatch();var prepared=input.prepared(session.id(),dispatch.id(),preparation(dispatch));
        var ack=new ContextAck(task.leaseToken(),task.controlEpoch(),dispatch.turnGeneration(),"provider-session","ack-retry-event",prepared.contextItemId(),"ack-retry-echo");input.acknowledge(session.id(),dispatch.id(),ack);
        var requested=input.resume(owner,session.id(),pause.id(),new ResumeRequest(task.controlEpoch(),"concurrent-resume"));assertEquals("resuming",requested.status());
        assertEquals("acknowledged",input.acknowledge(session.id(),dispatch.id(),ack).status(),"A lost ACK response may be retried after the owner has requested resume");
        var control=control();input.resumed(session.id(),pause.id(),new Resumed(task.leaseToken(),task.controlEpoch(),control.turnGeneration(),control.resumeRequestId()));
        assertEquals("acknowledged",input.acknowledge(session.id(),dispatch.id(),ack).status());assertEquals(1,count("SELECT COUNT(*) FROM ai_conversation_event WHERE conversation_id=? AND event_type='realtime.material.acknowledged'",conversation));assertEquals("live",input.state(owner,session.id()).phase());
    }
    @Test void priorPauseIdsCannotMintResumePermissionOrClearReceiptForANewGeneration()throws Exception {
        connect(false);pauseAndClear();var oldPause=pause;var oldClear=jdbc.queryForMap("SELECT clear_request_id,clear_provider_event_id FROM ai_realtime_input_pause WHERE id=?",pause.id());
        submit("file",ready("file","old evidence"));var dispatch=dispatch();var prepared=input.prepared(session.id(),dispatch.id(),preparation(dispatch));ack(dispatch,prepared);assertTrue(resume().respond());
        String oldRequest=jdbc.queryForObject("SELECT resume_request_id FROM ai_realtime_input_pause WHERE id=?",String.class,oldPause.id());
        pause=input.pause(owner,session.id(),new PauseRequest(task.controlEpoch(),"new-generation-pause"));var fresh=control();assertTrue(fresh.turnGeneration()>dispatch.turnGeneration());
        assertThrows(ConversationProblem.class,()->input.resumed(session.id(),oldPause.id(),new Resumed(task.leaseToken(),task.controlEpoch(),fresh.turnGeneration(),oldRequest)));
        assertThrows(ConversationProblem.class,()->input.cleared(session.id(),oldPause.id(),new Cleared(task.leaseToken(),task.controlEpoch(),fresh.turnGeneration(),"provider-session",(String)oldClear.get("clear_provider_event_id"),(String)oldClear.get("clear_request_id"))));
        assertEquals("requested",input.state(owner,session.id()).pause().status());assertEquals(1,count("SELECT COUNT(*) FROM ai_conversation_event WHERE conversation_id=? AND event_type='realtime.input.resumed'",conversation));
    }
    @Test void clearAckCannotBeatTheHeartbeatDeadlineSweep()throws Exception {
        connect(false);pause=input.pause(owner,session.id(),new PauseRequest(task.controlEpoch(),"late-clear"));var command=control();jdbc.update("UPDATE ai_realtime_input_pause SET updated_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 16 SECOND) WHERE id=?",pause.id());
        assertThrows(ConversationProblem.class,()->input.cleared(session.id(),pause.id(),new Cleared(task.leaseToken(),task.controlEpoch(),command.turnGeneration(),"provider-session","late-clear-ack",command.clearRequestId())));
        assertNull(jdbc.queryForObject("SELECT cleared_at FROM ai_realtime_input_pause WHERE id=?",java.sql.Timestamp.class,pause.id()));
    }
    @Test void resumeAckCannotBeatTheHeartbeatDeadlineSweep()throws Exception {
        connect(false);pauseAndClear();input.resume(owner,session.id(),pause.id(),new ResumeRequest(task.controlEpoch(),"late-resume"));var command=control();jdbc.update("UPDATE ai_realtime_input_pause SET updated_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 16 SECOND) WHERE id=?",pause.id());
        assertThrows(ConversationProblem.class,()->input.resumed(session.id(),pause.id(),new Resumed(task.leaseToken(),task.controlEpoch(),command.turnGeneration(),command.resumeRequestId())));
        assertNull(jdbc.queryForObject("SELECT resumed_at FROM ai_realtime_input_pause WHERE id=?",java.sql.Timestamp.class,pause.id()));
    }
    @Test void expiredPerceptionCannotBeatHeartbeatSweepButActualCostIsStillRetained()throws Exception {
        connect(false);pauseAndClear();submit("image",ready("image",null));var dispatch=dispatch();var body=preparation(dispatch);jdbc.update("UPDATE ai_realtime_material SET dispatched_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 46 SECOND) WHERE id=?",dispatch.id());
        assertThrows(ConversationProblem.class,()->input.prepared(session.id(),dispatch.id(),body));assertEquals(5,count("SELECT SUM(total_tokens) FROM ai_realtime_perception_usage WHERE material_id=?",dispatch.id()));
        assertNull(jdbc.queryForObject("SELECT context_text FROM ai_realtime_material WHERE id=?",String.class,dispatch.id()));assertNull(jdbc.queryForObject("SELECT observations_json FROM ai_realtime_material WHERE id=?",String.class,dispatch.id()));
    }
    @Test void disconnectedLeaseExpiryReleasesMaterialAndOnlyOriginalDispatchMayAuditLateCost()throws Exception {
        connect(false);pauseAndClear();submit("image",ready("image",null));var dispatch=dispatch();jdbc.update("UPDATE ai_realtime_session SET lease_expires_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",session.id());realtime.maintain();
        assertEquals("expired",realtime.get(owner,session.id()).status());assertEquals("ended",input.state(owner,session.id()).pause().status());assertEquals("canceled",input.material(owner,session.id(),dispatch.id()).status());
        var body=preparation(dispatch);assertThrows(ConversationProblem.class,()->input.prepared(session.id(),dispatch.id(),body));assertEquals(5,count("SELECT SUM(total_tokens) FROM ai_realtime_perception_usage WHERE material_id=?",dispatch.id()));assertNull(jdbc.queryForObject("SELECT observations_json FROM ai_realtime_material WHERE id=?",String.class,dispatch.id()));
        var wrong=new PerceptionUsage("b".repeat(64),task.controlEpoch(),dispatch.turnGeneration(),List.of(new UsageReport(dispatch.images().getFirst().attachmentId(),dispatch.perceptionModel(),new Usage(3L,2L,5L),"fixture-request")));assertThrows(ConversationProblem.class,()->input.reportUsage(session.id(),dispatch.id(),wrong));
    }

    @Test void anEchoAlreadyBoundToAnotherMaterialIsAControlledConflictNotASqlFailure()throws Exception {
        connect(false);pauseAndClear();submit("file",ready("file","first material"));var first=dispatch();var firstPrepared=input.prepared(session.id(),first.id(),preparation(first));input.acknowledge(session.id(),first.id(),new ContextAck(task.leaseToken(),task.controlEpoch(),first.turnGeneration(),"provider-session","first-event",firstPrepared.contextItemId(),"shared-echo"));assertTrue(resume().respond());
        pauseAndClear();submit("file",ready("file","second material"));var second=dispatch();var prepared=input.prepared(session.id(),second.id(),preparation(second));var error=assertThrows(ConversationProblem.class,()->input.acknowledge(session.id(),second.id(),new ContextAck(task.leaseToken(),task.controlEpoch(),second.turnGeneration(),"provider-session","second-event",prepared.contextItemId(),"shared-echo")));
        assertEquals(409,error.status());assertEquals("REALTIME_CONTEXT_ACK_CONFLICT",error.getMessage());assertEquals("prepared",input.material(owner,session.id(),second.id()).status());assertEquals(1,count("SELECT COUNT(*) FROM ai_realtime_material WHERE session_id=? AND ack_provider_item_id='shared-echo'",session.id()));
    }

}
