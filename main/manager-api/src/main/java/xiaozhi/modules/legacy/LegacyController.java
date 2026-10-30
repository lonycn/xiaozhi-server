package xiaozhi.modules.legacy;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.legacy.LegacyModels.*;
import xiaozhi.modules.security.user.SecurityUser;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ai/legacy/agents/{agentId}/sessions")
@ConditionalOnProperty(prefix = "features", name = "conversation-enabled", havingValue = "true")
public class LegacyController {
    private final LegacyService service;
    @GetMapping
    public Result<Page<Session>> sessions(@PathVariable String agentId,
            @RequestParam(defaultValue = "0") String before, @RequestParam(defaultValue = "50") int limit) {
        return new Result<Page<Session>>().ok(service.sessions(SecurityUser.getUserId(), agentId, before, limit));
    }
    @GetMapping("/{sessionId}/messages")
    public Result<Page<Message>> messages(@PathVariable String agentId, @PathVariable String sessionId,
            @RequestParam(defaultValue = "0") String after, @RequestParam(defaultValue = "100") int limit) {
        return new Result<Page<Message>>().ok(service.messages(SecurityUser.getUserId(), agentId, sessionId, after, limit));
    }
}
