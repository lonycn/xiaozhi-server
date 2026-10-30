package xiaozhi.modules.realtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.JsonNode;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.conversation.ConversationService;
import xiaozhi.modules.realtime.RealtimeModels.*;

@RestController
@RequestMapping("/internal/realtime-worker")
@ConditionalOnProperty(prefix="features",name={"conversation-enabled","realtime-ai-enabled"},havingValue="true")
public class RealtimeWorkerController {
    private final RealtimeService service;private final ConversationService requests;private final com.fasterxml.jackson.databind.ObjectMapper json;
    public RealtimeWorkerController(RealtimeService service,ConversationService requests,com.fasterxml.jackson.databind.ObjectMapper json){this.service=service;this.requests=requests;this.json=json;}
    @PostMapping("/consume") public Result<Task> consume(@RequestBody JsonNode body){return new Result<Task>().ok(service.consume(requests.request(body,Consume.class)));}
    @PostMapping("/sessions/{id}/heartbeat") public Result<Pulse> heartbeat(@PathVariable String id,@RequestBody JsonNode body){return new Result<Pulse>().ok(service.heartbeat(id,requests.request(body,Heartbeat.class)));}
    @PostMapping(value="/sessions/{id}/events",consumes="application/json") public Result<Receipt> event(@PathVariable String id,jakarta.servlet.http.HttpServletRequest request)throws java.io.IOException {
        final int maximum=1<<20;
        if(request.getContentLengthLong()>maximum)throw RealtimeStore.problem(413,"REALTIME_BODY_LIMIT");
        byte[] raw=request.getInputStream().readNBytes(maximum+1);if(raw.length>maximum)throw RealtimeStore.problem(413,"REALTIME_BODY_LIMIT");
        try{return new Result<Receipt>().ok(service.providerEvent(id,requests.request(json.readTree(raw),ProviderEvent.class)));}
        catch(com.fasterxml.jackson.core.JsonProcessingException ex){throw RealtimeStore.problem(400,"INVALID_REQUEST");}
    }
}
