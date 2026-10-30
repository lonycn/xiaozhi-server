package xiaozhi.modules.supportsummary;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.conversation.ConversationService;
import xiaozhi.modules.security.user.SecurityUser;
import static xiaozhi.modules.supportsummary.SummaryModels.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/support/conversations/{id}/summary")
@ConditionalOnProperty(prefix="features",name={"conversation-enabled","support-handoff-enabled"},havingValue="true")
public class SupportSummaryController {
    private final SupportSummaryService service;
    private final ConversationService conversations;
    @GetMapping
    public Result<List<Revision>> history(@PathVariable String id,@RequestParam(defaultValue="0") long before,@RequestParam(defaultValue="20") int limit){
        return new Result<List<Revision>>().ok(service.history(SecurityUser.getUserId(),id,before,limit));
    }
    @PostMapping("/draft")
    public Result<Revision> prepare(@PathVariable String id,@RequestBody(required=false) JsonNode body){
        if(body!=null)conversations.request(body,EmptyRequest.class);
        return new Result<Revision>().ok(service.prepare(SecurityUser.getUserId(),id));
    }
    public record EmptyRequest() { }
    @PutMapping
    public Result<Revision> edit(@PathVariable String id,@RequestBody JsonNode body){
        return new Result<Revision>().ok(service.edit(SecurityUser.getUserId(),id,conversations.request(body,Edit.class)));
    }
}
