package xiaozhi.modules.app;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import xiaozhi.modules.externalidentity.ExternalPathPolicy;

class AiAnswerDeliveryPolicyTest {
    @Test void speechAndSkillRoutesAreExactUserRoutesWithoutWorkerAuthorityOrQueries() {
        String c="/api/v1/ai/conversations/"+"a".repeat(32),id="b".repeat(32);
        for(String p:List.of(c+"/speech/"+id,c+"/speech/"+id+"/audio",c+"/skill","/api/v1/ai/skills","/api/v1/ai/runtime-control")) {
            assertTrue(ExternalPathPolicy.allowed("GET",p),p);
            assertEquals(Set.of(),ExternalPathPolicy.queryKeys("GET",p));
            assertFalse(ExternalPathPolicy.mediaAllowed("GET",p));
            assertFalse(ExternalPathPolicy.allowed("GET",p+"/child"));
        }
        for(String p:List.of(c+"/messages/"+id+"/speech",c+"/speech/"+id+"/cancel",c+"/skill")) {
            assertTrue(ExternalPathPolicy.allowed("POST",p));
            assertFalse(ExternalPathPolicy.allowed("DELETE",p));
            assertFalse(ExternalPathPolicy.allowed("PUT",p));
        }
        assertFalse(ExternalPathPolicy.allowed("POST","/internal/answer-speech-worker/claim"));
        assertFalse(ExternalPathPolicy.allowed("PUT","/internal/answer-speech-worker/jobs/"+id+"/audio"));
        assertFalse(ExternalPathPolicy.allowed("POST","/api/v1/ai/runtime-control"));
        assertFalse(ExternalPathPolicy.allowed("GET","/api/v1/ai/runtime-control/admin"));
        assertFalse(ExternalPathPolicy.allowed("GET","/api/v1/ai/runtime-control%2fadmin"));
    }
    @Test void dedicatedWorkerKeyRejectsUserAndOtherWorkerCredentials() throws Exception {
        var filter=new xiaozhi.modules.answerspeech.AnswerSpeechWorkerKeyFilter("s".repeat(64));
        for(String wrong:List.of("", "a".repeat(64))) {
            var request=new MockHttpServletRequest("POST","/internal/answer-speech-worker/claim");
            request.addHeader("Authorization","Bearer "+"s".repeat(64));
            request.addHeader("X-AI-Worker-Key","s".repeat(64));
            request.addHeader("X-Answer-Speech-Worker-Key",wrong);
            var response=new MockHttpServletResponse();
            assertFalse(filter.onPreHandle(request,response,null));
            assertEquals(401,response.getStatus());
        }
        var request=new MockHttpServletRequest("POST","/internal/answer-speech-worker/claim");
        request.addHeader("X-Answer-Speech-Worker-Key","s".repeat(64));
        assertTrue(filter.onPreHandle(request,new MockHttpServletResponse(),null));
    }
    @Test void binarySpeechBodyIsNotRewrittenAndRejectedQueriesAreNeverBuffered() throws Exception {
        var props=new xiaozhi.common.xss.XssProperties();props.setEnabled(true);
        var filter=new xiaozhi.common.xss.XssFilter(props,new org.springframework.util.AntPathMatcher());
        String p="/internal/answer-speech-worker/jobs/"+"a".repeat(32)+"/audio";
        byte[] bytes={0,-1,12,60,62,38,0};
        var request=new MockHttpServletRequest("PUT",p);request.setContentType("audio/wav");request.setContent(bytes);
        filter.doFilter(request,new MockHttpServletResponse(),(actual,response)->assertArrayEquals(bytes,actual.getInputStream().readAllBytes()));
        var rejected=new MockHttpServletRequest("PUT",p){@Override public jakarta.servlet.ServletInputStream getInputStream(){throw new AssertionError("Rejected body was read");}};
        rejected.setQueryString("token=not-accepted");var response=new MockHttpServletResponse();
        filter.doFilter(rejected,response,(a,b)->fail());assertEquals(400,response.getStatus());
    }
    @Test void historicAnswerSpeechAndOrdinarySkillsHaveSeparateConfigurationDependencies() {
        var env=new MockEnvironment().withProperty("features.conversation-enabled","true")
            .withProperty("features.answer-speech-enabled","true").withProperty("features.ai-skills-enabled","true");
        Map<?,?> c=(Map<?,?>)new AppInteractionCapabilities(env).value().get("capabilities");
        assertEquals("verification_required",((Map<?,?>)c.get("answer_tts")).get("status"));
        assertEquals("disabled",((Map<?,?>)c.get("readonly_skills")).get("status"));
        env.withProperty("features.ai-text-enabled","true").withProperty("features.agent-tools-enabled","true").withProperty("features.ai-context-enabled","true");
        var value=new AppInteractionCapabilities(env).value();c=(Map<?,?>)value.get("capabilities");
        assertEquals("configured",((Map<?,?>)c.get("readonly_skills")).get("status"));
        assertEquals("ordinary_run_only",((Map<?,?>)value.get("readonly_skills")).get("execution_scope"));
        assertEquals("provider_characters",((Map<?,?>)value.get("answer_speech")).get("usage_unit"));
        assertEquals("disabled",((Map<?,?>)c.get("runtime_admission")).get("status"));
        env.withProperty("ai.runtime-control.enabled","true");
        c=(Map<?,?>)new AppInteractionCapabilities(env).value().get("capabilities");
        assertEquals("configured",((Map<?,?>)c.get("runtime_admission")).get("status"));
    }
}
