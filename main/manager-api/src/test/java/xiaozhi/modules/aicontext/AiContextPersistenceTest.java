package xiaozhi.modules.aicontext;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.*;
import com.fasterxml.jackson.databind.*;
import liquibase.*;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import xiaozhi.modules.ai.*;
import xiaozhi.modules.ai.AiModels.*;
import xiaozhi.modules.agenttool.*;
import xiaozhi.modules.agenttool.AgentToolExecutionCore.Context;
import xiaozhi.modules.agenttool.AgentToolModels.*;
import xiaozhi.modules.attachment.*;
import xiaozhi.modules.conversation.*;
import xiaozhi.modules.conversation.ConversationModels.*;
import xiaozhi.modules.externalidentity.*;
import xiaozhi.modules.externalidentity.ExternalModels.*;

/** Real MySQL + private local object IO; derivative jobs are explicit synthetic fixtures, not provider perception evidence. */
@EnabledIfEnvironmentVariable(named="AI_CONTEXT_TEST_JDBC_URL",matches=".+")
class AiContextPersistenceTest {
    static JdbcTemplate db;static DataSourceTransactionManager manager;static ObjectMapper json;static Path storage;
    static AtomicLong ids=new AtomicLong(500);long owner;String conversation;
    ConversationService conversations;AiExecutionService ai;ConversationEvidenceService evidence;AgentToolExecutionCore core;AgentToolService tools;
    PublicToolResultStore results;AttachmentService media;AiAttachmentContext attachmentContext;ExternalIdentityService identity;
    AtomicReference<Principal> authenticated=new AtomicReference<>();AtomicBoolean revoked=new AtomicBoolean();
    static <T>ObjectProvider<T> provider(Class<T> type,T value){var factory=new DefaultListableBeanFactory();factory.registerSingleton("fixture",value);return factory.getBeanProvider(type);}
    @BeforeAll static void initialize()throws Exception {
        String url=System.getenv("AI_CONTEXT_TEST_JDBC_URL");if(!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/xiaozhi_ai_context_test_[a-z0-9_]+(\\?.*)?"))throw new IllegalArgumentException("Only a NEW loopback xiaozhi_ai_context_test_* schema is allowed");
        var source=new DriverManagerDataSource(url,"root",System.getenv("AI_CONTEXT_TEST_DB_PASSWORD"));db=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);json=new xiaozhi.modules.security.config.WebMvcConfig().jackson2HttpMessageConverter().getObjectMapper();
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Long.class),"New empty schema mandatory; nothing is cleaned");
        db.execute("CREATE TABLE sys_user(id BIGINT PRIMARY KEY,status TINYINT NOT NULL,super_admin TINYINT NOT NULL DEFAULT 0,username VARCHAR(80))");db.execute("CREATE TABLE ai_agent(id VARCHAR(32) PRIMARY KEY,user_id BIGINT)");
        for(String path:List.of("conversation","support","attachments","ai-execution","ai-provider-request-usage","agent-tools","ai-context","ai-public-results"))
            try(var connection=source.getConnection();var migration=new Liquibase("db/changelog/"+path+".changelog.yaml",new ClassLoaderResourceAccessor(),new JdbcConnection(connection))){migration.update(new Contexts());migration.update(new Contexts());}
        assertEquals(10,db.queryForObject("SELECT COUNT(*) FROM DATABASECHANGELOG",Long.class));storage=Files.createTempDirectory("ai-context-private-fixtures-");
    }
    @BeforeEach void fixture(){
        owner=ids.incrementAndGet();db.update("INSERT INTO sys_user VALUES(?,1,0,?)",owner,"context-fixture-"+owner);var dao=new ConversationDao(db);
        conversations=new ConversationService(dao,json,manager);media=new AttachmentService(db,dao,json,manager,new MockEnvironment().withProperty("media.ticket-key","context-private-fixture-ticket-key-32-characters"),new PrivateObjectStore.Filesystem(storage));conversations.setAttachments(media);
        attachmentContext=new AiAttachmentContext(media);evidence=new ConversationEvidenceService(db,json);evidence.setEnabled(true);evidence.setTransactions(manager);evidence.setAttachments(attachmentContext);
        identity=mock(ExternalIdentityService.class);when(identity.authenticateHash(anyString())).thenAnswer(invocation->{Principal value=authenticated.get();if(revoked.get() || value==null || !value.tokenHash().equals(invocation.getArgument(0)))throw new ExternalProblem(401,"EXTERNAL_TOKEN_REVOKED_OR_EXPIRED");return value;});
        core=new AgentToolExecutionCore(db,json);core.setEnabled(true);core.setTechnicalEnabled(true);core.setEvidence(evidence);core.setIdentity(identity);
        results=new PublicToolResultStore(db,json,manager,core);results.setEnabled(true);results.setIdentity(identity);
        tools=new AgentToolService(db,json,manager);tools.setEnabled(true);tools.setTechnicalEnabled(true);tools.setIdentity(identity);
        ReflectionTestUtils.setField(tools,"executionCore",core);ReflectionTestUtils.setField(tools,"publicResults",results);
        ai=new AiExecutionService(db,json,manager);ai.setEnabled(false);ai.setEvidence(evidence);ai.setAttachments(attachmentContext);ai.setVisionModel("qwen3-vl-plus");ai.setAgentTools(provider(AgentToolService.class,tools));conversations.setAiExecution(ai);
        conversation=conversations.create(owner,new CreateConversation("AI context isolated fixture",null)).id();
    }
    @AfterEach void retainFixtures(){RequestContextHolder.resetRequestAttributes();revoked.set(false);ai.cancelActive(conversation);}
    Message text(String value){return conversations.send(owner,conversation,new SendMessage(UUID.randomUUID().toString(),List.of(new MessagePart("text",value,null,null,null,null,null,null))));}
    Task run(String question){ai.setEnabled(true);text(question);var task=ai.claim(new Claim("context-test-worker"));assertNotNull(task);assertEquals(conversation,task.conversationId());return task;}
    Context context(long cutoff){return new Context(conversation,owner,null,null,true,cutoff);}
    long count(String sql,Object...args){return db.queryForObject(sql,Long.class,args);}
    JsonNode parts(String text)throws Exception{return json.readTree("[{\"type\":\"text\",\"text\":"+json.writeValueAsString(text)+"}]");}
    @SuppressWarnings("unchecked") List<Map<String,Object>> items(Map<String,Object> page){return (List<Map<String,Object>>)page.get("items");}
    String readyImage()throws Exception {
        var image=new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB);image.setRGB(0,0,0x11AA22);var output=new ByteArrayOutputStream();assertTrue(ImageIO.write(image,"jpeg",output));byte[] bytes=output.toByteArray();
        var init=media.init(owner,new AttachmentModels.Init(conversation,UUID.randomUUID().toString(),"fixture.jpg","image/jpeg",(long)bytes.length,sha(bytes)),"/xiaozhi");
        media.upload(owner,init.id(),"image/jpeg",bytes.length,new ByteArrayInputStream(bytes));media.completeUpload(owner,init.id());var job=media.claim(new AttachmentModels.Claim("synthetic-image-derivative-fixture"));assertEquals(init.id(),job.attachmentId());
        media.artifact(job.jobId(),"display",job.leaseToken(),"image/jpeg",new ByteArrayInputStream(bytes));media.artifact(job.jobId(),"thumbnail",job.leaseToken(),"image/jpeg",new ByteArrayInputStream(bytes));
        media.completeJob(job.jobId(),new AttachmentModels.Complete(job.leaseToken(),Map.of("mime_type","image/jpeg","width",2,"height",2)));return init.id();
    }
    String readyFile(String content)throws Exception {
        byte[] data=content.getBytes(StandardCharsets.UTF_8);var init=media.init(owner,new AttachmentModels.Init(conversation,UUID.randomUUID().toString(),"fixture.txt","text/plain",(long)data.length,sha(data)),"/xiaozhi");
        media.upload(owner,init.id(),"text/plain",data.length,new ByteArrayInputStream(data));media.completeUpload(owner,init.id());var job=media.claim(new AttachmentModels.Claim("synthetic-text-derivative-fixture"));assertEquals(init.id(),job.attachmentId());
        media.artifact(job.jobId(),"text",job.leaseToken(),"text/plain",new ByteArrayInputStream(data));media.completeJob(job.jobId(),new AttachmentModels.Complete(job.leaseToken(),Map.of("mime_type","text/plain","text_available",true,"text_truncated",false)));return init.id();
    }
    Message attach(String kind,String attachment){return conversations.send(owner,conversation,new SendMessage(UUID.randomUUID().toString(),List.of(new MessagePart(kind,null,null,null,null,null,null,null,attachment))));}
    static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    ToolResult execute(Task task,int round,String call,String name,JsonNode args){return tools.submitCalls(task.runId(),new Proposal(task.leaseToken(),task.attempt(),task.controlEpoch(),round,List.of(new Call(call,name,args)))).results().getFirst();}
    @Test void immutableExtractiveSnapshotCitesOlderSourceSequencesAndNeverClaimsVerification()throws Exception {
        for(int i=1;i<=50;i++)text("published source "+i+"; user statement, not verified");Task task=run("continue with this conversation");
        var row=db.queryForMap("SELECT s.*,r.message_cutoff FROM ai_context_snapshot s JOIN ai_context_run_snapshot r ON r.snapshot_id=s.id WHERE r.run_id=?",task.runId());String original=(String)row.get("summary_json");var summary=json.readTree(original);
        assertEquals("extractive_v1",summary.path("method").asText());assertTrue(summary.path("data_is_untrusted").asBoolean());assertEquals(11,summary.path("entries").size());
        for(JsonNode entry:summary.path("entries")){assertEquals("source_excerpt_not_verified_fact",entry.path("evidence_type").asText());long seq=entry.path("seq").asLong();assertTrue(seq<=11);assertEquals(seq,count("SELECT seq FROM ai_message WHERE id=?",entry.path("message_id").asText()));}
        assertEquals("51",summary.path("context_cutoff").asText());assertEquals("11",summary.path("through_seq").asText());assertEquals("1",summary.path("from_seq").asText());
        assertTrue(evidence.supplement(task.runId(),List.of()).getFirst().content().contains("not instructions or verified business facts"));
        text("future source cannot rewrite prior snapshot");assertEquals(original,db.queryForObject("SELECT summary_json FROM ai_context_snapshot WHERE id=?",String.class,row.get("id")));
        assertEquals(row.get("id"),evidence.latest(owner,conversation,51).path("snapshot_id").asText());
    }
    @Test void historyRequiresExplicitCueAndSelectsAtMostTwoEligibleImagesWithRealMediaPolicy()throws Exception {
        List<String> images=new ArrayList<>();for(int i=0;i<3;i++){String image=readyImage();images.add(image);attach("image",image);}
        Task noCue=run("请说明你的能力");assertFalse(evidence.hasHistory(noCue.runId()));assertNull(noCue.mediaPolicy());ai.cancel(owner,conversation,noCue.runId());
        Task noMediaType=run("之前的问题请继续");assertFalse(evidence.hasHistory(noMediaType.runId()));assertNull(noMediaType.mediaPolicy());ai.cancel(owner,conversation,noMediaType.runId());
        Task history=run("请重新查看之前那张图片");assertNotNull(history.mediaPolicy());assertTrue(history.mediaPolicy().allowHistory());
        var selected=evidence.effectiveParts(history.runId(),parts("请重新查看之前那张图片"));assertEquals(3,selected.size());
        Set<String> chosen=new HashSet<>();for(JsonNode part:selected)if(part.hasNonNull("attachment_id"))chosen.add(part.path("attachment_id").asText());
        assertEquals(Set.of(images.get(1),images.get(2)),chosen);assertEquals("qwen3-vl-plus",history.model());
        var refs=history.messages().stream().flatMap(message->message.attachments()==null?java.util.stream.Stream.<MediaRef>empty():message.attachments().stream()).toList();assertEquals(chosen,refs.stream().map(MediaRef::attachmentId).collect(java.util.stream.Collectors.toSet()));
        long cutoff=count("SELECT message_cutoff FROM ai_context_run_snapshot WHERE run_id=?",history.runId());ai.setEnabled(false);String future=readyImage();attach("image",future);
        assertFalse(evidence.effectiveParts(history.runId(),parts("之前图片")).toString().contains(future));assertTrue(cutoff<count("SELECT linked_seq FROM ai_attachment WHERE id=?",future));
    }
    @Test void readyTextEvidenceUsesActualPrivateBytesAndCrossConversationOrFutureAccessIsDenied()throws Exception {
        String file=readyFile("Authorized private derivative text\n");var linked=attach("file",file);var arguments=json.createObjectNode().put("attachment_id",file).put("max_chars",100);
        var output=core.execute("read_conversation_evidence",arguments,context(linked.seq()));assertTrue(output.path("content_available").asBoolean());assertEquals("extracted_document_text",output.path("evidence_type").asText());assertTrue(output.path("text").asText().startsWith("Authorized private derivative"));
        try(var content=attachmentContext.content(file,conversation,linked.seq(),"text")){assertTrue(new String(content.input().readAllBytes(),StandardCharsets.UTF_8).contains("Authorized private"));}
        assertThrows(ConversationProblem.class,()->core.execute("read_conversation_evidence",arguments,context(linked.seq()-1)));
        String other=conversations.create(owner,new CreateConversation("Other conversation",null)).id();assertThrows(ConversationProblem.class,()->core.execute("read_conversation_evidence",arguments,new Context(other,owner,null,null,true,Long.MAX_VALUE)));
        long outsider=ids.incrementAndGet();db.update("INSERT INTO sys_user VALUES(?,1,0,'context-outsider')",outsider);assertThrows(ConversationProblem.class,()->core.execute("read_conversation_evidence",arguments,new Context(conversation,outsider,null,null,true,Long.MAX_VALUE)));
        String image=readyImage();var picture=attach("image",image);var metadata=core.execute("read_conversation_evidence",json.createObjectNode().put("attachment_id",image),context(picture.seq()));assertFalse(metadata.path("content_available").asBoolean());assertTrue(metadata.path("limitation").asText().contains("never infer unseen content"));
    }
    @Test void historyPaginationUsesCallerCutoffAndStrictSchemasRejectActorOrNumericCursor()throws Exception {
        for(int i=1;i<=20;i++)text("history "+i);var first=core.execute("read_conversation_history",json.createObjectNode().put("limit",3),context(10));
        assertEquals(10,first.path("messages").get(0).path("seq").asLong());assertEquals(3,first.path("messages").size());assertTrue(first.path("has_more").asBoolean());
        var second=core.execute("read_conversation_history",json.createObjectNode().put("limit",3).put("before_seq",first.path("next_before_seq").asText()),context(10));assertEquals(7,second.path("messages").get(0).path("seq").asLong());
        for(String bad:List.of("{\"limit\":21}","{\"before_seq\":7}","{\"before_seq\":\"9999999999999999999\"}","{\"owner_id\":7}","{\"limit\":\"3\"}"))assertThrows(ConversationProblem.class,()->core.execute("read_conversation_history",json.readTree(bad),context(10)));
    }
    @Test void legacyToolDelegatePublishesImmutableWhitelistedResultWithExactCursor()throws Exception {
        Task task=run("technical tool proof");var call=execute(task,1,"technical-call","server_capabilities",json.createObjectNode());assertEquals("succeeded",call.status());
        var page=results.page(owner,conversation,0,1,null);var first=items(page).getFirst();assertEquals("available",first.get("result_access"));assertTrue(first.get("sequence") instanceof String);assertTrue(first.get("event_sequence") instanceof String);
        assertDoesNotThrow(()->Instant.parse((String)first.get("created_at")));
        var projection=(JsonNode)first.get("result");assertEquals("technical",projection.path("kind").asText());assertEquals(1,projection.path("data").path("protocol_version").asInt());
        String serialized=json.writeValueAsString(page);for(String forbidden:List.of("arguments_json","provider_call_id","lease_token","source_config_hash","token_hash"))assertFalse(serialized.contains(forbidden));
        var row=db.queryForMap("SELECT * FROM ai_tool_public_result WHERE tool_call_id=?",first.get("tool_call_id"));String immutable=(String)row.get("result_json");
        db.update("UPDATE agent_tool_call SET result_json=? WHERE id=?","{\"protocol_version\":999,\"password\":\"must-not-escape\"}",first.get("tool_call_id"));
        results.publishRun(db.queryForMap("SELECT * FROM ai_run WHERE id=?",task.runId()),db.queryForMap("SELECT * FROM agent_tool_call WHERE id=?",first.get("tool_call_id")),((Number)row.get("event_sequence")).longValue(),"succeeded");
        assertEquals(immutable,db.queryForObject("SELECT result_json FROM ai_tool_public_result WHERE tool_call_id=?",String.class,first.get("tool_call_id")));
        execute(task,2,"second-call","read_conversation_history",json.createObjectNode().put("limit",1));var newer=results.page(owner,conversation,0,1,null);assertEquals(true,newer.get("has_more"));
        var older=results.page(owner,conversation,Long.parseLong((String)newer.get("next_cursor")),1,null);assertEquals(first.get("tool_call_id"),items(older).getFirst().get("tool_call_id"));
    }
    @Test void exactSourceRenewalCanReadButRevocationAndReplacementTokenReturnOnlyRestrictedMetadata()throws Exception {
        var p=new Principal(owner,"a".repeat(64),owner,owner,"xiaozhi-context-test","subject",new Tenant("company","1"),new ExternalModels.Scope(List.of(),List.of()),Instant.now().plusSeconds(300));authenticated.set(p);
        var request=new MockHttpServletRequest();request.setAttribute(ExternalIdentityService.ATTRIBUTE,p);RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        Task task=run("source-bound technical tool");execute(task,1,"source-bound","server_capabilities",json.createObjectNode());assertEquals("available",items(results.page(owner,conversation,0,10,p)).getFirst().get("result_access"));
        var renewed=new Principal(p.tokenId(),p.tokenHash(),p.sourceId(),p.userId(),p.issuer(),p.subject(),p.tenant(),p.scope(),p.expiresAt().plusSeconds(300));authenticated.set(renewed);assertEquals("available",items(results.page(owner,conversation,0,10,renewed)).getFirst().get("result_access"));
        var replacement=new Principal(p.tokenId()+1,"b".repeat(64),p.sourceId(),p.userId(),p.issuer(),p.subject(),p.tenant(),p.scope(),renewed.expiresAt());authenticated.set(replacement);
        var restricted=items(results.page(owner,conversation,0,10,replacement)).getFirst();assertEquals("restricted",restricted.get("result_access"));assertNull(restricted.get("result"));assertEquals("succeeded",restricted.get("status"));
        authenticated.set(p);revoked.set(true);assertEquals("restricted",items(results.page(owner,conversation,0,10,p)).getFirst().get("result_access"));
    }
    @Test void releasedStaffSeesOnlyPublicResultsBeforeItsImmutableEventCutoff()throws Exception {
        Task task=run("staff cutoff technical tool");execute(task,1,"before-release","server_capabilities",json.createObjectNode());var first=items(results.page(owner,conversation,0,10,null)).getFirst();long cutoff=Long.parseLong((String)first.get("event_sequence"));
        long staff=ids.incrementAndGet();db.update("INSERT INTO sys_user VALUES(?,1,0,'historical-context-staff')",staff);db.update("INSERT INTO support_agent_profile(user_id,role) VALUES(?,'support_agent')",staff);String request=UUID.randomUUID().toString().replace("-","");
        db.update("INSERT INTO support_request(id,conversation_id,client_request_id,request_hash,status) VALUES(?,?,?,?, 'resolved')",request,conversation,"fixture-"+staff,"a".repeat(64));
        db.update("INSERT INTO support_assignment(id,request_id,conversation_id,agent_user_id,released_at,read_through_seq,read_through_event_seq) VALUES(?,?,?,?,CURRENT_TIMESTAMP(3),?,?)",UUID.randomUUID().toString().replace("-",""),request,conversation,staff,count("SELECT last_seq FROM ai_conversation WHERE id=?",conversation),cutoff);
        execute(task,2,"after-release","server_capabilities",json.createObjectNode());assertEquals(2,items(results.page(owner,conversation,0,10,null)).size());var history=items(results.page(staff,conversation,0,10,null));assertEquals(1,history.size());assertEquals(first.get("tool_call_id"),history.getFirst().get("tool_call_id"));
    }
}
