package xiaozhi.modules.externalidentity;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import liquibase.*;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import xiaozhi.modules.externalidentity.ExternalModels.*;
import xiaozhi.modules.conversation.*;
import xiaozhi.modules.conversation.ConversationModels.*;
import xiaozhi.modules.attachment.*;
import xiaozhi.modules.attachment.AttachmentModels.*;

/** Refuses pre-existing/nonempty DBs; no row/table/schema cleanup, no external service calls. */
@EnabledIfEnvironmentVariable(named="EXTERNAL_TEST_JDBC_URL",matches=".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ExternalIdentityPersistenceTest {
    static JdbcTemplate db;static ExternalIdentityService service;static ExternalSettings settings;
    static ConversationService conversations;static AttachmentService media;static ExternalMediaController mediaController;
    static final ObjectMapper JSON=JsonMapper.builder().addModule(new JavaTimeModule()).build();
    static final byte[] KEY=ExternalProtocolTest.KEY;
    static final String KEY_ID=ExternalProtocolTest.KEY_ID,ISSUER=ExternalProtocolTest.ISSUER;
    static String keys(){return "{\""+KEY_ID+"\":{\"issuer\":\""+ISSUER+"\",\"secret_b64\":\""+Base64.getEncoder().encodeToString(KEY)+"\"}}";}
    @BeforeAll static void initialize()throws Exception {
        String url=System.getenv("EXTERNAL_TEST_JDBC_URL");
        if(!url.matches("jdbc:mysql://127\\.0\\.0\\.1:34776/xiaozhi_external_test_[a-z0-9_]+(\\?.*)?"))throw new IllegalArgumentException("Requires new loopback xiaozhi_external_test_* schema on34776");
        createFreshSchema(url);
        var source=new DriverManagerDataSource(url,"root",System.getenv("EXTERNAL_TEST_DB_PASSWORD"));db=new JdbcTemplate(source);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Long.class),"Refuse nonempty test schema; nothing is cleaned");
        db.execute("CREATE TABLE sys_user(id BIGINT PRIMARY KEY,username VARCHAR(50) UNIQUE,password VARCHAR(100),super_admin TINYINT,status TINYINT,create_date DATETIME(3))");
        db.execute("CREATE TABLE sys_user_token(id BIGINT PRIMARY KEY,user_id BIGINT UNIQUE,token VARCHAR(100),expire_date DATETIME(3))");
        db.update("INSERT INTO sys_user VALUES(500,'local-fixture','isolated-password-fixture',0,1,CURRENT_TIMESTAMP(3))");
        db.update("INSERT INTO sys_user_token VALUES(1,500,'local-password-session',DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 12 HOUR))");
        db.execute("CREATE TABLE legacy_external_sentinel(id BIGINT PRIMARY KEY,value VARCHAR(80))");db.update("INSERT INTO legacy_external_sentinel VALUES(1,'preserve-existing-business-evidence')");
        db.execute("CREATE TABLE ai_agent(id VARCHAR(32) PRIMARY KEY,user_id BIGINT)");
        for(String name:List.of("conversation","support","attachments","external-identity"))try(var connection=source.getConnection();var migration=new Liquibase("db/changelog/"+name+".changelog.yaml",new ClassLoaderResourceAccessor(),new JdbcConnection(connection))){migration.update(new Contexts());migration.update(new Contexts());}
        assertEquals(4,db.queryForObject("SELECT COUNT(*) FROM DATABASECHANGELOG",Long.class));
        settings=ExternalProtocolTest.settings();var manager=new DataSourceTransactionManager(source);service=new ExternalIdentityService(db,JSON,manager,settings);
        var dao=new ConversationDao(db);conversations=new ConversationService(dao,JSON,manager);
        media=new AttachmentService(db,dao,JSON,manager,new MockEnvironment().withProperty("media.ticket-key","b6-isolated-media-signing-key-32-characters"),new PrivateObjectStore.Filesystem(Files.createTempDirectory("b6-private-evidence-")));
        conversations.setAttachments(media);mediaController=new ExternalMediaController(service,media,db);
    }
    private static void createFreshSchema(String url) {
        var match=java.util.regex.Pattern.compile("jdbc:mysql://127\\.0\\.0\\.1:34776/(xiaozhi_external_test_[a-z0-9_]+)(\\?.*)?").matcher(url);
        if(!match.matches())throw new IllegalArgumentException("Requires a new isolated external test schema");
        String name=match.group(1);
        var admin=new JdbcTemplate(new DriverManagerDataSource("jdbc:mysql://127.0.0.1:34776/?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true","root",System.getenv("EXTERNAL_TEST_DB_PASSWORD")));
        if(admin.queryForObject("SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=?",Long.class,name)!=0)throw new IllegalArgumentException("Refusing existing external test schema");
        admin.execute("CREATE DATABASE `"+name+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
    }
    static String id(){return UUID.randomUUID().toString();}
    static byte[] body(String subject,String source,String tenant)throws Exception {
        var value=JSON.createObjectNode().put("external_subject",subject).put("source_session_id",source)
            .put("source_expires_at",Long.toString(Instant.now().getEpochSecond()+3600)).put("display_name","同名测试用户");
        value.putObject("tenant").put("type","company").put("id",tenant);
        var scope=value.putObject("scope");scope.putArray("project_ids").add("site-1");scope.putArray("device_ids").add("device-1");
        return JSON.writeValueAsBytes(value);
    }
    static Verified signed(String path,byte[] raw,String nonce){String timestamp=Long.toString(Instant.now().getEpochSecond());return service.verify(path,raw,Map.of(
        "x-external-key-id",List.of(KEY_ID),"x-external-timestamp",List.of(timestamp),"x-external-nonce",List.of(nonce),
        "x-external-signature",List.of(ExternalIdentityService.signature(KEY,path,timestamp,nonce,raw))));}
    static Issued issue(String subject,String source,String tenant)throws Exception {byte[] raw=body(subject,source,tenant);return service.issue(signed(ExternalIdentityService.ISSUE,raw,id().replace("-","")),raw);}
    static Exchanged login(String subject,String source,String tenant)throws Exception{return service.exchange(issue(subject,source,tenant).ticket());}
    static void revoke(String subject,String source)throws Exception {byte[] raw=JSON.writeValueAsBytes(Map.of("external_subject",subject,"source_session_id",source));service.revoke(signed(ExternalIdentityService.REVOKE,raw,id().replace("-","")),raw);}
    @Test @Order(1) void concurrentSameNonceAndTicketHaveExactlyOneSuccess()throws Exception {
        byte[] raw=body("concurrent-"+id(),"source-A","1");Verified signed=signed(ExternalIdentityService.ISSUE,raw,id().replace("-",""));
        try(var pool=Executors.newFixedThreadPool(8)) {
            List<Callable<Object>> calls=new ArrayList<>();for(int i=0;i<8;i++)calls.add(()->{try{return service.issue(signed,raw);}catch(ExternalProblem error){return error.getMessage();}});
            var results=pool.invokeAll(calls).stream().map(f->{try{return f.get();}catch(Exception ex){throw new AssertionError(ex);}}).toList();
            assertEquals(1,results.stream().filter(Issued.class::isInstance).count());assertEquals(7,results.stream().filter("NONCE_REPLAY"::equals).count());
            Issued ticket=(Issued)results.stream().filter(Issued.class::isInstance).findFirst().orElseThrow();
            List<Callable<Object>> exchanges=new ArrayList<>();for(int i=0;i<8;i++)exchanges.add(()->{try{return service.exchange(ticket.ticket());}catch(ExternalProblem error){return error.getMessage();}});
            var exchanged=pool.invokeAll(exchanges).stream().map(f->{try{return f.get();}catch(Exception ex){throw new AssertionError(ex);}}).toList();
            assertEquals(1,exchanged.stream().filter(Exchanged.class::isInstance).count());assertEquals(7,exchanged.stream().filter("TICKET_USED"::equals).count());
        }
    }
    @Test @Order(2) void independentSessionsRevokeAWithoutBLocalOrCrossTenantSharing()throws Exception {
        String subject="multi-"+id();var a=login(subject,"A","1");var b=login(subject,"B","1");var otherTenant=login(subject,"C","2");
        assertEquals(a.userId(),b.userId());assertNotEquals(a.token(),b.token());assertNotEquals(a.userId(),otherTenant.userId());
        var customer=JSON.readTree(body(subject,"A","1"));((com.fasterxml.jackson.databind.node.ObjectNode)customer.get("tenant")).put("type","customer");
        byte[] customerRaw=JSON.writeValueAsBytes(customer);var customerToken=service.exchange(service.issue(signed(ExternalIdentityService.ISSUE,customerRaw,id().replace("-","")),customerRaw).ticket());
        assertNotEquals(a.userId(),customerToken.userId(),"company:1 and customer:1 are different namespaces");
        String conversation=conversations.create(Long.parseLong(a.userId()),new CreateConversation("tenant-one",null)).id();
        assertEquals(conversation,conversations.get(Long.parseLong(b.userId()),conversation).id());
        assertEquals(404,assertThrows(ConversationProblem.class,()->conversations.get(Long.parseLong(otherTenant.userId()),conversation)).status());
        conversations.send(Long.parseLong(a.userId()),conversation,new SendMessage(id(),List.of(new MessagePart("text","own message",null,null,null,null,null,null))));
        var pending=issue(subject,"A","1");revoke(subject,"A");
        assertEquals(401,assertThrows(ExternalProblem.class,()->service.authenticate(a.token())).status());assertThrows(ExternalProblem.class,()->service.authenticate(customerToken.token()));assertThrows(ExternalProblem.class,()->service.exchange(pending.ticket()));
        assertEquals(Long.parseLong(b.userId()),service.authenticate(b.token()).userId());assertNotNull(service.authenticate(otherTenant.token()));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM sys_user_token WHERE token='local-password-session' AND expire_date>CURRENT_TIMESTAMP(3)",Long.class));
        assertEquals("preserve-existing-business-evidence",db.queryForObject("SELECT value FROM legacy_external_sentinel WHERE id=1",String.class));
    }
    @Test @Order(3) void revokeBeforeFirstIssueLeavesOnlyTombstoneAndLateIssueCannotRestore()throws Exception {
        String subject="late-"+id();long users=db.queryForObject("SELECT COUNT(*) FROM sys_user",Long.class);revoke(subject,"A");revoke(subject,"A");
        assertEquals("SOURCE_SESSION_REVOKED",assertThrows(ExternalProblem.class,()->issue(subject,"A","1")).getMessage());
        assertEquals(users,db.queryForObject("SELECT COUNT(*) FROM sys_user",Long.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM external_source_session WHERE external_subject=? AND revoked_at IS NOT NULL",Long.class,subject));
    }
    @Test @Order(4) void revokeExchangeRaceAlwaysEndsWithNoUsableSourceToken()throws Exception {
        String subject="race-"+id();Issued ticket=issue(subject,"A","1");
        try(var pool=Executors.newFixedThreadPool(2)) {
            Future<Object> exchanged=pool.submit(()->{try{return service.exchange(ticket.ticket());}catch(ExternalProblem failure){return failure;}});
            Future<?> revoked=pool.submit(()->{try{revoke(subject,"A");}catch(Exception ex){throw new RuntimeException(ex);}});
            Object result=exchanged.get();revoked.get();
            if(result instanceof Exchanged success)assertThrows(ExternalProblem.class,()->service.authenticate(success.token()));
            else assertEquals(401,((ExternalProblem)result).status());
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM external_access_token t JOIN external_source_session s ON s.id=t.source_id WHERE s.external_subject=? AND t.revoked_at IS NULL",Long.class,subject));
        }
    }
    @Test @Order(5) void distinctSubjectSameDisplayNameNeverMergesAndSameSubjectDifferentIssuerIsSeparate()throws Exception {
        String subject="namespace-"+id();var first=login(subject,"A","1");var sameName=login(subject+"-other","A","1");assertNotEquals(first.userId(),sameName.userId());
        byte[] second=new byte[32];Arrays.fill(second,(byte)43);String keyId="xiaozhi-second-key",issuer="xiaozhi-second-source";
        String expanded=keys().substring(0,keys().length()-1)+",\""+keyId+"\":{\"issuer\":\""+issuer+"\",\"secret_b64\":\""+Base64.getEncoder().encodeToString(second)+"\"}}";
        try {
            settings.setKeysJson(expanded);byte[] raw=body(subject,"A","1");String timestamp=Long.toString(Instant.now().getEpochSecond()),nonce=id().replace("-","");
            Verified verified=service.verify(ExternalIdentityService.ISSUE,raw,Map.of("x-external-key-id",List.of(keyId),"x-external-timestamp",List.of(timestamp),"x-external-nonce",List.of(nonce),"x-external-signature",List.of(ExternalIdentityService.signature(second,ExternalIdentityService.ISSUE,timestamp,nonce,raw))));
            var other=service.exchange(service.issue(verified,raw).ticket());assertNotEquals(first.userId(),other.userId());
        } finally {settings.setKeysJson(keys());}
    }
    @Test @Order(6) void accountIssuerSourceExpiryAndTokenExpiryAreRecheckedAtUse()throws Exception {
        var token=login("dynamic-"+id(),"A","1");var principal=service.authenticate(token.token());
        db.update("UPDATE sys_user SET status=0 WHERE id=?",principal.userId());assertEquals("ACCOUNT_DISABLED",assertThrows(ExternalProblem.class,()->service.authenticate(token.token())).getMessage());
        var expired=login("expired-"+id(),"A","1");db.update("UPDATE external_source_session SET expires_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",service.authenticate(expired.token()).sourceId());
        assertEquals("SOURCE_SESSION_EXPIRED",assertThrows(ExternalProblem.class,()->service.authenticate(expired.token())).getMessage());
        var bearer=login("bearer-expiry-"+id(),"A","1");db.update("UPDATE external_access_token SET expires_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE token_hash=?",ExternalIdentityService.hash(bearer.token()));assertThrows(ExternalProblem.class,()->service.authenticate(bearer.token()));
        var issuer=login("issuer-"+id(),"A","1");try{settings.setEnabled(false);assertEquals(503,assertThrows(ExternalProblem.class,()->service.authenticate(issuer.token())).status());}finally{settings.setEnabled(true);}
        var privileged=login("privilege-"+id(),"A","1");db.update("UPDATE sys_user SET super_admin=1 WHERE id=?",Long.parseLong(privileged.userId()));assertEquals(403,assertThrows(ExternalProblem.class,()->service.authenticate(privileged.token())).status());
    }
    @Test @Order(7) void sourceGuardAndMediaTicketAreRevokedWithoutAffectingB()throws Exception {
        String subject="media-"+id();var a=login(subject,"A","1");var b=login(subject,"B","1");var actor=service.authenticate(a.token());
        String conversation=conversations.create(actor.userId(),new CreateConversation("private media",null)).id();
        ByteArrayOutputStream output=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(10,10,BufferedImage.TYPE_INT_RGB),"jpg",output);byte[] jpeg=output.toByteArray();
        var upload=media.init(actor.userId(),new Init(conversation,id(),"evidence.jpg","image/jpeg",(long)jpeg.length,ExternalIdentityService.hash(jpeg)),"");
        media.upload(actor.userId(),upload.id(),"image/jpeg",jpeg.length,new ByteArrayInputStream(jpeg));media.completeUpload(actor.userId(),upload.id());
        Task inspect=media.claim(new Claim("isolated-b6-media"));for(String variant:List.of("display","thumbnail"))media.artifact(inspect.jobId(),variant,inspect.leaseToken(),"image/jpeg",new ByteArrayInputStream(jpeg));
        media.completeJob(inspect.jobId(),new Complete(inspect.leaseToken(),Map.of("mime_type","image/jpeg","width",10,"height",10)));
        var request=new MockHttpServletRequest();request.setAttribute(ExternalIdentityService.ATTRIBUTE,actor);
        Runnable guard=service.longLivedGuard(request);guard.run();
        MediaAccess capability=mediaController.access(upload.id(),"display",request,new MockHttpServletResponse()).getData();
        String mediaTicket=java.net.URI.create(capability.url()).getQuery().split("=",2)[1];
        var contentRequest=new MockHttpServletRequest();contentRequest.addParameter("ticket",mediaTicket);var response=new MockHttpServletResponse();
        mediaController.content(upload.id(),mediaTicket,contentRequest,response);assertArrayEquals(jpeg,response.getContentAsByteArray());
        revoke(subject,"A");assertThrows(ExternalProblem.class,guard::run);
        assertThrows(ExternalProblem.class,()->mediaController.content(upload.id(),mediaTicket,contentRequest,new MockHttpServletResponse()));assertNotNull(service.authenticate(b.token()));
    }
    @Test @Order(8) void logoutOnlyRevokesSelectedBearerAndAllOriginalRowsRemain()throws Exception {
        String subject="logout-"+id();var first=login(subject,"A","1");var second=login(subject,"A","1");
        service.logout(service.authenticate(first.token()));assertThrows(ExternalProblem.class,()->service.authenticate(first.token()));assertNotNull(service.authenticate(second.token()));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM external_access_token WHERE token_hash=? AND revoked_at IS NOT NULL",Long.class,ExternalIdentityService.hash(first.token())));
        assertTrue(db.queryForObject("SELECT COUNT(*) FROM external_signature_nonce",Long.class)>0);assertTrue(db.queryForObject("SELECT COUNT(*) FROM external_launch_ticket WHERE consumed_at IS NOT NULL",Long.class)>0);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM legacy_external_sentinel",Long.class));
    }
    @Test @Order(9) void matchingShanghaiJdbcAndPlusEightSessionKeepRealTtlAndRevocation()throws Exception {
        String url=System.getenv("EXTERNAL_TEST_JDBC_URL").replace("serverTimezone=UTC","serverTimezone=Asia/Shanghai");
        if(!url.contains("serverTimezone="))url+=(url.contains("?")?"&":"?")+"serverTimezone=Asia/Shanghai";
        var source=new DriverManagerDataSource(url,"root",System.getenv("EXTERNAL_TEST_DB_PASSWORD")) {
            @Override public java.sql.Connection getConnection()throws java.sql.SQLException {
                var connection=super.getConnection();try(var statement=connection.createStatement()){statement.execute("SET time_zone = '+08:00'");}return connection;
            }
        };
        JdbcTemplate cstDb=new JdbcTemplate(source);assertEquals("+08:00",cstDb.queryForObject("SELECT @@session.time_zone",String.class));
        class MutableClock extends Clock {
            Instant value=Instant.now();public ZoneId getZone(){return ZoneOffset.UTC;}
            public Clock withZone(ZoneId zone){return this;}public Instant instant(){return value;}
        }
        MutableClock clock=new MutableClock();Instant start=clock.instant();
        ExternalIdentityService cst=new ExternalIdentityService(cstDb,JSON,new DataSourceTransactionManager(source),settings,clock);
        byte[] raw=body("shanghai-ticket-"+id(),"A","1");Issued ticket=cst.issue(signed(ExternalIdentityService.ISSUE,raw,id().replace("-","")),raw);
        var expiry=cstDb.query("SELECT expires_at FROM external_launch_ticket WHERE token_hash=?",new xiaozhi.common.jdbc.TimestampColumnMapRowMapper(),ExternalIdentityService.hash(ticket.ticket())).getFirst().get("expires_at");
        assertInstanceOf(java.sql.Timestamp.class,expiry);
        long seconds=Duration.between(start,((java.sql.Timestamp)expiry).toInstant()).getSeconds();assertTrue(seconds>=59 && seconds<=60,"Ticket is ~60 seconds, never eight hours longer");
        clock.value=start.plusSeconds(61);assertEquals(410,assertThrows(ExternalProblem.class,()->cst.exchange(ticket.ticket())).status());
        clock.value=start;raw=body("shanghai-bearer-"+id(),"A","1");var bearer=cst.exchange(cst.issue(signed(ExternalIdentityService.ISSUE,raw,id().replace("-","")),raw).ticket());
        assertNotNull(cst.authenticate(bearer.token()));clock.value=start.plusSeconds(301);assertEquals(401,assertThrows(ExternalProblem.class,()->cst.authenticate(bearer.token())).status());
        clock.value=start;String subject="shanghai-revoke-"+id();raw=body(subject,"A","1");var revoked=cst.exchange(cst.issue(signed(ExternalIdentityService.ISSUE,raw,id().replace("-","")),raw).ticket());
        byte[] revoke=JSON.writeValueAsBytes(Map.of("external_subject",subject,"source_session_id","A"));cst.revoke(signed(ExternalIdentityService.REVOKE,revoke,id().replace("-","")),revoke);
        assertEquals(401,assertThrows(ExternalProblem.class,()->cst.authenticate(revoked.token())).status());
    }

    @Test @Order(10) void mockHttpRawSignatureThroughActualXssThenSingleExchangeAndStrictBody()throws Exception {
        var properties=new xiaozhi.common.xss.XssProperties();properties.setEnabled(true);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new ExternalIdentityController(service,JSON))
            .setControllerAdvice(new ExternalExceptionHandler())
            .addFilters(new ExternalBoundaryFilter(()->service),new xiaozhi.common.xss.XssFilter(properties,new org.springframework.util.AntPathMatcher())).build();
        byte[] raw=body("http-"+id(),"A","1");var node=JSON.readTree(raw);((com.fasterxml.jackson.databind.node.ObjectNode)node).put("display_name","A < B & C > D");
        raw=("  "+JSON.writeValueAsString(node)+" \n").getBytes(StandardCharsets.UTF_8);
        String timestamp=Long.toString(Instant.now().getEpochSecond()),nonce=id().replace("-","");
        var request=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(ExternalIdentityService.ISSUE)
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(raw)
            .header("X-External-Key-Id",KEY_ID).header("X-External-Timestamp",timestamp).header("X-External-Nonce",nonce)
            .header("X-External-Signature",ExternalIdentityService.signature(KEY,ExternalIdentityService.ISSUE,timestamp,nonce,raw));
        var issued=mvc.perform(request).andReturn().getResponse();assertEquals(200,issued.getStatus());String ticket=JSON.readTree(issued.getContentAsString()).path("data").path("ticket").asText();assertTrue(ticket.startsWith("xzi_ticket_"));
        var exchange=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/external/identity/exchange")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(JSON.writeValueAsBytes(Map.of("ticket",ticket)));
        var consumed=mvc.perform(exchange).andReturn().getResponse();assertEquals(200,consumed.getStatus());
        String token=JSON.readTree(consumed.getContentAsString()).path("data").path("token").asText();assertNotNull(service.authenticate(token));
        assertEquals(409,mvc.perform(exchange).andReturn().getResponse().getStatus());
        var forged=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/external/identity/exchange")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(JSON.writeValueAsBytes(Map.of("ticket",ticket,"tenant_id","forged")));
        assertEquals(400,mvc.perform(forged).andReturn().getResponse().getStatus());
        String fullPath="/xiaozhi"+ExternalIdentityService.ISSUE,contextNonce=id().replace("-","");
        var contextRequest=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(fullPath).contextPath("/xiaozhi")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(raw)
            .header("X-External-Key-Id",KEY_ID).header("X-External-Timestamp",timestamp).header("X-External-Nonce",contextNonce)
            .header("X-External-Signature",ExternalIdentityService.signature(KEY,fullPath,timestamp,contextNonce,raw));
        assertEquals(200,mvc.perform(contextRequest).andReturn().getResponse().getStatus());
    }

    @Test @Order(11) void concurrentDifferentSourcesCreateOnlyOneTenantPrincipalAndNoOrphanUser()throws Exception {
        String subject="first-binding-"+id();long before=db.queryForObject("SELECT COUNT(*) FROM sys_user",Long.class);
        try(var pool=Executors.newFixedThreadPool(8)) {
            List<Callable<Exchanged>> calls=new ArrayList<>();
            for(int index=0;index<8;index++){int source=index;calls.add(()->login(subject,"source-"+source,"1"));}
            var results=pool.invokeAll(calls).stream().map(future->{try{return future.get();}catch(Exception error){throw new AssertionError(error);}}).toList();
            assertEquals(1,results.stream().map(Exchanged::userId).distinct().count());assertEquals(8,results.stream().map(Exchanged::token).distinct().count());
        }
        assertEquals(before+1,db.queryForObject("SELECT COUNT(*) FROM sys_user",Long.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM external_tenant_principal p JOIN external_identity i ON i.id=p.identity_id WHERE i.external_subject=?",Long.class,subject));
    }

    @Test @Order(12) void existingSchemaIsRefusedBeforeAnyFixtureMutation() {
        long before=db.queryForObject("SELECT COUNT(*) FROM external_access_token",Long.class);
        assertThrows(IllegalArgumentException.class,()->createFreshSchema(System.getenv("EXTERNAL_TEST_JDBC_URL")));
        assertEquals(before,db.queryForObject("SELECT COUNT(*) FROM external_access_token",Long.class));
        assertEquals("preserve-existing-business-evidence",db.queryForObject("SELECT value FROM legacy_external_sentinel WHERE id=1",String.class));
    }

}
