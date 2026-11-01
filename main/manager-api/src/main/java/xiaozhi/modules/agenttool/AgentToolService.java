package xiaozhi.modules.agenttool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import xiaozhi.modules.agenttool.AgentToolModels.*;
import xiaozhi.modules.conversation.*;
import xiaozhi.modules.externalidentity.*;
import xiaozhi.modules.externalidentity.ExternalModels.Principal;
import static xiaozhi.modules.agenttool.AgentToolRegistry.*;

/** Fixed read-only tools. Locks: conversation -> run -> call. No network IO occurs in transactions. */
@Service
public class AgentToolService {
    private final JdbcTemplate db;private final ObjectMapper json;private final TransactionTemplate tx;
    private final ConversationAccessService access;
    private boolean enabled,technicalEnabled;
    private ExternalIdentityService identity;private ExternalBusinessSettings settings;
    private ObjectProvider<ExternalBusinessAdapter> adapters;
    public AgentToolService(JdbcTemplate db,ObjectMapper json,PlatformTransactionManager manager) {
        this.db=db;this.json=json;tx=new TransactionTemplate(manager);
        access=new ConversationAccessService(new ConversationDao(db));
    }
    @Value("${features.agent-tools-enabled:false}") public void setEnabled(boolean value){enabled=value;}
    public boolean isEnabled(){return enabled;}
    public boolean hasBinding(String runId){return db.queryForObject("SELECT COUNT(*) FROM agent_tool_run_binding WHERE run_id=?",Long.class,runId)==1;}
    /** Caller owns the run transaction. Persisted delegation remains guarded after a feature-off restart. */
    public void validateBinding(String runId){
        var rows=db.queryForList("SELECT * FROM agent_tool_run_binding WHERE run_id=?",runId);
        if(rows.isEmpty()) {if(enabled)throw problem(409,"TOOL_CONTEXT_MISSING");return;}
        revalidate(rows.getFirst());
        if(!enabled && db.queryForObject("SELECT COUNT(*) FROM agent_tool_call WHERE run_id=?",Long.class,runId)>0)
            throw problem(409,"AGENT_TOOLS_DISABLED");
    }
    @Value("${agent-tools.technical-local-enabled:false}") public void setTechnicalEnabled(boolean value){technicalEnabled=value;}
    @Autowired(required=false) public void setIdentity(ExternalIdentityService value){identity=value;}
    @Autowired(required=false) public void setSettings(ExternalBusinessSettings value){settings=value;}
    @Autowired public void setAdapters(ObjectProvider<ExternalBusinessAdapter> value){adapters=value;}

    /** Call in the existing enqueue transaction, with the initiating request Principal, never an owner lookup. */
    public void prepareRun(String runId,String conversationId,long ownerId,Principal principal) {
        if(!enabled)return;
        var run=one("SELECT conversation_id FROM ai_run WHERE id=?",runId);
        var conversation=one("SELECT owner_user_id FROM ai_conversation WHERE id=?",conversationId);
        if(!conversationId.equals(run.get("conversation_id")) || n(conversation,"owner_user_id")!=ownerId)throw problem(403,"TOOL_CONTEXT_INVALID");
        String principalJson=null,config=null;
        if(principal!=null) {
            Principal current=identity==null?null:identity.authenticateHash(principal.tokenHash());
            if(principal.userId()!=ownerId || !sameBinding(principal,current))
                throw problem(403,"TOOL_CONTEXT_INVALID");
            principalJson=encode(current);config=configuration(current,true);
        }
        db.update("INSERT INTO agent_tool_run_binding(run_id,conversation_id,owner_user_id,principal_json,source_config_hash,local_tools_allowed) VALUES(?,?,?,?,?,?)",
                runId,conversationId,ownerId,principalJson,config,technicalEnabled?1:0);
    }
    public Catalog taskTools(String runId,int attempt,String leaseToken,long controlEpoch) {
        if(!enabled)return new Catalog(List.of(),0,0);
        return tx.execute(status->{
            var r=fence(runId,attempt,leaseToken,controlEpoch);var binding=binding(runId);Principal principal=revalidate(binding);
            List<JsonNode> tools=new ArrayList<>();
            for(String name:new TreeSet<>(NAMES)) {
                if(!registered(name))continue;
                if(local(name)) {if(technicalEnabled && n(binding,"local_tools_allowed")==1)tools.add(declaration(name));}
                else if(principal!=null && binding.get("source_config_hash")!=null && adapter()!=null
                        && (!name.equals("get_device") || !principal.scope().deviceIds().isEmpty())
                        && (!name.equals("get_work_order") || !principal.scope().projectIds().isEmpty()))tools.add(declaration(name));
            }
            return new Catalog(List.copyOf(tools),MAX_ROUNDS,MAX_CALLS);
        });
    }
    /** Invoked before model text publication and every continuation, including a replayed cached result. */
    public void validatePublish(String runId,int attempt,String leaseToken,long controlEpoch) {
        if(!enabled && db.queryForObject("SELECT COUNT(*) FROM agent_tool_run_binding WHERE run_id=?",Long.class,runId)==0)return;
        tx.executeWithoutResult(status->{fence(runId,attempt,leaseToken,controlEpoch);validateBinding(runId);});
    }
    /** Terminal run hook; caller already holds the conversation/run transaction locks. */
    public void cancelPending(String runId) {
        var run=lockRun(runId);
        for(var call:db.queryForList("SELECT * FROM agent_tool_call WHERE run_id=? AND status='running' FOR UPDATE",runId)) {
            db.update("UPDATE agent_tool_call SET status='discarded',error_code='TOOL_RUN_CANCELED',result_json=NULL WHERE id=?",call.get("id"));
            audit(run,call,"discarded","TOOL_RUN_CANCELED",0,null);
        }
    }
    private record Reserved(Map<String,Object> row,boolean execute) { }
    public BatchResult submitCalls(String runId,Proposal proposal) {
        requireEnabled();validateProposal(proposal);
        List<Reserved> reserved=tx.execute(status->{
            var run=fence(runId,proposal.attempt(),proposal.leaseToken(),proposal.controlEpoch());
            var sourceBinding=binding(runId);revalidate(sourceBinding);
            long previousRound=db.queryForObject("SELECT COALESCE(MAX(round_number),0) FROM agent_tool_call WHERE run_id=? AND run_attempt=?",Long.class,runId,proposal.attempt());
            List<Reserved> result=new ArrayList<>();int newCalls=0;
            for(Call call:proposal.calls()) {
                if(!registered(call.name()) || local(call.name()) && (!technicalEnabled || n(sourceBinding,"local_tools_allowed")!=1))
                    throw problem(403,"TOOL_NOT_ALLOWED");
                var existing=db.queryForList("SELECT * FROM agent_tool_call WHERE run_id=? AND provider_call_id=? FOR UPDATE",runId,call.providerCallId());
                if(!existing.isEmpty()) {
                    var row=existing.getFirst();
                    if(!call.name().equals(row.get("tool_name")) || !canonical(call.arguments()).equals(canonical(decode((String)row.get("arguments_json"))))
                            || proposal.round()!=n(row,"round_number"))throw problem(409,"TOOL_CALL_CONFLICT");
                    if(n(row,"run_attempt")!=proposal.attempt() || !hash(proposal.leaseToken()).equals(row.get("lease_hash")))
                        throw problem(409,"TOOL_CALL_FENCED");
                    if("running".equals(row.get("status")) || "discarded".equals(row.get("status")))throw problem(409,"TOOL_RESULT_UNCERTAIN");
                    result.add(new Reserved(row,false));
                } else {newCalls++;result.add(new Reserved(Map.of("pending",call),true));}
            }
            long count=db.queryForObject("SELECT COUNT(*) FROM agent_tool_call WHERE run_id=?",Long.class,runId);
            if(count+newCalls>MAX_CALLS)throw problem(409,"TOOL_CALL_BUDGET_EXCEEDED");
            if(newCalls>0 && proposal.round()!=previousRound+1)throw problem(409,"TOOL_ROUND_CONFLICT");
            if(newCalls>0 && elapsed(runId)>MAX_ELAPSED_SECONDS*1000L)throw problem(409,"TOOL_TIME_BUDGET_EXCEEDED");
            List<Reserved> completed=new ArrayList<>();
            for(Reserved item:result) {
                if(!item.execute()){completed.add(item);continue;}
                Call call=(Call)item.row().get("pending");String callId=id();
                if(!registered(call.name()))throw problem(403,"TOOL_NOT_ALLOWED");
                db.update("""
                        INSERT INTO agent_tool_call(id,run_id,run_attempt,control_epoch,lease_hash,provider_call_id,tool_name,round_number,arguments_json,arguments_hash,status)
                        VALUES(?,?,?,?,?,?,?,?,?,?,'running')
                        """,callId,runId,proposal.attempt(),proposal.controlEpoch(),hash(proposal.leaseToken()),call.providerCallId(),call.name(),proposal.round(),canonical(call.arguments()),hash(canonical(call.arguments())));
                db.update("INSERT INTO agent_tool_attempt(id,call_id,run_attempt,control_epoch,lease_hash) VALUES(?,?,?,?,?)",id(),callId,proposal.attempt(),proposal.controlEpoch(),hash(proposal.leaseToken()));
                var row=one("SELECT * FROM agent_tool_call WHERE id=?",callId);audit(run,row,"running",null,0,null);
                completed.add(new Reserved(row,true));
            }
            return completed;
        });
        List<ToolResult> results=new ArrayList<>();
        for(Reserved item:reserved) {
            if(!item.execute()) {validatePublish(runId,proposal.attempt(),proposal.leaseToken(),proposal.controlEpoch());results.add(result(item.row()));continue;}
            long start=System.nanoTime();JsonNode output=null;String error=null;
            try {
                // Dispatch guard is separate from reservation: revocation/cancellation may occur between calls.
                Principal principal=tx.execute(status->{fence(runId,proposal.attempt(),proposal.leaseToken(),proposal.controlEpoch());
                    if(elapsed(runId)>MAX_ELAPSED_SECONDS*1000L)throw problem(409,"TOOL_TIME_BUDGET_EXCEEDED");return revalidate(binding(runId));});
                output=execute((String)item.row().get("tool_name"),decode((String)item.row().get("arguments_json")),principal,binding(runId));
                if(encode(output).getBytes(StandardCharsets.UTF_8).length>MAX_OUTPUT_BYTES)throw problem(502,"TOOL_RESULT_SIZE_LIMIT");
            } catch(ConversationProblem denied) {error=safeError(denied.getMessage());}
              catch(Exception unavailable) {error="TOOL_EXECUTION_FAILED";}
            long duration=(System.nanoTime()-start)/1_000_000L;
            final JsonNode body=output;final String code=error;final String callId=(String)item.row().get("id");
            boolean accepted=Boolean.TRUE.equals(tx.execute(status->{
                Map<String,Object> run=lockRun(runId);String discarded=null;
                var current=one("SELECT * FROM agent_tool_call WHERE id=? FOR UPDATE",callId);
                if(!"running".equals(current.get("status")))return false;
                try {checkFence(run,proposal.attempt(),proposal.leaseToken(),proposal.controlEpoch());validateBinding(runId);}
                catch(ConversationProblem failure){discarded=safeError(failure.getMessage());}
                if(discarded!=null) {
                    db.update("UPDATE agent_tool_call SET status='discarded',error_code=?,result_json=NULL WHERE id=? AND status='running'",discarded,callId);
                    audit(run,item.row(),"discarded",discarded,duration,null);return false;
                }
                String finalCode=elapsed(runId)>MAX_ELAPSED_SECONDS*1000L?"TOOL_TIME_BUDGET_EXCEEDED":code;
                String state=finalCode==null?"succeeded":"failed",encoded=finalCode==null?encode(body):null;
                db.update("UPDATE agent_tool_call SET status=?,result_json=?,error_code=? WHERE id=? AND status='running'",state,encoded,finalCode,callId);
                audit(run,item.row(),state,finalCode,duration,encoded==null?null:hash(encoded));return true;
            }));
            if(!accepted)throw problem(409,"TOOL_RESULT_FENCED");
            results.add(result(one("SELECT * FROM agent_tool_call WHERE id=?",callId)));
        }
        validatePublish(runId,proposal.attempt(),proposal.leaseToken(),proposal.controlEpoch());
        int remaining=MAX_CALLS-db.queryForObject("SELECT COUNT(*) FROM agent_tool_call WHERE run_id=?",Integer.class,runId);
        return new BatchResult(List.copyOf(results),remaining);
    }
    private JsonNode execute(String name,JsonNode arguments,Principal principal,Map<String,Object> binding) {
        requireEnabled();
        if(!registered(name))throw problem(403,"TOOL_NOT_ALLOWED");
        if(local(name)) {
            if(!technicalEnabled || n(binding,"local_tools_allowed")!=1)throw problem(403,"TOOL_NOT_ALLOWED");
            return json.createObjectNode().put("protocol_version",1).put("read_only",true).put("technical_acceptance_only",true)
                    .put("business_records_included",false).put("message","This tool reports fixed server protocol capabilities only.");
        }
        if(principal==null || binding.get("source_config_hash")==null || adapter()==null)throw problem(503,"BUSINESS_NOT_CONFIGURED");
        var read=read(name,arguments);
        if(name.equals("get_device") && !principal.scope().deviceIds().contains(read.resourceId())
                || name.equals("get_work_order") && principal.scope().projectIds().isEmpty())throw problem(404,"RESOURCE_NOT_FOUND");
        var evidence=adapter().read(principal,read);
        if(evidence==null || !principal.issuer().equals(evidence.issuer()) || !principal.tenant().type().equals(evidence.tenantType())
                || !principal.tenant().id().equals(evidence.tenantId()) || !read.kind().equals(evidence.resourceType())
                || evidence.sourceRecordId()==null || !evidence.sourceRecordId().matches("[A-Za-z0-9_.:-]{1,100}")
                || read.resourceId()!=null && !read.resourceId().equals(evidence.sourceRecordId()) || evidence.observedAt()==null || evidence.data()==null)
            throw problem(502,"BUSINESS_PROVENANCE_INVALID");
        ObjectNode result=json.createObjectNode().put("data_is_untrusted",true).put("resource_type",evidence.resourceType())
                .put("source_record_id",evidence.sourceRecordId()).put("observed_at",evidence.observedAt().toString());
        result.set("data",sanitize(evidence.data(),0));return result;
    }
    // Explicit data fields only; upstream credentials, headers, actor context and arbitrary properties never enter model context.
    private static final Set<String> DATA_FIELDS=Set.of("record","references","data","source_record_id","observed_at","received_at","stale","project_ids",
            "id","name","title","status","type","model","serial_number","location","temperature","temperature_unit","humidity","unit","value",
            "alarm","alarms","severity","code","message","description","summary","content","text","created_at","updated_at","completed_at","device_id","work_order_id");
    JsonNode sanitize(JsonNode node,int depth) {
        if(depth>8)throw problem(502,"TOOL_RESULT_INVALID");
        if(node.isObject()) {ObjectNode value=json.createObjectNode();node.fields().forEachRemaining(entry->{if(DATA_FIELDS.contains(entry.getKey()))value.set(entry.getKey(),sanitize(entry.getValue(),depth+1));});return value;}
        if(node.isArray()) {if(node.size()>100)throw problem(502,"TOOL_RESULT_SIZE_LIMIT");var value=json.createArrayNode();node.forEach(item->value.add(sanitize(item,depth+1)));return value;}
        if(node.isTextual() && node.asText().length()>8000)throw problem(502,"TOOL_RESULT_SIZE_LIMIT");return node.deepCopy();
    }
    public HistoryPage history(Long actor,String conversationId,long before,int limit) {
        if(actor==null)throw problem(401,"AUTHENTICATION_REQUIRED");
        if(before<0 || limit<1 || limit>100)throw problem(400,"INVALID_CURSOR_OR_LIMIT");
        return tx.execute(status->{
            var grant=access.read(actor,conversationId,true);
            var rows=db.queryForList("""
                    SELECT a.*,c.run_id,c.provider_call_id,c.tool_name,c.run_attempt,c.round_number
                    FROM agent_tool_audit a JOIN agent_tool_call c ON c.id=a.call_id
                    WHERE a.conversation_id=? AND a.event_sequence<=? AND a.sequence<? ORDER BY a.sequence DESC LIMIT ?
                    """,conversationId,grant.eventCutoff(),before==0?Long.MAX_VALUE:before,limit+1);
            // A history item is an immutable status only; result payload, source identity and arguments are private.
            var items=rows.stream().limit(limit).map(row->new History(n(row,"sequence"),(String)row.get("call_id"),(String)row.get("run_id"),(String)row.get("provider_call_id"),
                    (String)row.get("tool_name"),(int)n(row,"run_attempt"),(int)n(row,"round_number"),(String)row.get("state"),
                    (String)row.get("error_code"),n(row,"event_sequence"),row.get("created_at").toString())).toList();
            return new HistoryPage(items,items.isEmpty()?before:items.getLast().sequence(),rows.size()>limit);
        });
    }
    private Map<String,Object> fence(String runId,int attempt,String token,long epoch) {var run=lockRun(runId);checkFence(run,attempt,token,epoch);return run;}
    private Map<String,Object> lockRun(String runId) {
        if(runId==null || !runId.matches("[a-f0-9]{32}"))throw problem(400,"INVALID_ID");
        var hint=one("SELECT conversation_id FROM ai_run WHERE id=?",runId);
        one("SELECT id FROM ai_conversation WHERE id=? FOR UPDATE",hint.get("conversation_id"));
        return one("SELECT * FROM ai_run WHERE id=? FOR UPDATE",runId);
    }
    private void checkFence(Map<String,Object> run,int attempt,String token,long epoch) {
        var c=one("SELECT state,control_epoch,owner_user_id FROM ai_conversation WHERE id=?",run.get("conversation_id"));
        if(token==null || !token.matches("[a-f0-9]{64}") || !token.equals(run.get("lease_token")) || n(run,"attempt")!=attempt
                || n(run,"control_epoch")!=epoch || n(c,"control_epoch")!=epoch || !"AI_ACTIVE".equals(c.get("state"))
                || !Set.of("running","streaming").contains(run.get("status"))
                || !Boolean.TRUE.equals(db.queryForObject("SELECT lease_expires_at>CURRENT_TIMESTAMP(3) FROM ai_run WHERE id=?",Boolean.class,run.get("id")))
                || db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=? AND status=1",Long.class,c.get("owner_user_id"))!=1)
            throw problem(409,"TOOL_LEASE_INVALID");
    }
    private Principal revalidate(Map<String,Object> binding) {
        if(binding.get("principal_json")==null)return null;
        try {
            Principal original=json.readValue((String)binding.get("principal_json"),Principal.class);
            Principal current=identity==null?null:identity.authenticateHash(original.tokenHash());
            if(original.userId()!=n(binding,"owner_user_id") || !sameBinding(original,current))throw problem(403,"TOOL_SOURCE_REVOKED");
            if(binding.get("source_config_hash")!=null && !binding.get("source_config_hash").equals(configuration(current,false)))throw problem(403,"TOOL_SOURCE_CHANGED");
            return current;
        } catch(ConversationProblem denied){throw denied;}catch(Exception invalid){throw problem(403,"TOOL_CONTEXT_INVALID");}
    }
    /** Renewal may extend this exact Bearer; no other identity, source, tenant, scope or token may replace it. */
    static boolean sameBinding(Principal original,Principal current) {
        return original!=null && current!=null && original.tokenId()==current.tokenId() && original.sourceId()==current.sourceId()
                && original.userId()==current.userId() && Objects.equals(original.tokenHash(),current.tokenHash())
                && Objects.equals(original.issuer(),current.issuer()) && Objects.equals(original.subject(),current.subject())
                && Objects.equals(original.tenant(),current.tenant()) && Objects.equals(original.scope(),current.scope());
    }
    private String configuration(Principal principal,boolean allowMissing) {
        try {
            if(settings==null)throw problem(503,"BUSINESS_NOT_CONFIGURED");
            var source=settings.source(principal.issuer());return hash(source.endpoint()+"\n"+source.keyId()+"\n"+HexFormat.of().formatHex(source.secret()));
        } catch(ConversationProblem failure) {if(allowMissing && "BUSINESS_NOT_CONFIGURED".equals(failure.getMessage()))return null;throw failure;}
    }
    private ExternalBusinessAdapter adapter() {var adapter=adapters==null?null:adapters.getIfAvailable();return adapter instanceof ExternalBusinessHttpAdapter?adapter:null;}
    private boolean registered(String name) {
        requireName(name);var rows=db.queryForList("SELECT * FROM agent_tool_definition WHERE name=? AND version=1 AND enabled=1",name);
        if(rows.size()!=1)return false;var row=rows.getFirst();
        return (local(name)?"technical_local":"external_business").equals(row.get("adapter")) && "read".equals(row.get("operation_class"))
                && parameters(name).equals(decode((String)row.get("input_schema_json"))) && n(row,"timeout_ms")==5000 && n(row,"max_result_bytes")==MAX_OUTPUT_BYTES;
    }
    private void validateProposal(Proposal proposal) {
        if(proposal==null || proposal.attempt()<1 || proposal.controlEpoch()<0 || proposal.round()<1 || proposal.round()>MAX_ROUNDS
                || proposal.calls()==null || proposal.calls().isEmpty() || proposal.calls().size()>MAX_CALLS)throw problem(400,"TOOL_PROPOSAL_INVALID");
        Set<String> seen=new HashSet<>();for(Call call:proposal.calls()) {
            if(call==null || call.providerCallId()==null || !call.providerCallId().matches("[A-Za-z0-9_.:-]{1,200}") || !seen.add(call.providerCallId()))throw problem(400,"TOOL_CALL_ID_INVALID");
            validate(call.name(),call.arguments());
        }
    }
    private void audit(Map<String,Object> run,Map<String,Object> call,String state,String error,long elapsed,String resultHash) {
        String conversation=(String)run.get("conversation_id");long seq=db.queryForObject("SELECT last_event_seq FROM ai_conversation WHERE id=?",Long.class,conversation)+1;
        Map<String,Object> payload=new LinkedHashMap<>();payload.put("run_id",run.get("id"));payload.put("call_id",call.get("id"));
        payload.put("tool_name",call.get("tool_name"));payload.put("status",state);if(error!=null)payload.put("error_code",error);
        db.update("INSERT INTO ai_conversation_event(id,conversation_id,seq,event_type,data_json) VALUES(?,?,?,?,?)",id(),conversation,seq,"ai.tool."+state,encode(payload));
        db.update("UPDATE ai_conversation SET last_event_seq=? WHERE id=?",seq,conversation);
        db.update("INSERT INTO agent_tool_audit(call_id,conversation_id,event_sequence,state,error_code,elapsed_ms,result_hash) VALUES(?,?,?,?,?,?,?)",call.get("id"),conversation,seq,state,error,elapsed,resultHash);
    }
    private long elapsed(String runId) {return db.queryForObject("SELECT COALESCE(TIMESTAMPDIFF(MICROSECOND,MIN(created_at),CURRENT_TIMESTAMP(3))/1000,0) FROM agent_tool_call WHERE run_id=?",Long.class,runId);}
    private ToolResult result(Map<String,Object> row) {
        String code=(String)row.get("error_code");JsonNode output=row.get("result_json")==null?json.createObjectNode().put("error_code",code==null?"TOOL_EXECUTION_FAILED":code):decode((String)row.get("result_json"));
        return new ToolResult((String)row.get("provider_call_id"),(String)row.get("tool_name"),(String)row.get("status"),output,code);
    }
    private Map<String,Object> binding(String runId) {return one("SELECT * FROM agent_tool_run_binding WHERE run_id=?",runId);}
    private Map<String,Object> one(String sql,Object...args) {var rows=db.queryForList(sql,args);if(rows.size()!=1)throw problem(409,"TOOL_CONTEXT_MISSING");return rows.getFirst();}
    private JsonNode decode(String value) {try{return json.readTree(value);}catch(Exception invalid){throw problem(400,"TOOL_JSON_INVALID");}}
    private String encode(Object value) {try{return json.writeValueAsString(value);}catch(Exception invalid){throw problem(400,"TOOL_JSON_INVALID");}}
    private String canonical(JsonNode value) {
        if(value.isObject()) {TreeMap<String,Object> fields=new TreeMap<>();value.fields().forEachRemaining(entry->fields.put(entry.getKey(),entry.getValue()));return encode(fields);}return encode(value);
    }
    private static String hash(String value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private static String safeError(String value) {return value!=null && value.matches("[A-Z][A-Z0-9_]{1,63}")?value:"TOOL_EXECUTION_FAILED";}
    private static long n(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static String id(){return UUID.randomUUID().toString().replace("-","");}
    private static ConversationProblem problem(int status,String code){return new ConversationProblem(status,code);}
    private void requireEnabled(){if(!enabled)throw problem(404,"AGENT_TOOLS_DISABLED");}
}
