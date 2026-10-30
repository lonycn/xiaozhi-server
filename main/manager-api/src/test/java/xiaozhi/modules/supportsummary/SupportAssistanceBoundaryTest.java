package xiaozhi.modules.supportsummary;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import xiaozhi.modules.conversation.*;
import xiaozhi.modules.security.user.SecurityUser;
import static xiaozhi.modules.supportsummary.AssistanceModels.*;

class SupportAssistanceBoundaryTest {
    ConversationService parser(){return new ConversationService(mock(ConversationDao.class),new ObjectMapper(),mock(PlatformTransactionManager.class));}
    @Test void explicitFlagIsRequiredForRealServiceAndBothControllers() {
        var runner=new ApplicationContextRunner().withUserConfiguration(SupportAssistanceService.class,SupportSummaryService.class,SupportSummaryWiringTest.Foundation.class);
        runner.withPropertyValues("features.conversation-enabled=true","features.support-handoff-enabled=true","features.ai-text-enabled=true").run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(SupportAssistanceService.class).isEmpty());});
        runner.withPropertyValues("features.conversation-enabled=true","features.support-handoff-enabled=true","features.support-summary-assistance-enabled=true").run(c->{assertNull(c.getStartupFailure());assertEquals(1,c.getBeansOfType(SupportAssistanceService.class).size());});
        for(String foundation:List.of("conversation-enabled","support-handoff-enabled"))runner.withPropertyValues("features.support-summary-assistance-enabled=true","features."+foundation+"=false").run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(SupportAssistanceService.class).isEmpty());});
    }
    @Test void externalCredentialsCannotReachAssistanceOrWorkerRoutes() {
        for(String method:List.of("GET","POST","PUT"))for(String path:List.of("/api/v1/support/conversations/c1/summary/assistance","/api/v1/support/conversations/c1/summary/assistance/job/retry","/internal/ai-worker/summary/claim","/internal/ai-worker/summary/job/complete"))assertFalse(xiaozhi.modules.externalidentity.ExternalPathPolicy.allowed(method,path));
    }
    @Test void authenticatedActorCannotBeSpoofedAndRequestFieldsAreStrict()throws Exception {
        var service=mock(SupportAssistanceService.class);var mvc=MockMvcBuilders.standaloneSetup(new SupportAssistanceController(service,parser())).setControllerAdvice(new SupportSummaryExceptionHandler()).build();
        try(var identity=mockStatic(SecurityUser.class)) {
            identity.when(SecurityUser::getUserId).thenReturn(3L);
            mvc.perform(post("/api/v1/support/conversations/c1/summary/assistance").contentType(MediaType.APPLICATION_JSON).content("{\"client_request_id\":\"key\",\"base_version\":1,\"actor_user_id\":4}")).andExpect(status().isBadRequest());
            verify(service,never()).generate(any(),any(),any());
            mvc.perform(post("/api/v1/support/conversations/c1/summary/assistance").contentType(MediaType.APPLICATION_JSON).content("{\"client_request_id\":\"key\",\"base_version\":1}")).andExpect(status().isOk());
            verify(service).generate(3L,"c1",new Generate("key",1));
            when(service.get(3L,"c1","job")).thenThrow(new ConversationProblem(404,"SUPPORT_ASSISTANCE_NOT_FOUND"));
            mvc.perform(get("/api/v1/support/conversations/c1/summary/assistance/job")).andExpect(status().isNotFound());
        }
    }
}
