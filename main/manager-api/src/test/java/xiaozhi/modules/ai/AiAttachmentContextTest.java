package xiaozhi.modules.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import xiaozhi.modules.attachment.AttachmentModels.*;
import xiaozhi.modules.attachment.AttachmentService;
import xiaozhi.modules.conversation.ConversationProblem;

class AiAttachmentContextTest {
    final ObjectMapper json=new ObjectMapper();
    View view(String kind,Map<String,Object> metadata,List<JobView> jobs) {
        return new View("123","a".repeat(32),"source","text/plain",kind,1,"hash","ready",null,metadata,jobs,Instant.now());
    }
    @Test void twoArgumentContextAndSnakeCaseReferencesRemainCompatible() throws Exception {
        String plain=json.writeValueAsString(new AiModels.ContextMessage("user","old text"));
        assertFalse(plain.contains("attachments"));
        String media=json.writeValueAsString(new AiModels.ContextMessage("user","look",List.of(
            new AiModels.MediaRef("123","frame_0","image/jpeg","video",1500L))));
        assertTrue(media.contains("attachment_id"));assertTrue(media.contains("time_ms"));
        assertFalse(media.contains("storage"));
    }
    @Test void pictureSelectsDisplayAndDocumentSelectsTextEvenWhenBlank() throws Exception {
        var service=mock(AttachmentService.class);var context=new AiAttachmentContext(service);
        when(service.aiDescriptor("123","conversation",4)).thenReturn(view("image",Map.of("artifacts",List.of("display","thumbnail")),List.of()));
        var image=context.plan("conversation",4,json.readTree("[{\"type\":\"image\",\"attachment_id\":\"123\"}]"));
        assertTrue(image.vision());assertFalse(image.waiting());assertNull(image.error());assertEquals("display",image.refs().getFirst().variant());
        when(service.aiDescriptor("123","conversation",4)).thenReturn(view("file",Map.of("artifacts",List.of("text"),"text_available",false),List.of()));
        var file=context.plan("conversation",4,json.readTree("[{\"type\":\"file\",\"attachment_id\":\"123\"}]"));
        assertFalse(file.vision());assertEquals("text",file.refs().getFirst().variant());
    }
    @Test void audioWaitsForTranscriptionAndNeverPretendsMissingTranscriptIsReady() throws Exception {
        var service=mock(AttachmentService.class);var context=new AiAttachmentContext(service);
        var parts=json.readTree("[{\"type\":\"audio\",\"attachment_id\":\"123\"}]");
        for(String state:List.of("queued","running")) {
            when(service.aiDescriptor("123","conversation",4)).thenReturn(view("audio",Map.of("artifacts",List.of("audio")),
                    List.of(new JobView("5","audio_transcription",state,1,null))));
            assertTrue(context.plan("conversation",4,parts).waiting());
        }
        when(service.aiDescriptor("123","conversation",4)).thenReturn(view("audio",Map.of("artifacts",List.of("audio")),List.of()));
        assertEquals("MEDIA_INPUT_UNAVAILABLE",context.plan("conversation",4,parts).error());
        when(service.aiDescriptor("123","conversation",4)).thenReturn(view("audio",Map.of("artifacts",List.of("audio","transcript")),List.of()));
        assertEquals("transcript",context.plan("conversation",4,parts).refs().getFirst().variant());
    }
    @Test void videoFramesHaveTimesAndAudioTranscriptIsRequired() throws Exception {
        var service=mock(AttachmentService.class);var context=new AiAttachmentContext(service);
        Map<String,Object> metadata=new HashMap<>();metadata.put("artifacts",List.of("poster","frame_0","audio","transcript"));
        metadata.put("frames",List.of(Map.of("artifact","frame_0","time_ms",1500)));metadata.put("has_audio",true);
        when(service.aiDescriptor("123","conversation",4)).thenReturn(view("video",metadata,List.of()));
        var parts=json.readTree("[{\"type\":\"video\",\"attachment_id\":\"123\"}]");
        var plan=context.plan("conversation",4,parts);
        assertEquals(List.of("frame_0","transcript"),plan.refs().stream().map(AiModels.MediaRef::variant).toList());
        assertEquals(1500L,plan.refs().getFirst().timeMs());assertTrue(plan.vision());
        metadata.put("frames",List.of(Map.of("artifact","original","time_ms",1)));
        assertEquals("MEDIA_INPUT_UNAVAILABLE",context.plan("conversation",4,parts).error());
    }
    @Test void inaccessibleOrCrossConversationAttachmentFailsClosed() throws Exception {
        var service=mock(AttachmentService.class);var context=new AiAttachmentContext(service);
        when(service.aiDescriptor(anyString(),anyString(),anyLong())).thenThrow(new ConversationProblem(404,"ATTACHMENT_NOT_FOUND"));
        assertEquals("MEDIA_INPUT_UNAVAILABLE",context.plan("conversation",4,
                json.readTree("[{\"type\":\"image\",\"attachment_id\":\"123\"}]")).error());
    }
    @Test void tooManyReferencesFailClosed() throws Exception {
        var service=mock(AttachmentService.class);var context=new AiAttachmentContext(service);
        when(service.aiDescriptor(anyString(),anyString(),anyLong())).thenReturn(view("image",Map.of("artifacts",List.of("display")),List.of()));
        var parts=json.createArrayNode();for(int i=0;i<17;i++)parts.addObject().put("type","image").put("attachment_id","123");
        assertEquals("MEDIA_INPUT_UNAVAILABLE",context.plan("conversation",4,parts).error());
    }
    @Test void explicitOmniUsesRawAudioWithoutAsrAndKeepsVerifiedVideoFrames() throws Exception {
        var service=mock(AttachmentService.class);var context=new AiAttachmentContext(service);
        context.setOmniModel("qwen3.8-omni-flash");
        for(String kind:List.of("audio","video")) {
            Map<String,Object> metadata=new HashMap<>();metadata.put("artifacts",List.of("audio","frame_0"));
            metadata.put("has_audio",true);metadata.put("frames",List.of(Map.of("artifact","frame_0","time_ms",1500)));
            when(service.aiDescriptor("123","conversation",4)).thenReturn(view(kind,metadata,List.of()));
            var parts=json.createArrayNode();parts.addObject().put("type",kind).put("attachment_id","123");
            var plan=context.plan("conversation",4,parts);
            assertTrue(plan.audio());assertFalse(plan.waiting());assertNull(plan.error());
            assertEquals(kind.equals("video")?List.of("frame_0","audio"):List.of("audio"),
                    plan.refs().stream().map(AiModels.MediaRef::variant).toList());
            metadata.put("artifacts",List.of("transcript","frame_0"));
            assertEquals("MEDIA_INPUT_UNAVAILABLE",context.plan("conversation",4,parts).error());
        }
    }

}
