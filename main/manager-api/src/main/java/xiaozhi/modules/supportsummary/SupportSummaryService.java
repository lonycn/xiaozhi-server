package xiaozhi.modules.supportsummary;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import xiaozhi.modules.conversation.*;
import static xiaozhi.modules.supportsummary.SummaryModels.*;

/** Internal evidence-bound drafts. Never publishes content into the customer ledger or invokes a model. */
@Service
@ConditionalOnProperty(prefix="features",name={"conversation-enabled","support-handoff-enabled"},havingValue="true")
public class SupportSummaryService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ConversationAccessService access;
    private final TransactionTemplate tx;
    public SupportSummaryService(JdbcTemplate jdbc, ObjectMapper json, ConversationAccessService access,
                                 PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.json=json; this.access=access; this.tx=new TransactionTemplate(manager);
    }
    /** Called only by the already-authorized handoff transaction, while it owns the conversation lock. */
    public void createForHandoff(String conversation) {
        tx.executeWithoutResult(status -> {
            long through=jdbc.queryForObject("SELECT last_seq FROM ai_conversation WHERE id=? FOR UPDATE",Long.class,conversation);
            var messages=jdbc.queryForList("SELECT id,seq,sender_type,parts_json FROM ai_message WHERE conversation_id=? AND seq<=? ORDER BY seq DESC LIMIT 81 FOR UPDATE",conversation,through);
            boolean truncated=messages.size()>80;
            var fields=new LinkedHashMap<String,List<Item>>();
            for(String field:FIELDS) fields.put(field,List.of(unknown("待确认：原文摘录草稿未对本项作语义归类或技术核验")));
            var user=new ArrayList<Item>(); var attachments=new ArrayList<Item>(); var hypotheses=new ArrayList<Item>();
            for(var message:messages.stream().limit(80).toList().reversed()) {
                String id=(String)message.get("id"), sender=(String)message.get("sender_type"); long seq=((Number)message.get("seq")).longValue();
                for(JsonNode part:tree((String)message.get("parts_json"))) {
                    if("text".equals(part.path("type").asText()) && !part.path("text").asText().isBlank()) {
                        String text=part.path("text").asText(); if(text.length()>500)truncated=true; String excerpt=text.substring(0,Math.min(500,text.length()));
                        var item=new Item(excerpt,"user".equals(sender)?"user_statement":"ai_inference",List.of(new Evidence(id,seq,null,excerpt)),null);
                        if("user".equals(sender))user.add(item);
                        else if("assistant".equals(sender)||"ai".equals(sender))hypotheses.add(item);
                    }
                    String attachment=part.path("attachment_id").asText(null);
                    if(attachment!=null && attachmentBound(conversation,id,seq,attachment))
                        attachments.add(new Item("原始附件（尚未核验内容）","evidence_reference",List.of(new Evidence(id,seq,attachment,null)),null));
                }
            }
            // These are excerpts for triage, explicitly not inferred goals, symptoms, project or device facts.
            if(!user.isEmpty())fields.put("user_goal",limit(user));
            if(!attachments.isEmpty())fields.put("evidence_attachments",limit(attachments));
            if(!hypotheses.isEmpty())fields.put("hypotheses",limit(hypotheses));
            fields.put("missing_information",List.of(unknown("待人工确认用户诉求、项目/设备、症状、已尝试步骤、事实依据及安全风险；仅摘录最近80条消息，每项最多20条，每段最多500字")));
            long previous=latestVersion(conversation);
            insert(conversation,previous,"deterministic_excerpt",null,through,truncated || user.size()>20 || attachments.size()>20 || hypotheses.size()>20,fields);
        });
    }
    private static List<Item> limit(List<Item> list){return list.stream().skip(Math.max(0,list.size()-20)).toList();}
    private static Item unknown(String text){return new Item(text,"unknown",List.of(),null);}

    public List<Revision> history(Long actor,String conversation,long before,int limit) {
        long user=actor(actor); validId(conversation);
        if(before<0 || limit<1 || limit>50)throw problem(400,"INVALID_SUMMARY_PAGE");
        return tx.execute(status -> {
            access.lockSenderRole(user); // follows profile/account -> conversation lock order
            // Locking reads are mandatory: an outer RR transaction may already have an older snapshot.
            var owners=jdbc.queryForList("SELECT owner_user_id FROM ai_conversation WHERE id=? FOR UPDATE",Long.class,conversation);
            if(owners.isEmpty()||owners.getFirst()==user)throw missing();
            var assignments=jdbc.query("""
                    SELECT a.id,a.released_at,a.read_through_seq FROM support_assignment a
                    JOIN support_request r ON r.id=a.request_id AND r.conversation_id=a.conversation_id
                    WHERE a.conversation_id=? AND a.agent_user_id=? AND (a.released_at IS NOT NULL
                      OR (r.status='active' AND r.assigned_agent_user_id=a.agent_user_id)) FOR UPDATE
                    """,(rs,n)->new AssignmentGrant(rs.getString("id"),rs.getTimestamp("released_at")==null,rs.getLong("read_through_seq")),conversation,user);
            if(assignments.isEmpty())throw missing();
            boolean current=assignments.stream().anyMatch(AssignmentGrant::current);
            var result=new ArrayList<Revision>();long cursor=before==0?Long.MAX_VALUE:before;
            while(result.size()<limit) {
                var candidates=jdbc.query("SELECT * FROM support_summary_revision WHERE conversation_id=? AND version<? ORDER BY version DESC LIMIT 50 FOR UPDATE",
                        (rs,n)->new VisibleRevision(row(rs,n),releasedIds(rs.getString("released_assignment_ids"))),conversation,cursor);
                for(var candidate:candidates) {
                    boolean visible=current||assignments.stream().anyMatch(assignment->!assignment.current()
                            && assignment.through()>=candidate.revision().sourceThroughSeq()
                            && !candidate.excluded().contains(assignment.id()));
                    if(visible)result.add(candidate.revision());
                    if(result.size()==limit)break;
                }
                if(candidates.size()<50)break;
                cursor=candidates.getLast().revision().version();
            }
            return result;
        });
    }
    /** Additive upgrade path for requests already active before the summary migration. */
    public Revision prepare(Long actor,String conversation) {
        long user=actor(actor);validId(conversation);
        return tx.execute(status -> {
            access.lockSenderRole(user);access.requireCurrentStaff(user,conversation);
            if(jdbc.queryForObject("SELECT owner_user_id FROM ai_conversation WHERE id=? FOR UPDATE",Long.class,conversation)==user)throw missing();
            if(latestVersion(conversation)==0)createForHandoff(conversation);
            return jdbc.queryForObject("SELECT * FROM support_summary_revision WHERE conversation_id=? ORDER BY version DESC LIMIT 1 FOR UPDATE",this::row,conversation);
        });
    }
    public Revision edit(Long actor,String conversation,Edit body) {
        long user=actor(actor); validId(conversation);
        if(body==null||body.baseVersion()<1||body.fields()==null||!body.fields().keySet().equals(new HashSet<>(FIELDS)))throw problem(400,"INVALID_SUMMARY_FIELDS");
        return tx.execute(status -> {
            access.lockSenderRole(user);
            var current=access.requireCurrentStaff(user,conversation);
            if(jdbc.queryForObject("SELECT owner_user_id FROM ai_conversation WHERE id=? FOR UPDATE",Long.class,conversation)==user)throw missing();
            if(latestVersion(conversation)!=body.baseVersion())throw problem(409,"SUMMARY_VERSION_CONFLICT");
            validate(conversation,current.lastSeq(),body.fields());
            return insert(conversation,body.baseVersion(),"staff_edit",user,current.lastSeq(),jdbc.queryForObject("SELECT source_truncated FROM support_summary_revision WHERE conversation_id=? AND version=? FOR UPDATE",Boolean.class,conversation,body.baseVersion()),body.fields());
        });
    }
    void validateModel(String conversation,long through,Map<String,List<Item>> fields) { validate(conversation,through,fields,true); }
    private void validate(String conversation,long through,Map<String,List<Item>> fields) { validate(conversation,through,fields,false); }
    private void validate(String conversation,long through,Map<String,List<Item>> fields,boolean model) {
        if(fields==null||!fields.keySet().equals(new HashSet<>(FIELDS)))throw problem(400,"INVALID_SUMMARY_FIELDS");
        // A staff edit may retain a generated inference citing user evidence. It may not invent AI provenance.
        Set<Item> retainedInferences=new HashSet<>();
        if(!model) {
            var previous=jdbc.queryForList("SELECT fields_json FROM support_summary_revision WHERE conversation_id=? ORDER BY version DESC LIMIT 1 FOR UPDATE",String.class,conversation);
            if(!previous.isEmpty())try {
                Map<String,List<Item>> saved=json.readValue(previous.getFirst(),new TypeReference<Map<String,List<Item>>>(){});
                for(Item item:saved.getOrDefault("hypotheses",List.of()))if("ai_inference".equals(item.attribution()))retainedInferences.add(item);
            }catch(java.io.IOException e){throw new IllegalStateException(e);}
        }
        int total=0;
        for(var field:fields.entrySet()) {
            if(field.getValue()==null||field.getValue().isEmpty()||field.getValue().size()>20)throw problem(400,"INVALID_SUMMARY_ITEMS");
            for(var item:field.getValue()) {
                if(item==null||item.text()==null||item.text().isBlank()||item.text().length()>2000||item.evidence()==null||item.evidence().size()>10)throw problem(400,"INVALID_SUMMARY_ITEM");
                total+=item.text().length();
                if(item.verificationNote()!=null&&item.verificationNote().length()>1000)throw problem(400,"INVALID_SUMMARY_ITEM");
                if(!Set.of("unknown","user_statement","staff_statement","staff_hypothesis","ai_inference","evidence_reference","verified_fact").contains(item.attribution()==null?"":item.attribution()))throw problem(400,"INVALID_SUMMARY_ATTRIBUTION");
                if("unknown".equals(item.attribution())) {if(!item.evidence().isEmpty())throw problem(400,"UNKNOWN_CANNOT_ASSERT_EVIDENCE");continue;}
                if(item.evidence().isEmpty())throw problem(400,"SUMMARY_EVIDENCE_REQUIRED");
                if("verified_facts".equals(field.getKey()) && !"verified_fact".equals(item.attribution()))throw problem(400,"SUMMARY_FACT_VERIFICATION_REQUIRED");
                if("verified_fact".equals(item.attribution()) && (!"verified_facts".equals(field.getKey())||item.verificationNote()==null||item.verificationNote().isBlank()||item.verificationNote().length()>1000))throw problem(400,"SUMMARY_FACT_VERIFICATION_REQUIRED");
                if("ai_inference".equals(item.attribution()) && !"hypotheses".equals(field.getKey()))throw problem(400,"AI_INFERENCE_ONLY_HYPOTHESIS");
                for(Evidence ref:item.evidence()) {
                    if(ref==null||ref.messageId()==null||ref.seq()<1||ref.seq()>through)throw problem(400,"INVALID_SUMMARY_EVIDENCE");
                    var rows=jdbc.queryForList("SELECT sender_type,parts_json FROM ai_message WHERE conversation_id=? AND id=? AND seq=? FOR UPDATE",conversation,ref.messageId(),ref.seq());
                    if(rows.isEmpty())throw problem(400,"INVALID_SUMMARY_EVIDENCE");
                    var message=rows.getFirst(); String sender=(String)message.get("sender_type");
                    boolean ai="assistant".equals(sender)||"ai".equals(sender);
                    if(("verified_fact".equals(item.attribution()) && !Set.of("user","support_agent","expert").contains(sender))
                      ||("user_statement".equals(item.attribution())&&!"user".equals(sender))
                      ||("staff_statement".equals(item.attribution())&&!Set.of("support_agent","expert").contains(sender))
                      ||("ai_inference".equals(item.attribution())&&!model&&!ai&&!retainedInferences.contains(item)))throw problem(400,"SUMMARY_SOURCE_ATTRIBUTION_MISMATCH");
                    boolean found=false;
                    for(JsonNode part:tree((String)message.get("parts_json"))) {
                        if(ref.attachmentId()!=null) {
                            if(ref.quote()!=null)throw problem(400,"INVALID_SUMMARY_EVIDENCE");
                            if(ref.attachmentId().equals(part.path("attachment_id").asText()) && attachmentBound(conversation,ref.messageId(),ref.seq(),ref.attachmentId()))found=true;
                        } else if(ref.quote()!=null&&!ref.quote().isBlank()&&ref.quote().length()<=1000
                                &&"text".equals(part.path("type").asText())&&part.path("text").asText().contains(ref.quote())) found=true;
                    }
                    if(!found)throw problem(400,"INVALID_SUMMARY_EVIDENCE");
                }
            }
        }
        if(total>30000)throw problem(400,"SUMMARY_TOO_LARGE");
    }
    private boolean attachmentBound(String conversation,String message,long seq,String attachment) {
        if(!attachment.matches("[0-9]{1,19}"))return false;
        return jdbc.queryForList("SELECT id FROM ai_attachment WHERE id=? AND conversation_id=? AND linked_message_id=? AND linked_seq=? FOR UPDATE",Long.class,attachment,conversation,message,seq).size()==1;
    }
    Revision insert(String conversation,long previous,String origin,Long editor,long through,boolean truncated,Map<String,List<Item>> fields) {
        // Snapshot released IDs while the conversation lock serializes all release/transfer/edit paths.
        var excluded=jdbc.queryForList("SELECT id FROM support_assignment WHERE conversation_id=? AND released_at IS NOT NULL FOR UPDATE",String.class,conversation);
        jdbc.update("INSERT INTO support_summary_revision(conversation_id,version,parent_version,origin,edited_by,source_through_seq,source_truncated,fields_json,released_assignment_ids) VALUES(?,?,?,?,?,?,?,?,?)",
            conversation,previous+1,previous==0?null:previous,origin,editor,through,truncated,encode(fields),encode(excluded));
        return jdbc.queryForObject("SELECT * FROM support_summary_revision WHERE conversation_id=? AND version=? FOR UPDATE",this::row,conversation,previous+1);
    }
    long latestVersion(String conversation){
        var versions=jdbc.queryForList("SELECT version FROM support_summary_revision WHERE conversation_id=? ORDER BY version DESC LIMIT 1 FOR UPDATE",Long.class,conversation);
        return versions.isEmpty()?0:versions.getFirst();
    }
    private record AssignmentGrant(String id,boolean current,long through) { }
    private record VisibleRevision(Revision revision,List<String> excluded) { }
    private List<String> releasedIds(String value){try{return json.readValue(value,new TypeReference<List<String>>(){});}catch(Exception e){throw new IllegalStateException(e);}}
    private Revision row(ResultSet rs,int ignored)throws SQLException {
        try {
            return new Revision(rs.getString("conversation_id"),rs.getLong("version"),rs.getObject("parent_version",Long.class),rs.getString("origin"),rs.getObject("edited_by",Long.class),rs.getLong("source_through_seq"),rs.getBoolean("source_truncated"),rs.getTimestamp("created_at").toInstant(),json.readValue(rs.getString("fields_json"),new TypeReference<Map<String,List<Item>>>(){}));
        } catch(java.io.IOException e){throw new IllegalStateException("Invalid stored summary",e);}
    }
    private JsonNode tree(String text){try{return json.readTree(text);}catch(Exception e){throw new IllegalStateException(e);}}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private static long actor(Long actor){if(actor==null||actor<=0)throw problem(401,"AUTHENTICATION_REQUIRED");return actor;}
    private static void validId(String id){if(id==null||!id.matches("[a-zA-Z0-9_-]{1,32}"))throw missing();}
    private static ConversationProblem missing(){return problem(404,"SUPPORT_SUMMARY_NOT_FOUND");}
    private static ConversationProblem problem(int status,String code){return new ConversationProblem(status,code);}
}
