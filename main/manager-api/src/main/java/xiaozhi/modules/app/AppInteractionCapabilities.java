package xiaozhi.modules.app;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.env.Environment;
import xiaozhi.modules.attachment.AttachmentRules;

/** Static protocol bounds and configured feature gates; no provider readiness inference. */
final class AppInteractionCapabilities {
    private final Environment env;
    AppInteractionCapabilities(Environment env){this.env=env;}
    private boolean flag(String name){return env.getProperty("features."+name,Boolean.class,false);}
    private boolean configured(String property,String fallback){return !env.getProperty(property,fallback).isBlank();}
    private static Map<String,String> capability(String status,String implementation){return Map.of("status",status,"implementation",implementation);}
    private static String dependent(boolean enabled,boolean configured){return !enabled?"disabled":configured?"verification_required":"unconfigured";}
    Map<String,Object> value(){
        boolean ledger=flag("conversation-enabled"),ai=ledger&&flag("ai-text-enabled");
        boolean attachment=ledger&&flag("attachments-enabled"),realtime=ledger&&flag("realtime-ai-enabled");
        boolean vad=realtime&&flag("realtime-server-vad-enabled");
        Map<String,Object> matrix=new LinkedHashMap<>();
        matrix.put("conversation_ledger",capability(ledger?"configured":"disabled","implemented"));
        matrix.put("text_ai",capability(dependent(ai,configured("ai.execution.model","qwen-flash")),"implemented"));
        matrix.put("attachments",capability(attachment?"verification_required":"disabled","implemented"));
        matrix.put("image_understanding",capability(dependent(ai&&attachment,configured("ai.execution.vision-model","")),"implemented"));
        matrix.put("raw_audio_understanding",capability(dependent(ai&&attachment,configured("ai.execution.omni-model","")),"implemented"));
        matrix.put("audio_transcription",capability(attachment&&env.getProperty("media.transcription-enabled",Boolean.class,false)?"verification_required":"disabled","implemented"));
        matrix.put("text_agent_tools",capability(ai&&flag("agent-tools-enabled")?"verification_required":"disabled","implemented"));
        matrix.put("realtime_audio",capability(realtime?"verification_required":"disabled","implemented"));
        matrix.put("realtime_server_vad",capability(vad?"verification_required":"disabled","implemented"));
        matrix.put("typed_tool_results",capability(ai&&flag("agent-tools-enabled")&&flag("agent-tool-results-enabled")?"configured":"disabled","implemented"));
        matrix.put("multimodal_agent_tools",capability(dependent(ai&&attachment&&flag("agent-tools-enabled")&&env.getProperty("ai.execution.multimodal-tools-enabled",Boolean.class,false),
                configured("ai.execution.vision-model","")||configured("ai.execution.omni-model","")),"implemented"));
        matrix.put("realtime_agent_tools",capability(realtime&&flag("agent-tools-enabled")&&flag("realtime-tools-enabled")?"verification_required":"disabled","implemented"));
        matrix.put("realtime_materials",capability(realtime&&attachment&&flag("agent-tools-enabled")&&flag("realtime-tools-enabled")&&flag("realtime-materials-enabled")?"verification_required":"disabled","implemented"));
        matrix.put("ai_context_memory",capability(ledger&&flag("ai-context-enabled")?"configured":"disabled","implemented"));
        matrix.put("answer_tts",capability(ledger&&flag("answer-speech-enabled")?"verification_required":"disabled","implemented"));
        matrix.put("readonly_skills",capability(ai&&flag("agent-tools-enabled")&&flag("ai-context-enabled")&&flag("ai-skills-enabled")?"configured":"disabled","implemented"));
        matrix.put("runtime_admission",capability(ledger&&env.getProperty("ai.runtime-control.enabled",Boolean.class,false)?"configured":"disabled","implemented"));
        Map<String,Object> types=Map.of(
            "image",Map.of("mime_types",List.of("image/jpeg","image/png","image/webp"),"max_bytes",(int)AttachmentRules.max("image/jpeg")),
            "document",Map.of("mime_types",List.of("application/pdf","text/plain"),"max_bytes",(int)AttachmentRules.max("application/pdf")),
            "audio",Map.of("mime_types",List.of("audio/wav","audio/mpeg","audio/mp4","audio/ogg"),"max_bytes",(int)AttachmentRules.max("audio/wav"),"max_duration_seconds",600),
            "video",Map.of("mime_types",List.of("video/mp4","video/webm"),"max_bytes",(int)AttachmentRules.max("video/mp4"),"max_duration_seconds",180));
        return Map.of("protocol_version","1.0","capabilities",matrix,
            "attachment_inputs",types,
            "realtime",Map.of("turn_detection_modes",vad?List.of("manual","server_vad"):List.of("manual"),
                "default_turn_detection_mode","manual","input_audio",Map.of("encoding","s16le","sample_rate_hz",16000,"channels",1),
                "output_audio",Map.of("encoding","s16le","sample_rate_hz",24000,"channels",1),
                "max_audio_chunk_bytes",65536,"max_audio_turn_bytes",1920000,"max_frame_bytes",190000,
                "max_frame_pixels",921600,"min_frame_interval_ms",1000),
            "realtime_materials",Map.of("mode","pause_clear_then_context_ack","allowed_parts",List.of("text","image","file"),
                "max_parts",3,"max_attachments",2,"max_message_text_chars",4000,"max_context_text_chars",12000,
                "max_image_bytes",1048576,"max_materials_per_session",6,"max_images_per_session",12,
                "max_known_perception_tokens",20000),
            "answer_speech",Map.of("max_text_chars",8000,"max_audio_bytes",5242880,"max_duration_seconds",120,
                "max_active_jobs_per_user",1,"max_jobs_per_hour",10,"mime_type","audio/wav","authorization_ttl_seconds",5,
                "usage_unit","provider_characters"),
            "readonly_skills",Map.of("execution_scope","ordinary_run_only","client_prompts_allowed",false,"business_writes_allowed",false),
            "availability_basis","configuration_only");
    }
}
