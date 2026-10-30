package xiaozhi.modules.supportmedia;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.conversation.ConversationService;
import xiaozhi.modules.conversation.ConversationModels.CursorPage;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.supportmedia.MediaModels.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/support")
@ConditionalOnProperty(prefix="features",name={"conversation-enabled","support-handoff-enabled","support-media-enabled"},havingValue="true")
public class MediaController {
    private final MediaService service;private final ConversationService requests;
    @PostMapping("/media-sessions") public Result<Session> create(@RequestBody JsonNode body){return new Result<Session>().ok(service.create(SecurityUser.getUserId(),requests.request(body,Create.class)));}
    @GetMapping("/media-sessions/current") public Result<Session> current(@RequestParam("conversation_id") String conversation){return new Result<Session>().ok(service.current(SecurityUser.getUserId(),conversation));}
    @GetMapping("/media-sessions/{id}") public Result<Session> get(@PathVariable String id){return new Result<Session>().ok(service.get(SecurityUser.getUserId(),id));}
    @GetMapping("/media-invitations") public Result<CursorPage<Invitation>> invitations(@RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="50") int limit){return new Result<CursorPage<Invitation>>().ok(service.invitations(SecurityUser.getUserId(),after,limit));}
    @GetMapping("/media-experts") public Result<?> experts(@RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="50") int limit){return new Result<>().ok(service.experts(SecurityUser.getUserId(),after,limit));}
    @PostMapping("/media-sessions/{id}/accept") public Result<Session> accept(@PathVariable String id,@RequestBody JsonNode body){return new Result<Session>().ok(service.accept(SecurityUser.getUserId(),id,requests.request(body,Accept.class)));}
    @PostMapping("/media-sessions/{id}/reject") public Result<Session> reject(@PathVariable String id,@RequestBody JsonNode body){return new Result<Session>().ok(service.reject(SecurityUser.getUserId(),id,requests.request(body,Epoch.class)));}
    @PostMapping("/media-sessions/{id}/token") public Result<Token> token(@PathVariable String id,@RequestBody JsonNode body){return new Result<Token>().ok(service.token(SecurityUser.getUserId(),id,requests.request(body,TokenRequest.class)));}
    @PostMapping("/media-sessions/{id}/ready") public Result<Session> ready(@PathVariable String id,@RequestBody JsonNode body){return new Result<Session>().ok(service.ready(SecurityUser.getUserId(),id,requests.request(body,Ready.class)));}
    @PostMapping("/media-sessions/{id}/end") public Result<Session> end(@PathVariable String id,@RequestBody JsonNode body){return new Result<Session>().ok(service.end(SecurityUser.getUserId(),id,requests.request(body,End.class)));}
    @PostMapping("/media-sessions/{id}/frames") public Result<xiaozhi.modules.annotation.AnnotationModels.Frame> capture(@PathVariable String id,@RequestBody JsonNode body){return new Result<xiaozhi.modules.annotation.AnnotationModels.Frame>().ok(service.capture(SecurityUser.getUserId(),id,requests.request(body,Capture.class)));}
    @PostMapping("/media-sessions/{id}/transfers") public Result<Transfer> createTransfer(@PathVariable String id,@RequestBody JsonNode body){return new Result<Transfer>().ok(service.createTransfer(SecurityUser.getUserId(),id,requests.request(body,CreateTransfer.class)));}
    @GetMapping("/media-transfers/{id}") public Result<Transfer> transfer(@PathVariable String id){return new Result<Transfer>().ok(service.getTransfer(SecurityUser.getUserId(),id));}
    @PostMapping("/media-transfers/{id}/approve") public Result<Transfer> approve(@PathVariable String id,@RequestBody JsonNode body){return new Result<Transfer>().ok(service.approveTransfer(SecurityUser.getUserId(),id,requests.request(body,Accept.class),false));}
    @PostMapping("/media-transfers/{id}/accept") public Result<Transfer> acceptTransfer(@PathVariable String id,@RequestBody JsonNode body){return new Result<Transfer>().ok(service.approveTransfer(SecurityUser.getUserId(),id,requests.request(body,Accept.class),true));}
    @PostMapping("/media-transfers/{id}/reject") public Result<Transfer> rejectTransfer(@PathVariable String id,@RequestBody JsonNode body){return new Result<Transfer>().ok(service.rejectTransfer(SecurityUser.getUserId(),id,requests.request(body,Epoch.class),false));}
    @PostMapping("/media-transfers/{id}/cancel") public Result<Transfer> cancelTransfer(@PathVariable String id,@RequestBody JsonNode body){return new Result<Transfer>().ok(service.rejectTransfer(SecurityUser.getUserId(),id,requests.request(body,Epoch.class),true));}
}
