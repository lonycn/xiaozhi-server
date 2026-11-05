package xiaozhi.modules.realtimetool;

import java.io.IOException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.conversation.ConversationProblem;
import xiaozhi.modules.realtimetool.RealtimeToolModels.*;

@RestController
@RequestMapping("/internal/realtime-worker/sessions/{id}")
@ConditionalOnProperty(prefix="features",name={"conversation-enabled","realtime-ai-enabled","realtime-tools-enabled"},havingValue="true")
public class RealtimeToolController {
    private final RealtimeToolService service;private final ObjectMapper json;
    public RealtimeToolController(RealtimeToolService service,ObjectMapper json){this.service=service;this.json=json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);}
    @PostMapping(value="/tool-proposals",consumes="application/json")
    public Result<Results> proposals(@PathVariable String id,HttpServletRequest request)throws IOException {
        if(request.getContentLengthLong()>32768)throw new ConversationProblem(413,"REALTIME_TOOL_BODY_LIMIT");
        byte[] raw=request.getInputStream().readNBytes(32769);if(raw.length>32768)throw new ConversationProblem(413,"REALTIME_TOOL_BODY_LIMIT");
        try{return new Result<Results>().ok(service.proposals(id,json.readValue(raw,Proposal.class)));}
        catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new ConversationProblem(400,"REALTIME_TOOL_PROPOSAL_INVALID");}
    }
    @ExceptionHandler(ConversationProblem.class) public ResponseEntity<Result<Void>> problem(ConversationProblem error){return ResponseEntity.status(error.status()).body(new Result<Void>().error(error.status(),error.getMessage()));}
    @ExceptionHandler(DataAccessException.class) public ResponseEntity<Result<Void>> database(DataAccessException error){return ResponseEntity.status(503).body(new Result<Void>().error(503,"REALTIME_TOOLS_UNAVAILABLE"));}
}
