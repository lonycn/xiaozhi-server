package xiaozhi.modules.externalidentity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.conversation.ConversationService;
import xiaozhi.modules.supportmedia.CallPresenceService;
import xiaozhi.modules.supportmedia.CallPresenceService.*;

@RestController
@RequestMapping("/api/v1/external/call-presence")
@ConditionalOnProperty(prefix="features",name={"external-identity-enabled","external-realtime-media-enabled","conversation-enabled","support-handoff-enabled","support-media-enabled"},havingValue="true")
public class ExternalCallPresenceController {
    private final ExternalIdentityService identity;
    private final ConversationService requests;
    private final CallPresenceService presence;
    public ExternalCallPresenceController(ExternalIdentityService identity,ConversationService requests,CallPresenceService presence){this.identity=identity;this.requests=requests;this.presence=presence;}
    @PostMapping public Result<Receipt> publish(@RequestBody JsonNode body,HttpServletRequest request,HttpServletResponse response){
        ExternalIdentityController.privateResponse(response);
        return new Result<Receipt>().ok(presence.publish(identity.required(request),requests.request(body,Update.class)));
    }
}
