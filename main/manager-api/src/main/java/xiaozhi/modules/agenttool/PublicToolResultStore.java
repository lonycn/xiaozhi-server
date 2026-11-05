package xiaozhi.modules.agenttool;

import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import xiaozhi.modules.conversation.*;
import xiaozhi.modules.externalidentity.*;
import xiaozhi.modules.externalidentity.ExternalModels.Principal;

/** Immutable public projections; current authorization is rechecked separately from historical status. */
@Service
public class PublicToolResultStore {
    private final JdbcTemplate db;private final ObjectMapper json;private final TransactionTemplate tx;
    private final ConversationAccessService access;private final AgentToolExecutionCore core;
    private ExternalIdentityService identity;private boolean enabled;
    public PublicToolResultStore(JdbcTemplate db,ObjectMapper json,PlatformTransactionManager manager,AgentToolExecutionCore core){this.db=db;this.json=json;tx=new TransactionTemplate(manager);this.core=core;access=new ConversationAccessService(new ConversationDao(db));}
    @Value("${features.agent-tool-results-enabled:false}") public void setEnabled(boolean value){enabled=value;}
    @Autowired(required=false) public void setIdentity(ExternalIdentityService value){identity=value;}
    public void publishRun(Map<String,Object> run,Map<String,Object> call,long eventSequence,String state) {
        if(!enabled||!Set.of("succeeded","failed").contains(state))return;
        var binding=db.queryForMap("SELECT * FROM agent_tool_run_binding WHERE run_id=?",run.get("id"));
        var current=db.queryForMap("SELECT * FROM agent_tool_call WHERE id=?",call.get("id"));
        long cutoff=db.queryForObject("SELECT seq FROM ai_message WHERE id=?",Long.class,run.get("input_message_id"));
        publish((String)run.get("conversation_id"),"run",(String)run.get("id"),null,null,current,binding,cutoff,eventSequence,state);
    }
    public void publishRealtime(Map<String,Object> session,Map<String,Object> call,long eventSequence,String state) {
        if(!enabled||!Set.of("succeeded","failed").contains(state))return;
        var binding=db.queryForMap("SELECT * FROM ai_realtime_tool_binding WHERE session_id=?",session.get("id"));
        publish((String)session.get("conversation_id"),"realtime",null,(String)session.get("id"),(String)call.get("response_id"),call,binding,n(binding,"message_cutoff"),eventSequence,state);
    }
    private void publish(String conversation,String type,String run,String session,String response,Map<String,Object> call,Map<String,Object> binding,long cutoff,long eventSequence,String state) {
        String name=(String)call.get("tool_name"),code=safe((String)call.get("error_code"));JsonNode projection=null;
        if(state.equals("succeeded") && call.get("result_json")!=null)projection=project(name,decode((String)call.get("result_json")),conversation);
        String encoded=projection==null?null:encode(projection);
        if(encoded!=null && encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>32768)throw problem(502,"PUBLIC_TOOL_RESULT_LIMIT");
        db.update("""
            INSERT INTO ai_tool_public_result(conversation_id,execution_type,tool_call_id,run_id,session_id,response_id,tool_name,status,error_code,
                event_sequence,message_cutoff,owner_user_id,principal_json,source_config_hash,result_json)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE tool_call_id=tool_call_id
            """,conversation,type,call.get("id"),run,session,response,name,state,code,eventSequence,cutoff,binding.get("owner_user_id"),binding.get("principal_json"),binding.get("source_config_hash"),encoded);
    }
    private JsonNode project(String name,JsonNode raw,String conversation) {
        ObjectNode result=json.createObjectNode().put("kind",name.equals("server_capabilities")?"technical":name.startsWith("read_conversation_")?"conversation_evidence":"business");
        ObjectNode data=result.putObject("data");ArrayNode citations=result.putArray("citations");
        if(name.equals("server_capabilities")) {
            for(String key:List.of("protocol_version","read_only","technical_acceptance_only","business_records_included","message"))if(raw.has(key))data.set(key,raw.get(key).deepCopy());
            citations.addObject().put("id","technical:server_capabilities").put("kind","technical").put("title","Server technical protocol").putObject("source_ref");
        } else if(name.equals("read_conversation_history")) {
            for(String key:List.of("messages","has_more","next_before_seq","truncated"))if(raw.has(key))data.set(key,raw.get(key).deepCopy());
            for(JsonNode message:raw.path("messages")){ObjectNode citation=citations.addObject().put("id","message:"+message.path("message_id").asText()).put("kind","message").put("title","Published conversation message");citation.putObject("source_ref").put("conversation_id",conversation).put("message_id",message.path("message_id").asText());}
        } else if(name.equals("read_conversation_evidence")) {
            for(String key:List.of("attachment_id","content_available","evidence_type","text","truncated","limitation","verified_derivatives"))if(raw.has(key))data.set(key,raw.get(key).deepCopy());
            citations.addObject().put("id","attachment:"+raw.path("attachment_id").asText()).put("kind","attachment").put("title","Conversation evidence").putObject("source_ref").put("conversation_id",conversation).put("attachment_id",raw.path("attachment_id").asText());
        } else {
            data.set("fields",core.sanitize(raw.path("data"),0));data.put("resource_type",raw.path("resource_type").asText()).put("record_id",raw.path("source_record_id").asText());
            if(raw.path("observed_at").isTextual())result.put("observed_at",raw.path("observed_at").asText());
            citations.addObject().put("id","business:"+raw.path("resource_type").asText()+":"+raw.path("source_record_id").asText()).put("kind","business").put("title","Authorized business source").putObject("source_ref").put("resource_type",raw.path("resource_type").asText()).put("record_id",raw.path("source_record_id").asText());
            if(raw.path("data").has("stale"))result.set("stale",raw.path("data").get("stale"));
        }
        result.put("data_is_untrusted",true);return result;
    }
    public Map<String,Object> page(Long actor,String conversation,long before,int limit,Principal current) {
        if(!enabled)throw problem(404,"TOOL_RESULTS_DISABLED");if(actor==null)throw problem(401,"AUTHENTICATION_REQUIRED");
        if(before<0||limit<1||limit>100)throw problem(400,"INVALID_CURSOR_OR_LIMIT");
        return tx.execute(status->{var grant=access.read(actor,conversation,true);
            var rows=db.query("SELECT * FROM ai_tool_public_result WHERE conversation_id=? AND event_sequence<=? AND sequence<? ORDER BY sequence DESC LIMIT ?",new xiaozhi.common.jdbc.TimestampColumnMapRowMapper(),conversation,grant.eventCutoff(),before==0?Long.MAX_VALUE:before,limit+1);
            List<Map<String,Object>> items=new ArrayList<>();for(var row:rows.stream().limit(limit).toList()) {
                boolean allowed=authorized(row,actor,current,grant.messageCutoff());Map<String,Object> item=new LinkedHashMap<>();
                item.put("sequence",Long.toString(n(row,"sequence")));item.put("event_sequence",Long.toString(n(row,"event_sequence")));item.put("tool_call_id",row.get("tool_call_id"));
                item.put("execution_type",row.get("execution_type"));for(String key:List.of("run_id","session_id","response_id"))if(row.get(key)!=null)item.put(key,row.get(key));
                item.put("name",row.get("tool_name"));item.put("version",1);item.put("status",row.get("status"));item.put("result_access",allowed?"available":"restricted");
                item.put("result",allowed&&row.get("result_json")!=null?decode((String)row.get("result_json")):null);
                String code=allowed?(String)row.get("error_code"):"TOOL_RESULT_AUTHORIZATION_REQUIRED";if(code!=null)item.put("error_code",code);
                item.put("retryable",allowed&&Set.of("TOOL_EXECUTION_FAILED","BUSINESS_SOURCE_UNAVAILABLE","BUSINESS_SOURCE_RATE_LIMITED").contains(code==null?"":code));
                item.put("created_at",((java.sql.Timestamp)row.get("created_at")).toInstant().toString());items.add(item);
            }
            return Map.<String,Object>of("items",items,"next_cursor",rows.isEmpty()?Long.toString(before):Long.toString(n(rows.get(Math.min(limit,rows.size())-1),"sequence")),"has_more",rows.size()>limit);
        });
    }
    private boolean authorized(Map<String,Object> row,long actor,Principal current,long cutoff) {
        try {
            String name=(String)row.get("tool_name");
            if(!name.equals("server_capabilities")&&!name.startsWith("read_conversation_")&&row.get("principal_json")==null)return false;
            if(row.get("principal_json")!=null) {
                if(actor!=n(row,"owner_user_id")||current==null||identity==null)return false;
                Principal original=json.readValue((String)row.get("principal_json"),Principal.class),fresh=identity.authenticateHash(current.tokenHash());
                if(!ExternalIdentityService.sameBinding(original,fresh))return false;
                core.validateContext(new AgentToolExecutionCore.Context((String)row.get("conversation_id"),actor,fresh,(String)row.get("source_config_hash"),false,n(row,"message_cutoff")));
            } else if(current!=null)return false;
            if(name.equals("read_conversation_evidence")&&row.get("result_json")!=null) {
                String id=decode((String)row.get("result_json")).path("data").path("attachment_id").asText();
                if(db.queryForObject("SELECT COUNT(*) FROM ai_attachment WHERE id=? AND conversation_id=? AND linked_seq<=? AND status='ready'",Long.class,id,row.get("conversation_id"),Math.min(cutoff,n(row,"message_cutoff")))!=1)return false;
            }
            return true;
        }catch(ConversationProblem denied){return false;}catch(com.fasterxml.jackson.core.JsonProcessingException invalid){return false;}
    }
    private JsonNode decode(String value){try{return json.readTree(value);}catch(Exception invalid){throw problem(500,"PUBLIC_TOOL_RESULT_INVALID");}}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception invalid){throw problem(500,"PUBLIC_TOOL_RESULT_INVALID");}}
    private static long n(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static String safe(String value){return value!=null&&value.matches("[A-Z][A-Z0-9_]{1,63}")?value:null;}
    private static ConversationProblem problem(int status,String code){return new ConversationProblem(status,code);}
}
