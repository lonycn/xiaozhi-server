package xiaozhi.modules.aicontext;

import java.util.Map;
import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataAccessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.agenttool.PublicToolResultStore;
import xiaozhi.modules.conversation.*;
import xiaozhi.modules.externalidentity.ExternalIdentityService;
import xiaozhi.modules.externalidentity.ExternalModels.Principal;
import xiaozhi.modules.security.user.SecurityUser;

@RestController
@RequestMapping("/api/v1/ai/conversations/{id}")
@ConditionalOnProperty(prefix="features",name="conversation-enabled",havingValue="true")
public class AiEvidenceController {
    private final PublicToolResultStore results;private final ConversationEvidenceService context;private final ConversationAccessService access;
    public AiEvidenceController(PublicToolResultStore results,ConversationEvidenceService context,ConversationAccessService access){this.results=results;this.context=context;this.access=access;}
    @GetMapping("/tool-results") public Result<Map<String,Object>> results(@PathVariable String id,@RequestParam(defaultValue="0")long before,
            @RequestParam(defaultValue="50")int limit,HttpServletRequest request,HttpServletResponse response){
        response.setHeader("Cache-Control","private, no-store");response.setHeader("X-Content-Type-Options","nosniff");
        Object principal=request.getAttribute(ExternalIdentityService.ATTRIBUTE);
        if(principal!=null && !(principal instanceof Principal))throw new ConversationProblem(403,"TOOL_CONTEXT_INVALID");
        return new Result<Map<String,Object>>().ok(results.page(SecurityUser.getUserId(),id,before,limit,(Principal)principal));
    }
    @GetMapping("/context") public Result<?> context(@PathVariable String id,HttpServletResponse response){
        response.setHeader("Cache-Control","private, no-store");Long actor=SecurityUser.getUserId();if(actor==null)throw new ConversationProblem(401,"AUTHENTICATION_REQUIRED");
        var grant=access.read(actor,id,false);return new Result<>().ok(context.latest(actor,id,grant.messageCutoff()));
    }
    @ExceptionHandler(ConversationProblem.class) public ResponseEntity<Result<Void>> problem(ConversationProblem error){return ResponseEntity.status(error.status()).body(new Result<Void>().error(error.status(),error.getMessage()));}
    @ExceptionHandler(DataAccessException.class) public ResponseEntity<Result<Void>> unavailable(DataAccessException error){return ResponseEntity.status(503).body(new Result<Void>().error(503,"AI_EVIDENCE_UNAVAILABLE"));}
}
