package xiaozhi.modules.aiskill;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.beans.factory.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import xiaozhi.modules.ai.AiModels.ContextMessage;
import xiaozhi.modules.conversation.*;
import xiaozhi.modules.externalidentity.*;
import xiaozhi.modules.externalidentity.ExternalModels.Principal;

/** Server-owned, versioned read-only instructions. Skills narrow capabilities; they never grant them. */
@Service
public class AiSkillService {
    public static final String SOURCE_POLICY="distinguish_user_material_tool_and_inference";
    private static final List<String> TOOLS=List.of("read_conversation_history","read_conversation_evidence");
    private static final String SHARED="Server read-only skill instructions, not conversation evidence. Distinguish user statements, unverified attachment observations, authorized tool results, and your own inferences. Cite actual conversation message/attachment references when available; do not invent records, tool results, or business authorization. Ask for missing information. Never execute scripts, arbitrary URLs, business writes or device commands. If evidence or source access is unavailable, say so clearly. ";
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Definition(String id,int version,String title,String description,List<String> allowedTools,String sourcePolicy){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Selection(String id,int version){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Request(String clientRequestId,String skillId,Integer version){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record View(Selection selection){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Snapshot(String id,int version,String sourcePolicy){}
    private record Fixed(Definition definition,String instruction){}
    private static final Map<String,Fixed> REGISTRY=Map.of(
        "materials_guidance",new Fixed(new Definition("materials_guidance",1,"材料识别与补问","围绕会话已有材料说明观察、局限和下一步补充信息",TOOLS,SOURCE_POLICY),SHARED+"Help the user inspect supplied materials. Separate directly visible or extracted details from uncertain interpretation; offer concise follow-up questions and nonbinding read-only suggestions."),
        "evidence_summary",new Fixed(new Definition("evidence_summary",1,"证据归纳","按原文与附件来源归纳已知信息和待核实事项",TOOLS,SOURCE_POLICY),SHARED+"Summarize available conversation evidence with source references. Group established source content, contradictions, missing information and tentative inferences. Extractive history summaries are not verified business facts."));
    private final JdbcTemplate db;private final ObjectMapper json;private final Environment env;private final ConversationDao conversations;private final TransactionTemplate tx;private ExternalIdentityService identity;private volatile Boolean hasTable;
    public AiSkillService(JdbcTemplate db,ObjectMapper json,PlatformTransactionManager manager,Environment env){this.db=db;this.json=json;this.env=env;conversations=new ConversationDao(db);tx=new TransactionTemplate(manager);}
    @Autowired(required=false) public void setIdentity(ExternalIdentityService value){identity=value;}
    public boolean enabled(){return env.getProperty("features.ai-skills-enabled",Boolean.class,false);}
    public Map<String,Object> catalog(){requireEnabled();return Map.of("items",REGISTRY.values().stream().map(Fixed::definition).sorted(Comparator.comparing(Definition::id)).toList());}
    public View selection(Long actor,String conversation,Principal current){requireEnabled();return tx.execute(status->{authorize(actor,conversation,current);return selection(conversation);});}
    public View select(Long actor,String conversation,Request request,Principal current){requireEnabled();if(request==null||request.clientRequestId()==null||!request.clientRequestId().matches("[A-Za-z0-9_.:-]{1,64}")||request.skillId()==null&&request.version()!=null||request.skillId()!=null&&(request.version()==null||request.version()!=1||!REGISTRY.containsKey(request.skillId())))throw problem(400,"INVALID_SKILL_SELECTION");
        return tx.execute(status->{authorize(actor,conversation,current);var old=db.queryForList("SELECT skill_id,skill_version FROM ai_skill_selection_audit WHERE conversation_id=? AND client_request_id=?",conversation,request.clientRequestId());
            if(!old.isEmpty()){if(!Objects.equals(old.getFirst().get("skill_id"),request.skillId())||!Objects.equals(old.getFirst().get("skill_version"),request.version()))throw problem(409,"SKILL_IDEMPOTENCY_CONFLICT");return new View(request.skillId()==null?null:new Selection(request.skillId(),request.version()));}
            db.update("INSERT INTO ai_skill_selection(conversation_id,owner_user_id,skill_id,skill_version) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE skill_id=VALUES(skill_id),skill_version=VALUES(skill_version),updated_at=CURRENT_TIMESTAMP(3)",conversation,actor,request.skillId(),request.version());
            db.update("INSERT INTO ai_skill_selection_audit(conversation_id,owner_user_id,client_request_id,skill_id,skill_version) VALUES(?,?,?,?,?)",conversation,actor,request.clientRequestId(),request.skillId(),request.version());return selection(conversation);
        });
    }
    /** Existing enqueue transaction owns the trusted run; workers never select a skill. */
    public void captureRun(String run,String conversation,long owner){if(!enabled())return;
        var actual=db.queryForList("SELECT r.conversation_id,c.owner_user_id FROM ai_run r JOIN ai_conversation c ON c.id=r.conversation_id WHERE r.id=?",run);if(actual.size()!=1||!conversation.equals(actual.getFirst().get("conversation_id"))||((Number)actual.getFirst().get("owner_user_id")).longValue()!=owner)throw problem(403,"SKILL_RUN_CONTEXT_INVALID");
        var selection=selection(conversation).selection();if(selection==null)return;Fixed fixed=fixed(selection.id(),selection.version());
        db.update("INSERT INTO ai_skill_run_snapshot(run_id,conversation_id,skill_id,skill_version,instruction_text,allowed_tools_json,source_policy,snapshot_hash) VALUES(?,?,?,?,?,?,?,?)",run,conversation,selection.id(),selection.version(),fixed.instruction,encode(TOOLS),SOURCE_POLICY,digest(fixed));
    }
    public Snapshot snapshot(String run){Fixed fixed=bound(run);return fixed==null?null:new Snapshot(fixed.definition.id(),fixed.definition.version(),SOURCE_POLICY);}
    public List<JsonNode> filterTools(String run,List<JsonNode> catalog){Fixed fixed=bound(run);if(fixed==null||catalog==null)return catalog;return catalog.stream().filter(tool->TOOLS.contains(tool.path("function").path("name").asText())).toList();}
    public void requireTool(String run,String tool){if(bound(run)!=null&&!TOOLS.contains(tool))throw problem(403,"SKILL_TOOL_NOT_ALLOWED");}
    public List<ContextMessage> applyContext(String run,List<ContextMessage> messages){Fixed fixed=bound(run);if(fixed==null)return messages;
        int budget=64000-fixed.instruction.length();List<ContextMessage> bounded=new ArrayList<>();
        // Preserve the newest user turn and its already-authorized media; trim oldest text first.
        for(int i=messages.size()-1;i>=0&&budget>0;i--){var item=messages.get(i);String content=item.content()==null?"":item.content();if(content.length()>budget)content=content.substring(content.length()-budget);budget-=content.length();bounded.add(new ContextMessage(item.role(),content,item.attachments()));}
        Collections.reverse(bounded);bounded.add(0,new ContextMessage("system",fixed.instruction));return List.copyOf(bounded);
    }
    private Fixed bound(String run){
        // Feature-off legacy installations may not yet have these additive tables. Persisted snapshots
        // remain enforced after a flag-off restart; discovering table existence never reads a missing table.
        if(!enabled()&&!snapshotTableExists())return null;
        var rows=db.queryForList("SELECT * FROM ai_skill_run_snapshot WHERE run_id=?",run);if(rows.isEmpty())return null;var row=rows.getFirst();Fixed fixed=fixed((String)row.get("skill_id"),((Number)row.get("skill_version")).intValue());
        if(!fixed.instruction.equals(row.get("instruction_text"))||!encode(TOOLS).equals(row.get("allowed_tools_json"))||!SOURCE_POLICY.equals(row.get("source_policy"))||!digest(fixed).equals(row.get("snapshot_hash")))throw problem(409,"SKILL_SNAPSHOT_INVALID");return fixed;
    }
    private boolean snapshotTableExists(){if(Boolean.TRUE.equals(hasTable))return true;hasTable=db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='ai_skill_run_snapshot'",Long.class)==1;return hasTable;}
    private View selection(String conversation){var rows=db.queryForList("SELECT skill_id,skill_version FROM ai_skill_selection WHERE conversation_id=?",conversation);if(rows.isEmpty()||rows.getFirst().get("skill_id")==null)return new View(null);var row=rows.getFirst();fixed((String)row.get("skill_id"),((Number)row.get("skill_version")).intValue());return new View(new Selection((String)row.get("skill_id"),((Number)row.get("skill_version")).intValue()));}
    private void authorize(Long actor,String conversation,Principal current){if(actor==null||actor<=0)throw problem(401,"AUTHENTICATION_REQUIRED");if(conversation==null||!conversation.matches("[a-f0-9]{32}"))throw problem(400,"INVALID_SKILL_SELECTION");if(db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=? AND status=1",Long.class,actor)!=1)throw problem(401,"ACCOUNT_DISABLED");conversations.owned(conversation,actor,true);if(current!=null&&(identity==null||current.userId()!=actor||!ExternalIdentityService.sameBinding(current,identity.authenticateHash(current.tokenHash()))))throw problem(403,"SKILL_SOURCE_CHANGED");}
    private static Fixed fixed(String id,int version){Fixed fixed=REGISTRY.get(id);if(fixed==null||version!=1)throw problem(409,"SKILL_VERSION_UNAVAILABLE");return fixed;}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception invalid){throw problem(400,"INVALID_SKILL_SELECTION");}}
    private String digest(Fixed fixed){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((fixed.definition.id()+"\n1\n"+fixed.instruction+"\n"+encode(TOOLS)+"\n"+SOURCE_POLICY).getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private void requireEnabled(){if(!enabled())throw problem(404,"AI_SKILLS_DISABLED");}
    private static ConversationProblem problem(int status,String code){return new ConversationProblem(status,code);}
}
