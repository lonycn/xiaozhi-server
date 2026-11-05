package xiaozhi.modules.conversation;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.shiro.SecurityUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.security.user.SecurityUser;

/** Internal console only: external tokens keep their existing, explicit conversation routes. */
@RestController
@RequestMapping("/api/v1/ai/conversations/index")
@ConditionalOnProperty(prefix="features",name="conversation-enabled",havingValue="true")
public class ConversationIndexController {
    private final ConversationIndexService service;
    public ConversationIndexController(ConversationIndexService service){this.service=service;}
    private long owner(){Long id=SecurityUser.getUserId();if(id==null||id<=0)throw new ConversationProblem(401,"UNAUTHORIZED");return id;}
    private boolean legacyAllowed(){return SecurityUtils.getSubject().isPermitted("sys:role:normal");}
    private void single(HttpServletRequest request){if(request.getParameterMap().values().stream().anyMatch(v->v.length!=1))throw new ConversationProblem(400,"INVALID_CONVERSATION_INDEX_QUERY");}
    @GetMapping public ResponseEntity<Result<ConversationIndexService.Page>> list(@RequestParam Map<String,String> params,HttpServletRequest request){
        single(request);var page=service.list(owner(),params,legacyAllowed());
        return ResponseEntity.ok().header("Cache-Control","private, no-store").header("X-Content-Type-Options","nosniff").body(new Result<ConversationIndexService.Page>().ok(page));
    }
    @GetMapping("/options") public ResponseEntity<Result<Map<String,Object>>> options(HttpServletRequest request){
        if(!request.getParameterMap().isEmpty())throw new ConversationProblem(400,"INVALID_CONVERSATION_INDEX_QUERY");
        var options=service.options(owner(),legacyAllowed());
        return ResponseEntity.ok().header("Cache-Control","private, no-store").body(new Result<Map<String,Object>>().ok(options));
    }
}
