package xiaozhi.modules.esp32conversation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import xiaozhi.modules.conversation.*;

/** Trusted transport adapter only. No public API accepts this context from JSON. */
@Service
public class Esp32ConversationService {
    public record TrustedContext(String deviceId, String bindingVersion, long ownerId, String agentId,
            String connectionId, long generation, String gatewayId) { }
    public interface ContextGuard { void requireCurrent(TrustedContext context); default void requireBinding(TrustedContext context) { requireCurrent(context); } }
    private final JdbcTemplate jdbc;
    private final ConversationDao conversations;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final ObjectProvider<Esp32AiExecutionBridge> worker;
    private final ObjectProvider<ContextGuard> guard;
    @Autowired(required = false)
    private Esp32IntentService intents;
    public Esp32ConversationService(JdbcTemplate jdbc, ConversationDao conversations, ObjectMapper json,
            PlatformTransactionManager manager, ObjectProvider<Esp32AiExecutionBridge> worker,
            ObjectProvider<ContextGuard> guard) {
        this.jdbc=jdbc; this.conversations=conversations; this.json=json;
        this.tx=new TransactionTemplate(manager); this.worker=worker; this.guard=guard;
    }
    public boolean isAiOnly(String conversationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM esp32_device_session WHERE conversation_id=? AND interaction_mode='ai_only')", Boolean.class, conversationId));
    }
    public void requireNoHuman(String conversationId) {
        if(isAiOnly(conversationId)) throw problem(409,"ESP32_AI_ONLY");
    }
    /** Shared ledger read paths must call this in addition to their ordinary owner check. */
    public void requireCurrentBindingForConversation(String conversationId,long owner) {
        var rows=jdbc.queryForList("SELECT * FROM esp32_device_session WHERE conversation_id=?",conversationId);
        if(rows.isEmpty())return;
        var session=rows.getFirst();
        if(owner<=0||number(session,"owner_user_id")!=owner)throw problem(404,"CONVERSATION_NOT_FOUND");
        try{binding(context(session));}catch(org.springframework.web.server.ResponseStatusException | ConversationProblem e){throw problem(404,"CONVERSATION_NOT_FOUND");}
    }
    private void current(TrustedContext c) {
        if(c==null || c.deviceId()==null || c.ownerId()<=0 || c.agentId()==null || c.bindingVersion()==null || c.generation()<1) throw problem(403,"DEVICE_CONTEXT_REQUIRED");
        ContextGuard g=guard.getIfAvailable();
        if(g==null) throw problem(503,"DEVICE_CONTEXT_GUARD_UNAVAILABLE");
        g.requireCurrent(c);
    }
    private void binding(TrustedContext c) {
        ContextGuard g=guard.getIfAvailable();if(g==null)throw problem(503,"DEVICE_CONTEXT_GUARD_UNAVAILABLE");g.requireBinding(c);
    }
    public Map<String,Object> connect(TrustedContext c,String executionMode) {
        if(!Set.of("legacy_record_only","ai_worker").contains(executionMode)) throw problem(400,"INVALID_EXECUTION_MODE");
        return tx.execute(t->{
            current(c);
            var rows=jdbc.queryForList("SELECT * FROM esp32_device_session WHERE device_id=? AND binding_version=? FOR UPDATE",c.deviceId(),c.bindingVersion());
            if(rows.isEmpty()) {
                if(!conversations.ownsAgent(c.agentId(),c.ownerId())) throw problem(403,"AGENT_ACCESS_DENIED");
                String session=id(),conversation=id();
                conversations.insertConversation(conversation,c.ownerId(),c.agentId(),"ESP32 AI");
                jdbc.update("INSERT INTO esp32_device_session(id,device_id,binding_version,owner_user_id,agent_id,conversation_id,execution_mode,connection_id,generation,gateway_id) VALUES(?,?,?,?,?,?,?,?,?,?)",session,c.deviceId(),c.bindingVersion(),c.ownerId(),c.agentId(),conversation,executionMode,c.connectionId(),c.generation(),c.gatewayId());
                return session(session,true);
            }
            var s=rows.getFirst(); verify(c,s);
            if(!executionMode.equals(s.get("execution_mode"))) throw problem(409,"EXECUTION_MODE_FIXED");
            if(!c.connectionId().equals(s.get("connection_id")) || number(s,"generation")!=c.generation()) {
                invalidate((String)s.get("id"),"reconnected");
                jdbc.update("UPDATE esp32_device_session SET connection_id=?,generation=?,gateway_id=? WHERE id=?",c.connectionId(),c.generation(),c.gatewayId(),s.get("id"));
            }
            return session((String)s.get("id"),true);
        });
    }
    public Map<String,Object> input(TrustedContext c,String sessionId,String inputKey,String text) {
        return input(c,sessionId,inputKey,text,null);
    }
    public Map<String,Object> input(TrustedContext c,String sessionId,String inputKey,String text,String rolePrompt) {
        key(inputKey); text(text);
        if(rolePrompt!=null && (rolePrompt.isBlank() || rolePrompt.length()>16000)) throw problem(400,"INVALID_ROLE_PROMPT");
        return tx.execute(t->{
            current(c); var s=session(sessionId,true); verifyConnection(c,s);
            String fingerprint=hash(encode(List.of(c.deviceId(),c.bindingVersion(),c.ownerId(),c.agentId(),c.connectionId(),c.generation(),c.gatewayId(),text)));
            var previous=jdbc.queryForList("SELECT * FROM esp32_voice_turn WHERE session_id=? AND input_key=?",sessionId,inputKey);
            if(!previous.isEmpty()) {
                var p=previous.getFirst(); if(!fingerprint.equals(p.get("request_hash"))) throw problem(409,"INPUT_KEY_REUSED");
                return p;
            }
            long sequence=number(s,"last_turn_seq")+1;
            if(sequence>10000) throw problem(409,"SESSION_TURN_LIMIT");
            invalidate(sessionId,"superseded");
            s=session(sessionId,true);
            String turn=id(),message=id(),conversation=(String)s.get("conversation_id");
            append(conversation,message,"esp32:"+turn+":input",c.ownerId(),"user",text,"accepted");
            jdbc.update("INSERT INTO esp32_voice_turn(id,session_id,input_key,request_hash,turn_seq,control_version,connection_id,generation,execution_mode,input_message_id,status) VALUES(?,?,?,?,?,?,?,?,?,?,?)",turn,sessionId,inputKey,fingerprint,sequence,number(s,"control_version"),c.connectionId(),c.generation(),s.get("execution_mode"),message,"accepted");
            jdbc.update("UPDATE esp32_device_session SET last_turn_seq=? WHERE id=?",sequence,sessionId);
            if("ai_worker".equals(s.get("execution_mode"))) {
                var intent = intents == null ? null : intents.resolve(c.agentId(), text);
                if (intent == null) {
                    var executor=worker.getIfAvailable(); if(executor==null) throw problem(503,"DEVICE_WORKER_UNAVAILABLE");
                    if(rolePrompt==null)executor.submit(c,conversation,message,turn);
                    else executor.submit(c,conversation,message,turn,rolePrompt);
                } else {
                    String replyId = null;
                    if (!intent.reply().isEmpty()) {
                        replyId = id();
                        append(conversation,replyId,"esp32:"+turn+":output",0,"assistant",intent.reply(),"completed");
                    }
                    jdbc.update("""
                            UPDATE esp32_voice_turn SET intent_action=?,intent_rule=?,intent_version=?,
                            intent_reply=?,output_message_id=? WHERE id=?
                            """, intent.action(), intent.ruleId(), intent.version(), intent.reply(), replyId, turn);
                }
            }
            return turn(turn);
        });
    }
    public Map<String,Object> output(TrustedContext c,String sessionId,String turnId,String text) {
        text(text);
        return tx.execute(t->{current(c);var s=session(sessionId,true);verifyConnection(c,s);var v=turn(turnId);
            validTurn(s,v);
            if(!"legacy_record_only".equals(v.get("execution_mode"))) throw problem(409,"CANONICAL_WORKER_OWNS_OUTPUT");
            if("failed".equals(v.get("status")))throw problem(409,"TURN_FAILED");
            String h=hash(text);
            if(v.get("output_message_id")!=null) {if(!h.equals(v.get("output_hash"))) throw problem(409,"OUTPUT_REUSED");return v;}
            String message=id();append((String)s.get("conversation_id"),message,"esp32:"+turnId+":output",0,"assistant",text,"completed");
            jdbc.update("UPDATE esp32_voice_turn SET output_message_id=?,output_hash=?,status='completed' WHERE id=?",message,h,turnId);return turn(turnId);
        });
    }
    public Map<String,Object> report(TrustedContext c,String sessionId,String turnId,String eventKey,String eventType,String data) {
        key(eventKey);text(data);
        if(!Set.of("tool_result","failure").contains(eventType))throw problem(400,"INVALID_EVENT_TYPE");
        return tx.execute(t->{current(c);var session=session(sessionId,true);verifyConnection(c,session);var turn=turn(turnId);validTurn(session,turn);
            if(!"legacy_record_only".equals(turn.get("execution_mode")))throw problem(409,"CANONICAL_WORKER_OWNS_EVENTS");
            String payload=encode(Map.of("source","trusted_gateway","data",data));String fingerprint=hash(eventType+"|"+data);
            var prior=jdbc.queryForList("SELECT * FROM esp32_turn_report WHERE turn_id=? AND event_key=?",turnId,eventKey);
            if(!prior.isEmpty()){if(!fingerprint.equals(prior.getFirst().get("request_hash")))throw problem(409,"EVENT_KEY_REUSED");return prior.getFirst();}
            Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM esp32_turn_report WHERE turn_id=?",Integer.class,turnId);
            if(count!=null&&count>=32)throw problem(429,"TURN_EVENT_LIMIT");
            String event=id();jdbc.update("INSERT INTO esp32_turn_report(id,turn_id,event_key,event_type,request_hash,data_json) VALUES(?,?,?,?,?,?)",event,turnId,eventKey,eventType,fingerprint,payload);
            var conversation=conversations.byId((String)session.get("conversation_id"),true);
            conversations.insertEvent(id(),conversation.id(),conversation.lastEventSeq()+1,"esp32."+eventType,encode(Map.of("turn_id",turnId,"report_id",event,"source","trusted_gateway","data",data)));
            conversations.advance(conversation.id(),conversation.lastSeq(),conversation.lastEventSeq()+1);
            if("failure".equals(eventType))jdbc.update("UPDATE esp32_voice_turn SET status='failed',failure_code='GATEWAY_EXECUTION_FAILED' WHERE id=?",turnId);
            return jdbc.queryForMap("SELECT * FROM esp32_turn_report WHERE id=?",event);
        });
    }
    /** Root worker calls before publishing or returning a reply to the current gateway. */
    public boolean canPublish(String turnId) {
        var v=turn(turnId);var s=session((String)v.get("session_id"),false);
        try {validTurn(s,v);if("failed".equals(v.get("status")))return false;current(context(s));return true;} catch(org.springframework.web.server.ResponseStatusException | ConversationProblem e){return false;}
    }
    public TrustedContext contextForTurn(String turnId) {var v=turn(turnId);return context(session((String)v.get("session_id"),false));}
    public Map<String,Object> reply(TrustedContext c,String sessionId,String turnId) {
        return tx.execute(t->{current(c);var s=session(sessionId,true);verifyConnection(c,s);var v=turn(turnId);validTurn(s,v);
            if(!"ai_worker".equals(v.get("execution_mode")))throw problem(409,"LEGACY_EXECUTOR_OWNS_OUTPUT");
            if (v.get("intent_reply") instanceof String text && !text.isEmpty()) {
                return Map.of("status", "completed", "text", text, "replyId", v.get("output_message_id"));
            }
            var executor=worker.getIfAvailable();if(executor==null)throw problem(503,"DEVICE_WORKER_UNAVAILABLE");
            return executor.reply((String)s.get("conversation_id"),(String)v.get("input_message_id"));});
    }
    public void disconnect(TrustedContext c,String sessionId) {
        tx.executeWithoutResult(t->{current(c);var s=session(sessionId,true);verifyConnection(c,s);invalidate(sessionId,"disconnected");});
    }
    public void abort(TrustedContext c,String sessionId) {
        tx.executeWithoutResult(t->{current(c);var s=session(sessionId,true);verifyConnection(c,s);invalidate(sessionId,"aborted");});
    }
    /** Conditional cancellation: a delayed old abort cannot advance a newer turn. */
    public void abortTurn(TrustedContext c,String sessionId,String turnId,long controlVersion) {
        if(turnId==null||!turnId.matches("[a-f0-9]{32}")||controlVersion<0)throw problem(400,"INVALID_ABORT_TURN");
        tx.executeWithoutResult(t->{
            current(c);var session=session(sessionId,true);verifyConnection(c,session);
            var rows=jdbc.queryForList("SELECT * FROM esp32_voice_turn WHERE id=? FOR UPDATE",turnId);
            if(rows.size()!=1)throw problem(409,"STALE_ABORT");var voice=rows.getFirst();
            if(!session.get("id").equals(voice.get("session_id"))||number(session,"control_version")!=controlVersion
                    ||number(voice,"control_version")!=controlVersion||number(session,"last_turn_seq")!=number(voice,"turn_seq")
                    ||!session.get("connection_id").equals(voice.get("connection_id"))||number(session,"generation")!=number(voice,"generation")
                    ||!"accepted".equals(voice.get("status")))throw problem(409,"STALE_ABORT");
            invalidate(sessionId,"aborted");
        });
    }
    public List<Map<String,Object>> list(long owner,String deviceId,int limit) {
        if(owner<=0)throw problem(401,"AUTHENTICATION_REQUIRED");if(limit<1||limit>100)throw problem(400,"INVALID_LIMIT");
        // A trusted registry guard rechecks binding ownership, including unbind/rebind revocation.
        var rows=jdbc.queryForList("SELECT * FROM esp32_device_session WHERE owner_user_id=? AND device_id=? ORDER BY created_at DESC LIMIT ?",owner,deviceId,limit);
        return rows.stream().filter(s->{try{binding(context(s));return true;}catch(org.springframework.web.server.ResponseStatusException | ConversationProblem e){return false;}}).toList();
    }
    public List<Map<String,Object>> turns(long owner,String sessionId,long after,int limit) {
        if(after<0||limit<1||limit>100)throw problem(400,"INVALID_PAGE");var s=session(sessionId,false);
        if(owner<=0||number(s,"owner_user_id")!=owner)throw problem(404,"SESSION_NOT_FOUND");binding(context(s));
        return jdbc.queryForList("SELECT * FROM esp32_voice_turn WHERE session_id=? AND turn_seq>? ORDER BY turn_seq LIMIT ?",sessionId,after,limit);
    }
    private TrustedContext context(Map<String,Object>s){return new TrustedContext((String)s.get("device_id"),(String)s.get("binding_version"),number(s,"owner_user_id"),(String)s.get("agent_id"),(String)s.get("connection_id"),number(s,"generation"),(String)s.get("gateway_id"));}
    private void validTurn(Map<String,Object>s,Map<String,Object>v){if(!s.get("id").equals(v.get("session_id"))||number(s,"control_version")!=number(v,"control_version")||number(s,"last_turn_seq")!=number(v,"turn_seq")||!s.get("connection_id").equals(v.get("connection_id"))||number(s,"generation")!=number(v,"generation")||"canceled".equals(v.get("status")))throw problem(409,"TURN_SUPERSEDED");}
    private void invalidate(String session,String reason){jdbc.update("UPDATE esp32_device_session SET control_version=control_version+1 WHERE id=?",session);jdbc.update("UPDATE esp32_voice_turn SET status='canceled',failure_code=? WHERE session_id=? AND status='accepted'",reason,session);}
    private void verify(TrustedContext c,Map<String,Object>s){if(c.ownerId()!=number(s,"owner_user_id")||!c.agentId().equals(s.get("agent_id"))||!c.bindingVersion().equals(s.get("binding_version"))||!c.deviceId().equals(s.get("device_id")))throw problem(403,"DEVICE_BINDING_CHANGED");}
    private void verifyConnection(TrustedContext c,Map<String,Object>s){verify(c,s);if(!c.connectionId().equals(s.get("connection_id"))||c.generation()!=number(s,"generation"))throw problem(409,"STALE_CONNECTION");}
    private Map<String,Object> session(String id,boolean lock){var rows=jdbc.queryForList("SELECT * FROM esp32_device_session WHERE id=?"+(lock?" FOR UPDATE":""),id);if(rows.isEmpty())throw problem(404,"SESSION_NOT_FOUND");return rows.getFirst();}
    private Map<String,Object> turn(String id){var rows=jdbc.queryForList("SELECT * FROM esp32_voice_turn WHERE id=?",id);if(rows.isEmpty())throw problem(404,"TURN_NOT_FOUND");return rows.getFirst();}
    private void append(String conversation,String message,String client,long sender,String role,String text,String status){
        var c=conversations.byId(conversation,true);String parts=encode(List.of(Map.of("type","text","text",text)));long seq=c.lastSeq()+1,event=c.lastEventSeq()+1;
        conversations.insertMessage(message,conversation,seq,client,sender,parts,hash(parts),role,status);
        conversations.insertEvent(id(),conversation,event,"esp32.message.accepted",encode(Map.of("message_id",message,"seq",seq,"source","trusted_gateway")));
        conversations.advance(conversation,seq,event);
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private static long number(Map<String,Object>s,String k){return ((Number)s.get(k)).longValue();}
    private static String id(){return UUID.randomUUID().toString().replace("-","");}
    private static void key(String k){if(k==null||!k.matches("[A-Za-z0-9_.:-]{1,64}"))throw problem(400,"INVALID_INPUT_KEY");}
    private static void text(String s){if(s==null||s.isBlank()||s.length()>16000)throw problem(400,"TEXT_REQUIRED_MAX_16000");}
    private static String hash(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static ConversationProblem problem(int status,String code){return new ConversationProblem(status,code);}
}
