package xiaozhi.modules.attachment;

import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.attachment.AttachmentModels.*;
import xiaozhi.modules.conversation.ConversationService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/media-worker")
@ConditionalOnProperty(prefix="features",name={"conversation-enabled","attachments-enabled"},havingValue="true")
public class MediaWorkerController {
    private final AttachmentService service;
    private final ConversationService requests;
    @PostMapping("/claim") public Result<Task> claim(@RequestBody JsonNode body){return new Result<Task>().ok(service.claim(requests.request(body,Claim.class)));}
    @PostMapping("/jobs/{id}/heartbeat") public Result<?> heartbeat(@PathVariable String id,@RequestBody JsonNode body){return new Result<>().ok(service.heartbeat(id,requests.request(body,Lease.class)));}
    @GetMapping("/jobs/{id}/input") public void input(@PathVariable String id,@RequestHeader("X-Media-Lease") String lease,HttpServletRequest request,HttpServletResponse response)throws IOException{
        try(var selected=service.workerInput(id,lease)){AttachmentController.write(selected,request,response,false);}
    }
    @PutMapping("/jobs/{id}/artifacts/{kind}") public Result<?> artifact(@PathVariable String id,@PathVariable String kind,@RequestHeader("X-Media-Lease") String lease,HttpServletRequest request)throws IOException{return new Result<>().ok(service.artifact(id,kind,lease,request.getContentType(),request.getInputStream()));}
    @PostMapping("/jobs/{id}/complete") public Result<View> complete(@PathVariable String id,@RequestBody JsonNode body){return new Result<View>().ok(service.completeJob(id,requests.request(body,Complete.class)));}
    @PostMapping("/jobs/{id}/fail") public Result<View> fail(@PathVariable String id,@RequestBody JsonNode body){return new Result<View>().ok(service.failJob(id,requests.request(body,Fail.class)));}
}
