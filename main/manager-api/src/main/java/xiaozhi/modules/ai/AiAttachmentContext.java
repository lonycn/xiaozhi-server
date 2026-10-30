package xiaozhi.modules.ai;

import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.JsonNode;
import xiaozhi.modules.ai.AiModels.MediaRef;
import xiaozhi.modules.attachment.AttachmentModels.View;
import xiaozhi.modules.attachment.AttachmentService;
import xiaozhi.modules.conversation.ConversationProblem;

/** Only derivatives of current input are eligible; historical originals never enter model context. */
@Component
@ConditionalOnProperty(prefix="features",name={"conversation-enabled","attachments-enabled"},havingValue="true")
public class AiAttachmentContext {
    private final AttachmentService attachments;
    public AiAttachmentContext(AttachmentService attachments) { this.attachments=attachments; }
    public record Plan(List<MediaRef> refs,boolean waiting,boolean vision,String error) { }
    public Plan plan(String conversationId,long inputSeq,JsonNode parts) {
        List<MediaRef> refs=new ArrayList<>();boolean vision=false,waiting=false;
        try {
            for(JsonNode part:parts) {
                if(!part.hasNonNull("attachment_id")) continue;
                String id=part.path("attachment_id").asText(),kind=part.path("type").asText();
                if(!id.matches("[0-9]+")) return unavailable();
                View descriptor=attachments.aiDescriptor(id,conversationId,inputSeq);
                if(!descriptor.kind().equals(kind)) return unavailable();
                Map<String,Object> metadata=descriptor.metadata();
                Object available=metadata.get("artifacts");
                if(!(available instanceof Collection<?> artifacts)) return unavailable();
                if(kind.equals("image")) {
                    vision=true;if(!artifacts.contains("display")) return unavailable();
                    refs.add(new MediaRef(id,"display","image/jpeg",kind,null));
                } else if(kind.equals("file")) {
                    if(!artifacts.contains("text")) return unavailable();
                    refs.add(new MediaRef(id,"text","text/plain",kind,null));
                } else if(kind.equals("video")) {
                    vision=true;
                    if(!(metadata.get("frames") instanceof List<?> frames) || frames.isEmpty() || frames.size()>6) return unavailable();
                    Set<String> unique=new HashSet<>();
                    for(Object frame:frames) {
                        if(!(frame instanceof Map<?,?> item) || !(item.get("artifact") instanceof String variant)
                                || !variant.matches("frame_[0-5]") || !unique.add(variant) || !artifacts.contains(variant)
                                || !(item.get("time_ms") instanceof Number time) || time.longValue()<0 || time.longValue()>180000)
                            return unavailable();
                        refs.add(new MediaRef(id,variant,"image/jpeg",kind,((Number)item.get("time_ms")).longValue()));
                    }
                } else if(!kind.equals("audio")) return unavailable();
                if(kind.equals("audio") || kind.equals("video") && Boolean.TRUE.equals(metadata.get("has_audio"))) {
                    if(artifacts.contains("transcript")) refs.add(new MediaRef(id,"transcript","text/plain",kind,null));
                    else if(descriptor.jobs().stream().anyMatch(job -> job.jobType().equals("audio_transcription")
                            && Set.of("queued","running").contains(job.status()))) waiting=true;
                    else return unavailable();
                }
            }
        } catch(ConversationProblem problem) { return unavailable(); }
        if(refs.size()>16) return unavailable();
        return new Plan(List.copyOf(refs),waiting,vision,null);
    }
    private static Plan unavailable() { return new Plan(List.of(),false,false,"MEDIA_INPUT_UNAVAILABLE"); }
    public AttachmentService.Content content(String attachmentId,String conversationId,long inputSeq,String variant) {
        return attachments.aiContent(attachmentId,conversationId,inputSeq,variant);
    }
}
