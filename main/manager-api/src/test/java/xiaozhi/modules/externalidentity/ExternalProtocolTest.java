package xiaozhi.modules.externalidentity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.mock.web.*;
import org.springframework.dao.TransientDataAccessResourceException;
import com.fasterxml.jackson.databind.*;
import xiaozhi.modules.externalidentity.ExternalModels.*;
import xiaozhi.modules.ai.*;
import xiaozhi.modules.conversation.*;
import xiaozhi.modules.conversation.ConversationModels.*;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.security.service.impl.ShiroServiceImpl;
import xiaozhi.modules.security.dao.SysUserTokenDao;
import xiaozhi.modules.sys.dao.SysUserDao;
import xiaozhi.modules.security.entity.SysUserTokenEntity;

class ExternalProtocolTest {
    static final ObjectMapper JSON=new ObjectMapper();static final byte[] KEY=new byte[32];
    static {Arrays.fill(KEY,(byte)42);}
    static final String KEY_ID="xiaozhi-test-key",ISSUER="xiaozhi-daleng-test";
    static ExternalSettings settings(){var settings=new ExternalSettings();settings.setEnabled(true);settings.setKeysJson("{\""+KEY_ID+"\":{\"issuer\":\""+ISSUER+"\",\"secret_b64\":\""+Base64.getEncoder().encodeToString(KEY)+"\"}}");return settings;}
    static ExternalIdentityService service(ExternalSettings settings){return new ExternalIdentityService(mock(JdbcTemplate.class),JSON,mock(PlatformTransactionManager.class),settings,Clock.fixed(Instant.ofEpochSecond(1700000000),ZoneOffset.UTC));}
    static Map<String,List<String>> headers(String path,byte[] raw,String nonce){String time="1700000000";return new HashMap<>(Map.of("x-external-key-id",List.of(KEY_ID),"x-external-timestamp",List.of(time),"x-external-nonce",List.of(nonce),"x-external-signature",List.of(ExternalIdentityService.signature(KEY,path,time,nonce,raw))));}
    @Test void newFrozenVectorMatchesAndDeploymentRejectsPublicKey()throws Exception {
        var vector=JSON.readTree(Files.readString(Path.of("../../docs/ai-customer-service/B6_SIGNING_VECTOR.fixture")));
        byte[] key=Base64.getDecoder().decode(vector.path("key_b64").asText()),body=vector.path("body_utf8").asText().getBytes(StandardCharsets.UTF_8);
        assertEquals(vector.path("body_sha256").asText(),ExternalIdentityService.hash(body));
        assertEquals(vector.path("signature_hex").asText(),ExternalIdentityService.signature(key,vector.path("path").asText(),vector.path("timestamp").asText(),vector.path("nonce").asText(),body));
        var config=settings();config.setKeysJson("{\"xiaozhi-public-v1\":{\"issuer\":\"xiaozhi-public\",\"secret_b64\":\""+vector.path("key_b64").asText()+"\"}}");
        assertEquals(503,assertThrows(ExternalProblem.class,config::keys).status());
        assertNotEquals(vector.path("signature_hex").asText(),ExternalIdentityService.signature(key,vector.path("path").asText()+"/",vector.path("timestamp").asText(),vector.path("nonce").asText(),body));
    }
    @Test void signaturesAreRawBodyPathAndUniqueHeaderBoundAndNoBadSignatureTouchesDb() {
        var service=service(settings());byte[] raw="{\"test\":\"中文\"}".getBytes(StandardCharsets.UTF_8);String nonce="a".repeat(32);
        var headers=headers(ExternalIdentityService.ISSUE,raw,nonce);
        assertEquals(ISSUER,service.verify(ExternalIdentityService.ISSUE,raw,headers).issuer());
        assertThrows(ExternalProblem.class,()->service.verify(ExternalIdentityService.REVOKE,raw,headers));
        assertThrows(ExternalProblem.class,()->service.verify(ExternalIdentityService.ISSUE,(new String(raw,StandardCharsets.UTF_8)+"\n").getBytes(StandardCharsets.UTF_8),headers));
        headers.put("x-external-nonce",List.of(nonce,nonce));assertThrows(ExternalProblem.class,()->service.verify(ExternalIdentityService.ISSUE,raw,headers));
        var expired=headers(ExternalIdentityService.ISSUE,raw,nonce);expired.put("x-external-timestamp",List.of("1699999900"));
        expired.put("x-external-signature",List.of(ExternalIdentityService.signature(KEY,ExternalIdentityService.ISSUE,"1699999900",nonce,raw)));assertEquals("SIGNATURE_EXPIRED",assertThrows(ExternalProblem.class,()->service.verify(ExternalIdentityService.ISSUE,raw,expired)).getMessage());
    }
    @Test void optionalConfigurationIsDefaultOffStrictAndNeverReusesOriginalNamespace() {
        var config=new ExternalSettings();assertThrows(ExternalProblem.class,config::keys);
        for(String encoded:List.of("{}","[]","{bad}","{\"old-key\":{\"issuer\":\"daleng-dev\",\"secret_b64\":\"abc\"}}")) {
            config.setEnabled(true);config.setKeysJson(encoded);assertThrows(ExternalProblem.class,config::keys);
        }
        assertFalse(settings().toString().contains(Base64.getEncoder().encodeToString(KEY)));
        var aliases=settings();String secret=Base64.getEncoder().encodeToString(KEY);
        aliases.setKeysJson("{\"xiaozhi-key-one\":{\"issuer\":\"xiaozhi-source\",\"secret_b64\":\""+secret+"\"},\"xiaozhi-key-two\":{\"issuer\":\"xiaozhi-source\",\"secret_b64\":\""+secret+"\"}}");
        assertThrows(ExternalProblem.class,aliases::keys,"Duplicate-secret aliases must not let a request bypass a key-id scoped nonce");
    }
    @Test void whitelistAllowsOwnChatMediaAndHandoffButRejectsLegacyStaffRtcAndUnknown() {
        String id="a".repeat(32);
        for(String path:List.of("/api/v1/ai/conversations","/api/v1/ai/conversations/"+id+"/messages","/api/v1/ai/conversations/"+id+"/handoff","/api/v1/ai/uploads/init","/api/v1/support/annotations"))assertTrue(ExternalPathPolicy.allowed("POST",path),path);
        assertTrue(ExternalPathPolicy.allowed("POST","/api/v1/support/requests/"+id+"/end"));
        assertTrue(ExternalPathPolicy.allowed("GET","/api/v1/external/media-invitations"));
        assertTrue(ExternalPathPolicy.allowed("GET","/api/v1/app/capabilities"));
        assertFalse(ExternalPathPolicy.allowed("GET","/api/v1/support/media-invitations"));
        assertEquals(java.util.Set.of("after","limit"),ExternalPathPolicy.queryKeys("GET","/api/v1/external/media-invitations"));
        assertTrue(ExternalPathPolicy.allowed("POST","/api/v1/support/media-sessions/123/token"));
        assertFalse(ExternalPathPolicy.allowed("GET","/api/v1/support/media-sessions/123/token"));
        assertFalse(ExternalPathPolicy.allowed("POST","/api/v1/support/media-transfers/123/accept"));
        assertTrue(ExternalPathPolicy.allowed("PUT","/api/v1/ai/uploads/123/content"));
        for(String path:List.of("/user/refresh","/user/logout","/config/anything","/api/v1/support/agents","/api/v1/support/requests/"+id+"/claim","/internal/ai-worker/claim","/api/v1/ai/attachments/123/access","/api/v1/ai/attachments/123/content","/api/v1/ai/new-admin-api"))
            assertFalse(ExternalPathPolicy.allowed("POST",path)||ExternalPathPolicy.allowed("GET",path),path);
        assertFalse(ExternalPathPolicy.allowed("GET","/api/v1/ai/conversations/%61"+id.substring(1)));
    }
    @Test void filterRunsBeforeAnonPathsAndRejectsClientContextWithoutLookingUpIdentity()throws Exception {
        var service=mock(ExternalIdentityService.class);var filter=new ExternalBoundaryFilter(()->service);
        for(String path:List.of("/config/anything","/api/v1/ai/attachments/123/content","/api/v1/support/media-sessions/1/token")) {
            var request=new MockHttpServletRequest("GET",path);request.addHeader("Authorization","Bearer xext_"+"a".repeat(64));var response=new MockHttpServletResponse();
            filter.doFilter(request,response,(a,b)->fail("blocked external route reached downstream"));assertEquals(403,response.getStatus());
        }
        var request=new MockHttpServletRequest("GET","/api/v1/ai/conversations");request.addHeader("Authorization","Bearer xext_"+"a".repeat(64));request.addParameter("tenant_id","forged");var response=new MockHttpServletResponse();
        filter.doFilter(request,response,(a,b)->fail("forged context reached downstream"));assertEquals(400,response.getStatus());verifyNoInteractions(service);
    }
    @Test void localBearerIsUnchangedAndExternalDependencyFailureIs503()throws Exception {
        var filter=new ExternalBoundaryFilter(()->null);var local=new MockHttpServletRequest("GET","/user/info");local.addHeader("Authorization","Bearer local-fixture");AtomicInteger calls=new AtomicInteger();
        filter.doFilter(local,new MockHttpServletResponse(),(a,b)->calls.incrementAndGet());assertEquals(1,calls.get());
        var external=new MockHttpServletRequest("GET","/api/v1/ai/conversations");external.addHeader("Authorization","Bearer xext_"+"a".repeat(64));var response=new MockHttpServletResponse();filter.doFilter(external,response,(a,b)->fail());assertEquals(503,response.getStatus());
        var identity=mock(ExternalIdentityService.class);when(identity.authenticate(anyString())).thenThrow(new TransientDataAccessResourceException("private diagnostic"));response=new MockHttpServletResponse();new ExternalBoundaryFilter(()->identity).doFilter(external,response,(a,b)->fail());
        assertEquals(503,response.getStatus());assertFalse(response.getContentAsString().contains("private"));
    }
    @Test void shiroKeepsLegacyLookupAndExternalTokensNeverFallBackToLocalTokenTable() {
        var dao=mock(SysUserTokenDao.class);var identity=mock(ExternalIdentityService.class);var service=new ShiroServiceImpl(mock(SysUserDao.class),dao);service.setExternalIdentity(identity);
        SysUserTokenEntity local=new SysUserTokenEntity();when(dao.getByToken("local-token")).thenReturn(local);assertSame(local,service.getByToken("local-token"));
        when(identity.authenticate("xext_invalid")).thenThrow(new ExternalProblem(401,"EXTERNAL_TOKEN_INVALID"));assertThrows(ExternalProblem.class,()->service.getByToken("xext_invalid"));
        verify(dao).getByToken("local-token");verifyNoMoreInteractions(dao);
    }
    @Test void externalSseRechecksSourceBeforeSecondBatchAndKeepsLocalContract()throws Exception {
        var ai=mock(AiExecutionService.class);var conversations=mock(ConversationService.class);var source=mock(ExternalIdentityService.class);
        var controller=new AiController(ai,conversations,JSON);controller.setExternalIdentity(source);String id="a".repeat(32);AtomicInteger checks=new AtomicInteger();
        when(source.longLivedGuard(any())).thenReturn(()->{if(checks.incrementAndGet()>=3)throw new ExternalProblem(401,"SOURCE_SESSION_REVOKED");});
        var event=new Event("id",id,1,"message.created",JSON.createObjectNode(),Instant.now());
        when(conversations.events(7L,id,0,100)).thenReturn(new CursorPage<>(List.of(event),1,true));
        var request=new MockHttpServletRequest();request.addHeader("Authorization","Bearer xext_"+"a".repeat(64));var response=new MockHttpServletResponse();
        try(var security=mockStatic(SecurityUser.class)){security.when(SecurityUser::getUserId).thenReturn(7L);controller.stream(id,0,null,request,response);}
        assertTrue(response.getContentAsString().contains("id: 1\n"));verify(conversations,times(1)).events(anyLong(),anyString(),anyLong(),anyInt());assertEquals(3,checks.get());
    }
    @Test void realXssChainPreservesSignedRawBodyAndStillFiltersOtherPaths()throws Exception {
        var properties=new xiaozhi.common.xss.XssProperties();properties.setEnabled(true);
        var xss=new xiaozhi.common.xss.XssFilter(properties,new org.springframework.util.AntPathMatcher());
        var boundary=new ExternalBoundaryFilter(()->null);byte[] raw=("  {\"display_name\":\"A < B & C > D\"} \n").getBytes(StandardCharsets.UTF_8);
        var request=new MockHttpServletRequest("POST",ExternalIdentityService.ISSUE);request.setContentType("application/json");request.setContent(raw);
        var signed=headers(ExternalIdentityService.ISSUE,raw,"a".repeat(32));signed.forEach((key,values)->values.forEach(value->request.addHeader(key,value)));
        AtomicInteger verified=new AtomicInteger();
        boundary.doFilter(request,new MockHttpServletResponse(),(bounded,response)->xss.doFilter(bounded,response,(actual,output)->{
            byte[] received=actual.getInputStream().readAllBytes();assertArrayEquals(raw,received);
            assertEquals(ISSUER,service(settings()).verify(ExternalIdentityService.ISSUE,received,signed).issuer());verified.incrementAndGet();
        }));assertEquals(1,verified.get());
        var ordinary=new MockHttpServletRequest("POST","/api/v1/external/identity/issue-extra");ordinary.setContentType("application/json");ordinary.setContent(raw);
        xss.doFilter(ordinary,new MockHttpServletResponse(),(actual,output)->assertInstanceOf(xiaozhi.common.xss.XssHttpServletRequestWrapper.class,actual));
    }
    @Test void boundedRawFilterRejectsOversizeBeforeXssCanReadAnything()throws Exception {
        var request=new MockHttpServletRequest("POST",ExternalIdentityService.ISSUE);request.setContentType("application/json");request.setContent(new byte[32769]);
        var response=new MockHttpServletResponse();new ExternalBoundaryFilter(()->null).doFilter(request,response,(actual,output)->fail("Oversized wire body reached XSS/controller"));
        assertEquals(413,response.getStatus());
    }

    @Test void businessRequiresTrustedScopeAndMissingDelegateReturnsNotConfigured() {
        var identity=mock(ExternalIdentityService.class);
        @SuppressWarnings("unchecked") org.springframework.beans.factory.ObjectProvider<ExternalBusinessAdapter> adapters=mock(org.springframework.beans.factory.ObjectProvider.class);
        Principal actor=new Principal(1,"hash",2,7,ISSUER,"subject",new Tenant("company","1"),new Scope(List.of("site-1"),List.of("device-1")),Instant.now().plusSeconds(300));
        when(identity.required(any())).thenReturn(actor);var controller=new ExternalBusinessController(identity,adapters);
        assertEquals(404,assertThrows(ExternalProblem.class,()->controller.device("other-device",new MockHttpServletRequest(),new MockHttpServletResponse())).status());
        assertEquals("BUSINESS_NOT_CONFIGURED",assertThrows(ExternalProblem.class,()->controller.device("device-1",new MockHttpServletRequest(),new MockHttpServletResponse())).getMessage());
        ExternalBusinessAdapter fake=mock(ExternalBusinessAdapter.class);when(adapters.getIfAvailable()).thenReturn(fake);
        when(fake.read(any(),any())).thenReturn(new ExternalBusinessAdapter.Evidence(ISSUER,"company","2","device","device-1",Instant.now(),JSON.createObjectNode()));
        assertEquals("BUSINESS_PROVENANCE_INVALID",assertThrows(ExternalProblem.class,()->controller.device("device-1",new MockHttpServletRequest(),new MockHttpServletResponse())).getMessage());
    }

    @Test void validSignatureNeverRelaxesTenantScopeRoleOrNumericSchemas()throws Exception {
        var service=service(settings());
        String valid="{\"external_subject\":\"test\",\"source_session_id\":\"source\",\"source_expires_at\":1700003600,\"display_name\":\"test\",\"tenant\":{\"type\":\"company\",\"id\":\"1\"},\"scope\":{\"project_ids\":[],\"device_ids\":[]}}";
        for(String raw:List.of(valid.replace("1700003600","true"),valid.replace("1700003600","1.7e9"),
                valid.replace("\"company\"","\"admin\""),valid.replace("\"id\":\"1\"","\"id\":\"01\""),
                valid.substring(0,valid.length()-1)+",\"role\":\"admin\"}",valid+"{}")) {
            byte[] bytes=raw.getBytes(StandardCharsets.UTF_8);var verified=service.verify(ExternalIdentityService.ISSUE,bytes,headers(ExternalIdentityService.ISSUE,bytes,"b".repeat(32)));
            assertEquals(400,assertThrows(ExternalProblem.class,()->service.issue(verified,bytes)).status());
        }
    }

    @Test void unrelatedBearerWithEmbeddedSourcePrefixDoesNotHijackOtherAuthProtocols()throws Exception {
        var filter=new ExternalBoundaryFilter(()->{throw new AssertionError("Unrelated JWT must not access external identity store");});
        var request=new MockHttpServletRequest("POST","/internal/livekit/webhook");request.addHeader("Authorization","Bearer eyJheader.payload-xext_embedded.signature");
        AtomicInteger calls=new AtomicInteger();filter.doFilter(request,new MockHttpServletResponse(),(a,b)->calls.incrementAndGet());assertEquals(1,calls.get());
    }

}
