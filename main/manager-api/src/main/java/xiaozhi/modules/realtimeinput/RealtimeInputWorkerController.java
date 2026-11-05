package xiaozhi.modules.realtimeinput;

import java.io.IOException;
import java.io.EOFException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.realtimeinput.RealtimeInputModels.*;

/** Historical usage-only reports remain available to the dedicated worker after a rollout flag turns off. */
@RestController
@RequestMapping("/internal/realtime-worker/sessions/{id}")
@ConditionalOnProperty(prefix="features",name="conversation-enabled",havingValue="true")
public class RealtimeInputWorkerController {
    private final RealtimeInputService service;private final RealtimeInputJson json;
    public RealtimeInputWorkerController(RealtimeInputService service,ObjectMapper json){this.service=service;this.json=new RealtimeInputJson(json);}
    @PostMapping(value="/input-pauses/{pause}/cleared",consumes="application/json") public Result<PauseView> cleared(@PathVariable String id,@PathVariable String pause,HttpServletRequest request)throws IOException{return new Result<PauseView>().ok(service.cleared(id,pause,json.read(request,Cleared.class)));}
    @PostMapping(value="/input-pauses/{pause}/resumed",consumes="application/json") public Result<ResumePermission> resumed(@PathVariable String id,@PathVariable String pause,HttpServletRequest request)throws IOException{return new Result<ResumePermission>().ok(service.resumed(id,pause,json.read(request,Resumed.class)));}
    @PostMapping(value="/materials/{material}/prepared",consumes="application/json") public Result<Prepared> prepared(@PathVariable String id,@PathVariable String material,HttpServletRequest request)throws IOException{return new Result<Prepared>().ok(service.prepared(id,material,json.read(request,Prepare.class)));}
    @PostMapping(value="/materials/{material}/perception-usage",consumes="application/json") public Result<?> usage(@PathVariable String id,@PathVariable String material,HttpServletRequest request)throws IOException{return new Result<>().ok(service.reportUsage(id,material,json.read(request,PerceptionUsage.class)));}
    @PostMapping(value="/materials/{material}/context-ack",consumes="application/json") public Result<MaterialView> ack(@PathVariable String id,@PathVariable String material,HttpServletRequest request)throws IOException{return new Result<MaterialView>().ok(service.acknowledge(id,material,json.read(request,ContextAck.class)));}
    @PostMapping(value="/materials/{material}/fail",consumes="application/json") public Result<MaterialView> fail(@PathVariable String id,@PathVariable String material,HttpServletRequest request)throws IOException{return new Result<MaterialView>().ok(service.fail(id,material,json.read(request,Failed.class)));}
    @GetMapping("/materials/{material}/attachments/{attachment}/content") public void content(@PathVariable String id,@PathVariable String material,@PathVariable String attachment,@RequestParam String variant,@RequestHeader("X-Realtime-Lease") String lease,HttpServletRequest request,HttpServletResponse response)throws IOException{
        if(request.getParameterMap().size()!=1 || request.getParameterValues("variant")==null || request.getParameterValues("variant").length!=1)throw new xiaozhi.modules.conversation.ConversationProblem(400,"INVALID_MATERIAL_REQUEST");
        try(var content=service.content(id,material,attachment,variant,lease)){response.setContentType(content.mimeType());response.setContentLengthLong(content.size());response.setHeader("Cache-Control","private, no-store");response.setHeader("X-Content-Type-Options","nosniff");
            byte[] buffer=new byte[16384];long remaining=content.size();while(remaining>0){int size=content.input().read(buffer,0,(int)Math.min(buffer.length,remaining));if(size<0)throw new EOFException("Bounded private material unavailable");response.getOutputStream().write(buffer,0,size);remaining-=size;}
        }
    }
    @ExceptionHandler(DataAccessException.class) public ResponseEntity<Result<Void>> database(DataAccessException error){return ResponseEntity.status(503).body(new Result<Void>().error(503,"REALTIME_MATERIAL_UNAVAILABLE"));}
}
