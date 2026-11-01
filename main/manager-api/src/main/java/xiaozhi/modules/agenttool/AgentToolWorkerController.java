package xiaozhi.modules.agenttool;

import java.io.IOException;
import java.util.Set;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.agenttool.AgentToolModels.*;
import xiaozhi.modules.conversation.ConversationProblem;

/** Dedicated worker-authenticated protocol. No generic execute endpoint is exposed to app users. */
@RestController
@RequestMapping("/internal/ai-worker/runs/{id}")
@ConditionalOnProperty(prefix="features",name={"conversation-enabled","ai-text-enabled","agent-tools-enabled"},havingValue="true")
public class AgentToolWorkerController {
    private final AgentToolService service;
    private final ObjectMapper json;
    public AgentToolWorkerController(AgentToolService service,ObjectMapper json) {
        this.service=service;this.json=json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
    @GetMapping("/tools") public Result<Catalog> catalog(@PathVariable String id,@RequestParam int attempt,
            @RequestParam("control_epoch") long controlEpoch,@RequestHeader("X-AI-Lease") String leaseToken) {
        return new Result<Catalog>().ok(service.taskTools(id,attempt,leaseToken,controlEpoch));
    }
    @PostMapping(value="/tools",consumes="application/json")
    public Result<BatchResult> execute(@PathVariable String id,HttpServletRequest request)throws IOException {
        int maximum=32768;
        if(request.getContentLengthLong()>maximum)throw new ConversationProblem(413,"TOOL_BODY_LIMIT");
        byte[] raw=request.getInputStream().readNBytes(maximum+1);
        if(raw.length>maximum)throw new ConversationProblem(413,"TOOL_BODY_LIMIT");
        Proposal proposal;
        try {
            JsonNode node=json.readTree(raw);
            exact(node,Set.of("lease_token","attempt","control_epoch","round","calls"));
            if(!node.path("lease_token").isTextual() || !node.path("attempt").canConvertToInt() || !node.path("attempt").isIntegralNumber()
                    || !node.path("control_epoch").isIntegralNumber() || !node.path("control_epoch").canConvertToLong()
                    || !node.path("round").isIntegralNumber() || !node.path("round").canConvertToInt() || !node.path("calls").isArray())throw invalid();
            for(JsonNode call:node.path("calls")) {
                exact(call,Set.of("provider_call_id","name","arguments"));
                if(!call.path("provider_call_id").isTextual() || !call.path("name").isTextual() || !call.path("arguments").isObject())throw invalid();
            }
            proposal=json.treeToValue(node,Proposal.class);
        }catch(com.fasterxml.jackson.core.JsonProcessingException failure){throw invalid();}
        return new Result<BatchResult>().ok(service.submitCalls(id,proposal));
    }
    private static void exact(JsonNode node,Set<String> fields) {
        if(node==null || !node.isObject() || node.size()!=fields.size())throw invalid();
        node.fieldNames().forEachRemaining(name->{if(!fields.contains(name))throw invalid();});
    }
    private static ConversationProblem invalid(){return new ConversationProblem(400,"TOOL_PROPOSAL_INVALID");}
}
