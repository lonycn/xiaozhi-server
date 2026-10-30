package xiaozhi.modules.annotation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.annotation.AnnotationModels.*;
import xiaozhi.modules.conversation.ConversationModels.CursorPage;
import xiaozhi.modules.conversation.ConversationService;
import xiaozhi.modules.security.user.SecurityUser;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/support")
@ConditionalOnProperty(prefix="features",name={"conversation-enabled","attachments-enabled","annotations-enabled"},havingValue="true")
public class AnnotationController {
    private final AnnotationService service;
    private final ConversationService requests;
    @PostMapping("/frames") public Result<Frame> createFrame(@RequestBody JsonNode body){return new Result<Frame>().ok(service.createFrame(SecurityUser.getUserId(),requests.request(body,CreateFrame.class)));}
    @GetMapping("/frames/{id}") public Result<Frame> frame(@PathVariable String id){return new Result<Frame>().ok(service.getFrame(SecurityUser.getUserId(),id));}
    @PostMapping("/frames/{id}/retry") public Result<Frame> retryFrame(@PathVariable String id,@RequestBody JsonNode body){requests.request(body,Empty.class);return new Result<Frame>().ok(service.retryFrame(SecurityUser.getUserId(),id));}
    @PostMapping("/annotations") public Result<Annotation> createAnnotation(@RequestBody JsonNode body){return new Result<Annotation>().ok(service.createAnnotation(SecurityUser.getUserId(),requests.request(body,CreateAnnotation.class)));}
    @GetMapping("/annotations/{id}") public Result<Annotation> annotation(@PathVariable String id){return new Result<Annotation>().ok(service.getAnnotation(SecurityUser.getUserId(),id));}
    @GetMapping("/annotations") public Result<CursorPage<Annotation>> list(@RequestParam("conversation_id") String conversation,@RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="50") int limit){return new Result<CursorPage<Annotation>>().ok(service.list(SecurityUser.getUserId(),conversation,after,limit));}
    @PostMapping("/annotations/{id}/retry") public Result<Annotation> retryAnnotation(@PathVariable String id,@RequestBody JsonNode body){requests.request(body,Empty.class);return new Result<Annotation>().ok(service.retryAnnotation(SecurityUser.getUserId(),id));}
}
