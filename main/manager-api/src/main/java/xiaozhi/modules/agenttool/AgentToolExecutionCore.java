package xiaozhi.modules.agenttool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import xiaozhi.modules.conversation.ConversationProblem;
import xiaozhi.modules.externalidentity.*;
import xiaozhi.modules.externalidentity.ExternalModels.Principal;
import xiaozhi.modules.aicontext.ConversationEvidenceService;
import static xiaozhi.modules.agenttool.AgentToolRegistry.*;

/** Shared read-only execution core; callers alone own run/session lease and generation fences. */
@Service
public class AgentToolExecutionCore {
    public record Context(String conversationId,long ownerId,Principal principal,String sourceConfigFingerprint,
            boolean technicalLocalAllowed,long messageCutoff) {
        public Context(String conversationId,long ownerId,Principal principal,String fingerprint,boolean local) {
            this(conversationId,ownerId,principal,fingerprint,local,Long.MAX_VALUE);
        }
        @Override public String toString(){return "AgentToolContext[redacted]";}
    }
    private final JdbcTemplate db;private final ObjectMapper json;
    private boolean enabled,technicalEnabled;private ExternalIdentityService identity;
    private ExternalBusinessSettings settings;private ObjectProvider<ExternalBusinessAdapter> adapters;
    private ConversationEvidenceService evidenceService;
    public AgentToolExecutionCore(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
    @Value("${features.agent-tools-enabled:false}") public void setEnabled(boolean value){enabled=value;}
    @Value("${agent-tools.technical-local-enabled:false}") public void setTechnicalEnabled(boolean value){technicalEnabled=value;}
    @Autowired(required=false) public void setIdentity(ExternalIdentityService value){identity=value;}
    @Autowired(required=false) public void setSettings(ExternalBusinessSettings value){settings=value;}
    @Autowired public void setAdapters(ObjectProvider<ExternalBusinessAdapter> value){adapters=value;}
    @Autowired(required=false) public void setEvidence(ConversationEvidenceService value){evidenceService=value;}
    public Principal validateContext(Context context) {
        if(context==null || context.conversationId()==null || !context.conversationId().matches("[a-f0-9]{32}")
                || context.ownerId()<=0 || context.messageCutoff()<0)throw problem(403,"TOOL_CONTEXT_INVALID");
        if(db.queryForObject("SELECT COUNT(*) FROM ai_conversation c JOIN sys_user u ON u.id=c.owner_user_id WHERE c.id=? AND c.owner_user_id=? AND u.status=1",Long.class,context.conversationId(),context.ownerId())!=1)
            throw problem(403,"TOOL_CONTEXT_INVALID");
        if(context.principal()==null)return null;
        Principal fresh=identity==null?null:identity.authenticateHash(context.principal().tokenHash());
        if(!ExternalIdentityService.sameBinding(context.principal(),fresh) || fresh.userId()!=context.ownerId())throw problem(403,"TOOL_SOURCE_REVOKED");
        if(context.sourceConfigFingerprint()!=null && !context.sourceConfigFingerprint().equals(fingerprint(fresh,false)))throw problem(403,"TOOL_SOURCE_CHANGED");
        return fresh;
    }
    public List<JsonNode> catalog(Context context) {
        if(!enabled)return List.of();Principal principal=validateContext(context);List<JsonNode> result=new ArrayList<>();
        for(String name:new TreeSet<>(NAMES)) {
            if(!registered(name))continue;
            if(local(name)) {if(technicalEnabled && context.technicalLocalAllowed())result.add(declaration(name));}
            else if(evidence(name)) {if(evidenceService!=null && evidenceService.enabled())result.add(declaration(name));}
            else if(principal!=null && context.sourceConfigFingerprint()!=null && adapter()!=null
                    && (!name.equals("get_device") || !principal.scope().deviceIds().isEmpty())
                    && (!name.equals("get_work_order") || !principal.scope().projectIds().isEmpty()))result.add(declaration(name));
        }
        return List.copyOf(result);
    }
    public void validate(String name,JsonNode arguments){AgentToolRegistry.validate(name,arguments);}
    public JsonNode execute(String name,JsonNode arguments,Context context) {
        requireEnabled();validate(name,arguments);Principal principal=validateContext(context);
        if(!registered(name))throw problem(403,"TOOL_NOT_ALLOWED");
        if(local(name)) {
            if(!technicalEnabled || !context.technicalLocalAllowed())throw problem(403,"TOOL_NOT_ALLOWED");
            return json.createObjectNode().put("protocol_version",1).put("read_only",true).put("technical_acceptance_only",true)
                    .put("business_records_included",false).put("message","This tool reports fixed server protocol capabilities only.");
        }
        if(evidence(name)) {
            if(evidenceService==null || !evidenceService.enabled())throw problem(404,"AI_CONTEXT_DISABLED");
            JsonNode result=evidenceService.execute(name,arguments,context);validateContext(context);
            if(encode(result).getBytes(StandardCharsets.UTF_8).length>MAX_OUTPUT_BYTES)throw problem(502,"TOOL_RESULT_SIZE_LIMIT");return result;
        }
        if(principal==null || context.sourceConfigFingerprint()==null || adapter()==null)throw problem(503,"BUSINESS_NOT_CONFIGURED");
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
        result.set("data",sanitize(evidence.data(),0));validateContext(context);
        if(encode(result).getBytes(StandardCharsets.UTF_8).length>MAX_OUTPUT_BYTES)throw problem(502,"TOOL_RESULT_SIZE_LIMIT");return result;
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
    public String fingerprint(Principal principal,boolean allowMissing) {
        try {
            if(settings==null)throw problem(503,"BUSINESS_NOT_CONFIGURED");
            var source=settings.source(principal.issuer());return hash(source.endpoint()+"\n"+source.keyId()+"\n"+HexFormat.of().formatHex(source.secret()));
        } catch(ConversationProblem denied){if(allowMissing && "BUSINESS_NOT_CONFIGURED".equals(denied.getMessage()))return null;throw denied;}
    }
    public boolean registered(String name) {
        requireName(name);var rows=db.queryForList("SELECT * FROM agent_tool_definition WHERE name=? AND version=1 AND enabled=1",name);
        if(rows.size()!=1)return false;var row=rows.getFirst();
        String adapter=local(name)?"technical_local":evidence(name)?"conversation_evidence":"external_business";
        return adapter.equals(row.get("adapter")) && "read".equals(row.get("operation_class")) && parameters(name).equals(decode((String)row.get("input_schema_json")))
                && ((Number)row.get("timeout_ms")).longValue()==5000 && ((Number)row.get("max_result_bytes")).longValue()==MAX_OUTPUT_BYTES;
    }
    private ExternalBusinessAdapter adapter(){var value=adapters==null?null:adapters.getIfAvailable();return value instanceof ExternalBusinessHttpAdapter?value:null;}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception invalid){throw problem(400,"TOOL_JSON_INVALID");}}
    private JsonNode decode(String value){try{return json.readTree(value);}catch(Exception invalid){throw problem(400,"TOOL_JSON_INVALID");}}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private static ConversationProblem problem(int status,String code){return new ConversationProblem(status,code);}
    private void requireEnabled(){if(!enabled)throw problem(404,"AGENT_TOOLS_DISABLED");}
}
