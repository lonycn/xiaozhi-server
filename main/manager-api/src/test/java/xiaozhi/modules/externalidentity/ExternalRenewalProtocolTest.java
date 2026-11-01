package xiaozhi.modules.externalidentity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.mock.web.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import xiaozhi.modules.externalidentity.ExternalModels.*;

class ExternalRenewalProtocolTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final byte[] KEY=new byte[32];static {Arrays.fill(KEY,(byte)61);}
    static final String KEY_ID="xiaozhi-renewal-test-key",ISSUER="xiaozhi-renewal-test";
    static String keys(byte[] key){return "{\""+KEY_ID+"\":{\"issuer\":\""+ISSUER+"\",\"secret_b64\":\""+Base64.getEncoder().encodeToString(key)+"\"}}";}
    static ExternalSettings settings(){var s=new ExternalSettings();s.setEnabled(true);s.setKeysJson(keys(KEY));return s;}
    static Map<String,List<String>> headers(String path,byte[] raw,Instant now){String timestamp=Long.toString(now.getEpochSecond()),nonce=UUID.randomUUID().toString().replace("-","");return Map.of(
        "x-external-key-id",List.of(KEY_ID),"x-external-timestamp",List.of(timestamp),"x-external-nonce",List.of(nonce),
        "x-external-signature",List.of(ExternalIdentityService.signature(KEY,path,timestamp,nonce,raw)));}
    static byte[] raw(Map<String,Object> changes)throws Exception {
        Map<String,Object> value=new LinkedHashMap<>();value.put("external_subject","subject");value.put("source_session_id","source");value.put("token_id","1");
        value.put("tenant",Map.of("type","company","id","1"));value.put("scope",Map.of("project_ids",List.of("p"),"device_ids",List.of("d")));
        value.put("requested_lifetime_seconds",300);value.putAll(changes);return JSON.writeValueAsBytes(value);
    }
    @Test void disabledRenewalAndInvalidLifetimeNeverReachDatabase()throws Exception {
        var db=mock(JdbcTemplate.class);var settings=settings();Instant now=Instant.ofEpochSecond(1700000000);
        var service=new ExternalIdentityService(db,JSON,mock(PlatformTransactionManager.class),settings,Clock.fixed(now,ZoneOffset.UTC));
        byte[] raw=raw(Map.of());
        assertFalse(settings.renewalEnabled());
        assertEquals("RENEWAL_NOT_CONFIGURED",assertThrows(ExternalProblem.class,()->service.renew(ExternalIdentityService.RENEW,raw,headers(ExternalIdentityService.RENEW,raw,now))).getMessage());
        settings.setRenewalEnabled(true);settings.setTokenSeconds(60);assertEquals(60,settings.renewalMaxSeconds());
        assertEquals("INVALID_RENEWAL_LIFETIME",assertThrows(ExternalProblem.class,()->service.renew(ExternalIdentityService.RENEW,raw,headers(ExternalIdentityService.RENEW,raw,now))).getMessage());
        settings.setRenewalMaxSeconds(901);assertThrows(ExternalProblem.class,settings::renewalMaxSeconds);verifyNoInteractions(db);
    }
    @Test void signedRenewalHasExactRawBodyPathHeadersAndBoundedSchema()throws Exception {
        var settings=settings();settings.setRenewalEnabled(true);Instant now=Instant.ofEpochSecond(1700000000);var db=mock(JdbcTemplate.class);
        var service=new ExternalIdentityService(db,JSON,mock(PlatformTransactionManager.class),settings,Clock.fixed(now,ZoneOffset.UTC));
        byte[] raw=raw(Map.of());var headers=headers(ExternalIdentityService.RENEW,raw,now);
        assertEquals(ISSUER,service.verify(ExternalIdentityService.RENEW,raw,headers).issuer());
        assertThrows(ExternalProblem.class,()->service.renew(ExternalIdentityService.ISSUE,raw,headers));
        assertThrows(ExternalProblem.class,()->service.renew(ExternalIdentityService.RENEW,(new String(raw,StandardCharsets.UTF_8)+" ").getBytes(StandardCharsets.UTF_8),headers));
        for(var changes:List.of(Map.<String,Object>of("source_expires_at",1700003600),Map.<String,Object>of("token_id","01"),
                Map.<String,Object>of("requested_lifetime_seconds",true),Map.<String,Object>of("requested_lifetime_seconds",0),Map.<String,Object>of("tenant",Map.of("type","admin","id","1")))) {
            byte[] invalid=raw(changes);assertEquals(400,assertThrows(ExternalProblem.class,()->service.renew(ExternalIdentityService.RENEW,invalid,headers(ExternalIdentityService.RENEW,invalid,now))).status());
        }
        verifyNoInteractions(db);
    }
    @Test void controllerRequiresSignedExactPostAndPrivateResponse()throws Exception {
        var service=mock(ExternalIdentityService.class);var controller=new ExternalIdentityController(service,JSON);
        var request=new MockHttpServletRequest("POST",ExternalIdentityService.RENEW);request.setContent(raw(Map.of()));
        var response=new MockHttpServletResponse();when(service.renew(anyString(),any(),anyMap())).thenReturn(Map.of("token_id","1"));
        assertEquals("1",controller.renew(request,response).getData().get("token_id"));assertEquals("no-store",response.getHeader("Cache-Control"));
        request.setQueryString("token=leak");assertThrows(ExternalProblem.class,()->controller.renew(request,new MockHttpServletResponse()));
        verify(service,times(1)).renew(anyString(),any(),anyMap());
    }
    @Test void publicViewExposesOnlyNonsecretReferenceAndKeepsPrincipalShape()throws Exception {
        var service=new ExternalIdentityService(mock(JdbcTemplate.class),JSON,mock(PlatformTransactionManager.class),settings());
        var principal=new Principal(19,"private-token-digest",7,8,ISSUER,"subject",new Tenant("company","1"),new Scope(List.of("p"),List.of("d")),Instant.now().plusSeconds(300));
        var view=service.view(principal);assertEquals("19",view.get("token_id"));assertEquals(false,view.get("renewal_enabled"));
        String wire=JSON.findAndRegisterModules().writeValueAsString(view);assertFalse(wire.contains("private-token-digest"));assertFalse(wire.contains("source_id"));
        var renewed=new Principal(19,"private-token-digest",7,8,ISSUER,"subject",principal.tenant(),principal.scope(),principal.expiresAt().plusSeconds(300));
        assertTrue(ExternalIdentityService.sameBinding(principal,renewed));
        var replacement=new Principal(20,"new-token-digest",7,8,ISSUER,"subject",principal.tenant(),principal.scope(),renewed.expiresAt());
        assertFalse(ExternalIdentityService.sameBinding(principal,replacement));assertFalse(ExternalIdentityService.sameBinding(principal,null));
    }
}
