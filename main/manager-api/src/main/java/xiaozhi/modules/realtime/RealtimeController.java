package xiaozhi.modules.realtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.JsonNode;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.conversation.ConversationService;
import xiaozhi.modules.realtime.RealtimeModels.*;
import xiaozhi.modules.security.user.SecurityUser;

@RestController
@RequestMapping("/api/v1/ai/realtime")
@ConditionalOnProperty(prefix="features",name={"conversation-enabled","realtime-ai-enabled"},havingValue="true")
public class RealtimeController {
    private final RealtimeService service;private final ConversationService requests;
    public RealtimeController(RealtimeService service,ConversationService requests){this.service=service;this.requests=requests;}
    @PostMapping("/sessions") public Result<Session> create(@RequestBody JsonNode body){return new Result<Session>().ok(service.create(SecurityUser.getUserId(),requests.request(body,Create.class)));}
    @GetMapping("/sessions/current") public Result<Session> current(@RequestParam("conversation_id") String conversationId){return new Result<Session>().ok(service.current(SecurityUser.getUserId(),conversationId));}
    @GetMapping("/sessions/{id}") public Result<Session> get(@PathVariable String id){return new Result<Session>().ok(service.get(SecurityUser.getUserId(),id));}
    @PostMapping("/sessions/{id}/connect-ticket") public Result<Ticket> ticket(@PathVariable String id,@RequestBody JsonNode body){return new Result<Ticket>().ok(service.ticket(SecurityUser.getUserId(),id,requests.request(body,Epoch.class)));}
    @PostMapping("/sessions/{id}/messages") public Result<Sent> send(@PathVariable String id,@RequestBody JsonNode body){return new Result<Sent>().ok(service.send(SecurityUser.getUserId(),id,requests.request(body,Send.class)));}
    @GetMapping("/sessions/{id}/injections") public Result<Page<Injection>> injections(@PathVariable String id,@RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="50") int limit){return new Result<Page<Injection>>().ok(service.injections(SecurityUser.getUserId(),id,after,limit));}
    @PostMapping("/sessions/{id}/interrupt") public Result<Command> interrupt(@PathVariable String id,@RequestBody JsonNode body){return new Result<Command>().ok(service.interrupt(SecurityUser.getUserId(),id,requests.request(body,Interrupt.class)));}
    @PostMapping("/sessions/{id}/end") public Result<Session> end(@PathVariable String id,@RequestBody JsonNode body){return new Result<Session>().ok(service.end(SecurityUser.getUserId(),id,requests.request(body,Epoch.class)));}
}
