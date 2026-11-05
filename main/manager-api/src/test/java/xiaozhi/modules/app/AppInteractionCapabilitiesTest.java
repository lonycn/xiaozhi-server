package xiaozhi.modules.app;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import xiaozhi.modules.security.user.SecurityUser;

class AppInteractionCapabilitiesTest {
    private static Map<?,?> map(Object o){return (Map<?,?>)o;}
    @Test void oldFlagsRemainCompatibleAndMissingOptionalFeaturesAreOff(){
        try(var security=mockStatic(SecurityUser.class)){
            security.when(SecurityUser::getUserId).thenReturn(1L);
            var data=new AppCapabilitiesController(new MockEnvironment()).capabilities().getData();
            assertEquals("1.0",data.get("contract_version"));
            var flags=map(data.get("features"));assertEquals(false,flags.get("ai_text"));assertEquals(false,flags.get("external_token_renewal"));
            var interaction=map(data.get("interaction"));var capabilities=map(interaction.get("capabilities"));
            assertEquals("disabled",map(capabilities.get("text_ai")).get("status"));
            assertEquals("implemented",map(capabilities.get("typed_tool_results")).get("implementation"));
            assertEquals("disabled",map(capabilities.get("typed_tool_results")).get("status"));
            assertEquals("implemented",map(capabilities.get("answer_tts")).get("implementation"));
            assertEquals("disabled",map(capabilities.get("answer_tts")).get("status"));
            assertEquals("disabled",map(capabilities.get("realtime_agent_tools")).get("status"));
        }
    }
    @Test void flagsAndConfigurationAreNotConfusedWithProviderReadiness(){
        var env=new MockEnvironment().withProperty("features.conversation-enabled","true").withProperty("features.ai-text-enabled","true")
            .withProperty("features.attachments-enabled","true").withProperty("features.realtime-ai-enabled","true")
            .withProperty("features.realtime-server-vad-enabled","true").withProperty("features.agent-tools-enabled","true");
        var v=new AppInteractionCapabilities(env).value();var c=map(v.get("capabilities"));
        assertEquals("configured",map(c.get("conversation_ledger")).get("status"));
        assertEquals("verification_required",map(c.get("text_ai")).get("status"));
        assertEquals("unconfigured",map(c.get("image_understanding")).get("status"));
        assertEquals("unconfigured",map(c.get("raw_audio_understanding")).get("status"));
        assertEquals("verification_required",map(c.get("text_agent_tools")).get("status"));
        assertEquals(List.of("manual","server_vad"),map(v.get("realtime")).get("turn_detection_modes"));
        assertEquals("disabled",map(c.get("typed_tool_results")).get("status"));
        assertEquals("disabled",map(c.get("ai_context_memory")).get("status"));
        env.withProperty("features.agent-tool-results-enabled","true").withProperty("features.ai-context-enabled","true")
            .withProperty("features.realtime-tools-enabled","true").withProperty("ai.execution.multimodal-tools-enabled","true");
        var gated=new AppInteractionCapabilities(env).value();var gc=map(gated.get("capabilities"));
        assertEquals("configured",map(gc.get("typed_tool_results")).get("status"));
        assertEquals("configured",map(gc.get("ai_context_memory")).get("status"));
        assertEquals("verification_required",map(gc.get("realtime_agent_tools")).get("status"));
        assertEquals("unconfigured",map(gc.get("multimodal_agent_tools")).get("status"));
        env.withProperty("features.agent-tools-enabled","false");
        var off=map(new AppInteractionCapabilities(env).value().get("capabilities"));
        assertEquals("disabled",map(off.get("typed_tool_results")).get("status"));
        assertEquals("disabled",map(off.get("realtime_agent_tools")).get("status"));
        env.withProperty("ai.execution.vision-model","secret-fixture-should-not-be-returned");
        var revised=new AppInteractionCapabilities(env).value();assertEquals("verification_required",map(map(revised.get("capabilities")).get("image_understanding")).get("status"));
        assertFalse(revised.toString().contains("secret-fixture"));
    }
    @Test void foregroundIncomingCallsRequireExternalMediaAndSecurePublicRtcUrl(){
        var env=new MockEnvironment();
        for(String feature:List.of("conversation-enabled","support-handoff-enabled","support-media-enabled",
                "external-identity-enabled","external-realtime-media-enabled")){
            env.withProperty("features."+feature,"true");
        }
        try(var security=mockStatic(SecurityUser.class)){
            security.when(SecurityUser::getUserId).thenReturn(1L);
            var controller=new AppCapabilitiesController(env);
            for(String url:List.of("","ws://rtc.fixture.invalid","wss://","wss://user@rtc.fixture.invalid")){
                env.withProperty("support-media.livekit.public-url",url);
                assertEquals(false,map(controller.capabilities().getData().get("features")).get("incoming_support_calls"));
            }
            env.withProperty("support-media.livekit.public-url","wss://rtc.fixture.invalid");
            assertEquals(true,map(controller.capabilities().getData().get("features")).get("incoming_support_calls"));
            env.withProperty("features.external-realtime-media-enabled","false");
            assertEquals(false,map(controller.capabilities().getData().get("features")).get("incoming_support_calls"));
        }
    }
    @Test void inputBoundsMatchAttachmentRulesAndRealtimeContract(){
        var v=new AppInteractionCapabilities(new MockEnvironment()).value();var inputs=map(v.get("attachment_inputs"));
        assertEquals(20*1024*1024,map(inputs.get("image")).get("max_bytes"));assertEquals(50*1024*1024,map(inputs.get("audio")).get("max_bytes"));
        assertEquals(100*1024*1024,map(inputs.get("video")).get("max_bytes"));assertEquals(180,map(inputs.get("video")).get("max_duration_seconds"));
        var rtc=map(v.get("realtime"));assertEquals(16000,map(rtc.get("input_audio")).get("sample_rate_hz"));assertEquals(24000,map(rtc.get("output_audio")).get("sample_rate_hz"));
        assertEquals(List.of("manual"),rtc.get("turn_detection_modes"));
    }
}
