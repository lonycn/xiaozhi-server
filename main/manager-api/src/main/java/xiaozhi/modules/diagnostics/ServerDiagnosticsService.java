package xiaozhi.modules.diagnostics;

import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Read-only operational evidence. Never reports provider or media transport readiness. */
@Service
public class ServerDiagnosticsService {
    private final JdbcTemplate db;
    private final Environment env;
    public ServerDiagnosticsService(JdbcTemplate db, Environment env){this.db=db;this.env=env;}
    private boolean flag(String name){return env.getProperty("features."+name,Boolean.class,false);}
    public boolean administrator(long user){
        Long count=db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=? AND status=1 AND super_admin=1",Long.class,user);
        return count!=null && count==1;
    }
    public Map<String,Object> snapshot(){
        db.queryForObject("SELECT 1",Integer.class);
        boolean conversation=flag("conversation-enabled"), ai=conversation&&flag("ai-text-enabled");
        boolean attachments=conversation&&flag("attachments-enabled"), support=conversation&&flag("support-handoff-enabled");
        boolean media=support&&flag("support-media-enabled"), realtime=conversation&&flag("realtime-ai-enabled");
        boolean tools=ai&&flag("agent-tools-enabled");
        Map<String,Object> flags=Map.of("conversations",conversation,"ai_text",ai,"attachments",attachments,
                "support_media",media,"realtime_ai",realtime,"agent_tools",tools);
        Map<String,Object> checks=new LinkedHashMap<>();
        checks.put("ai_runs",count(ai,"SELECT COUNT(*) AS active, COALESCE(SUM(status IN ('running','streaming') AND lease_expires_at<CURRENT_TIMESTAMP(3)),0) AS expired FROM ai_run WHERE status IN ('queued','running','streaming')"));
        checks.put("attachment_jobs",count(attachments,"SELECT COUNT(*) AS active, COALESCE(SUM(status='running' AND lease_expires_at<CURRENT_TIMESTAMP(3)),0) AS expired FROM ai_media_job WHERE status IN ('queued','running')"));
        checks.put("support_media_operations",count(media,"SELECT COUNT(*) AS active, COALESCE(SUM(status='running' AND lease_expires_at<CURRENT_TIMESTAMP(3)),0) AS expired FROM support_media_outbox WHERE status IN ('queued','running')"));
        checks.put("realtime_sessions",count(realtime,"SELECT COUNT(*) AS active, COALESCE(SUM(lease_expires_at<CURRENT_TIMESTAMP(3)),0) AS expired FROM ai_realtime_session WHERE status IN ('pending','connecting','active')"));
        checks.put("tool_failures_60m",toolFailures(tools));
        boolean degraded=checks.values().stream().anyMatch(value->{
            var check=(Map<?,?>)value;Object expired=check.get("expired_leases");
            return "unknown".equals(check.get("state")) || expired instanceof Number number && number.longValue()>0;
        });
        return Map.of("state",degraded?"degraded":"limited","readiness_scope","database_and_job_ledgers",
                "database",Map.of("state","ok"),"features",flags,"checks",checks);
    }
    private Map<String,Object> count(boolean enabled,String sql){
        if(!enabled)return Map.of("state","disabled");
        try {var row=db.queryForMap(sql);return Map.of("state","ok","active",((Number)row.get("active")).longValue(),"expired_leases",((Number)row.get("expired")).longValue());}
        catch(RuntimeException unavailable){return Map.of("state","unknown");}
    }
    private Map<String,Object> toolFailures(boolean enabled){
        if(!enabled)return Map.of("state","disabled");
        try {
            var rows=db.queryForList("""
                SELECT state, CASE WHEN error_code IN ('TOOL_EXECUTION_FAILED','TOOL_RESULT_FENCED','TOOL_RUN_CANCELED','TOOL_SOURCE_CHANGED','TOOL_SOURCE_REVOKED','TOOL_RESULT_INVALID','TOOL_RESULT_SIZE_LIMIT','TOOL_RESULT_UNCERTAIN','BUSINESS_NOT_CONFIGURED','BUSINESS_PROVENANCE_INVALID') THEN error_code ELSE 'OTHER' END AS safe_code,
                       COUNT(*) AS total FROM agent_tool_audit
                WHERE state IN ('failed','discarded') AND created_at>=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 60 MINUTE)
                GROUP BY state,safe_code ORDER BY total DESC LIMIT 100
                """);
            List<Map<String,Object>> groups=new ArrayList<>();long failed=0,discarded=0;
            for(var row:rows) {
                long total=((Number)row.get("total")).longValue();
                if("failed".equals(row.get("state")))failed+=total;else discarded+=total;
                groups.add(Map.of("status",row.get("state"),"error_code",row.get("safe_code"),"count",total));
            }
            return Map.of("state","ok","failed",failed,"discarded",discarded,"groups",groups);
        }catch(RuntimeException unavailable){return Map.of("state","unknown");}
    }
}
