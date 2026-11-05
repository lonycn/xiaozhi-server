package xiaozhi.modules.ai;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import com.fasterxml.jackson.databind.*;
import xiaozhi.modules.ai.AiModels.*;
import xiaozhi.modules.conversation.ConversationProblem;
class AiProviderUsageAuditProtocolTest {
    final ObjectMapper json=new xiaozhi.modules.security.config.WebMvcConfig().jackson2HttpMessageConverter().getObjectMapper();
    final AiProviderUsageAudit service=mock(AiProviderUsageAudit.class);
    final AiProviderUsageAuditController controller=new AiProviderUsageAuditController(service,json);
    final String valid="{\"lease_token\":\""+"a".repeat(64)+"\",\"attempt\":1,\"provider_requests\":[{\"round\":1,\"provider_request_id\":null,\"usage\":{\"input_tokens\":5,\"output_tokens\":2,\"total_tokens\":7},\"finish_reason\":\"tool_calls\"}]}";
    MockHttpServletRequest request(String body){var r=new MockHttpServletRequest();r.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));return r;}
    @Test void strictRawBodyRejectsCoercionDuplicatesUnknownTrailingAndNonscalarUsage()throws Exception {
        assertEquals(7L,controller.read(request(valid)).providerRequests().getFirst().usage().totalTokens());
        for(String body:List.of(valid.replace("\"attempt\":1","\"attempt\":\"1\""),valid.replace("\"attempt\":1","\"attempt\":1.0"),valid.replace("\"attempt\":1","\"attempt\":1,\"attempt\":2"),valid.replace("\"round\":1","\"round\":\"1\""),valid.replace("\"total_tokens\":7","\"total_tokens\":\"7\""),valid.replace("\"total_tokens\":7","\"total_tokens\":{\"private\":7}"),valid.replace("\"finish_reason\":","\"raw_response\":\"not allowed\",\"finish_reason\":"),valid.replace("\"attempt\":1","\"owner_id\":1,\"attempt\":1"),valid+" {}","null","[]"))assertEquals(400,assertThrows(ConversationProblem.class,()->controller.read(request(body))).status());verifyNoInteractions(service);
    }
    @Test void queryAndOversizeAreRejectedBeforeServiceOrUnboundedBodyRead()throws Exception {
        var query=new MockHttpServletRequest(){@Override public jakarta.servlet.ServletInputStream getInputStream(){throw new AssertionError("Rejected query body read");}};query.setQueryString("token=forbidden");assertEquals(400,assertThrows(ConversationProblem.class,()->controller.read(query)).status());assertEquals(413,assertThrows(ConversationProblem.class,()->controller.read(request("x".repeat(16385)))).status());verifyNoInteractions(service);
    }
    @Test void auditCapabilityIsOptionalForLegacyTaskConstructorsAndNumericAttemptStaysIntegral()throws Exception {
        Task old=new Task("run","conversation","input","output",1,1,"lease",45,"qwen-flash",List.of());assertFalse(json.valueToTree(old).has("usage_audit"));Task current=new Task("run","conversation","input","output",1,1,"lease",45,"qwen-flash",List.of(),null,null,null,null,true);JsonNode task=json.readTree(json.writeValueAsBytes(current));assertTrue(task.path("usage_audit").asBoolean());assertTrue(task.path("attempt").isIntegralNumber());assertFalse(controller.read(request(valid)).toString().contains("a".repeat(64)));
    }
    @Test void invalidRangesAndRequestPrefixesCannotReachSql() {
        var store=new AiProviderUsageStore(null);for(var body:List.of(List.of(new ProviderRequest(2,null,null,null)),List.of(new ProviderRequest(1,"bad\nidentifier",null,"stop")),List.of(new ProviderRequest(1,null,new Usage(1000000001L,0L,1000000001L),"stop")),List.of(new ProviderRequest(1,null,new Usage(1L,1L,2L,2L),"stop"))))assertThrows(ConversationProblem.class,()->store.validateReport(body));
    }
    @Test void sqlAndInputErrorsAreSanitizedWithActualStatuses() {
        var failure=controller.database(new org.springframework.dao.DataAccessResourceFailureException("SELECT raw_private; jdbc:mysql://secret-host"));assertEquals(503,failure.getStatusCode().value());assertFalse(json.valueToTree(failure.getBody()).toString().contains("secret-host"));assertEquals(400,controller.input(new java.io.IOException("private path")).getStatusCode().value());
    }
}
