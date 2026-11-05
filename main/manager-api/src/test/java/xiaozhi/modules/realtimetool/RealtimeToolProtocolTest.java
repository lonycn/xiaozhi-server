package xiaozhi.modules.realtimetool;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import com.fasterxml.jackson.databind.*;
import xiaozhi.modules.conversation.ConversationProblem;
import xiaozhi.modules.realtime.RealtimeModels.*;

class RealtimeToolProtocolTest {
    private final ObjectMapper json=new xiaozhi.modules.security.config.WebMvcConfig().jackson2HttpMessageConverter().getObjectMapper();
    @Test void duplicateJsonCoercionsActorInjectionAndLargeBodyAreRejected()throws Exception {
        var service=mock(RealtimeToolService.class);var controller=new RealtimeToolController(service,json);
        String valid="{\"lease_token\":\""+"a".repeat(64)+"\",\"control_epoch\":1,\"turn_generation\":2,\"provider_session_id\":\"provider1\",\"response_id\":\"response1\",\"round\":1,\"calls\":[{\"provider_item_id\":\"item1\",\"provider_call_id\":\"call1\",\"name\":\"server_capabilities\",\"arguments\":{}}]}";
        for(String value:new String[]{valid+"{}",valid.replace("\"control_epoch\":1","\"control_epoch\":\"1\""),valid.replace("\"round\":1","\"round\":1.5"),
                valid.replace("\"arguments\":{}","\"arguments\":{\"a\":1,\"a\":2}"),valid.replace("\"round\":1","\"round\":1,\"owner_id\":7")}){
            var request=new MockHttpServletRequest();request.setContent(value.getBytes(StandardCharsets.UTF_8));
            assertEquals(400,assertThrows(ConversationProblem.class,()->controller.proposals("a".repeat(32),request)).status());
        }
        var oversized=new MockHttpServletRequest();oversized.setContent(new byte[32769]);assertEquals(413,assertThrows(ConversationProblem.class,()->controller.proposals("a".repeat(32),oversized)).status());verifyNoInteractions(service);
        var request=new MockHttpServletRequest();request.setContent(valid.getBytes(StandardCharsets.UTF_8));controller.proposals("a".repeat(32),request);verify(service).proposals(eq("a".repeat(32)),any());
    }
    @Test void legacyWireOmitsEveryNewFieldAndMeasurementsStayNumeric()throws Exception {
        var task=new Task("a".repeat(32),"b".repeat(32),1,"c".repeat(64),"server",12,"model",new Consent(true,false),java.util.List.of(),0,
                new Limits(2,1800,65536,1920000,190000,921600,1000,16000,128000),new AudioFormat("s16le",16000,1),new AudioFormat("s16le",24000,1),"manual");
        JsonNode wire=json.valueToTree(task);for(String field:new String[]{"tools","tool_policy","turn_generation"})assertFalse(wire.has(field));
        var pulse=new Pulse(true,12,java.util.List.of(),java.util.List.of(),3L);assertTrue(json.valueToTree(pulse).path("turn_generation").isIntegralNumber());
        var event=new ProviderEvent("a".repeat(64),1L,"event1","response.started",null,null,"r1",null,null,null,null,null,null);
        var legacy=json.valueToTree(event);for(String field:new String[]{"turn_generation","provider_call_id","continuation_id"})assertFalse(legacy.has(field));
    }
    @Test void sqlFailuresCannotExposeSqlOrConnectionDetails(){
        var controller=new RealtimeToolController(mock(RealtimeToolService.class),json);var response=controller.database(new org.springframework.dao.DataAccessResourceFailureException("SELECT secrets FROM private; jdbc:mysql://sensitive-host"));
        assertEquals(503,response.getStatusCode().value());String body=json.valueToTree(response.getBody()).toString();assertTrue(body.contains("REALTIME_TOOLS_UNAVAILABLE"));assertFalse(body.contains("SELECT"));assertFalse(body.contains("sensitive-host"));
    }
}
