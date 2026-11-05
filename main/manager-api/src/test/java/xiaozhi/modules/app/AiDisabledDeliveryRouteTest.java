package xiaozhi.modules.app;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.fasterxml.jackson.databind.ObjectMapper;
import xiaozhi.modules.answerspeech.*;
import xiaozhi.modules.aiskill.*;
import xiaozhi.modules.realtimeinput.*;
import xiaozhi.modules.airuntime.*;
import xiaozhi.modules.conversation.*;
import xiaozhi.modules.security.user.SecurityUser;

class AiDisabledDeliveryRouteTest {
    @Test void disabledFeaturesRetainTypedHandlersInsteadOfFallingIntoLegacyHttp200Errors() {
        var speech=mock(AnswerSpeechService.class);var skills=mock(AiSkillService.class);var materials=mock(RealtimeInputService.class);var runtime=mock(AiRuntimeControl.class);
        when(skills.catalog()).thenThrow(new ConversationProblem(404,"AI_SKILLS_DISABLED"));
        when(speech.get(eq(1L),anyString(),anyString(),isNull()))
                .thenThrow(new ConversationProblem(404,"ANSWER_SPEECH_DISABLED"));
        when(materials.state(eq(1L),anyString())).thenThrow(new ConversationProblem(404,"REALTIME_MATERIALS_DISABLED"));
        when(runtime.snapshot(1L)).thenThrow(new ConversationProblem(404,"AI_RUNTIME_CONTROL_DISABLED"));
        new ApplicationContextRunner().withPropertyValues("features.conversation-enabled=true",
                "features.ai-skills-enabled=false","features.answer-speech-enabled=false","features.realtime-materials-enabled=false","ai.runtime-control.enabled=false")
            .withBean(ObjectMapper.class,ObjectMapper::new)
            .withBean(AnswerSpeechService.class,()->speech).withBean(AiSkillService.class,()->skills).withBean(RealtimeInputService.class,()->materials)
            .withBean(AiRuntimeControl.class,()->runtime)
            .withUserConfiguration(AnswerSpeechController.class,AiSkillController.class,RealtimeInputController.class,AiRuntimeControlController.class).run(context->{
                assertNotNull(context.getBean(AiSkillController.class));
                assertNotNull(context.getBean(AnswerSpeechController.class));
                assertNotNull(context.getBean(RealtimeInputController.class));
                var mvc=MockMvcBuilders.standaloneSetup(context.getBean(AiSkillController.class),context.getBean(AnswerSpeechController.class),context.getBean(RealtimeInputController.class),context.getBean(AiRuntimeControlController.class))
                        .setControllerAdvice(new ConversationExceptionHandler(),new xiaozhi.modules.realtime.RealtimeExceptionHandler()).build();
                try(var security=mockStatic(SecurityUser.class)){
                    security.when(SecurityUser::getUserId).thenReturn(1L);
                    mvc.perform(get("/api/v1/ai/skills")).andExpect(status().isNotFound())
                            .andExpect(jsonPath("$.msg").value("AI_SKILLS_DISABLED"));
                    mvc.perform(get("/api/v1/ai/conversations/"+"a".repeat(32)+"/speech/"+"b".repeat(32)))
                            .andExpect(status().isNotFound()).andExpect(jsonPath("$.msg").value("ANSWER_SPEECH_DISABLED"));
                    mvc.perform(get("/api/v1/ai/realtime/sessions/"+"a".repeat(32)+"/input-state"))
                            .andExpect(status().isNotFound()).andExpect(jsonPath("$.msg").value("REALTIME_MATERIALS_DISABLED"));
                    mvc.perform(get("/api/v1/ai/runtime-control"))
                            .andExpect(status().isNotFound()).andExpect(jsonPath("$.msg").value("AI_RUNTIME_CONTROL_DISABLED"));
                }
            });
    }
}
