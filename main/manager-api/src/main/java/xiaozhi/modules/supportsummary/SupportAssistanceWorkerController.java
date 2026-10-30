package xiaozhi.modules.supportsummary;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.conversation.ConversationService;
import static xiaozhi.modules.supportsummary.AssistanceModels.*;

/** Covered exclusively by the existing /internal/ai-worker/** key filter. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/ai-worker/summary")
@ConditionalOnProperty(prefix="features",name={"conversation-enabled","support-handoff-enabled","support-summary-assistance-enabled"},havingValue="true")
public class SupportAssistanceWorkerController {
    private final SupportAssistanceService service;
    private final ConversationService requests;
    public record Claim(String worker_id) { }
    public record Lease(String lease_token) { }
    @PostMapping("/claim") public Result<Task> claim(@RequestBody JsonNode body){return new Result<Task>().ok(service.claim(requests.request(body,Claim.class).worker_id()));}
    @PostMapping("/{id}/heartbeat") public Result<Job> heartbeat(@PathVariable String id,@RequestBody JsonNode body){return new Result<Job>().ok(service.heartbeat(id,requests.request(body,Lease.class).lease_token()));}
    @PostMapping("/{id}/complete") public Result<Job> complete(@PathVariable String id,@RequestBody JsonNode body){return new Result<Job>().ok(service.complete(id,requests.request(body,Complete.class)));}
    @PostMapping("/{id}/fail") public Result<Job> fail(@PathVariable String id,@RequestBody JsonNode body){return new Result<Job>().ok(service.fail(id,requests.request(body,Fail.class)));}
}
