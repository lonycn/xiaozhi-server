package xiaozhi.modules.supportsummary;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
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
import static xiaozhi.modules.supportsummary.SummaryModels.*;

class SupportSummaryControllerTest {
    ConversationService parser(){return new ConversationService(mock(ConversationDao.class),new ObjectMapper(),mock(PlatformTransactionManager.class));}
    @Test void externalCredentialsCannotRouteToInternalSummaries() {
        String path="/api/v1/support/conversations/"+"a".repeat(32)+"/summary";
        for(String method:List.of("GET","PUT","POST")) {
            assertFalse(xiaozhi.modules.externalidentity.ExternalPathPolicy.allowed(method,path));
            assertFalse(xiaozhi.modules.externalidentity.ExternalPathPolicy.allowed(method,path+"/draft"));
        }
    }
    @Test void requiresExistingFoundationFlags() {
        var runner=new ApplicationContextRunner().withBean(SupportSummaryService.class,()->mock(SupportSummaryService.class)).withBean(ConversationService.class,this::parser).withUserConfiguration(SupportSummaryController.class);
        runner.withPropertyValues("features.conversation-enabled=true","features.support-handoff-enabled=false").run(c->assertTrue(c.getBeansOfType(SupportSummaryController.class).isEmpty()));
        runner.withPropertyValues("features.conversation-enabled=true","features.support-handoff-enabled=true").run(c->assertEquals(1,c.getBeansOfType(SupportSummaryController.class).size()));
    }
    @Test void usesAuthenticatedIdentityRejectsSpoofedEditorAndMapsResourceErrors()throws Exception {
        var service=mock(SupportSummaryService.class);var mvc=MockMvcBuilders.standaloneSetup(new SupportSummaryController(service,parser())).setControllerAdvice(new SupportSummaryExceptionHandler()).build();
        try(var identity=mockStatic(SecurityUser.class)) {
            identity.when(SecurityUser::getUserId).thenReturn(3L);
            when(service.history(3L,"c1",0,20)).thenReturn(List.of());
            mvc.perform(get("/api/v1/support/conversations/c1/summary?actor=4")).andExpect(status().isOk());verify(service).history(3L,"c1",0,20);
            mvc.perform(put("/api/v1/support/conversations/c1/summary").contentType(MediaType.APPLICATION_JSON).content("{\"base_version\":1,\"fields\":{},\"edited_by\":4}")).andExpect(status().isBadRequest());
            verify(service,never()).edit(anyLong(),anyString(),any());
            when(service.history(3L,"c1",0,20)).thenThrow(new ConversationProblem(404,"SUPPORT_SUMMARY_NOT_FOUND"));
            mvc.perform(get("/api/v1/support/conversations/c1/summary")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
            mvc.perform(get("/api/v1/support/conversations/c1/summary?before=bad")).andExpect(status().isBadRequest());
        }
    }
}
