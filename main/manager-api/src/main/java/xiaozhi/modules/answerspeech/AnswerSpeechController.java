package xiaozhi.modules.answerspeech;
import java.io.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.externalidentity.*;
import xiaozhi.modules.externalidentity.ExternalModels.Principal;
import static xiaozhi.modules.answerspeech.AnswerSpeechModels.*;
@RestController
@RequestMapping("/api/v1/ai/conversations/{conversation}")
@ConditionalOnProperty(prefix="features",name="conversation-enabled",havingValue="true")
public class AnswerSpeechController {
    private final AnswerSpeechService service;private final AnswerSpeechJson json;
    public AnswerSpeechController(AnswerSpeechService service,ObjectMapper mapper){this.service=service;json=new AnswerSpeechJson(mapper);}
    @PostMapping(value="/messages/{message}/speech",consumes="application/json") public Result<Job> create(@PathVariable String conversation,@PathVariable String message,HttpServletRequest request,HttpServletResponse response)throws IOException{headers(response);return new Result<Job>().ok(service.create(SecurityUser.getUserId(),conversation,message,json.read(request,Create.class),principal(request)));}
    @GetMapping("/speech/{id}") public Result<Job> get(@PathVariable String conversation,@PathVariable String id,HttpServletRequest request,HttpServletResponse response){AnswerSpeechJson.noQuery(request);headers(response);return new Result<Job>().ok(service.get(SecurityUser.getUserId(),conversation,id,principal(request)));}
    @PostMapping(value="/speech/{id}/cancel",consumes="application/json") public Result<Job> cancel(@PathVariable String conversation,@PathVariable String id,HttpServletRequest request,HttpServletResponse response)throws IOException{var body=json.read(request,com.fasterxml.jackson.databind.JsonNode.class);if(!body.isObject()||!body.isEmpty())throw new xiaozhi.modules.conversation.ConversationProblem(400,"INVALID_SPEECH_REQUEST");headers(response);return new Result<Job>().ok(service.cancel(SecurityUser.getUserId(),conversation,id,principal(request)));}
    @GetMapping("/speech/{id}/audio") public void audio(@PathVariable String conversation,@PathVariable String id,HttpServletRequest request,HttpServletResponse response)throws IOException{AnswerSpeechJson.noQuery(request);headers(response);
        try(var content=service.audio(SecurityUser.getUserId(),conversation,id,principal(request))){response.setContentType("audio/wav");response.setContentLengthLong(content.size());response.setHeader("Content-Disposition","inline; filename=\"answer.wav\"");byte[] buffer=new byte[16384];long left=content.size();while(left>0){content.guard().run();int length=content.input().read(buffer,0,(int)Math.min(left,buffer.length));if(length<0)throw new EOFException("Private answer audio unavailable");response.getOutputStream().write(buffer,0,length);left-=length;}}
    }
    private static Principal principal(HttpServletRequest request){return request.getAttribute(ExternalIdentityService.ATTRIBUTE) instanceof Principal p?p:null;}
    private static void headers(HttpServletResponse response){response.setHeader("Cache-Control","private, no-store");response.setHeader("X-Content-Type-Options","nosniff");}
    @ExceptionHandler(IOException.class) public ResponseEntity<Result<Void>> io(IOException error){return ResponseEntity.status(503).body(new Result<Void>().error(503,"SPEECH_AUDIO_UNAVAILABLE"));}
    @ExceptionHandler(DataAccessException.class) public ResponseEntity<Result<Void>> database(DataAccessException error){return ResponseEntity.status(503).body(new Result<Void>().error(503,"ANSWER_SPEECH_UNAVAILABLE"));}
}
