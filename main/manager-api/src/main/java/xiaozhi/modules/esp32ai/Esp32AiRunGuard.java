package xiaozhi.modules.esp32ai;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import xiaozhi.modules.agent.Esp32ReplyLength;
import xiaozhi.modules.conversation.ConversationProblem;
import org.springframework.web.server.ResponseStatusException;
import xiaozhi.modules.esp32conversation.Esp32ConversationService.TrustedContext;
import xiaozhi.modules.esp32gateway.Esp32GatewayService;
import xiaozhi.modules.esp32gateway.Esp32GatewayService.DeviceContext;
import java.util.*;

/** A run's source is immutable. Always call before conversation/run locking. */
@Service
public class Esp32AiRunGuard {
    private final JdbcTemplate db;
    private final Esp32GatewayService gateway;
    public Esp32AiRunGuard(JdbcTemplate db,Esp32GatewayService gateway){this.db=db;this.gateway=gateway;}
    public boolean bound(String runId){return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM esp32_ai_run_binding WHERE run_id=?)",Boolean.class,runId));}
    public TrustedContext context(String runId){var b=binding(runId);return new TrustedContext((String)b.get("device_id"),(String)b.get("binding_version"),n(b,"owner_id"),(String)b.get("agent_id"),(String)b.get("connection_id"),n(b,"generation"),(String)b.get("gateway_id"));}
    public DeviceContext gatewayContext(String runId){return deviceContext(context(runId));}
    public String turnId(String runId){return (String)binding(runId).get("turn_id");}
    public static DeviceContext deviceContext(TrustedContext c){return new DeviceContext(c.deviceId(),c.ownerId(),c.agentId(),c.bindingVersion(),c.connectionId(),c.generation(),c.gatewayId(),0);}
    public String rolePrompt(String runId) {
        return db.query("SELECT role_prompt FROM esp32_ai_run_binding WHERE run_id=?",
                rs->rs.next()?rs.getString(1):null,runId);
    }
    @Transactional public void prepare(String runId,TrustedContext ctx,String turnId){
        prepare(runId,ctx,turnId,null);
    }
    @Transactional public void prepare(String runId,TrustedContext ctx,String turnId,String rolePrompt){
        assertGateway(ctx);
        var hint=one("SELECT session_id FROM esp32_voice_turn WHERE id=?",turnId);
        var session=one("SELECT * FROM esp32_device_session WHERE id=? FOR UPDATE",hint.get("session_id"));
        var turn=one("SELECT * FROM esp32_voice_turn WHERE id=? FOR UPDATE",turnId);
        verify(ctx,session,turn);
        var run=one("SELECT conversation_id,input_message_id FROM ai_run WHERE id=?",runId);
        if(!run.get("conversation_id").equals(session.get("conversation_id"))||!run.get("input_message_id").equals(turn.get("input_message_id")))throw fail("ESP32_RUN_SOURCE_MISMATCH");
        if (rolePrompt == null) {
            String length = db.query("SELECT esp32_reply_length FROM ai_agent WHERE id=?",
                    rs -> rs.next() ? rs.getString(1) : null, ctx.agentId());
            rolePrompt = Esp32ReplyLength.apply(length, null);
        }
        db.update("INSERT INTO esp32_ai_run_binding(run_id,device_id,owner_id,agent_id,binding_version,connection_id,generation,gateway_id,turn_id,session_id,control_version,turn_seq,role_prompt) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",runId,ctx.deviceId(),ctx.ownerId(),ctx.agentId(),ctx.bindingVersion(),ctx.connectionId(),ctx.generation(),ctx.gatewayId(),turnId,session.get("id"),session.get("control_version"),turn.get("turn_seq"),rolePrompt);
    }
    @Transactional public void validate(String runId){
        if(!bound(runId))return;
        var b=binding(runId);var ctx=context(runId);assertGateway(ctx);
        var session=one("SELECT * FROM esp32_device_session WHERE id=? FOR UPDATE",b.get("session_id"));
        var turn=one("SELECT * FROM esp32_voice_turn WHERE id=? FOR UPDATE",b.get("turn_id"));
        verify(ctx,session,turn);
        if(n(session,"control_version")!=n(b,"control_version")||n(turn,"turn_seq")!=n(b,"turn_seq"))throw fail("ESP32_TURN_SUPERSEDED");
    }
    private void verify(TrustedContext ctx,Map<String,Object>s,Map<String,Object>t){
        if(!ctx.deviceId().equals(s.get("device_id"))||ctx.ownerId()!=n(s,"owner_user_id")||!ctx.agentId().equals(s.get("agent_id"))||!ctx.bindingVersion().equals(s.get("binding_version"))||!ctx.connectionId().equals(s.get("connection_id"))||ctx.generation()!=n(s,"generation")||!ctx.gatewayId().equals(s.get("gateway_id"))||!s.get("id").equals(t.get("session_id"))||n(s,"control_version")!=n(t,"control_version")||n(s,"last_turn_seq")!=n(t,"turn_seq")||!ctx.connectionId().equals(t.get("connection_id"))||ctx.generation()!=n(t,"generation")||!"ai_worker".equals(s.get("execution_mode"))||!"ai_only".equals(s.get("interaction_mode"))||!"ai_worker".equals(t.get("execution_mode"))||!"accepted".equals(t.get("status")))throw fail("ESP32_TURN_SUPERSEDED");
    }
    private Map<String,Object> binding(String id){return one("SELECT * FROM esp32_ai_run_binding WHERE run_id=?",id);}
    private Map<String,Object> one(String sql,Object...args){var rows=db.queryForList(sql,args);if(rows.size()!=1)throw fail("ESP32_SOURCE_UNAVAILABLE");return rows.getFirst();}
    private static long n(Map<String,Object>r,String k){return ((Number)r.get(k)).longValue();}
    private void assertGateway(TrustedContext ctx){try{gateway.assertCurrent(deviceContext(ctx));}catch(ResponseStatusException e){throw fail("ESP32_SOURCE_REVOKED");}}
    static ConversationProblem fail(String code){return new ConversationProblem(409,code);}
}
