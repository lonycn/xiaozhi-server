package xiaozhi.modules.airuntime;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import xiaozhi.modules.conversation.ConversationProblem;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.security.user.SecurityUser;

@RestController
@RequestMapping("/api/v1/ai/runtime-control")
@ConditionalOnProperty(prefix="features",name="conversation-enabled",havingValue="true")
public class AiRuntimeControlController {
    private final AiRuntimeControl control;
    public AiRuntimeControlController(AiRuntimeControl control){this.control=control;}
    @GetMapping public Result<AiRuntimeControl.Snapshot> get(HttpServletRequest request,HttpServletResponse response){
        response.setHeader("Cache-Control","private, no-store");response.setHeader("X-Content-Type-Options","nosniff");
        if(!control.enabled())throw new ConversationProblem(404,"AI_RUNTIME_CONTROL_DISABLED");
        if(!request.getParameterMap().isEmpty())throw new ConversationProblem(400,"INVALID_RUNTIME_CONTROL_QUERY");
        return new Result<AiRuntimeControl.Snapshot>().ok(control.snapshot(SecurityUser.getUserId()));
    }
}
