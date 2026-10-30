package xiaozhi.modules.legacy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import xiaozhi.modules.security.user.SecurityUser;

class LegacyControllerTest {
    @Test void onlyEnabledWithConversationFeature() {
        var runner = new ApplicationContextRunner().withBean(LegacyService.class, () -> mock(LegacyService.class))
                .withUserConfiguration(LegacyController.class);
        runner.run(context -> assertTrue(context.getBeansOfType(LegacyController.class).isEmpty()));
        runner.withPropertyValues("features.conversation-enabled=true")
                .run(context -> assertEquals(1, context.getBeansOfType(LegacyController.class).size()));
    }
    @Test void authenticationIdentityAndHttpErrors() throws Exception {
        var service = mock(LegacyService.class);
        when(service.messages(eq(7L), eq("agent"), eq("other"), eq("0"), eq(100)))
                .thenThrow(new LegacyProblem(404, "SESSION_NOT_FOUND"));
        var mvc = MockMvcBuilders.standaloneSetup(new LegacyController(service)).setControllerAdvice(new LegacyAdvice()).build();
        try (var security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUserId).thenReturn(7L);
            mvc.perform(get("/api/v1/ai/legacy/agents/agent/sessions/other/messages"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.msg").value("SESSION_NOT_FOUND"));
            mvc.perform(get("/api/v1/ai/legacy/agents/agent/sessions?limit=abc"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
            verify(service).messages(7L, "agent", "other", "0", 100);
        }
    }
}
