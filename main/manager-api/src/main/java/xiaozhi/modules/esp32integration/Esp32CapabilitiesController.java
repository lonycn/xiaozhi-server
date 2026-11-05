package xiaozhi.modules.esp32integration;

import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletResponse;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.conversation.ConversationProblem;

@RestController
@ConditionalOnProperty(prefix="features",name="esp32-enabled",havingValue="true")
public class Esp32CapabilitiesController {
    private final Environment env;
    public Esp32CapabilitiesController(Environment env){this.env=env;}
    @GetMapping("/api/v1/esp32/capabilities") public Result<Map<String,Object>> capabilities(HttpServletResponse response){
        if(SecurityUser.getUserId()==null)throw new ConversationProblem(401,"AUTHENTICATION_REQUIRED");
        response.setHeader("Cache-Control","private, no-store");
        return new Result<Map<String,Object>>().ok(Map.of("ai_only",true,"human_support",false,"protocol_versions",List.of(1),
                "execution_modes",List.of("ai_worker"),"ai_configured",env.getProperty("features.ai-text-enabled",Boolean.class,false),
                "tools_configured",env.getProperty("features.agent-tools-enabled",Boolean.class,false),"readiness_scope","configuration_only"));
    }
}
