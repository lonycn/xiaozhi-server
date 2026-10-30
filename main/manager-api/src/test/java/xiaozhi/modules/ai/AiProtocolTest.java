package xiaozhi.modules.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import xiaozhi.modules.ai.AiModels.*;
import xiaozhi.modules.conversation.*;

class AiProtocolTest {
    @Test void workerCredentialIsDedicatedHeaderAndFailsClosed() throws Exception {
        String secret="0123456789abcdef".repeat(2);
        var filter=new AiWorkerKeyFilter(secret);
        var request=new MockHttpServletRequest(); var response=new MockHttpServletResponse();
        request.addHeader("Authorization","Bearer "+secret);
        request.addParameter("token",secret);
        assertFalse(filter.isAccessAllowed(request,response,null));
        request.addHeader("X-AI-Worker-Key",secret+"x");
        assertFalse(filter.isAccessAllowed(request,response,null));
        request.removeHeader("X-AI-Worker-Key"); request.addHeader("X-AI-Worker-Key",secret);
        assertTrue(filter.isAccessAllowed(request,response,null));
        assertFalse(new AiWorkerKeyFilter("").isAccessAllowed(request,response,null));
        assertFalse(filter.onAccessDenied(request,response)); assertEquals(401,response.getStatus());
    }
    @Test void disabledExecutionNeverTouchesDatabase() {
        var jdbc=mock(JdbcTemplate.class);
        var service=new AiExecutionService(jdbc,new ObjectMapper(),mock(PlatformTransactionManager.class));
        service.onUserMessage("a".repeat(32),"b".repeat(32));
        assertEquals(503,assertThrows(ConversationProblem.class,()->service.claim(new Claim("worker"))).status());
        verifyNoInteractions(jdbc);
    }
    @Test void onlyActualNonnegativeCompleteUsageAccepted() {
        AiExecutionService.validateUsage(null);
        AiExecutionService.validateUsage(new Usage(0L,0L,0L));
        AiExecutionService.validateUsage(new Usage(109L,248L,357L,44L));
        assertThrows(ConversationProblem.class,()->AiExecutionService.validateUsage(new Usage(1L,1L,2L,2L)));
        assertThrows(ConversationProblem.class,()->AiExecutionService.validateUsage(new Usage(1L,1L,2L,-1L)));
        assertThrows(ConversationProblem.class,()->AiExecutionService.validateUsage(new Usage(null,1L,1L)));
        assertThrows(ConversationProblem.class,()->AiExecutionService.validateUsage(new Usage(1L,-1L,0L)));
    }
    @Test void strictWorkerRequestsRejectSpoofedContextAndUnknownUsage() throws Exception {
        var json=new ObjectMapper();
        var requests=new ConversationService(mock(ConversationDao.class),json,mock(PlatformTransactionManager.class));
        assertThrows(ConversationProblem.class,()->requests.request(json.readTree("{\"worker_id\":\"w\",\"model\":\"spoofed\"}"),Claim.class));
        assertThrows(ConversationProblem.class,()->requests.request(json.readTree("{\"lease_token\":\"a\",\"last_sequence\":0,\"usage\":{\"input_tokens\":0,\"output_tokens\":0,\"total_tokens\":0,\"estimated\":true}}"),Complete.class));
        assertThrows(ConversationProblem.class,()->requests.request(json.readTree("{\"owner_user_id\":1}"),Empty.class));
        assertThrows(ConversationProblem.class,()->requests.request(json.readTree("{\"lease_token\":\"a\",\"sequence\":1.5,\"text\":\"x\"}"),Delta.class));
    }
    @Test void publicRunNeverSerializesCapability() throws Exception {
        var fields=java.util.Arrays.stream(Run.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList();
        assertFalse(fields.contains("leaseToken")); assertFalse(fields.contains("leaseOwner"));
        assertFalse(fields.contains("workerKey"));
    }
}
