package xiaozhi.modules.aiskill;
import java.io.IOException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.conversation.ConversationProblem;
import xiaozhi.modules.externalidentity.*;
import xiaozhi.modules.externalidentity.ExternalModels.Principal;
import xiaozhi.modules.security.user.SecurityUser;
@RestController
@RequestMapping("/api/v1/ai")
@ConditionalOnProperty(prefix="features",name="conversation-enabled",havingValue="true")
public class AiSkillController {
    private final AiSkillService service;private final ObjectMapper json;
    public AiSkillController(AiSkillService service,ObjectMapper json){this.service=service;this.json=json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);}
    @GetMapping("/skills") public Result<?> catalog(HttpServletRequest request,HttpServletResponse response){guard(request,response);return new Result<>().ok(service.catalog());}
    @GetMapping("/conversations/{id}/skill") public Result<AiSkillService.View> selection(@PathVariable String id,HttpServletRequest request,HttpServletResponse response){guard(request,response);return new Result<AiSkillService.View>().ok(service.selection(SecurityUser.getUserId(),id,principal(request)));}
    @PostMapping(value="/conversations/{id}/skill",consumes="application/json") public Result<AiSkillService.View> select(@PathVariable String id,HttpServletRequest request,HttpServletResponse response)throws IOException{guard(request,response);if(request.getContentLengthLong()>16384)throw new ConversationProblem(413,"SKILL_BODY_LIMIT");byte[] bytes=request.getInputStream().readNBytes(16385);if(bytes.length>16384)throw new ConversationProblem(413,"SKILL_BODY_LIMIT");AiSkillService.Request value;try{value=json.readValue(bytes,AiSkillService.Request.class);}catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new ConversationProblem(400,"INVALID_SKILL_SELECTION");}return new Result<AiSkillService.View>().ok(service.select(SecurityUser.getUserId(),id,value,principal(request)));}
    private static Principal principal(HttpServletRequest request){return request.getAttribute(ExternalIdentityService.ATTRIBUTE) instanceof Principal p?p:null;}
    private static void guard(HttpServletRequest request,HttpServletResponse response){if(request.getQueryString()!=null)throw new ConversationProblem(400,"INVALID_SKILL_SELECTION");response.setHeader("Cache-Control","private, no-store");response.setHeader("X-Content-Type-Options","nosniff");}
    @ExceptionHandler(DataAccessException.class) public ResponseEntity<Result<Void>> database(DataAccessException error){return ResponseEntity.status(503).body(new Result<Void>().error(503,"AI_SKILLS_UNAVAILABLE"));}
}
