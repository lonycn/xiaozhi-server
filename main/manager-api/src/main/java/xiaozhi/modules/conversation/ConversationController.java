package xiaozhi.modules.conversation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.databind.JsonNode;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.conversation.ConversationModels.*;
import xiaozhi.modules.security.user.SecurityUser;

@RestController
@RequiredArgsConstructor
@Tag(name = "统一持久对话（P1）")
@RequestMapping("/api/v1/ai/conversations")
@ConditionalOnProperty(prefix = "features", name = "conversation-enabled", havingValue = "true")
public class ConversationController {
    private final ConversationService service;

    @PostMapping
    public Result<Conversation> create(@RequestBody JsonNode body) {
        return new Result<Conversation>().ok(service.create(SecurityUser.getUserId(), service.request(body, CreateConversation.class)));
    }

    @GetMapping
    public Result<CursorPage<Conversation>> list(@RequestParam(defaultValue = "0") long before,
            @RequestParam(defaultValue = "50") int limit) {
        return new Result<CursorPage<Conversation>>().ok(service.list(SecurityUser.getUserId(), before, limit));
    }

    @GetMapping("/{id}")
    public Result<Conversation> get(@PathVariable String id) {
        return new Result<Conversation>().ok(service.get(SecurityUser.getUserId(), id));
    }

    @PostMapping("/{id}/messages")
    public Result<Message> send(@PathVariable String id, @RequestBody JsonNode body) {
        return new Result<Message>().ok(service.send(SecurityUser.getUserId(), id, service.request(body, SendMessage.class)));
    }

    @GetMapping("/{id}/messages")
    public Result<CursorPage<Message>> messages(@PathVariable String id,
            @RequestParam(defaultValue = "0") long after, @RequestParam(defaultValue = "50") int limit) {
        return new Result<CursorPage<Message>>().ok(service.messages(SecurityUser.getUserId(), id, after, limit));
    }

    @GetMapping("/{id}/events")
    public Result<CursorPage<Event>> events(@PathVariable String id,
            @RequestParam(defaultValue = "0") long after, @RequestParam(defaultValue = "50") int limit) {
        return new Result<CursorPage<Event>>().ok(service.events(SecurityUser.getUserId(), id, after, limit));
    }
}
