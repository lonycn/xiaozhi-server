package xiaozhi.modules.support;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import xiaozhi.modules.conversation.ConversationDao;
import xiaozhi.modules.conversation.ConversationProblem;
import xiaozhi.modules.conversation.ConversationService;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.support.SupportModels.Handoff;

class SupportControllerTest {
    private ConversationService parser() {
        return new ConversationService(mock(ConversationDao.class), new ObjectMapper(),
                mock(PlatformTransactionManager.class));
    }

    @Test
    void requiresBothFeatureFlags() {
        var runner = new ApplicationContextRunner()
                .withBean(SupportService.class, () -> mock(SupportService.class))
                .withBean(ConversationService.class, this::parser)
                .withUserConfiguration(SupportController.class)
                .withPropertyValues("features.conversation-enabled=false", "features.support-handoff-enabled=false");
        runner.run(c -> assertTrue(c.getBeansOfType(SupportController.class).isEmpty()));
        runner.withPropertyValues("features.conversation-enabled=true")
                .run(c -> assertTrue(c.getBeansOfType(SupportController.class).isEmpty()));
        runner.withPropertyValues("features.support-handoff-enabled=true")
                .run(c -> assertTrue(c.getBeansOfType(SupportController.class).isEmpty()));
        runner.withPropertyValues("features.conversation-enabled=true", "features.support-handoff-enabled=true")
                .run(c -> assertEquals(1, c.getBeansOfType(SupportController.class).size()));
    }

    @Test
    void rejectsSpoofedFieldsAndUsesAuthenticatedIdentity() throws Exception {
        var service = mock(SupportService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new SupportController(service, parser()))
                .setControllerAdvice(new SupportExceptionHandler()).build();
        try (var security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUserId).thenReturn(7L);
            mvc.perform(post("/api/v1/ai/conversations/abc/handoff").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"client_request_id\":\"key\",\"requested_mode\":\"text\",\"owner_user_id\":9}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
            verifyNoInteractions(service);
            mvc.perform(post("/api/v1/ai/conversations/abc/handoff").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"client_request_id\":\"key\",\"requested_mode\":\"text\",\"target_agent_user_id\":null}"))
                    .andExpect(status().isOk());
            verify(service).handoff(eq(7L), eq("abc"), eq(new Handoff("key", "text", null)));
        }
    }

    @Test
    void mapsServiceFailuresToHttpStatuses() throws Exception {
        var service = mock(SupportService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new SupportController(service, parser()))
                .setControllerAdvice(new SupportExceptionHandler()).build();
        try (var security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUserId).thenReturn(7L);
            for (int code : new int[] {403, 404, 409}) {
                doThrow(new ConversationProblem(code, "TEST_ERROR")).when(service).get(7L, "id");
                mvc.perform(get("/api/v1/support/requests/id"))
                        .andExpect(status().is(code)).andExpect(jsonPath("$.code").value(code));
            }
            mvc.perform(get("/api/v1/support/queue?limit=bad"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        }
    }
}
