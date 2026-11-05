package xiaozhi.modules.realtimeinput;

import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.realtimeinput.RealtimeInputModels.*;

@RestController
@RequestMapping("/api/v1/ai/realtime/sessions/{id}")
@ConditionalOnProperty(prefix="features",name="conversation-enabled",havingValue="true")
public class RealtimeInputController {
    private final RealtimeInputService service;private final RealtimeInputJson json;
    public RealtimeInputController(RealtimeInputService service,ObjectMapper json){this.service=service;this.json=new RealtimeInputJson(json);}
    @GetMapping("/input-state") public Result<State> state(@PathVariable String id,HttpServletRequest request,HttpServletResponse response){noQuery(request);response.setHeader("Cache-Control","private, no-store");return new Result<State>().ok(service.state(SecurityUser.getUserId(),id));}
    @GetMapping("/materials/{material}") public Result<MaterialView> material(@PathVariable String id,@PathVariable String material,HttpServletRequest request,HttpServletResponse response){noQuery(request);response.setHeader("Cache-Control","private, no-store");return new Result<MaterialView>().ok(service.material(SecurityUser.getUserId(),id,material));}
    @PostMapping(value="/input-pauses",consumes="application/json") public Result<PauseView> pause(@PathVariable String id,HttpServletRequest request)throws IOException{return new Result<PauseView>().ok(service.pause(SecurityUser.getUserId(),id,json.read(request,PauseRequest.class)));}
    @PostMapping(value="/input-pauses/{pause}/resume",consumes="application/json") public Result<PauseView> resume(@PathVariable String id,@PathVariable String pause,HttpServletRequest request)throws IOException{return new Result<PauseView>().ok(service.resume(SecurityUser.getUserId(),id,pause,json.read(request,ResumeRequest.class)));}
    @PostMapping(value="/input-pauses/{pause}/cancel",consumes="application/json") public Result<PauseView> cancel(@PathVariable String id,@PathVariable String pause,HttpServletRequest request)throws IOException{return new Result<PauseView>().ok(service.cancel(SecurityUser.getUserId(),id,pause,json.read(request,CancelRequest.class)));}
    @PostMapping(value="/materials",consumes="application/json") public Result<Submitted> submit(@PathVariable String id,HttpServletRequest request)throws IOException{return new Result<Submitted>().ok(service.submit(SecurityUser.getUserId(),id,json.read(request,MaterialRequest.class)));}
    @ExceptionHandler(DataAccessException.class) public ResponseEntity<Result<Void>> database(DataAccessException error){return ResponseEntity.status(503).body(new Result<Void>().error(503,"REALTIME_MATERIAL_UNAVAILABLE"));}
    private static void noQuery(HttpServletRequest request){if(request.getQueryString()!=null)throw new xiaozhi.modules.conversation.ConversationProblem(400,"INVALID_MATERIAL_REQUEST");}
}
