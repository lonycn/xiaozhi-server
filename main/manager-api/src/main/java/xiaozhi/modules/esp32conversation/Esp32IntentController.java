package xiaozhi.modules.esp32conversation;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.conversation.ConversationProblem;
import xiaozhi.modules.security.user.SecurityUser;

import java.util.Map;

@RestController
@RequiresPermissions("sys:role:normal")
@RequestMapping("/agent/{agentId}/esp32-intents")
@ConditionalOnProperty(name = "features.esp32-intents-enabled", havingValue = "true")
public class Esp32IntentController {
    private final Esp32IntentService intents;
    private final AgentService agents;

    public Esp32IntentController(Esp32IntentService intents, AgentService agents) {
        this.intents = intents;
        this.agents = agents;
    }

    public record Preview(Esp32IntentService.Policy policy, String text) {}
    public record Revision(long revision) {}

    @GetMapping
    public Esp32IntentService.View view(@PathVariable String agentId) {
        requireOwner(agentId);
        return intents.view(agentId);
    }

    @PutMapping("/draft")
    public Esp32IntentService.View save(@PathVariable String agentId, @RequestBody Esp32IntentService.Draft draft) {
        requireOwner(agentId);
        return intents.save(agentId, draft);
    }

    @PostMapping("/preview")
    public Object preview(@PathVariable String agentId, @RequestBody Preview preview) {
        requireOwner(agentId);
        if (preview.text() == null || preview.text().isBlank() || preview.text().length() > 200) {
            throw new ConversationProblem(400, "INTENT_INVALID_PHRASE");
        }
        var match = intents.preview(preview.policy(), preview.text());
        return match == null ? Map.of("matched", false) : Map.of("matched", true, "rule", match);
    }

    @PostMapping("/publish")
    public Esp32IntentService.View publish(@PathVariable String agentId, @RequestBody Revision request) {
        requireOwner(agentId);
        return intents.publish(agentId, request.revision(), SecurityUser.getUserId(), false);
    }

    @PostMapping("/rollback")
    public Esp32IntentService.View rollback(@PathVariable String agentId, @RequestBody Revision request) {
        requireOwner(agentId);
        return intents.publish(agentId, request.revision(), SecurityUser.getUserId(), true);
    }

    private void requireOwner(String agentId) {
        if (!agentId.matches("[A-Za-z0-9_-]{1,32}") || !agents.checkAgentPermission(agentId, SecurityUser.getUserId())) {
            throw new ConversationProblem(404, "AGENT_NOT_FOUND");
        }
    }
}
