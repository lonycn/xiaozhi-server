package xiaozhi.modules.supportsummary;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import xiaozhi.modules.conversation.*;
import static xiaozhi.modules.supportsummary.AssistanceModels.*;
import static xiaozhi.modules.supportsummary.SummaryModels.*;

/** Durable, explicitly staff-triggered internal generation. Never writes a message or executes a tool. */
@Service
@ConditionalOnProperty(prefix="features",name={"conversation-enabled","support-handoff-enabled","support-summary-assistance-enabled"},havingValue="true")
public class SupportAssistanceService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ConversationAccessService access;
    private final SupportSummaryService summaries;
    private final TransactionTemplate tx;
    private String model="qwen-flash";
    private static final int LEASE_SECONDS=120;
    private static final Set<String> ERRORS=Set.of("PROVIDER_TIMEOUT","PROVIDER_AUTH","PROVIDER_RATE_LIMIT","PROVIDER_UNAVAILABLE","PROVIDER_ERROR","WORKER_ERROR","INVALID_MODEL_OUTPUT","PROVIDER_OUTPUT_LIMIT");
    public SupportAssistanceService(JdbcTemplate jdbc,ObjectMapper json,ConversationAccessService access,SupportSummaryService summaries,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.json=json;this.access=access;this.summaries=summaries;this.tx=new TransactionTemplate(manager);
    }
    @Value("${ai.execution.model:qwen-flash}") public void setModel(String value) {
        if(value==null||!value.matches("[A-Za-z0-9_.:/-]{1,128}"))throw new IllegalArgumentException("Invalid assistance model");model=value;
    }
    public Job generate(Long actor,String conversation,Generate body) {
        if(body==null||body.baseVersion()<1||body.clientRequestId()==null||!body.clientRequestId().matches("[A-Za-z0-9_.:-]{1,80}"))throw problem(400,"INVALID_ASSISTANCE_REQUEST");
        return tx.execute(status->{
            authorize(actor,conversation);
            var prior=jdbc.queryForList("SELECT id,base_version FROM support_summary_job WHERE conversation_id=? AND actor_user_id=? AND client_request_id=? FOR UPDATE",conversation,actor,body.clientRequestId());
            if(!prior.isEmpty()) {
                if(number(prior.getFirst(),"base_version")!=body.baseVersion())throw problem(409,"ASSISTANCE_IDEMPOTENCY_CONFLICT");
                var existing=lock((String)prior.getFirst().get("id"));
                if(!Objects.equals(existing.get("assignment_id"),assignment(conversation,actor)))throw problem(409,"ASSISTANCE_IDEMPOTENCY_CONFLICT");
                return job((String)prior.getFirst().get("id"));
            }
            if(summaries.latestVersion(conversation)!=body.baseVersion())throw problem(409,"SUMMARY_VERSION_CONFLICT");
            var active=jdbc.queryForList("SELECT id FROM support_summary_job WHERE conversation_id=? AND status IN ('queued','running') FOR UPDATE",conversation);
            for(var existing:active) {
                var row=lock((String)existing.get("id"));
                if(validCurrent(row))throw problem(409,"ASSISTANCE_ALREADY_PENDING");
                stale(row);
            }
            long through=jdbc.queryForObject("SELECT last_seq FROM ai_conversation WHERE id=? FOR UPDATE",Long.class,conversation);
            var rows=jdbc.queryForList("SELECT id,seq,sender_type,parts_json FROM ai_message WHERE conversation_id=? AND seq<=? ORDER BY seq DESC LIMIT 81 FOR UPDATE",conversation,through);
            var messages=new ArrayList<Object>();boolean truncated=rows.size()>80;
            for(var row:rows.stream().limit(80).toList().reversed()) {
                var parts=new ArrayList<Object>();
                for(JsonNode part:tree((String)row.get("parts_json"))) {
                    if("text".equals(part.path("type").asText())) {
                        String text=part.path("text").asText();if(text.length()>2000)truncated=true;
                        parts.add(Map.of("type","text","text",text.substring(0,Math.min(2000,text.length()))));
                    }
                    String attachment=part.path("attachment_id").asText(null);
                    if(attachment!=null&&!jdbc.queryForList("SELECT id FROM ai_attachment WHERE id=? AND conversation_id=? AND linked_message_id=? AND linked_seq=? FOR UPDATE",attachment,conversation,row.get("id"),row.get("seq")).isEmpty())
                        parts.add(Map.of("type","attachment_reference","attachment_id",attachment,"content_verified",false));
                }
                messages.add(Map.of("message_id",row.get("id"),"seq",row.get("seq"),"sender_type",row.get("sender_type"),"parts",parts));
            }
            String fields=jdbc.queryForObject("SELECT fields_json FROM support_summary_revision WHERE conversation_id=? AND version=? FOR UPDATE",String.class,conversation,body.baseVersion());
            String source=encode(Map.of("messages",messages,"base_fields",tree(fields),"source_truncated",truncated,"source_through_seq",through));
            String id=UUID.randomUUID().toString().replace("-","");
            jdbc.update("INSERT INTO support_summary_job(id,conversation_id,actor_user_id,assignment_id,client_request_id,base_version,source_through_seq,source_json,source_sha256,model) VALUES(?,?,?,?,?,?,?,?,?,?)",id,conversation,actor,assignment(conversation,actor),body.clientRequestId(),body.baseVersion(),through,source,sha(source),model);
            return job(id);
        });
    }
    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Availability(boolean enabled,Job latestJob) { }
    public Availability latest(Long actor,String conversation) {
        return tx.execute(status->{authorize(actor,conversation);String assignment=assignment(conversation,actor);
            var ids=jdbc.queryForList("SELECT id FROM support_summary_job WHERE conversation_id=? AND actor_user_id=? AND assignment_id=? ORDER BY list_sequence DESC LIMIT 1 FOR UPDATE",String.class,conversation,actor,assignment);
            return new Availability(true,ids.isEmpty()?null:get(actor,conversation,ids.getFirst()));});
    }
    public Job get(Long actor,String conversation,String id) {
        return tx.execute(status->{authorize(actor,conversation);var row=lock(id);if(!conversation.equals(row.get("conversation_id")))throw missing();
            // Only the exact initiating assignment can see model output, even if the same agent is assigned again.
            if(number(row,"actor_user_id")!=actor||!Objects.equals(row.get("assignment_id"),assignment(conversation,actor)))throw missing();
            if(Set.of("queued","running","completed").contains(row.get("status"))&&!validCurrent(row))stale(row);
            return job(id);});
    }
    public Job retry(Long actor,String conversation,String id) {
        return tx.execute(status->{authorize(actor,conversation);var row=lock(id);
            if(!conversation.equals(row.get("conversation_id"))||number(row,"actor_user_id")!=actor||!Objects.equals(row.get("assignment_id"),assignment(conversation,actor)))throw missing();
            if(!"failed".equals(row.get("status"))||number(row,"attempt")>=3)throw problem(409,"ASSISTANCE_RETRY_UNAVAILABLE");
            if(!validCurrent(row)){stale(row);return job(id);}
            jdbc.update("UPDATE support_summary_job SET status='queued',error_code=NULL,updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",id);return job(id);});
    }
    public Task claim(String worker) {
        if(worker==null||!worker.matches("[A-Za-z0-9_.:-]{1,128}"))throw problem(400,"INVALID_WORKER_ID");
        var ids=jdbc.queryForList("SELECT id,actor_user_id,conversation_id FROM support_summary_job WHERE status='queued' OR (status='running' AND lease_expires_at<=CURRENT_TIMESTAMP(3)) ORDER BY list_sequence LIMIT 32");
        for(var candidate:ids) {
            Task task=tx.execute(status->{
                boolean authorized=tryAuthorize(number(candidate,"actor_user_id"),(String)candidate.get("conversation_id"));
                var row=lock((String)candidate.get("id"));
                if(!Set.of("queued","running").contains(row.get("status")))return null;
                if(!authorized||!validCurrent(row)){stale(row);return null;}
                if("running".equals(row.get("status"))) {
                    if(live(row))return null;
                    finishAttempt(row,"failed","LEASE_EXPIRED",null,null);
                }
                if(number(row,"attempt")>=3){jdbc.update("UPDATE support_summary_job SET status='failed',error_code='RETRY_EXHAUSTED',updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",row.get("id"));return null;}
                String token=UUID.randomUUID().toString().replace("-","");int attempt=(int)number(row,"attempt")+1;
                jdbc.update("UPDATE support_summary_job SET status='running',attempt=?,lease_token=?,lease_owner=?,lease_expires_at=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL ? SECOND),updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",attempt,token,worker,LEASE_SECONDS,row.get("id"));
                jdbc.update("INSERT INTO support_summary_attempt(job_id,attempt,model,worker_id,status) VALUES(?,?,?,?,'running')",row.get("id"),attempt,row.get("model"),worker);
                return new Task((String)row.get("id"),token,LEASE_SECONDS,(String)row.get("model"),tree((String)row.get("source_json")));
            });if(task!=null)return task;
        }return null;
    }
    public Job heartbeat(String id,String token) {return callback(id,token,row->{jdbc.update("UPDATE support_summary_job SET lease_expires_at=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL ? SECOND) WHERE id=?",LEASE_SECONDS,id);return job(id);});}
    public Job fail(String id,Fail body) {
        if(body==null||!ERRORS.contains(body.errorCode()))throw problem(400,"INVALID_ASSISTANCE_FAILURE");
        if(body.usage()!=null)validateUsage(body.usage());
        if(body.providerRequestId()!=null&&!body.providerRequestId().matches("[A-Za-z0-9_.:-]{1,256}"))throw problem(400,"INVALID_ASSISTANCE_RESULT");
        String usage=body.usage()==null?null:encode(body.usage());
        return callback(id,body.leaseToken(),row->{finishAttempt(row,"failed",body.errorCode(),usage,body.providerRequestId());jdbc.update("UPDATE support_summary_job SET status='failed',error_code=?,usage_json=?,provider_request_id=?,updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",body.errorCode(),usage,body.providerRequestId(),id);return job(id);});
    }
    public Job complete(String id,Complete body) {
        if(body==null||body.usage()==null||body.providerRequestId()==null||!body.providerRequestId().matches("[A-Za-z0-9_.:-]{1,256}"))throw problem(400,"INVALID_ASSISTANCE_RESULT");
        Usage u=body.usage();validateUsage(u);
        return callback(id,body.leaseToken(),row->{
            String conversation=(String)row.get("conversation_id");long through=number(row,"source_through_seq");
            try {validateResult(row,body);}
            catch(ConversationProblem invalid) {
                finishAttempt(row,"failed","INVALID_MODEL_OUTPUT",encode(u),body.providerRequestId());
                jdbc.update("UPDATE support_summary_job SET status='failed',error_code='INVALID_MODEL_OUTPUT',usage_json=?,provider_request_id=?,updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",encode(u),body.providerRequestId(),id);return job(id);
            }
            boolean truncated=tree((String)row.get("source_json")).path("source_truncated").asBoolean();
            var revision=summaries.insert(conversation,number(row,"base_version"),"model_assisted",number(row,"actor_user_id"),through,truncated,body.fields());
            finishAttempt(row,"completed",null,encode(u),body.providerRequestId());
            jdbc.update("UPDATE support_summary_job SET status='completed',result_version=?,suggestions_json=?,usage_json=?,provider_request_id=?,updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",revision.version(),encode(body.suggestions()),encode(u),body.providerRequestId(),id);return job(id);
        });
    }
    private void validateUsage(Usage u){if(u.inputTokens()<0||u.outputTokens()<0||u.totalTokens()<0||u.inputTokens()>10000000||u.outputTokens()>10000000||u.totalTokens()!=u.inputTokens()+u.outputTokens())throw problem(400,"INVALID_ASSISTANCE_USAGE");}
    private void validateResult(Map<String,Object> row,Complete body) {
        String conversation=(String)row.get("conversation_id");long through=number(row,"source_through_seq");
        JsonNode base=tree((String)row.get("source_json")).path("base_fields");
        if(body.fields()==null||!json.valueToTree(body.fields().get("verified_facts")).equals(base.path("verified_facts")))throw problem(400,"MODEL_CANNOT_VERIFY_FACTS");
        for(var entry:body.fields().entrySet())for(var item:entry.getValue()==null?List.<Item>of():entry.getValue())
            if(item==null||(!"verified_facts".equals(entry.getKey())&&(item.verificationNote()!=null||!Set.of("unknown","user_statement","staff_statement","evidence_reference","ai_inference").contains(item.attribution()==null?"":item.attribution()))))throw problem(400,"INVALID_MODEL_ATTRIBUTION");
        summaries.validateModel(conversation,through,body.fields());
        if(body.suggestions()==null||body.suggestions().size()>10)throw problem(400,"INVALID_MODEL_SUGGESTIONS");
        for(Item item:body.suggestions()) {
            if(item==null||!"ai_inference".equals(item.attribution())||item.verificationNote()!=null)throw problem(400,"INVALID_MODEL_SUGGESTIONS");
            var fields=new LinkedHashMap<String,List<Item>>();for(String field:FIELDS)fields.put(field,List.of(new Item("待确认","unknown",List.of(),null)));
            fields.put("hypotheses",List.of(item));summaries.validateModel(conversation,through,fields);
        }
        // Require every quote/ref to have been sent in this immutable bounded model input.
        for(List<Item> items:body.fields().values())for(Item item:items)if(!"verified_fact".equals(item.attribution()))checkSnapshot(row,item);
        for(Item item:body.suggestions())checkSnapshot(row,item);
    }
    private void checkSnapshot(Map<String,Object> row,Item item) {
        JsonNode messages=tree((String)row.get("source_json")).path("messages");
        for(Evidence ref:item.evidence()) {
            boolean found=false;
            for(JsonNode message:messages)if(message.path("message_id").asText().equals(ref.messageId())&&message.path("seq").asLong()==ref.seq())
                for(JsonNode part:message.path("parts")) {
                    if(ref.attachmentId()!=null&&ref.attachmentId().equals(part.path("attachment_id").asText()))found=true;
                    if(ref.attachmentId()==null&&ref.quote()!=null&&part.path("text").asText().contains(ref.quote()))found=true;
                }
            if(!found)throw problem(400,"EVIDENCE_OUTSIDE_MODEL_SOURCE");
        }
    }
    private Job callback(String id,String token,Function<Map<String,Object>,Job> action) {
        var candidates=jdbc.queryForList("SELECT actor_user_id,conversation_id FROM support_summary_job WHERE id=?",id);if(candidates.isEmpty())throw missing();
        Job result=tx.execute(status->{var candidate=candidates.getFirst();boolean authorized=tryAuthorize(number(candidate,"actor_user_id"),(String)candidate.get("conversation_id"));var row=lock(id);
            if(!authorized||!validCurrent(row)){if(Set.of("running","queued").contains(row.get("status")))stale(row);return null;}
            if(token==null||!token.equals(row.get("lease_token")))throw problem(409,"ASSISTANCE_LEASE_INVALID");
            if(Set.of("completed","failed").contains(row.get("status")))return job(id);
            if(!"running".equals(row.get("status"))||!live(row))throw problem(409,"ASSISTANCE_LEASE_INVALID");return action.apply(row);});
        if(result==null)throw problem(409,"ASSISTANCE_SOURCE_CHANGED");return result;
    }
    private boolean tryAuthorize(long actor,String conversation){try{authorize(actor,conversation);return true;}catch(ConversationProblem e){if(e.status()==404){jdbc.queryForList("SELECT id FROM ai_conversation WHERE id=? FOR UPDATE",conversation);return false;}throw e;}}
    private void authorize(Long actor,String conversation) {
        if(actor==null||actor<=0)throw problem(401,"AUTHENTICATION_REQUIRED");
        access.lockSenderRole(actor);access.requireCurrentStaff(actor,conversation);
        if(jdbc.queryForObject("SELECT owner_user_id FROM ai_conversation WHERE id=? FOR UPDATE",Long.class,conversation).equals(actor))throw missing();
    }
    private String assignment(String conversation,long actor) {var ids=jdbc.queryForList("SELECT id FROM support_assignment WHERE conversation_id=? AND agent_user_id=? AND released_at IS NULL FOR UPDATE",String.class,conversation,actor);return ids.size()==1?ids.getFirst():null;}
    private boolean validCurrent(Map<String,Object> row) {
        return Objects.equals(row.get("assignment_id"),assignment((String)row.get("conversation_id"),number(row,"actor_user_id")))
            && jdbc.queryForObject("SELECT last_seq FROM ai_conversation WHERE id=? FOR UPDATE",Long.class,row.get("conversation_id"))==number(row,"source_through_seq")
            && summaries.latestVersion((String)row.get("conversation_id"))==(row.get("result_version")==null?number(row,"base_version"):number(row,"result_version"));
    }
    private void stale(Map<String,Object> row){finishAttempt(row,"stale","SOURCE_CHANGED",null,null);jdbc.update("UPDATE support_summary_job SET status='stale',error_code='SOURCE_CHANGED',updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",row.get("id"));}
    private void finishAttempt(Map<String,Object> row,String status,String error,String usage,String provider) {jdbc.update("UPDATE support_summary_attempt SET status=?,error_code=?,usage_json=?,provider_request_id=?,finished_at=CURRENT_TIMESTAMP(3) WHERE job_id=? AND attempt=? AND status='running'",status,error,usage,provider,row.get("id"),row.get("attempt"));}
    private boolean live(Map<String,Object> row){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT lease_expires_at>CURRENT_TIMESTAMP(3) FROM support_summary_job WHERE id=?",Boolean.class,row.get("id")));}
    private Map<String,Object> lock(String id){var rows=jdbc.query("SELECT * FROM support_summary_job WHERE id=? FOR UPDATE",new xiaozhi.common.jdbc.TimestampColumnMapRowMapper(),id);if(rows.isEmpty())throw missing();return rows.getFirst();}
    private Job job(String id){var r=lock(id);try{return new Job(id,(String)r.get("conversation_id"),(String)r.get("status"),number(r,"base_version"),number(r,"source_through_seq"),(int)number(r,"attempt"),(String)r.get("model"),(String)r.get("error_code"),r.get("result_version")==null?null:number(r,"result_version"),instant(r.get("created_at")),instant(r.get("updated_at")),r.get("usage_json")==null?null:tree((String)r.get("usage_json")),(String)r.get("provider_request_id"),r.get("suggestions_json")==null?List.of():json.readValue((String)r.get("suggestions_json"),new TypeReference<List<Item>>(){}));}catch(java.io.IOException e){throw new IllegalStateException(e);}}
    private JsonNode tree(String value){try{return json.readTree(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private static java.time.Instant instant(Object value){
        if(value instanceof java.sql.Timestamp timestamp)return timestamp.toInstant();
        throw new IllegalStateException("Invalid stored assistance timestamp");
    }
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static String sha(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static ConversationProblem missing(){return problem(404,"SUPPORT_ASSISTANCE_NOT_FOUND");}
    private static ConversationProblem problem(int status,String code){return new ConversationProblem(status,code);}
}
