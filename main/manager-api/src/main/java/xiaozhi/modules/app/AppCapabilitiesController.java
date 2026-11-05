package xiaozhi.modules.app;

import java.util.List;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.conversation.ConversationProblem;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.supportmedia.livekit.LiveKitAdapter;

/** Public contract, not operational readiness or provider configuration. */
@RestController
public class AppCapabilitiesController {
    private final Environment env;
    public AppCapabilitiesController(Environment env){this.env=env;}
    private boolean flag(String name){return env.getProperty("features."+name,Boolean.class,false);}
    @GetMapping("/api/v1/app/capabilities")
    public Result<Map<String,Object>> capabilities(){
        if(SecurityUser.getUserId()==null)throw new ConversationProblem(401,"AUTHENTICATION_REQUIRED");
        boolean conversation=flag("conversation-enabled"),support=conversation&&flag("support-handoff-enabled");
        boolean realtime=conversation&&flag("realtime-ai-enabled");
        Map<String,Object> features=new java.util.LinkedHashMap<>(Map.of(
                "conversations",conversation,"ai_text",conversation&&flag("ai-text-enabled"),"attachments",conversation&&flag("attachments-enabled"),
                "annotations",support&&flag("annotations-enabled"),
                "human_support",support,"support_media",support&&flag("support-media-enabled"),
                "realtime_ai",realtime,"realtime_server_vad",realtime&&flag("realtime-server-vad-enabled"),
                "external_identity",flag("external-identity-enabled"),
                "external_realtime_media",flag("external-identity-enabled")&&flag("external-realtime-media-enabled")&&(realtime || (support&&flag("support-media-enabled")))));
        features.put("incoming_support_calls",support&&flag("support-media-enabled")
                &&flag("external-identity-enabled")&&flag("external-realtime-media-enabled")
                &&LiveKitAdapter.securePublicUrl(env.getProperty("support-media.livekit.public-url","")));
        features.put("agent_text_tools",conversation&&flag("ai-text-enabled")&&flag("agent-tools-enabled"));
        features.put("realtime_materials",realtime&&flag("attachments-enabled")&&flag("agent-tools-enabled")&&flag("realtime-tools-enabled")&&flag("realtime-materials-enabled"));
        features.put("answer_speech",conversation&&flag("answer-speech-enabled"));
        features.put("ai_skills",conversation&&flag("ai-text-enabled")&&flag("agent-tools-enabled")&&flag("ai-context-enabled")&&flag("ai-skills-enabled"));
        features.put("ai_runtime_control",conversation&&env.getProperty("ai.runtime-control.enabled",Boolean.class,false));
        features.put("external_token_renewal",flag("external-identity-enabled")&&env.getProperty("external-identity.renewal-enabled",Boolean.class,false));
        return new Result<Map<String,Object>>().ok(Map.of(
            "contract_version","1.0", "features",features,
            "interaction",new AppInteractionCapabilities(env).value(),
            "limits",Map.of("pagination_default",50,"pagination_max",100,"support_end_reason_max_chars",500),
            "errors",Map.of("authentication_required",401,"forbidden",403,"not_found",404,"conflict",409,"rate_limited",429,"unavailable",503),
            "notes",List.of("Feature flags indicate configuration, not service readiness.")));
    }
}
