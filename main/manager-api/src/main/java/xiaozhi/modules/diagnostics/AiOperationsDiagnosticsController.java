package xiaozhi.modules.diagnostics;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.externalidentity.ExternalIdentityService;
import xiaozhi.modules.security.user.SecurityUser;
/** Internal super-administrators only. This new endpoint leaves /readiness semantics intact. */
@RestController
public class AiOperationsDiagnosticsController {
    private final ServerDiagnosticsService access;private final AiOperationsDiagnosticsService service;
    public AiOperationsDiagnosticsController(ServerDiagnosticsService access,AiOperationsDiagnosticsService service){this.access=access;this.service=service;}
    @GetMapping("/api/v1/admin/diagnostics/ai") public ResponseEntity<Result<?>> get(HttpServletRequest request){
        String authorization=request.getHeader("Authorization");if(request.getAttribute(ExternalIdentityService.ATTRIBUTE)!=null||authorization!=null&&authorization.matches("(?i)^Bearer\\s+xext_.*"))return error(403,"DIAGNOSTICS_ADMIN_REQUIRED");
        Long actor=SecurityUser.getUserId();if(actor==null||actor<=0)return error(401,"AUTHENTICATION_REQUIRED");
        try{if(!access.administrator(actor))return error(403,"DIAGNOSTICS_ADMIN_REQUIRED");if(request.getQueryString()!=null||!request.getParameterMap().isEmpty())return error(400,"INVALID_AI_DIAGNOSTICS_QUERY");return response(200,new Result<>().ok(service.snapshot(actor)));}
        catch(RuntimeException unavailable){var result=new Result<Map<String,Object>>().error(503,"AI_DIAGNOSTICS_DATABASE_UNKNOWN");result.setData(Map.of("state","degraded","readiness_scope","configuration_and_job_ledgers","database",Map.of("state","unknown")));return response(503,result);}
    }
    private static ResponseEntity<Result<?>> error(int status,String code){return response(status,new Result<>().error(status,code));}
    private static ResponseEntity<Result<?>> response(int status,Result<?> value){return ResponseEntity.status(status).header("Cache-Control","private, no-store").header("X-Content-Type-Options","nosniff").body(value);}
}
