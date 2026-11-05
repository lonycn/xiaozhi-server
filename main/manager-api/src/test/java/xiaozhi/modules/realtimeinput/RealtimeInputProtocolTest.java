package xiaozhi.modules.realtimeinput;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import xiaozhi.modules.conversation.ConversationProblem;
import xiaozhi.modules.realtimeinput.RealtimeInputModels.*;

class RealtimeInputProtocolTest {
    private final ObjectMapper mapper=new xiaozhi.modules.security.config.WebMvcConfig().jackson2HttpMessageConverter().getObjectMapper();
    @Test void boundedStrictJsonRejectsDuplicateTrailingCoercionAndQueriesBeforeReading()throws Exception {
        var parser=new RealtimeInputJson(mapper);String valid="{\"expected_epoch\":1,\"client_pause_id\":\"pause1\"}";
        for(String body:List.of(valid+"{}",valid.replace("\"expected_epoch\":1","\"expected_epoch\":\"1\""),valid.replace("\"expected_epoch\":1","\"expected_epoch\":1.5"),valid.replace("\"client_pause_id\":\"pause1\"","\"client_pause_id\":\"pause1\",\"client_pause_id\":\"pause2\""),valid.replace("\"expected_epoch\":1","\"expected_epoch\":1,\"owner_id\":7"))){
            var request=new MockHttpServletRequest();request.setContent(body.getBytes(StandardCharsets.UTF_8));assertEquals(400,assertThrows(ConversationProblem.class,()->parser.read(request,PauseRequest.class)).status());
        }
        var big=new MockHttpServletRequest();big.setContent(new byte[32769]);assertEquals(413,assertThrows(ConversationProblem.class,()->parser.read(big,PauseRequest.class)).status());
        var query=new MockHttpServletRequest();query.setQueryString("owner_id=7");query.setContent(new byte[100000]);assertEquals(400,assertThrows(ConversationProblem.class,()->parser.read(query,PauseRequest.class)).status());
        var good=new MockHttpServletRequest();good.setContent(valid.getBytes(StandardCharsets.UTF_8));assertEquals(1,parser.read(good,PauseRequest.class).expectedEpoch());
    }
    @Test void observationNullIsAControlled400BeforeAuditOrSourceLookup(){
        var store=mock(xiaozhi.modules.realtime.RealtimeStore.class);var provider=mock(org.springframework.beans.factory.ObjectProvider.class);
        @SuppressWarnings("unchecked") var service=new RealtimeInputService(store,mapper,new org.springframework.mock.env.MockEnvironment(),provider,provider,provider,provider);
        assertEquals(400,assertThrows(ConversationProblem.class,()->service.prepared("a".repeat(32),"b".repeat(32),new Prepare("c".repeat(64),1L,1L,java.util.Arrays.asList((Observation)null)))).status());verifyNoInteractions(store);
    }
    @Test void publicViewsUseIsoTimesAndNumericGenerationsAndSqlErrorsStayGeneric()throws Exception {
        var pause=new PauseView("id","session","paused",3,Instant.parse("2026-11-02T00:00:00Z"));var tree=mapper.valueToTree(pause);assertTrue(tree.path("turn_generation").isIntegralNumber());assertEquals("2026-11-02T00:00:00Z",tree.path("created_at").asText());
        var permission=mapper.valueToTree(new ResumePermission(3,false,"pause",null));assertFalse(permission.path("respond").asBoolean());assertTrue(permission.path("material_id").isNull());
        var controller=new RealtimeInputController(mock(RealtimeInputService.class),mapper);var result=controller.database(new org.springframework.dao.DataAccessResourceFailureException("SELECT private_data; jdbc:mysql://secret-host"));assertEquals(503,result.getStatusCode().value());String body=mapper.valueToTree(result.getBody()).toString();assertFalse(body.contains("SELECT"));assertFalse(body.contains("secret-host"));
    }
}
