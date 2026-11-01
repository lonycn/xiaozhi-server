package xiaozhi.modules.diagnostics;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.externalidentity.ExternalIdentityService;
import xiaozhi.modules.security.user.SecurityUser;

@RestController
public class ServerDiagnosticsController {
    private final ServerDiagnosticsService service;
    public ServerDiagnosticsController(ServerDiagnosticsService service){this.service=service;}
    @GetMapping("/api/v1/admin/diagnostics/readiness")
    public ResponseEntity<Result<?>> readiness(HttpServletRequest request){
        String authorization=request.getHeader("Authorization");
        if(request.getAttribute(ExternalIdentityService.ATTRIBUTE)!=null || authorization!=null && authorization.matches("(?i)^Bearer\\s+xext_.*"))
            return error(403,"DIAGNOSTICS_ADMIN_REQUIRED");
        Long user=SecurityUser.getUserId();
        if(user==null || user<=0)return error(401,"AUTHENTICATION_REQUIRED");
        try {
            if(!service.administrator(user))return error(403,"DIAGNOSTICS_ADMIN_REQUIRED");
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Result<>().ok(service.snapshot()));
        } catch(RuntimeException unavailable){
            Result<Map<String,Object>> result=new Result<Map<String,Object>>().error(503,"DIAGNOSTICS_DATABASE_UNKNOWN");
            result.setData(Map.of("state","degraded","readiness_scope","database_and_job_ledgers","database",Map.of("state","unknown")));
            return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(result);
        }
    }
    private static ResponseEntity<Result<?>> error(int status,String code){
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new Result<>().error(status,code));
    }
}
