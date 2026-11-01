package xiaozhi.modules.externalidentity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import org.springframework.web.context.request.*;
import liquibase.*;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import xiaozhi.modules.app.AppCapabilitiesController;
import xiaozhi.modules.diagnostics.ServerDiagnosticsService;
import xiaozhi.modules.conversation.*;
import xiaozhi.modules.conversation.ConversationModels.*;
import xiaozhi.modules.externalidentity.ExternalModels.*;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.support.*;
import xiaozhi.modules.support.SupportModels.*;
import xiaozhi.modules.supportmedia.*;
import xiaozhi.modules.supportmedia.MediaModels.*;
import xiaozhi.modules.supportmedia.livekit.LiveKitAdapter;

/** Creates a new, uniquely named loopback schema; no database or rows are deleted. */
@EnabledIfEnvironmentVariable(named="EXTERNAL_APP_TEST_JDBC_URL",matches=".+")
class ExternalAppContractPersistenceTest {
    static JdbcTemplate db; static ExternalIdentityService identity; static ExternalMediaAuthority authority;
    static ExternalInvitationController inbox; static ConversationService conversations; static SupportService support;
    static MediaService media; static MediaMaintenance maintenance; static MockEnvironment env;
    static final Consent CONSENT=new Consent(true,false,false);
    static String key(){return UUID.randomUUID().toString().replace("-","");}
    @BeforeAll static void setup() throws Exception {
        // Shared fixture creates a fresh schema and refuses any existing schema; it never drops data.
        ExternalIdentityPersistenceTest.initialize(System.getenv("EXTERNAL_APP_TEST_JDBC_URL"));
        db=ExternalIdentityPersistenceTest.db; identity=ExternalIdentityPersistenceTest.service;
        var source=(DriverManagerDataSource)db.getDataSource();
        try(var connection=source.getConnection();var migration=new Liquibase("db/changelog/support-summary.changelog.yaml",new ClassLoaderResourceAccessor(),new JdbcConnection(connection))){migration.update(new Contexts());}
        var manager=new DataSourceTransactionManager(source); var dao=new ConversationDao(db);
        conversations=ExternalIdentityPersistenceTest.conversations;
        env=new MockEnvironment().withProperty("support-media.livekit.room-prefix","test-room-").withProperty("features.support-media-enabled","true").withProperty("features.external-realtime-media-enabled","true");
        ExternalIdentityPersistenceTest.settings.setMediaEnabled(true);
        authority=new ExternalMediaAuthority(db,ExternalIdentityPersistenceTest.settings,env,manager);
        inbox=new ExternalInvitationController(identity,authority,db);
        var store=new MediaStore(db,manager);var policy=new MediaPolicy(store,dao);policy.setExternal(authority);
        var livekit=new LiveKitAdapter(new LiveKitAdapter.Config("http://fixture.invalid","ws://fixture.invalid","fixture-key","fixture-secret-at-least-32-characters","test-room-"),ExternalIdentityPersistenceTest.JSON,policy,Clock.systemUTC(),
            (uri,authorization,body,timeout)->new LiveKitAdapter.RpcResponse(404,"{\"code\":\"not_found\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        media=new MediaService(store,policy,livekit,dao,ExternalIdentityPersistenceTest.JSON,env);maintenance=new MediaMaintenance(media,store,policy,livekit);
        support=new SupportService(db,ExternalIdentityPersistenceTest.JSON,manager,conversations);support.setMediaHooksForTests(media);
        db.update("INSERT INTO sys_user(id,username,password,super_admin,status,create_date) VALUES(71,'fixture-staff','fixture',0,1,CURRENT_TIMESTAMP(3))");
        db.update("INSERT INTO support_agent_profile(user_id,role,capacity_text,accepting,presence_expires_at) VALUES(71,'expert',20,1,DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 1 HOUR))");
    }
    static Principal principal(Exchanged value){return identity.authenticate(value.token());}
    static MockHttpServletRequest request(Principal p){var req=new MockHttpServletRequest();req.setAttribute(ExternalIdentityService.ATTRIBUTE,p);return req;}
    static void caller(Principal p){RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request(p)));}
    static List<Invitation> list(Principal p,long after,int limit){return inbox.list(after,limit,request(p),new MockHttpServletResponse()).getData().items();}
    @AfterEach void reset(){RequestContextHolder.resetRequestAttributes();}
    @Test void identityScopedPreviewBindingAndOwnerEndPreserveHistory() throws Exception {
        String subject="contract-"+key(); var a=principal(ExternalIdentityPersistenceTest.login(subject,"A","1"));
        var b=principal(ExternalIdentityPersistenceTest.login(subject,"B","1"));
        var otherToken=principal(ExternalIdentityPersistenceTest.login(subject,"A","1"));
        var otherTenant=principal(ExternalIdentityPersistenceTest.login(subject,"C","2"));
        var otherUser=principal(ExternalIdentityPersistenceTest.login(subject+"-other","A","1"));
        byte[] secondKey=new byte[32];Arrays.fill(secondKey,(byte)43);
        String secondId="xiaozhi-second-key", secondIssuer="xiaozhi-second-source";
        String keys=ExternalIdentityPersistenceTest.keys();
        ExternalIdentityPersistenceTest.settings.setKeysJson(keys.substring(0,keys.length()-1)+",\""+secondId+"\":{\"issuer\":\""+secondIssuer+"\",\"secret_b64\":\""+Base64.getEncoder().encodeToString(secondKey)+"\"}}");
        byte[] raw=ExternalIdentityPersistenceTest.body(subject,"A","1");String timestamp=Long.toString(Instant.now().getEpochSecond()),nonce=key();
        var signature=identity.verify(ExternalIdentityService.ISSUE,raw,Map.of("x-external-key-id",List.of(secondId),"x-external-timestamp",List.of(timestamp),"x-external-nonce",List.of(nonce),"x-external-signature",List.of(ExternalIdentityService.signature(secondKey,ExternalIdentityService.ISSUE,timestamp,nonce,raw))));
        var otherIssuer=principal(identity.exchange(identity.issue(signature,raw).ticket()));
        long owner=a.userId();assertEquals(owner,b.userId());assertNotEquals(a.tokenId(),otherToken.tokenId());
        String conversation=conversations.create(owner,new CreateConversation("contract fixture",null)).id();
        conversations.send(owner,conversation,new SendMessage(key(),List.of(new MessagePart("text","preserve contract history",null,null,null,null,null,null))));
        var req=support.handoff(owner,conversation,new Handoff(key(),"text",null));support.claim(71L,req.requestId());
        db.update("INSERT INTO support_summary_revision(conversation_id,version,origin,source_through_seq,source_truncated,fields_json,released_assignment_ids) VALUES(?,1,'deterministic_excerpt',1,0,'{}','[]')",conversation);
        var session=media.create(71L,new Create(req.requestId(),key(),"audio",CONSENT));
        assertEquals(1,list(a,0,50).size());assertEquals(1,list(b,0,50).size());
        assertTrue(list(otherTenant,0,50).isEmpty());assertTrue(list(otherUser,0,50).isEmpty());assertTrue(list(otherIssuer,0,50).isEmpty());
        assertNull(db.queryForObject("SELECT external_source_id FROM support_media_session WHERE id=?",Long.class,session.id()));
        caller(a);var accepted=media.accept(owner,session.id(),new Accept(session.controlEpoch(),CONSENT));assertEquals("connecting",accepted.status());
        assertEquals(a.sourceId(),db.queryForObject("SELECT external_source_id FROM support_media_session WHERE id=?",Long.class,session.id()));
        assertEquals(a.tokenId(),db.queryForObject("SELECT external_token_id FROM support_media_session WHERE id=?",Long.class,session.id()));
        db.update("INSERT INTO support_media_room(id,session_id,generation,room_name) VALUES(987654322,?,2,?)",session.id(),"test-room-"+session.id()+"-g2-"+key());
        db.update("INSERT INTO support_media_transfer(id,session_id,requester_user_id,source_assignment_id,source_generation,target_generation,target_agent_user_id,client_transfer_id,request_hash,control_epoch,expires_at) VALUES(987654321,?,71,?,1,2,71,'fixture-transfer',?,1,DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 1 HOUR))",session.id(),accepted.assignmentId(),"f".repeat(64));
        assertEquals("transfer",list(a,0,50).getFirst().type());
        assertTrue(list(b,0,50).isEmpty());assertTrue(list(otherToken,0,50).isEmpty());
        caller(b);assertThrows(ExternalProblem.class,()->media.get(owner,session.id()));
        caller(otherToken);assertThrows(ExternalProblem.class,()->media.get(owner,session.id()));
        var diagnosticEnv=new MockEnvironment().withProperty("features.conversation-enabled","true").withProperty("features.support-handoff-enabled","true")
            .withProperty("features.support-media-enabled","true").withProperty("features.ai-text-enabled","true").withProperty("features.realtime-ai-enabled","true");
        var diagnostics=new ServerDiagnosticsService(db,diagnosticEnv).snapshot();assertEquals("limited",diagnostics.get("state"));
        var checks=(Map<?,?>)diagnostics.get("checks");assertEquals("ok",((Map<?,?>)checks.get("ai_runs")).get("state"));
        assertTrue(((Number)((Map<?,?>)checks.get("support_media_operations")).get("active")).longValue()>0);
        diagnosticEnv.withProperty("features.agent-tools-enabled","true");
        var missingTools=new ServerDiagnosticsService(db,diagnosticEnv).snapshot();assertEquals("degraded",missingTools.get("state"));
        assertEquals(Map.of("state","unknown"),((Map<?,?>)missingTools.get("checks")).get("tool_failures_60m"));
        db.execute("CREATE TABLE agent_tool_audit (state VARCHAR(20),error_code VARCHAR(64),created_at DATETIME(3))");
        db.update("INSERT INTO agent_tool_audit VALUES('failed','TOOL_EXECUTION_FAILED',CURRENT_TIMESTAMP(3)),('discarded','PRIVATE_USER_TEXT',CURRENT_TIMESTAMP(3)),('failed','TOOL_EXECUTION_FAILED',DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 61 MINUTE))");
        var grouped=new ServerDiagnosticsService(db,diagnosticEnv).snapshot();assertEquals("limited",grouped.get("state"));
        var failures=(Map<?,?>)((Map<?,?>)grouped.get("checks")).get("tool_failures_60m");
        assertEquals(1L,failures.get("failed"));assertEquals(1L,failures.get("discarded"));
        assertTrue(failures.toString().contains("OTHER"));assertFalse(failures.toString().contains("PRIVATE_USER_TEXT"));
        caller(a);assertEquals(404,assertThrows(ConversationProblem.class,()->support.endByOwner(otherUser.userId(),req.requestId(),null)).status());
        assertEquals("resolved",support.endByOwner(owner,req.requestId(),new EndRequest("done")).status());
        long events=db.queryForObject("SELECT COUNT(*) FROM ai_conversation_event WHERE conversation_id=?",Long.class,conversation);
        assertEquals("resolved",support.endByOwner(owner,req.requestId(),null).status());
        assertEquals(events,db.queryForObject("SELECT COUNT(*) FROM ai_conversation_event WHERE conversation_id=?",Long.class,conversation));
        assertEquals("RESOLVED",conversations.get(owner,conversation).state());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ai_message WHERE conversation_id=?",Long.class,conversation));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM support_summary_revision WHERE conversation_id=?",Long.class,conversation));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM support_assignment WHERE request_id=? AND released_at IS NOT NULL",Long.class,req.requestId()));
        assertEquals("ending",db.queryForObject("SELECT status FROM support_media_session WHERE id=?",String.class,session.id()));
        assertTrue(db.queryForObject("SELECT COUNT(*) FROM support_media_outbox WHERE session_id=? AND kind='delete_room'",Long.class,session.id())>0);
        maintenance.processOutbox();
        assertEquals("ended",db.queryForObject("SELECT status FROM support_media_session WHERE id=?",String.class,session.id()));
    }
    @Test void capabilitiesDefaultsAndPrerequisites() {
        try(var security=mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUserId).thenReturn(500L);
            var defaults=new AppCapabilitiesController(new MockEnvironment()).capabilities().getData();
            @SuppressWarnings("unchecked") var flags=(Map<String,Boolean>)defaults.get("features");
            assertTrue(flags.values().stream().noneMatch(Boolean::booleanValue));
            assertFalse(flags.get("agent_text_tools")); assertFalse(flags.get("external_token_renewal"));
            var enabled=new MockEnvironment().withProperty("features.support-handoff-enabled","true").withProperty("features.support-media-enabled","true");
            @SuppressWarnings("unchecked") var gated=(Map<String,Boolean>)new AppCapabilitiesController(enabled).capabilities().getData().get("features");assertFalse(gated.get("support_media"));
            enabled.withProperty("features.conversation-enabled","true");
            @SuppressWarnings("unchecked") var active=(Map<String,Boolean>)new AppCapabilitiesController(enabled).capabilities().getData().get("features");assertTrue(active.get("support_media"));
            enabled.withProperty("features.ai-text-enabled","true").withProperty("features.agent-tools-enabled","true")
                .withProperty("features.external-identity-enabled","true").withProperty("external-identity.renewal-enabled","true");
            @SuppressWarnings("unchecked") var expanded=(Map<String,Boolean>)new AppCapabilitiesController(enabled).capabilities().getData().get("features");
            assertTrue(expanded.get("agent_text_tools")); assertTrue(expanded.get("external_token_renewal"));
            String json=defaults.toString();assertFalse(json.contains("fixture.invalid"));assertFalse(json.contains("secret"));
        }
    }
}
