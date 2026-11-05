package xiaozhi.modules.externalidentity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import com.fasterxml.jackson.databind.json.JsonMapper;
import xiaozhi.modules.conversation.*;
import xiaozhi.modules.supportmedia.CallPresenceService;
import xiaozhi.modules.supportmedia.CallPresenceService.*;

class ExternalCallPresenceControllerTest {
    @Test void presenceRouteIsExactPostMediaOnlyAndStaffAvailabilityIsNeverExternal(){
        String path="/api/v1/external/call-presence";
        assertTrue(ExternalPathPolicy.allowed("POST",path));assertTrue(ExternalPathPolicy.mediaAllowed("POST",path));
        assertFalse(ExternalPathPolicy.allowed("GET",path));assertFalse(ExternalPathPolicy.allowed("POST",path+"/child"));
        assertFalse(ExternalPathPolicy.allowed("GET","/api/v1/support/requests/"+"a".repeat(32)+"/call-availability"));
        assertFalse(ExternalPathPolicy.publicEntry(path));assertEquals(java.util.Set.of(),ExternalPathPolicy.queryKeys("POST",path));
    }
    @Test void strictUpdateRejectsSpoofedIdentityAndCoercedReadiness()throws Exception{
        var json=JsonMapper.builder().build();var parser=new ConversationService(mock(ConversationDao.class),json,mock(org.springframework.transaction.PlatformTransactionManager.class));
        for(String body:java.util.List.of("{\"conversation_id\":\"x\",\"available\":\"true\",\"video\":false}","{\"conversation_id\":\"x\",\"available\":true,\"video\":1}","{\"conversation_id\":\"x\",\"available\":true,\"video\":false,\"source_id\":99}"))
            assertThrows(ConversationProblem.class,()->parser.request(json.readTree(body),Update.class));
    }
    @Test void routesNeedAllFiveFeatureFlags(){
        var runner=new ApplicationContextRunner().withBean(ExternalIdentityService.class,()->mock(ExternalIdentityService.class)).withBean(ConversationService.class,()->mock(ConversationService.class)).withBean(CallPresenceService.class,()->mock(CallPresenceService.class)).withUserConfiguration(ExternalCallPresenceController.class);
        runner.run(context->assertTrue(context.getBeansOfType(ExternalCallPresenceController.class).isEmpty()));
        runner.withPropertyValues("features.external-identity-enabled=true","features.external-realtime-media-enabled=true","features.conversation-enabled=true","features.support-handoff-enabled=true","features.support-media-enabled=true").run(context->{assertNull(context.getStartupFailure());assertEquals(1,context.getBeansOfType(ExternalCallPresenceController.class).size());});
    }
    @Test void responsesExposeCapabilitiesAndExpiryWithoutSourceCredentials(){
        var json=new xiaozhi.modules.security.config.WebMvcConfig().jackson2HttpMessageConverter().getObjectMapper();
        var value=json.valueToTree(new Receipt("conversation","online",true,false,Instant.parse("2026-11-05T01:00:15Z"),5));
        assertEquals("2026-11-05T01:00:15Z",value.get("expires_at").asText());assertEquals(5,value.get("heartbeat_seconds").asInt());assertTrue(value.get("audio").asBoolean());assertFalse(value.has("token_id"));assertFalse(value.has("source_id"));
    }
}
