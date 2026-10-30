package xiaozhi.modules.support;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.conversation.ConversationModels.*;
import xiaozhi.modules.conversation.ConversationService;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.support.SupportModels.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix="features", name={"conversation-enabled","support-handoff-enabled"}, havingValue="true")
public class SupportController {
    private final SupportService service;
    private final ConversationService conversations;

    @PutMapping("/support/agents/{userId}")
    @RequiresPermissions("support:agents:manage")
    public Result<Agent> agent(@PathVariable Long userId, @RequestBody JsonNode body) {
        return ok(service.upsertAgent(SecurityUser.getUserId(),userId,conversations.request(body,AgentUpdate.class)));
    }
    @PostMapping("/support/presence")
    @RequiresPermissions("support:presence:write")
    public Result<Agent> presence(@RequestBody JsonNode body) {
        return ok(service.presence(SecurityUser.getUserId(), conversations.request(body,PresenceUpdate.class)));
    }
    @PostMapping("/ai/conversations/{id}/handoff")
    public Result<Request> handoff(@PathVariable String id, @RequestBody JsonNode body) {
        return ok(service.handoff(SecurityUser.getUserId(),id,conversations.request(body,Handoff.class)));
    }
    @GetMapping("/support/queue")
    @RequiresPermissions("support:queue:read")
    public Result<CursorPage<Request>> queue(@RequestParam(defaultValue="0") long before,
                                             @RequestParam(defaultValue="50") int limit) {
        return ok(service.queue(SecurityUser.getUserId(),before,limit));
    }
    @GetMapping("/support/requests/{id}")
    public Result<Request> get(@PathVariable String id) { return ok(service.get(SecurityUser.getUserId(),id)); }
    @PostMapping("/support/requests/{id}/claim")
    @RequiresPermissions("support:request:claim")
    public Result<Request> claim(@PathVariable String id, @RequestBody(required=false) JsonNode body) {
        empty(body);
        return ok(service.claim(SecurityUser.getUserId(),id));
    }
    @PostMapping("/support/requests/{id}/messages")
    @RequiresPermissions("support:message:send")
    public Result<Message> send(@PathVariable String id, @RequestBody JsonNode body) {
        return ok(service.send(SecurityUser.getUserId(),id,conversations.request(body,SendMessage.class)));
    }
    @PostMapping("/support/requests/{id}/resolve")
    @RequiresPermissions("support:request:resolve")
    public Result<Request> resolve(@PathVariable String id, @RequestBody(required=false) JsonNode body) {
        return ok(service.resolve(SecurityUser.getUserId(),id,end(body)));
    }
    @PostMapping("/support/requests/{id}/cancel")
    public Result<Request> cancel(@PathVariable String id, @RequestBody(required=false) JsonNode body) {
        return ok(service.cancel(SecurityUser.getUserId(),id,end(body)));
    }
    @PostMapping("/support/requests/{id}/requeue")
    @RequiresPermissions(value={"support:request:resolve","support:agents:manage"},logical=org.apache.shiro.authz.annotation.Logical.OR)
    public Result<Request> requeue(@PathVariable String id, @RequestBody(required=false) JsonNode body) {
        return ok(service.requeue(SecurityUser.getUserId(),id,end(body),SecurityUtils.getSubject().isPermitted("support:agents:manage")));
    }
    @PostMapping("/ai/conversations/{id}/resume-ai")
    public Result<Conversation> resume(@PathVariable String id, @RequestBody(required=false) JsonNode body) {
        empty(body);
        return ok(service.resumeAi(SecurityUser.getUserId(),id));
    }
    private EndRequest end(JsonNode body) { return body == null ? null : conversations.request(body,EndRequest.class); }
    private void empty(JsonNode body) { if(body != null) conversations.request(body,EmptyRequest.class); }
    public record EmptyRequest() { }
    private static <T> Result<T> ok(T data) { return new Result<T>().ok(data); }
}
