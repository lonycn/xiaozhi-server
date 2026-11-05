package xiaozhi.modules.answerspeech;
import java.io.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import xiaozhi.common.utils.Result;
import static xiaozhi.modules.answerspeech.AnswerSpeechModels.*;
@RestController
@RequestMapping("/internal/answer-speech-worker")
@ConditionalOnProperty(prefix="features",name="conversation-enabled",havingValue="true")
public class AnswerSpeechWorkerController {
    private final AnswerSpeechService service;private final AnswerSpeechJson json;
    public AnswerSpeechWorkerController(AnswerSpeechService service,ObjectMapper mapper){this.service=service;json=new AnswerSpeechJson(mapper);}
    @PostMapping(value="/claim",consumes="application/json") public Result<Task> claim(HttpServletRequest request)throws IOException{return new Result<Task>().ok(service.claim(json.read(request,Claim.class)));}
    @PostMapping(value="/jobs/{id}/heartbeat",consumes="application/json") public Result<?> heartbeat(@PathVariable String id,HttpServletRequest request)throws IOException{return new Result<>().ok(service.heartbeat(id,json.read(request,Fence.class)));}
    @PutMapping(value="/jobs/{id}/audio",consumes="audio/wav") public Result<Uploaded> upload(@PathVariable String id,@RequestHeader("X-Speech-Lease") String lease,@RequestHeader("X-Speech-Attempt") String attempt,@RequestHeader("X-Message-Version") String version,@RequestHeader("X-Text-SHA256") String text,@RequestHeader("X-Audio-SHA256") String audio,HttpServletRequest request)throws IOException{
        AnswerSpeechJson.noQuery(request);if(!attempt.matches("[1-9][0-9]{0,2}"))throw new xiaozhi.modules.conversation.ConversationProblem(400,"INVALID_SPEECH_REQUEST");return new Result<Uploaded>().ok(service.upload(id,new Fence(lease,Integer.valueOf(attempt),version,text),audio,request.getInputStream(),request.getContentLengthLong()));
    }
    @PostMapping(value="/jobs/{id}/complete",consumes="application/json") public Result<Job> complete(@PathVariable String id,HttpServletRequest request)throws IOException{return new Result<Job>().ok(service.complete(id,json.read(request,Finish.class)));}
    @PostMapping(value="/jobs/{id}/fail",consumes="application/json") public Result<Job> fail(@PathVariable String id,HttpServletRequest request)throws IOException{return new Result<Job>().ok(service.fail(id,json.read(request,Failed.class)));}
    @PostMapping(value="/jobs/{id}/usage",consumes="application/json") public Result<?> usage(@PathVariable String id,HttpServletRequest request)throws IOException{return new Result<>().ok(service.reportUsage(id,json.read(request,UsageReport.class)));}
    @ExceptionHandler(IOException.class) public ResponseEntity<Result<Void>> io(IOException error){return ResponseEntity.status(503).body(new Result<Void>().error(503,"SPEECH_AUDIO_UNAVAILABLE"));}
    @ExceptionHandler(DataAccessException.class) public ResponseEntity<Result<Void>> database(DataAccessException error){return ResponseEntity.status(503).body(new Result<Void>().error(503,"ANSWER_SPEECH_UNAVAILABLE"));}
}
