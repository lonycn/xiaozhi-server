package xiaozhi.modules.supportsummary;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.conversation.ConversationService;
import xiaozhi.modules.security.user.SecurityUser;
import static xiaozhi.modules.supportsummary.AssistanceModels.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/support/conversations/{id}/summary/assistance")
@ConditionalOnProperty(prefix="features",name={"conversation-enabled","support-handoff-enabled","support-summary-assistance-enabled"},havingValue="true")
public class SupportAssistanceController {
    private final SupportAssistanceService service;
    private final ConversationService requests;
    @GetMapping public Result<SupportAssistanceService.Availability> latest(@PathVariable String id){return new Result<SupportAssistanceService.Availability>().ok(service.latest(SecurityUser.getUserId(),id));}
    @PostMapping public Result<Job> generate(@PathVariable String id,@RequestBody JsonNode body){return new Result<Job>().ok(service.generate(SecurityUser.getUserId(),id,requests.request(body,Generate.class)));}
    @GetMapping("/{jobId}") public Result<Job> get(@PathVariable String id,@PathVariable String jobId){return new Result<Job>().ok(service.get(SecurityUser.getUserId(),id,jobId));}
    @PostMapping("/{jobId}/retry") public Result<Job> retry(@PathVariable String id,@PathVariable String jobId,@RequestBody(required=false) JsonNode body){if(body!=null)requests.request(body,SupportSummaryController.EmptyRequest.class);return new Result<Job>().ok(service.retry(SecurityUser.getUserId(),id,jobId));}
}
