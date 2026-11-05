package xiaozhi.modules.ai;

import java.io.IOException;
import java.util.Set;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.conversation.ConversationProblem;

/** Dedicated AI worker key route. Remains registered for late scalar audit when AI text is disabled. */
@RestController
@RequestMapping("/internal/ai-worker/runs/{id}")
@ConditionalOnProperty(prefix="features",name="conversation-enabled",havingValue="true")
public class AiProviderUsageAuditController {
    private final AiProviderUsageAudit service;private final ObjectMapper json;
    public AiProviderUsageAuditController(AiProviderUsageAudit service,ObjectMapper json){this.service=service;this.json=json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);}
    @PostMapping(value="/usage",consumes="application/json") public Result<?> usage(@PathVariable String id,HttpServletRequest request)throws IOException{return new Result<>().ok(service.record(id,read(request)));}
    AiModels.UsageAudit read(HttpServletRequest request)throws IOException {
        if(request.getQueryString()!=null)throw invalid();if(request.getContentLengthLong()>16384)throw new ConversationProblem(413,"AI_USAGE_BODY_LIMIT");byte[] bytes=request.getInputStream().readNBytes(16385);if(bytes.length>16384)throw new ConversationProblem(413,"AI_USAGE_BODY_LIMIT");
        try{JsonNode node=json.readTree(bytes);exact(node,Set.of("lease_token","attempt","provider_requests"));if(!node.path("lease_token").isTextual()||!node.path("attempt").isIntegralNumber()||!node.path("provider_requests").isArray()||node.path("provider_requests").size()>4)throw invalid();
            for(JsonNode report:node.get("provider_requests")){exact(report,Set.of("round","provider_request_id","usage","finish_reason"));if(!report.path("round").isIntegralNumber())throw invalid();
                for(String key:Set.of("provider_request_id","finish_reason"))if(report.hasNonNull(key)&&!report.get(key).isTextual())throw invalid();
                if(report.hasNonNull("usage")){JsonNode usage=report.get("usage");exact(usage,Set.of("input_tokens","output_tokens","total_tokens","input_audio_tokens"));for(String key:Set.of("input_tokens","output_tokens","total_tokens"))if(!usage.path(key).isIntegralNumber())throw invalid();if(usage.hasNonNull("input_audio_tokens")&&!usage.get("input_audio_tokens").isIntegralNumber())throw invalid();}
            }return json.treeToValue(node,AiModels.UsageAudit.class);
        }catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw invalid();}
    }
    private static void exact(JsonNode node,Set<String> keys){if(node==null||!node.isObject())throw invalid();node.fieldNames().forEachRemaining(key->{if(!keys.contains(key))throw invalid();});}
    private static ConversationProblem invalid(){return new ConversationProblem(400,"INVALID_PROVIDER_USAGE_AUDIT");}
    @ExceptionHandler(DataAccessException.class) public ResponseEntity<Result<Void>> database(DataAccessException error){return ResponseEntity.status(503).body(new Result<Void>().error(503,"AI_USAGE_AUDIT_UNAVAILABLE"));}
    @ExceptionHandler(IOException.class) public ResponseEntity<Result<Void>> input(IOException error){return ResponseEntity.status(400).body(new Result<Void>().error(400,"INVALID_PROVIDER_USAGE_AUDIT"));}
}
