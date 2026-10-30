package xiaozhi.modules.conversation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import xiaozhi.modules.conversation.ConversationModels.SendMessage;
import xiaozhi.modules.security.user.SecurityUser;

class ConversationControllerTest {
    @Test
    void routesAreRegisteredOnlyWhenExplicitlyEnabled() {
        var runner = new ApplicationContextRunner().withBean(ConversationService.class, () -> mock(ConversationService.class))
                .withUserConfiguration(ConversationController.class);
        runner.run(context -> assertTrue(context.getBeansOfType(ConversationController.class).isEmpty()));
        runner.withPropertyValues("features.conversation-enabled=true")
                .run(context -> assertEquals(1, context.getBeansOfType(ConversationController.class).size()));
    }

    @Test
    void returnsRealHttpStatusAndStandardResultEnvelope() throws Exception {
        var service = mock(ConversationService.class);
        when(service.get(any(), anyString())).thenThrow(new ConversationProblem(404, "CONVERSATION_NOT_FOUND"));
        var mvc = MockMvcBuilders.standaloneSetup(new ConversationController(service))
                .setControllerAdvice(new ConversationExceptionHandler()).build();
        mvc.perform(get("/api/v1/ai/conversations/another-user"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.msg").value("CONVERSATION_NOT_FOUND"));
        mvc.perform(get("/api/v1/ai/conversations?limit=abc"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        mvc.perform(post("/api/v1/ai/conversations").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void senderIdentityComesFromAuthenticatedSession() throws Exception {
        var service = mock(ConversationService.class);
        var request = new SendMessage("client", java.util.List.of(ConversationServiceTest.text("test")));
        when(service.request(any(), eq(SendMessage.class))).thenReturn(request);
        var mvc = MockMvcBuilders.standaloneSetup(new ConversationController(service)).build();
        try (var security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUserId).thenReturn(7L);
            mvc.perform(post("/api/v1/ai/conversations/abc/messages")
                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
            verify(service).send(7L, "abc", request);
        }
    }
}
