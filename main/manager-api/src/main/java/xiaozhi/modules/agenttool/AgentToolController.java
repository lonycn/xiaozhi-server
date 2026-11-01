package xiaozhi.modules.agenttool;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletResponse;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.agenttool.AgentToolModels.HistoryPage;
import xiaozhi.modules.security.user.SecurityUser;

@RestController
@RequestMapping("/api/v1/ai/conversations/{id}")
@ConditionalOnProperty(prefix="features",name={"conversation-enabled","agent-tools-enabled"},havingValue="true")
public class AgentToolController {
    private final AgentToolService service;
    public AgentToolController(AgentToolService service){this.service=service;}
    @GetMapping("/tools") public Result<HistoryPage> history(@PathVariable String id,@RequestParam(defaultValue="0") long before,
            @RequestParam(defaultValue="50") int limit,HttpServletResponse response) {
        response.setHeader("Cache-Control","private, no-store");response.setHeader("X-Content-Type-Options","nosniff");
        return new Result<HistoryPage>().ok(service.history(SecurityUser.getUserId(),id,before,limit));
    }
}
