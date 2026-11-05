package xiaozhi.modules.realtimetool;

import static xiaozhi.modules.realtime.RealtimeStore.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import xiaozhi.modules.agenttool.AgentToolExecutionCore;
import xiaozhi.modules.agenttool.AgentToolExecutionCore.Context;
import xiaozhi.modules.externalidentity.ExternalIdentityService;
import xiaozhi.modules.externalidentity.ExternalModels.Principal;
import xiaozhi.modules.realtime.*;
import xiaozhi.modules.realtime.RealtimeModels.*;
import xiaozhi.modules.realtimetool.RealtimeToolModels.*;

/** Independent realtime execution ledger. Conversation -> session -> response -> call locks; IO is outside transactions. */
@Service
public class RealtimeToolService {
    private final RealtimeStore store;private final ObjectMapper json;private final Environment env;private final AgentToolExecutionCore core;
    private ExternalIdentityService identity;
    private xiaozhi.modules.agenttool.PublicToolResultStore publicResults;
    @Autowired(required=false) public void setPublicResults(xiaozhi.modules.agenttool.PublicToolResultStore value){publicResults=value;}
    public static final Policy POLICY=new Policy(3,6,4096,32768,30,128000,24,131072,32,100000);
    public RealtimeToolService(RealtimeStore store,ObjectMapper json,Environment env,AgentToolExecutionCore core){this.store=store;this.json=json;this.env=env;this.core=core;}
    @Autowired(required=false) public void setIdentity(ExternalIdentityService value){identity=value;}
    public boolean enabled(){return env.getProperty("features.realtime-tools-enabled",Boolean.class,false);}
    public boolean bound(Map<String,Object> s){return !store.rows("SELECT session_id FROM ai_realtime_tool_binding WHERE session_id=?",s.get("id")).isEmpty();}
    public Long generation(Map<String,Object> s){var rows=store.rows("SELECT turn_generation FROM ai_realtime_tool_binding WHERE session_id=?",s.get("id"));return rows.isEmpty()?null:number(rows.getFirst(),"turn_generation");}
    public void prepare(Map<String,Object> s,Principal principal){
        if(!enabled())return;
        if(principal!=null){
            if(identity==null || principal.userId()!=number(s,"owner_user_id") || !(s.get("external_source_id") instanceof Number)
                    || principal.sourceId()!=number(s,"external_source_id") || principal.tokenId()!=number(s,"external_token_id"))throw problem(403,"REALTIME_TOOL_BINDING_INVALID");
            Principal current=identity.authenticateHash(principal.tokenHash());if(!ExternalIdentityService.sameBinding(principal,current))throw problem(403,"REALTIME_TOOL_BINDING_INVALID");principal=current;
        }else if(s.get("external_source_id")!=null)throw problem(403,"REALTIME_TOOL_BINDING_INVALID");
        String fingerprint=principal==null?null:core.fingerprint(principal,true);
        boolean technical=env.getProperty("agent-tools.technical-local-enabled",Boolean.class,false);
        if(core.catalog(new Context(string(s,"conversation_id"),number(s,"owner_user_id"),principal,fingerprint,technical,number(s,"context_seq"))).isEmpty())return;
        store.jdbc().update("INSERT INTO ai_realtime_tool_binding(session_id,owner_user_id,control_epoch,principal_json,source_config_hash,technical_allowed,message_cutoff) VALUES(?,?,?,?,?,?,?)",
                s.get("id"),s.get("owner_user_id"),s.get("control_epoch"),principal==null?null:encode(principal),fingerprint,
                technical?1:0,s.get("context_seq"));
    }
    public Catalog catalog(Map<String,Object> s){
        if(!bound(s))return new Catalog(null,null,null);
        requireEnabled();var b=binding(s);var context=context(s,b);List<JsonNode> tools=core.catalog(context);
        return new Catalog(tools.isEmpty()?null:tools,tools.isEmpty()?null:POLICY,number(b,"turn_generation"));
    }
    public void validateSession(Map<String,Object> s){if(bound(s)){requireEnabled();context(s,binding(s));}}
    private Context context(Map<String,Object> s,Map<String,Object> b){
        Principal original=null;
        if(b.get("principal_json")!=null){try{original=json.readValue(string(b,"principal_json"),Principal.class);}catch(Exception invalid){throw problem(403,"REALTIME_TOOL_BINDING_INVALID");}}
        Context context=new Context(string(s,"conversation_id"),number(b,"owner_user_id"),original,string(b,"source_config_hash"),yes(b,"technical_allowed"),number(b,"message_cutoff"));
        Principal current=core.validateContext(context);
        return new Context(context.conversationId(),context.ownerId(),current,context.sourceConfigFingerprint(),context.technicalLocalAllowed(),context.messageCutoff());
    }
    /** All callers already hold conversation/session locks. New input invalidates old results before audio commit. */
    public long invalidate(Map<String,Object> s,String reason,Long messageCutoff){
        if(!bound(s))return 0;var b=binding(s);long next=Math.addExact(number(b,"turn_generation"),1);
        discard(s,"REALTIME_TOOL_SUPERSEDED");
        store.jdbc().update("UPDATE ai_realtime_tool_binding SET turn_generation=?,current_response_id=NULL,message_cutoff=? WHERE session_id=?",next,
                messageCutoff==null?number(b,"message_cutoff"):Math.max(number(b,"message_cutoff"),messageCutoff),s.get("id"));
        for(var turn:store.rows("SELECT * FROM ai_realtime_turn WHERE session_id=? AND active_slot=1 FOR UPDATE",s.get("id"))){
            store.jdbc().update("UPDATE ai_realtime_turn SET status='canceled',ended_at=CURRENT_TIMESTAMP(3) WHERE id=?",turn.get("id"));
            store.jdbc().update("UPDATE ai_message SET status='canceled' WHERE id=?",turn.get("message_id"));
            event(s,"assistant.message.canceled",Map.of("message_id",turn.get("message_id"),"response_id",turn.get("provider_item_id"),"source","provider_realtime","turn_generation",next,"error_code",reason));
        }
        event(s,"realtime.turn.changed",Map.of("turn_generation",next,"reason",reason));return next;
    }
    public void inputStarted(Map<String,Object> s,ProviderEvent e){guardGeneration(s,e.turnGeneration());invalidate(s,"INPUT_STARTED",null);}
    public void guardEvent(Map<String,Object> s,ProviderEvent e){
        if(!bound(s)){
            if(Set.of("input.started","response.step.completed","tool.result.ack","response.continue").contains(e.type()) || e.turnGeneration()!=null || e.continuationId()!=null || e.providerCallId()!=null)
                throw problem(409,"REALTIME_TOOLS_DISABLED");return;
        }
        validateSession(s);
        if(e.type().startsWith("response.") || e.type().equals("tool.result.ack") || e.type().equals("input.started")){
            // Late cancellation may retain real usage, but can never publish content or authorize continuation.
            if(e.type().equals("response.canceled")) {var r=response(s,e.responseId());if(e.turnGeneration()==null || number(r,"turn_generation")!=e.turnGeneration() || !Objects.equals(r.get("lease_hash"),s.get("lease_hash")) || number(r,"control_epoch")!=number(s,"control_epoch"))throw problem(409,"REALTIME_TOOL_GENERATION_CONFLICT");return;}
            guardGeneration(s,e.turnGeneration());
        }
        if(e.responseId()!=null && !e.type().equals("response.started") && (e.type().startsWith("response.") || e.type().equals("tool.result.ack"))){
            var r=response(s,e.responseId());if(number(r,"turn_generation")!=e.turnGeneration() || number(r,"control_epoch")!=number(s,"control_epoch") || !Objects.equals(r.get("lease_hash"),s.get("lease_hash")))throw problem(409,"REALTIME_TOOL_RESPONSE_FENCED");
        }
        if(Set.of("response.delta","response.completed","response.step.completed").contains(e.type())){
            var r=response(s,e.responseId());if(!"streaming".equals(r.get("status")) || !Objects.equals(binding(s).get("current_response_id"),e.responseId()))throw problem(409,"REALTIME_TOOL_RESPONSE_FENCED");
        }
    }
    /** true means a continuation reused the existing logical assistant turn; false means caller creates its first turn. */
    public boolean resume(Map<String,Object> s,ProviderEvent e){
        if(!bound(s))return false;
        guardGeneration(s,e.turnGeneration());
        if(e.continuationId()==null){
            if(!store.rows("SELECT id FROM ai_realtime_provider_response WHERE session_id=? AND turn_generation=?",s.get("id"),e.turnGeneration()).isEmpty())throw problem(409,"REALTIME_TOOL_CONTINUATION_REQUIRED");return false;
        }
        var previous=store.one("SELECT * FROM ai_realtime_provider_response WHERE session_id=? AND continuation_id=? FOR UPDATE",s.get("id"),e.continuationId());
        if(number(previous,"turn_generation")!=e.turnGeneration() || !"continued".equals(previous.get("status")) || previous.get("continued_response_id")!=null
                || !Objects.equals(previous.get("lease_hash"),s.get("lease_hash")))throw problem(409,"REALTIME_TOOL_CONTINUATION_INVALID");
        var turn=store.one("SELECT * FROM ai_realtime_turn WHERE id=? FOR UPDATE",previous.get("logical_turn_id"));
        if(!"streaming".equals(turn.get("status")))throw problem(409,"REALTIME_TOOL_RESPONSE_FENCED");
        store.jdbc().update("UPDATE ai_realtime_turn SET provider_item_id=? WHERE id=?",e.responseId(),turn.get("id"));
        registerResponse(s,e,turn,(int)number(previous,"step_number")+1,string(turn,"output_text"));
        store.jdbc().update("UPDATE ai_realtime_provider_response SET continued_response_id=? WHERE id=?",e.responseId(),previous.get("id"));return true;
    }
    public void started(Map<String,Object> s,ProviderEvent e,Map<String,Object> turn){if(bound(s))registerResponse(s,e,turn,1,"");}
    private void registerResponse(Map<String,Object> s,ProviderEvent e,Map<String,Object> turn,int step,String prefix){
        var b=binding(s);long count=count("SELECT COUNT(*) FROM ai_realtime_provider_response WHERE session_id=?",s.get("id"));
        if(step>4 || count>=POLICY.maxSessionProviderSteps() || knownTokens(s)>=POLICY.maxSessionTokens())throw problem(409,"REALTIME_TOOL_SESSION_BUDGET");
        long latest=count("SELECT COALESCE(MAX(seq),0) FROM ai_message WHERE conversation_id=? AND sender_type='user'",s.get("conversation_id"));
        store.jdbc().update("UPDATE ai_realtime_tool_binding SET current_response_id=?,message_cutoff=? WHERE session_id=?",e.responseId(),Math.max(number(b,"message_cutoff"),latest),s.get("id"));
        store.jdbc().update("INSERT INTO ai_realtime_provider_response(id,session_id,provider_session_id,response_id,turn_generation,control_epoch,lease_hash,logical_turn_id,message_id,step_number,status,prefix_text) VALUES(?,?,?,?,?,?,?,?,?,?,'streaming',?)",
                id(),s.get("id"),s.get("provider_session_id"),e.responseId(),e.turnGeneration(),s.get("control_epoch"),s.get("lease_hash"),turn.get("id"),turn.get("message_id"),step,prefix);
    }
    public boolean stepCompleted(Map<String,Object> s,ProviderEvent e){
        var r=response(s,e.responseId());if(!"tool_calls".equals(e.finishReason()))throw problem(400,"REALTIME_TOOL_STEP_INVALID");
        saveUsage(r,e);
        if(number(r,"step_number")>POLICY.maxRounds() || sessionBudgetExceeded(s)){
            store.jdbc().update("UPDATE ai_realtime_provider_response SET status='canceled',ended_at=CURRENT_TIMESTAMP(3) WHERE id=?",r.get("id"));
            event(s,"realtime.tool.budget_exceeded",Map.of("response_id",e.responseId(),"turn_generation",e.turnGeneration(),"error_code","TOOL_LIMIT_EXCEEDED"));return false;
        }
        store.jdbc().update("UPDATE ai_realtime_provider_response SET status='waiting_tools',ended_at=CURRENT_TIMESTAMP(3) WHERE id=?",r.get("id"));
        event(s,"realtime.response.waiting_tools",Map.of("response_id",e.responseId(),"message_id",r.get("message_id"),"turn_generation",e.turnGeneration()));
        return true;
    }
    public Usage complete(Map<String,Object> s,ProviderEvent e){
        if(!bound(s))return e.usage();var r=response(s,e.responseId());saveUsage(r,e);
        store.jdbc().update("UPDATE ai_realtime_provider_response SET status=?,ended_at=CURRENT_TIMESTAMP(3) WHERE id=?",e.type().equals("response.canceled")?"canceled":"completed",r.get("id"));
        if(e.type().equals("response.completed"))store.jdbc().update("UPDATE ai_realtime_tool_binding SET current_response_id=NULL WHERE session_id=?",s.get("id"));
        var aggregate=store.one("SELECT COUNT(*) AS steps,COUNT(u.total_tokens) AS known,SUM(u.input_tokens) AS inputs,SUM(u.output_tokens) AS outputs,SUM(u.total_tokens) AS totals FROM ai_realtime_provider_response r LEFT JOIN ai_realtime_response_usage u ON u.response_row_id=r.id WHERE r.logical_turn_id=?",r.get("logical_turn_id"));
        if(number(aggregate,"steps")!=number(aggregate,"known"))return null;
        return new Usage(number(aggregate,"inputs"),number(aggregate,"outputs"),number(aggregate,"totals"));
    }
    public String completedText(Map<String,Object> s,ProviderEvent e){if(!bound(s))return e.text();return string(response(s,e.responseId()),"prefix_text")+e.text();}
    private void saveUsage(Map<String,Object> r,ProviderEvent e){
        Usage usage=e.usage();var old=store.rows("SELECT * FROM ai_realtime_response_usage WHERE response_row_id=?",r.get("id"));
        if(usage!=null && (usage.inputTokens()>1000000000L || usage.outputTokens()>1000000000L || usage.totalTokens()>1000000000L))throw problem(400,"INVALID_USAGE");
        if(!old.isEmpty())throw problem(409,"REALTIME_RESPONSE_USAGE_REUSED");
        store.jdbc().update("INSERT INTO ai_realtime_response_usage(response_row_id,provider_event_id,finish_reason,input_tokens,output_tokens,total_tokens) VALUES(?,?,?,?,?,?)",
                r.get("id"),e.providerEventId(),e.finishReason(),usage==null?null:usage.inputTokens(),usage==null?null:usage.outputTokens(),usage==null?null:usage.totalTokens());
    }
    public Map<String,Object> logicalTurn(Map<String,Object> s,String responseId){return store.one("SELECT t.* FROM ai_realtime_turn t JOIN ai_realtime_provider_response r ON r.logical_turn_id=t.id WHERE r.session_id=? AND r.response_id=? FOR UPDATE",s.get("id"),responseId);}
    public String continuation(Map<String,Object> s,ProviderEvent e){if(!e.type().equals("response.continue") || !bound(s))return null;return string(response(s,e.responseId()),"continuation_id");}
    public void acknowledge(Map<String,Object> s,ProviderEvent e){
        var r=response(s,e.responseId());if(!"waiting_tools".equals(r.get("status")))throw problem(409,"REALTIME_TOOL_ACK_INVALID");
        var call=store.one("SELECT * FROM ai_realtime_tool_call WHERE response_row_id=? AND provider_call_id=? FOR UPDATE",r.get("id"),e.providerCallId());
        if(!Set.of("succeeded","failed").contains(call.get("status")) || !Objects.equals(call.get("result_item_id"),e.requestItemId())
                || instant(call,"finished_at")==null || instant(call,"finished_at").plusSeconds(30).isBefore(Instant.now()))throw problem(409,"REALTIME_TOOL_ACK_INVALID");
        if(call.get("acknowledged_at")!=null){if(!Objects.equals(call.get("ack_provider_item_id"),e.providerItemId()))throw problem(409,"REALTIME_TOOL_ACK_REUSED");return;}
        if(!store.rows("SELECT id FROM ai_realtime_tool_call WHERE session_id=? AND ack_provider_item_id=?",s.get("id"),e.providerItemId()).isEmpty()
                || !store.rows("SELECT id FROM ai_realtime_turn WHERE session_id=? AND provider_item_id=?",s.get("id"),e.providerItemId()).isEmpty()
                || !store.rows("SELECT id FROM ai_realtime_injection WHERE session_id=? AND provider_item_id=?",s.get("id"),e.providerItemId()).isEmpty())throw problem(409,"REALTIME_TOOL_ACK_REUSED");
        store.jdbc().update("UPDATE ai_realtime_tool_call SET acknowledged_at=CURRENT_TIMESTAMP(3),ack_provider_item_id=? WHERE id=?",e.providerItemId(),call.get("id"));audit(s,call,"acknowledged",null,0,null);
    }
    public void authorizeContinue(Map<String,Object> s,ProviderEvent e){
        var r=response(s,e.responseId());if(!"waiting_tools".equals(r.get("status")))throw problem(409,"REALTIME_TOOL_CONTINUATION_INVALID");
        var calls=store.rows("SELECT * FROM ai_realtime_tool_call WHERE response_row_id=? FOR UPDATE",r.get("id"));
        if(calls.isEmpty() || calls.stream().anyMatch(call->!Set.of("succeeded","failed").contains(call.get("status")) || call.get("acknowledged_at")==null))throw problem(409,"REALTIME_TOOL_ACK_REQUIRED");
        if(elapsed(s,e.turnGeneration())>30000 || knownTokens(s)>=POLICY.maxSessionTokens())throw problem(409,"REALTIME_TOOL_SESSION_BUDGET");
        store.jdbc().update("UPDATE ai_realtime_provider_response SET status='continued',continuation_id=? WHERE id=?",id(),r.get("id"));
        event(s,"realtime.response.continue_authorized",Map.of("response_id",e.responseId(),"turn_generation",e.turnGeneration()));
    }
    private record Reserved(Map<String,Object> call,boolean execute){}
    public Results proposals(String sessionId,Proposal body){
        requireEnabled();validate(body);
        List<Reserved> reserved=store.tx(()->{
            var s=lockSession(sessionId);fence(s,body);var b=binding(s);context(s,b);var r=response(s,body.responseId());
            if(!Objects.equals(s.get("provider_session_id"),body.providerSessionId()) || !Objects.equals(r.get("provider_session_id"),body.providerSessionId())
                    || !"waiting_tools".equals(r.get("status")) || number(r,"step_number")!=body.round() || number(r,"turn_generation")!=body.turnGeneration()
                    || number(r,"control_epoch")!=body.controlEpoch() || !Objects.equals(r.get("lease_hash"),s.get("lease_hash")))throw problem(409,"REALTIME_TOOL_RESPONSE_FENCED");
            List<Reserved> rows=new ArrayList<>();int fresh=0;
            for(Call call:body.calls()){
                core.validate(call.name(),call.arguments());var previous=store.rows("SELECT * FROM ai_realtime_tool_call WHERE session_id=? AND provider_call_id=? FOR UPDATE",sessionId,call.providerCallId());
                if(!previous.isEmpty()){
                    var old=previous.getFirst();if(!Objects.equals(old.get("response_row_id"),r.get("id")) || !call.providerItemId().equals(old.get("provider_item_id"))
                            || !call.name().equals(old.get("tool_name")) || !hash(canonical(call.arguments())).equals(old.get("arguments_hash")))throw problem(409,"REALTIME_TOOL_CALL_CONFLICT");
                    if(!Set.of("succeeded","failed").contains(old.get("status")))throw problem(409,"REALTIME_TOOL_RESULT_UNCERTAIN");rows.add(new Reserved(old,false));
                }else{fresh++;rows.add(new Reserved(Map.of("proposal",call),true));}
            }
            if(fresh>0 && count("SELECT COUNT(*) FROM ai_realtime_tool_call WHERE response_row_id=?",r.get("id"))>0)throw problem(409,"REALTIME_TOOL_BATCH_CONFLICT");
            if(count("SELECT COUNT(*) FROM ai_realtime_tool_call WHERE session_id=?",sessionId)+fresh>24
                    || count("SELECT COUNT(*) FROM ai_realtime_tool_call WHERE session_id=? AND turn_generation=?",sessionId,body.turnGeneration())+fresh>6
                    || elapsed(s,body.turnGeneration())>30000 || knownTokens(s)>=100000)throw problem(409,"REALTIME_TOOL_SESSION_BUDGET");
            List<Reserved> result=new ArrayList<>();
            for(var row:rows){if(!row.execute()){result.add(row);continue;}Call call=(Call)row.call().get("proposal");String id=id();
                store.jdbc().update("INSERT INTO ai_realtime_tool_call(id,session_id,response_row_id,provider_call_id,provider_item_id,result_item_id,tool_name,arguments_json,arguments_hash,turn_generation,control_epoch,lease_hash,round_number,status) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,'running')",
                        id,sessionId,r.get("id"),call.providerCallId(),call.providerItemId(),"rt-tool-result-"+id,call.name(),canonical(call.arguments()),hash(canonical(call.arguments())),body.turnGeneration(),body.controlEpoch(),s.get("lease_hash"),body.round());
                var inserted=store.one("SELECT * FROM ai_realtime_tool_call WHERE id=?",id);
                store.jdbc().update("INSERT INTO ai_realtime_tool_attempt(id,call_id,lease_hash,control_epoch,turn_generation) VALUES(?,?,?,?,?)",id(),id,s.get("lease_hash"),body.controlEpoch(),body.turnGeneration());
                audit(s,inserted,"running",null,0,null);result.add(new Reserved(inserted,true));
            }return result;
        });
        List<ToolResult> results=new ArrayList<>();
        for(Reserved row:reserved){
            if(!row.execute()){store.tx(()->{var s=lockSession(sessionId);fence(s,body);context(s,binding(s));return null;});results.add(result(row.call()));continue;}
            long start=System.nanoTime();JsonNode output=null;String error=null;
            try{
                Context trusted=store.tx(()->{var s=lockSession(sessionId);fence(s,body);if(elapsed(s,body.turnGeneration())>30000)throw problem(409,"REALTIME_TOOL_TIME_BUDGET");return context(s,binding(s));});
                output=core.execute(string(row.call(),"tool_name"),decode(string(row.call(),"arguments_json")),trusted);
                if(output==null || !output.isObject() || bytes(encode(output))>32768)throw problem(502,"REALTIME_TOOL_RESULT_LIMIT");
            }catch(xiaozhi.modules.conversation.ConversationProblem denied){error=safeError(denied.getMessage());}catch(Exception unavailable){error="TOOL_EXECUTION_FAILED";}
            final JsonNode value=output;final String code=error;long duration=(System.nanoTime()-start)/1000000;
            boolean accepted=store.tx(()->{
                var s=lockSession(sessionId);var call=store.one("SELECT * FROM ai_realtime_tool_call WHERE id=? FOR UPDATE",row.call().get("id"));if(!"running".equals(call.get("status")))return false;
                try{fence(s,body);context(s,binding(s));}
                catch(xiaozhi.modules.conversation.ConversationProblem denied){store.jdbc().update("UPDATE ai_realtime_tool_call SET status='discarded',error_code=?,finished_at=CURRENT_TIMESTAMP(3) WHERE id=?",safeError(denied.getMessage()),call.get("id"));audit(s,call,"discarded",safeError(denied.getMessage()),duration,null);return false;}
                String finalCode=code;String encoded=code==null?encode(value):null;int size=encoded==null?0:bytes(encoded);
                if(elapsed(s,body.turnGeneration())>30000)finalCode="REALTIME_TOOL_TIME_BUDGET";
                if(count("SELECT COALESCE(SUM(result_bytes),0) FROM ai_realtime_tool_call WHERE session_id=?",sessionId)+size>131072
                        || count("SELECT COALESCE(SUM(result_bytes),0) FROM ai_realtime_tool_call WHERE session_id=? AND turn_generation=?",sessionId,body.turnGeneration())+size>32768)finalCode="REALTIME_TOOL_RESULT_LIMIT";
                String state=finalCode==null?"succeeded":"failed";if(finalCode!=null){encoded=null;size=0;}
                store.jdbc().update("UPDATE ai_realtime_tool_call SET status=?,result_json=?,error_code=?,result_bytes=?,finished_at=CURRENT_TIMESTAMP(3) WHERE id=?",state,encoded,finalCode,size,call.get("id"));
                audit(s,call,state,finalCode,duration,encoded==null?null:hash(encoded));return true;
            });
            if(!accepted)throw problem(409,"REALTIME_TOOL_RESULT_FENCED");results.add(result(store.one("SELECT * FROM ai_realtime_tool_call WHERE id=?",row.call().get("id"))));
        }
        return store.tx(()->{var s=lockSession(sessionId);fence(s,body);context(s,binding(s));return new Results(List.copyOf(results),(int)(6-count("SELECT COUNT(*) FROM ai_realtime_tool_call WHERE session_id=? AND turn_generation=?",sessionId,body.turnGeneration())),body.turnGeneration());});
    }
    public void discard(Map<String,Object> s,String reason){
        if(!bound(s))return;
        for(var call:store.rows("SELECT * FROM ai_realtime_tool_call WHERE session_id=? AND status='running' FOR UPDATE",s.get("id"))){store.jdbc().update("UPDATE ai_realtime_tool_call SET status='discarded',error_code=?,finished_at=CURRENT_TIMESTAMP(3) WHERE id=?",reason,call.get("id"));audit(s,call,"discarded",reason,0,null);}
        store.jdbc().update("UPDATE ai_realtime_provider_response SET status='discarded',ended_at=CURRENT_TIMESTAMP(3) WHERE session_id=? AND (status IN ('streaming','waiting_tools') OR (status='continued' AND continued_response_id IS NULL))",s.get("id"));
    }
    private void guardGeneration(Map<String,Object> s,Long expected){requireEnabled();var b=binding(s);if(expected==null || expected!=number(b,"turn_generation"))throw problem(409,"REALTIME_TOOL_GENERATION_CONFLICT");}
    private Map<String,Object> lockSession(String sessionId){var hint=store.session(sessionId,false);store.conversation(string(hint,"conversation_id"));return store.session(sessionId,true);}
    private void fence(Map<String,Object> s,Proposal p){
        requireEnabled();var c=store.conversation(string(s,"conversation_id"));guardGeneration(s,p.turnGeneration());
        if(!env.getProperty("features.realtime-ai-enabled",Boolean.class,false) || s.get("external_source_id")!=null && !env.getProperty("features.external-realtime-media-enabled",Boolean.class,false)
                || "server_vad".equals(s.get("turn_detection_mode")) && !env.getProperty("features.realtime-server-vad-enabled",Boolean.class,false)
                || !"active".equals(s.get("status")) || !"AI_ACTIVE".equals(c.get("state")) || number(c,"control_epoch")!=p.controlEpoch() || number(s,"control_epoch")!=p.controlEpoch()
                || !Objects.equals(s.get("lease_hash"),hash(p.leaseToken())) || instant(s,"lease_expires_at")==null || !instant(s,"lease_expires_at").isAfter(Instant.now())
                || count("SELECT COUNT(*) FROM sys_user WHERE id=? AND status=1",s.get("owner_user_id"))!=1
                || Duration.between(instant(s,"created_at"),Instant.now()).toSeconds()>=1800)throw problem(409,"REALTIME_TOOL_LEASE_INVALID");
    }
    private void validate(Proposal p){
        if(p==null || p.leaseToken()==null || !p.leaseToken().matches("[a-f0-9]{64}") || p.controlEpoch()==null || p.turnGeneration()==null || p.turnGeneration()<0
                || p.round()<1 || p.round()>3 || !providerId(p.providerSessionId()) || !providerId(p.responseId()) || p.calls()==null || p.calls().isEmpty() || p.calls().size()>6)throw problem(400,"REALTIME_TOOL_PROPOSAL_INVALID");
        Set<String> ids=new HashSet<>(),items=new HashSet<>();for(Call call:p.calls())if(call==null || !providerId(call.providerCallId()) || !providerId(call.providerItemId()) || !ids.add(call.providerCallId()) || !items.add(call.providerItemId())
                || call.arguments()==null || !call.arguments().isObject() || bytes(encode(call.arguments()))>4096)throw problem(400,"REALTIME_TOOL_PROPOSAL_INVALID");
    }
    private Map<String,Object> binding(Map<String,Object> s){var b=store.one("SELECT * FROM ai_realtime_tool_binding WHERE session_id=? FOR UPDATE",s.get("id"));if(number(b,"owner_user_id")!=number(s,"owner_user_id") || number(b,"control_epoch")!=number(s,"control_epoch"))throw problem(403,"REALTIME_TOOL_BINDING_INVALID");return b;}
    private Map<String,Object> response(Map<String,Object> s,String response){return store.one("SELECT * FROM ai_realtime_provider_response WHERE session_id=? AND response_id=? FOR UPDATE",s.get("id"),response);}
    private long knownTokens(Map<String,Object> s){return count("SELECT COALESCE(SUM(u.total_tokens),0) FROM ai_realtime_response_usage u JOIN ai_realtime_provider_response r ON r.id=u.response_row_id WHERE r.session_id=?",s.get("id"));}
    public boolean sessionBudgetExceeded(Map<String,Object> s){return bound(s) && knownTokens(s)>POLICY.maxSessionTokens();}
    private long elapsed(Map<String,Object> s,long generation){return count("SELECT COALESCE(TIMESTAMPDIFF(MICROSECOND,MIN(created_at),CURRENT_TIMESTAMP(3))/1000,0) FROM ai_realtime_provider_response WHERE session_id=? AND turn_generation=?",s.get("id"),generation);}
    private long count(String sql,Object...args){return store.jdbc().queryForObject(sql,Long.class,args);}
    private ToolResult result(Map<String,Object> call){String error=string(call,"error_code");JsonNode output=call.get("result_json")==null?json.createObjectNode().put("error_code",error==null?"TOOL_EXECUTION_FAILED":error):decode(string(call,"result_json"));return new ToolResult(string(call,"provider_call_id"),string(call,"provider_item_id"),string(call,"result_item_id"),string(call,"tool_name"),string(call,"status"),output,error);}
    private void audit(Map<String,Object> s,Map<String,Object> call,String state,String error,long elapsed,String resultHash){
        Map<String,Object> data=new LinkedHashMap<>();data.put("tool_call_id",call.get("id"));data.put("name",call.get("tool_name"));data.put("status",state);data.put("turn_generation",call.get("turn_generation"));if(error!=null)data.put("error_code",error);
        long seq=event(s,"realtime.tool."+state,data);store.jdbc().update("INSERT INTO ai_realtime_tool_audit(call_id,event_sequence,state,error_code,elapsed_ms,result_hash) VALUES(?,?,?,?,?,?)",call.get("id"),seq,state,error,elapsed,resultHash);
        if(publicResults!=null && Set.of("succeeded","failed").contains(state)){
            var fresh=new LinkedHashMap<>(store.one("SELECT * FROM ai_realtime_tool_call WHERE id=?",call.get("id")));
            fresh.put("response_id",store.one("SELECT response_id FROM ai_realtime_provider_response WHERE id=?",fresh.get("response_row_id")).get("response_id"));
            fresh.put("generation",fresh.get("turn_generation"));publicResults.publishRealtime(s,fresh,seq,state);
        }
    }
    private long event(Map<String,Object> s,String type,Map<String,Object> data){var c=store.conversation(string(s,"conversation_id"));long seq=number(c,"last_event_seq")+1;Map<String,Object> payload=new LinkedHashMap<>(data);payload.put("realtime_session_id",s.get("id"));payload.put("control_epoch",java.math.BigInteger.valueOf(number(s,"control_epoch")));if(payload.get("turn_generation") instanceof Number n)payload.put("turn_generation",java.math.BigInteger.valueOf(n.longValue()));store.jdbc().update("INSERT INTO ai_conversation_event(id,conversation_id,seq,event_type,data_json) VALUES(?,?,?,?,?)",id(),s.get("conversation_id"),seq,type,encode(payload));store.jdbc().update("UPDATE ai_conversation SET last_event_seq=? WHERE id=?",seq,s.get("conversation_id"));return seq;}
    private JsonNode decode(String value){try{return json.readTree(value);}catch(Exception invalid){throw problem(400,"REALTIME_TOOL_JSON_INVALID");}}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception invalid){throw problem(400,"REALTIME_TOOL_JSON_INVALID");}}
    private String canonical(JsonNode value){if(value.isObject()){var sorted=new TreeMap<String,JsonNode>();value.fields().forEachRemaining(e->sorted.put(e.getKey(),e.getValue()));return encode(sorted);}return encode(value);}
    private static int bytes(String value){return value.getBytes(StandardCharsets.UTF_8).length;}
    private static boolean providerId(String value){return value!=null && value.matches("[A-Za-z0-9_.:-]{1,200}");}
    private static String safeError(String code){return code!=null && code.matches("[A-Z][A-Z0-9_]{1,63}")?code:"TOOL_EXECUTION_FAILED";}
    private void requireEnabled(){if(!enabled() || !env.getProperty("features.agent-tools-enabled",Boolean.class,false))throw problem(409,"REALTIME_TOOLS_DISABLED");}
}
