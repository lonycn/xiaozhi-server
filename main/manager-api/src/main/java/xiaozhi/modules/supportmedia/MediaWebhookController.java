package xiaozhi.modules.supportmedia;

import static xiaozhi.modules.supportmedia.MediaStore.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.supportmedia.livekit.LiveKitAdapter;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(prefix="features",name={"conversation-enabled","support-handoff-enabled","support-media-enabled"},havingValue="true")
public class MediaWebhookController {
    private final LiveKitAdapter livekit;private final MediaStore store;private final MediaPolicy policy;private final MediaService service;
    @PostMapping(value="/internal/livekit/webhook",consumes={"application/webhook+json","application/json"})
    public Result<?> receive(jakarta.servlet.http.HttpServletRequest request)throws java.io.IOException {
        final int maximum=1<<20;if(request.getContentLengthLong()>maximum)throw problem(413,"MEDIA_WEBHOOK_SIZE_LIMIT");
        byte[] raw=request.getInputStream().readNBytes(maximum+1);if(raw.length>maximum)throw problem(413,"MEDIA_WEBHOOK_SIZE_LIMIT");
        return webhook(raw,request.getHeader("Authorization"));
    }
    /** Bounded, already-read raw bytes. This helper is not independently routed. */
    public Result<?> webhook(byte[] raw,String authorization){
        JsonNode event=livekit.verifyWebhook(raw,authorization);String id=event.path("id").asText(),type=event.path("event").asText(),room=event.path("room").path("name").asText(),identity=event.path("participant").path("identity").asText(),sid=event.path("participant").path("sid").asText(),roomSid=event.path("room").path("sid").asText();
        if(!id.matches("[A-Za-z0-9_-]{1,128}") || !type.matches("[a-z_]{1,64}"))throw problem(400,"INVALID_MEDIA_WEBHOOK");String digest=MediaService.hash(new String(raw,StandardCharsets.UTF_8));
        store.tx(()->{var known=store.rows("SELECT * FROM support_media_room WHERE room_name=?",room);Map<String,Object> s=null,r=null;if(!known.isEmpty()){r=known.getFirst();s=policy.lock(number(r,"session_id"),null);r=store.room(number(r,"id"),true);}
            var existing=store.rows("SELECT body_hash FROM support_media_webhook WHERE event_id=? FOR UPDATE",id);if(!existing.isEmpty()){if(!digest.equals(string(existing.getFirst(),"body_hash")))throw problem(409,"MEDIA_WEBHOOK_ID_REUSED");return null;}
            String result="foreign_ignored";
            if(r!=null){boolean stale=!roomSid.isEmpty() && r.get("livekit_sid")!=null && !roomSid.equals(string(r,"livekit_sid"));result=stale?"stale_ignored":"observed";
                if(!stale){var members=store.members(number(r,"id"));var member=members.stream().filter(m->identity.equals(string(m,"identity"))).findFirst().orElse(null);
                    if(type.equals("participant_joined") && identity.matches("[A-Za-z0-9_-]{1,64}") && (member==null || "revoked".equals(string(member,"status")) || Set.of("retired","cleanup_pending","cleaned").contains(string(r,"status"))))service.queue(s,r,null,"remove_participant",identity,true);
                    if(type.equals("participant_left") && member!=null && sid.equals(string(member,"participant_sid")))store.jdbc.update("UPDATE support_media_member SET ready_at=NULL,receiving_audio=0,receiving_video=0 WHERE id=?",member.get("id"));
                    if(type.equals("room_finished") && !Set.of("retired","cleanup_pending","cleaned").contains(string(r,"status")))store.jdbc.update("UPDATE support_media_member SET ready_at=NULL,receiving_audio=0,receiving_video=0 WHERE room_id=?",r.get("id"));
                }
            }
            store.jdbc.update("INSERT INTO support_media_webhook(event_id,body_hash,session_id,room_id,event_type,result) VALUES(?,?,?,?,?,?)",id,digest,s==null?null:s.get("id"),r==null?null:r.get("id"),type,result);return null;
        });return new Result<>().ok(Map.of("accepted",true));
    }
}
