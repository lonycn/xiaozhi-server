package xiaozhi.modules.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.ai.AiModels.*;
import xiaozhi.modules.conversation.ConversationService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/ai-worker")
@ConditionalOnProperty(prefix="features",name={"conversation-enabled","ai-text-enabled"},havingValue="true")
public class AiWorkerController {
    private final AiExecutionService service;
    private final ConversationService requests;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    @PostMapping("/claim") public Result<Task> claim(@RequestBody JsonNode body) {
        return new Result<Task>().ok(service.claim(requests.request(body,Claim.class)));
    }
    @GetMapping("/runs/{id}/attachments/{attachmentId}/content")
    public void media(@PathVariable String id,@PathVariable String attachmentId,@RequestParam String variant,
            @RequestHeader("X-AI-Lease") String token,jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        try(var content=service.media(id,attachmentId,variant,token)) {
            response.setContentType(content.mimeType());response.setContentLengthLong(content.size());
            response.setHeader("X-Content-Type-Options","nosniff");response.setHeader("Cache-Control","private, no-store");
            response.setHeader("Content-Disposition","attachment");
            byte[] buffer=new byte[65536];long remaining=content.size();
            while(remaining>0) {int count=content.input().read(buffer,0,(int)Math.min(buffer.length,remaining));
                if(count<0)throw new java.io.EOFException("Private media truncated");
                response.getOutputStream().write(buffer,0,count);remaining-=count;}
        }
    }
    @PostMapping("/runs/{id}/heartbeat") public Result<?> heartbeat(@PathVariable String id,@RequestBody JsonNode body) {
        return new Result<>().ok(service.heartbeat(id,requests.request(body,Lease.class)));
    }
    @PostMapping(value="/runs/{id}/delta", consumes="application/json") public Result<?> delta(@PathVariable String id,jakarta.servlet.http.HttpServletRequest request) throws java.io.IOException {
        final int maximum=1<<20;
        if(request.getContentLengthLong()>maximum)throw new xiaozhi.modules.conversation.ConversationProblem(413,"AI_BODY_LIMIT");
        byte[] raw=request.getInputStream().readNBytes(maximum+1);
        if(raw.length>maximum)throw new xiaozhi.modules.conversation.ConversationProblem(413,"AI_BODY_LIMIT");
        try {
            JsonNode body=json.readTree(raw);
            if (body != null && body.isObject() &&
                    ((body.hasNonNull("lease_token") && !body.get("lease_token").isTextual()) ||
                     (body.hasNonNull("sequence") && !body.get("sequence").isIntegralNumber()) ||
                     (body.hasNonNull("text") && !body.get("text").isTextual())))
                throw new xiaozhi.modules.conversation.ConversationProblem(400,"INVALID_REQUEST");
            return new Result<>().ok(service.delta(id,requests.request(body,Delta.class)));
        }
        catch(com.fasterxml.jackson.core.JsonProcessingException ex) { throw new xiaozhi.modules.conversation.ConversationProblem(400,"INVALID_REQUEST"); }
    }
    @PostMapping("/runs/{id}/complete") public Result<Run> complete(@PathVariable String id,@RequestBody JsonNode body) {
        return new Result<Run>().ok(service.complete(id,requests.request(body,Complete.class)));
    }
    @PostMapping("/runs/{id}/fail") public Result<Run> fail(@PathVariable String id,@RequestBody JsonNode body) {
        return new Result<Run>().ok(service.fail(id,requests.request(body,Fail.class)));
    }
}
