package xiaozhi.modules.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import xiaozhi.modules.ai.AiModels.*;
import xiaozhi.modules.conversation.*;
import xiaozhi.modules.conversation.ConversationModels.CursorPage;

/** Conversation locks always precede run and message locks; callbacks never trust worker context. */
@Service
public class AiExecutionService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final ConversationAccessService access;
    private boolean enabled;
    private String model = "qwen-flash";
    private int leaseSeconds = 45;
    private String visionModel = "";
    private AiAttachmentContext attachments;
    private final java.util.concurrent.atomic.AtomicLong discoveryCursor = new java.util.concurrent.atomic.AtomicLong();
    static final String DISCOVERY_QUERY = "SELECT id,conversation_id,list_sequence FROM ai_run WHERE list_sequence>? AND (status='queued' OR (status IN ('running','streaming') AND lease_expires_at<=CURRENT_TIMESTAMP(3))) ORDER BY list_sequence LIMIT 32";
    private static final Set<String> ERRORS = Set.of("MODEL_MISMATCH", "PROVIDER_AUTH", "PROVIDER_RATE_LIMIT",
            "PROVIDER_TIMEOUT", "PROVIDER_UNAVAILABLE", "PROVIDER_ERROR", "WORKER_ERROR",
            "MEDIA_INPUT_UNAVAILABLE", "MODEL_CAPABILITY_UNAVAILABLE");

    public AiExecutionService(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.json = json; this.tx = new TransactionTemplate(manager);
        this.access = new ConversationAccessService(new ConversationDao(jdbc));
    }
    @Value("${features.ai-text-enabled:false}") public void setEnabled(boolean value) { enabled = value; }
    @Value("${ai.execution.model:qwen-flash}") public void setModel(String value) { model = value; }
    @Value("${ai.execution.vision-model:}") public void setVisionModel(String value) { visionModel = value == null ? "" : value.trim(); }
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setAttachments(AiAttachmentContext value) { attachments = value; }
    @Value("${ai.execution.lease-seconds:45}") public void setLeaseSeconds(int value) {
        if (value < 1 || value > 300) throw new IllegalArgumentException("Invalid lease seconds");
        leaseSeconds = value;
    }

    /** Invoked inside the message insertion transaction after advancing its ledger cursors. */
    public void onUserMessage(String conversationId, String inputId) {
        if (!enabled) return;
        Map<String,Object> c = conversation(conversationId);
        if (!"AI_ACTIVE".equals(c.get("state"))) return;
        cancelActive(conversationId);
        long sequence = number(c, "last_seq") + 1;
        String run = id(), output = id();
        jdbc.update("""
                INSERT INTO ai_message(id,conversation_id,seq,client_message_id,sender_type,sender_id,parts_json,request_hash,status)
                VALUES (?,?,?,?, 'assistant','ai', '[]', ?, 'queued')
                """, output, conversationId, sequence, "ai:" + run, hash(run));
        jdbc.update("""
                INSERT INTO ai_run(id,conversation_id,input_message_id,output_message_id,control_epoch,output_text,model)
                VALUES(?,?,?,?,?,'',?)
                """, run, conversationId, inputId, output, number(c,"control_epoch"), inputNeedsVision(inputId) && !visionModel.isBlank() ? visionModel : model);
        jdbc.update("UPDATE ai_conversation SET last_seq=? WHERE id=?", sequence, conversationId);
        Map<String,Object> r = row(run, true);
        event(r, "ai.run.queued", Map.of());
        event(r, "assistant.message.created", Map.of("seq", Long.toString(sequence)));
    }

    /** Called by every human mode/epoch transition inside its existing transaction. */
    public void cancelActive(String conversationId) {
        for (var r : jdbc.queryForList("SELECT * FROM ai_run WHERE conversation_id=? AND active_slot=1 FOR UPDATE", conversationId))
            terminal(r, "canceled", null, "ai.run.canceled");
    }

    public void authorizeStream(Long actor) {
        actor(actor);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=? AND status=1",Long.class,actor)==0)
            throw problem(401,"AUTHENTICATION_REQUIRED");
    }

    public CursorPage<Run> list(Long actor, String conversationId, long before, int limit) {
        actor(actor); validId(conversationId);
        if (before < 0 || limit < 1 || limit > 100) throw problem(400,"INVALID_CURSOR_OR_LIMIT");
        return tx.execute(t -> {
            var grant = access.read(actor, conversationId, true);
            // A run is mutable; released staff receive immutable events/messages only.
            if (!grant.owner() && !grant.current()) throw problem(404,"CONVERSATION_NOT_FOUND");
            var rows = jdbc.queryForList("SELECT * FROM ai_run WHERE conversation_id=? AND list_sequence<? ORDER BY list_sequence DESC LIMIT ?",
                    conversationId, before == 0 ? Long.MAX_VALUE : before, limit + 1);
            var items = rows.stream().limit(limit).map(this::view).toList();
            return new CursorPage<>(items, items.isEmpty() ? before : items.getLast().listSequence(), rows.size() > limit);
        });
    }
    public Run get(Long actor, String conversationId, String runId) {
        actor(actor); validId(conversationId); validId(runId);
        return tx.execute(t -> { var grant = access.read(actor, conversationId, true);
            if (!grant.owner() && !grant.current()) throw problem(404,"CONVERSATION_NOT_FOUND");
            return view(inConversation(runId, conversationId)); });
    }
    public Run cancel(Long actor, String conversationId, String runId) {
        actor(actor); validId(conversationId); validId(runId);
        return tx.execute(t -> {
            new ConversationDao(jdbc).owned(conversationId, actor, true);
            var r = inConversation(runId, conversationId);
            if (active(r)) terminal(r,"canceled",null,"ai.run.canceled");
            return view(row(runId, false));
        });
    }
    public Run retry(Long actor, String conversationId, String runId) {
        requireEnabled(); actor(actor); validId(conversationId); validId(runId);
        return tx.execute(t -> {
            new ConversationDao(jdbc).owned(conversationId, actor, true);
            var c = conversation(conversationId);
            var r = inConversation(runId, conversationId);
            if (!"AI_ACTIVE".equals(c.get("state")) || !("failed".equals(r.get("status")) || "canceled".equals(r.get("status")))
                    || number(r,"attempt") >= 3) throw problem(409,"RUN_NOT_RETRYABLE");
            String latest = jdbc.queryForObject("SELECT id FROM ai_message WHERE conversation_id=? AND sender_type='user' ORDER BY seq DESC LIMIT 1",String.class,conversationId);
            if (!r.get("input_message_id").equals(latest)
                    || !jdbc.queryForList("SELECT id FROM ai_run WHERE conversation_id=? AND active_slot=1 FOR UPDATE", conversationId).isEmpty())
                throw problem(409,"RUN_SUPERSEDED_OR_ACTIVE");
            jdbc.update("UPDATE ai_run SET status='queued',control_epoch=?,lease_token=NULL,lease_owner=NULL,lease_expires_at=NULL,error_code=NULL,updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",
                    number(c,"control_epoch"),runId);
            message(r, (String) r.get("output_text"), "queued");
            event(r,"ai.run.queued",Map.of());
            return view(row(runId,false));
        });
    }

    public Task claim(Claim body) {
        requireEnabled();
        if (body == null || body.workerId() == null || !body.workerId().matches("[a-zA-Z0-9_.:-]{1,128}")) throw problem(400,"INVALID_WORKER_ID");
        // Discovery is non-locking; each candidate is rechecked after taking its conversation lock.
        long after=discoveryCursor.get();
        var candidates = jdbc.queryForList(DISCOVERY_QUERY,after);
        if(candidates.isEmpty() && after!=0) {
            discoveryCursor.set(0);candidates=jdbc.queryForList(DISCOVERY_QUERY,0L);
        }
        for (var candidate : candidates) {
            // Waiting transcript jobs must not pin discovery to the first 32 rows forever.
            discoveryCursor.set(number(candidate,"list_sequence"));
            Task task = tx.execute(t -> {
                var c = conversation((String) candidate.get("conversation_id"));
                var r = row((String) candidate.get("id"), true);
                if (!active(r)) return null;
                if (!ownerEnabled(c) || !"AI_ACTIVE".equals(c.get("state")) || number(c,"control_epoch") != number(r,"control_epoch")) {
                    terminal(r,"canceled",null,"ai.run.canceled"); return null;
                }
                if (!"queued".equals(r.get("status")) && leaseLive(r)) return null;
                if (number(r,"attempt") >= 3) { terminal(r,"failed","RETRY_EXHAUSTED","ai.run.failed"); return null; }
                var plan = attachmentPlan(r);
                if (plan.waiting()) return null;
                if (plan.error() != null) { terminal(r,"failed",plan.error(),"ai.run.failed"); return null; }
                if (plan.vision() && visionModel.isBlank()) {
                    terminal(r,"failed","MODEL_CAPABILITY_UNAVAILABLE","ai.run.failed"); return null;
                }
                String selectedModel = plan.vision() ? visionModel : model;
                jdbc.update("UPDATE ai_run SET model=? WHERE id=?", selectedModel,r.get("id"));
                int attempt = (int) number(r,"attempt") + 1;
                String token = id() + id();
                jdbc.update("""
                        UPDATE ai_run SET status='running',attempt=?,lease_token=?,lease_owner=?,
                        lease_expires_at=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL ? SECOND),last_chunk_sequence=0,
                        last_chunk_hash=NULL,output_text='',error_code=NULL,usage_json=NULL,finish_reason=NULL,
                        provider_request_id=NULL,updated_at=CURRENT_TIMESTAMP(3) WHERE id=?
                        """, attempt,token,body.workerId(),leaseSeconds,r.get("id"));
                r = row((String) r.get("id"),true);
                message(r,"","running");
                if (attempt > 1) event(r,"assistant.message.reset",Map.of());
                event(r,"ai.run.started",Map.of());
                return new Task((String)r.get("id"),(String)r.get("conversation_id"),(String)r.get("input_message_id"),
                        (String)r.get("output_message_id"),number(r,"control_epoch"),attempt,token,leaseSeconds,
                        (String)r.get("model"),context(r, plan.refs()));
            });
            if (task != null) return task;
        }
        return null;
    }
    public Map<String,Object> heartbeat(String runId, Lease body) {
        return callback(runId,body == null ? null : body.leaseToken(), r -> {
            jdbc.update("UPDATE ai_run SET lease_expires_at=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL ? SECOND),updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",leaseSeconds,runId);
            return Map.of("active",true);
        });
    }
    public Map<String,Object> delta(String runId, Delta body) {
        if (body == null || body.sequence() == null || body.sequence() < 1 || body.text() == null || body.text().isEmpty() || body.text().length() > 16000)
            throw problem(400,"INVALID_DELTA");
        return callback(runId,body.leaseToken(),r -> {
            long previous = number(r,"last_chunk_sequence");
            String hash = hash(body.text());
            if (body.sequence() == previous && hash.equals(r.get("last_chunk_hash"))) return Map.of("sequence",Long.toString(previous));
            if (body.sequence() != previous + 1) throw problem(409,"CHUNK_SEQUENCE_CONFLICT");
            String text = (String) r.get("output_text") + body.text();
            if (text.length() > 256000) throw problem(409,"OUTPUT_LIMIT");
            jdbc.update("UPDATE ai_run SET status='streaming',last_chunk_sequence=?,last_chunk_hash=?,output_text=?,updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",body.sequence(),hash,text,runId);
            message(r,text,"streaming");
            event(r,"assistant.text.delta",Map.of("chunk_sequence",Long.toString(body.sequence()),"text",body.text()));
            return Map.of("sequence",Long.toString(body.sequence()));
        });
    }
    public Run complete(String runId, Complete body) {
        if (body == null || body.lastSequence() == null || body.lastSequence() < 0
                || (body.finishReason()!=null && !Set.of("stop","length","content_filter","tool_calls","unknown").contains(body.finishReason()))
                || (body.providerRequestId()!=null && (body.providerRequestId().length()>200 || body.providerRequestId().matches(".*[\\r\\n].*")))) throw problem(400,"INVALID_COMPLETION");
        validateUsage(body.usage());
        return callback(runId,body.leaseToken(),r -> {
            if (body.lastSequence() != number(r,"last_chunk_sequence")) throw problem(409,"CHUNK_SEQUENCE_CONFLICT");
            String usage = body.usage() == null ? null : encode(body.usage());
            jdbc.update("UPDATE ai_run SET usage_json=?,finish_reason=?,provider_request_id=? WHERE id=?",usage,body.finishReason(),body.providerRequestId(),runId);
            if (body.usage()!=null) jdbc.update("INSERT INTO ai_model_usage(run_id,attempt,model,input_tokens,output_tokens,total_tokens,provider_request_id) VALUES(?,?,?,?,?,?,?)",
                    runId,number(r,"attempt"),r.get("model"),body.usage().inputTokens(),body.usage().outputTokens(),body.usage().totalTokens(),body.providerRequestId());
            terminal(r,"completed",null,"assistant.message.completed");
            return view(row(runId,false));
        });
    }
    public Run fail(String runId, Fail body) {
        if (body==null || !ERRORS.contains(body.errorCode()==null ? "" : body.errorCode())) throw problem(400,"INVALID_ERROR_CODE");
        return callback(runId,body.leaseToken(),r -> {
            terminal(r,"failed",body.errorCode(),"ai.run.failed"); return view(row(runId,false));
        });
    }
    private <T> T callback(String runId, String token, java.util.function.Function<Map<String,Object>,T> action) {
        requireEnabled(); validId(runId);
        if (token==null || !token.matches("[a-f0-9]{64}")) throw problem(409,"LEASE_INVALID");
        var hint = row(runId,false);
        return tx.execute(t -> {
            var c = conversation((String)hint.get("conversation_id"));
            var r = row(runId,true);
            if (!ownerEnabled(c) || !"AI_ACTIVE".equals(c.get("state")) || number(c,"control_epoch")!=number(r,"control_epoch")
                    || !("running".equals(r.get("status")) || "streaming".equals(r.get("status")))
                    || !token.equals(r.get("lease_token")) || !leaseLive(r)) throw problem(409,"LEASE_INVALID");
            return action.apply(r);
        });
    }
    private boolean ownerEnabled(Map<String,Object> conversation) {
        // Non-locking account read avoids inverting support's profile/account -> conversation order.
        return jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=? AND status=1",
                Long.class,conversation.get("owner_user_id")) == 1;
    }
    private boolean leaseLive(Map<String,Object> r) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT lease_expires_at>CURRENT_TIMESTAMP(3) FROM ai_run WHERE id=?",Boolean.class,r.get("id")));
    }
    private boolean inputNeedsVision(String inputId) {
        String parts=jdbc.queryForObject("SELECT parts_json FROM ai_message WHERE id=?",String.class,inputId);
        for (JsonNode part:decode(parts))
            if (part.hasNonNull("attachment_id") && Set.of("image","video").contains(part.path("type").asText())) return true;
        return false;
    }
    private AiAttachmentContext.Plan attachmentPlan(Map<String,Object> r) {
        var input=jdbc.queryForMap("SELECT seq,parts_json FROM ai_message WHERE id=?",r.get("input_message_id"));
        JsonNode parts=decode((String)input.get("parts_json"));
        boolean hasMedia=false;
        for(JsonNode part:parts) if(part.hasNonNull("attachment_id")) {hasMedia=true;break;}
        if(!hasMedia) return new AiAttachmentContext.Plan(List.of(),false,false,null);
        if(attachments==null) return new AiAttachmentContext.Plan(List.of(),false,false,"MEDIA_INPUT_UNAVAILABLE");
        return attachments.plan((String)r.get("conversation_id"),number(input,"seq"),parts);
    }
    public xiaozhi.modules.attachment.AttachmentService.Content media(String runId,String attachmentId,String variant,String token) {
        record Authorized(String conversationId,long sequence) { }
        Authorized authorized=callback(runId,token,r->{
            if(attachments==null) throw problem(404,"MEDIA_INPUT_UNAVAILABLE");
            var plan=attachmentPlan(r);
            if(plan.waiting() || plan.error()!=null || plan.refs().stream().noneMatch(ref ->
                    ref.attachmentId().equals(attachmentId) && ref.variant().equals(variant)))
                throw problem(404,"MEDIA_INPUT_UNAVAILABLE");
            long seq=jdbc.queryForObject("SELECT seq FROM ai_message WHERE id=?",Long.class,r.get("input_message_id"));
            return new Authorized((String)r.get("conversation_id"),seq);
        });
        // Authorized transaction is finished before any object-store stream is opened.
        return attachments.content(attachmentId,authorized.conversationId(),authorized.sequence(),variant);
    }
    private List<ContextMessage> context(Map<String,Object> r,List<MediaRef> currentRefs) {
        long input = jdbc.queryForObject("SELECT seq FROM ai_message WHERE id=?",Long.class,r.get("input_message_id"));
        var rows = jdbc.queryForList("SELECT id,sender_type,parts_json FROM ai_message WHERE conversation_id=? AND seq<=? AND (sender_type='user' OR (sender_type IN ('assistant','support_agent','expert') AND status='completed')) ORDER BY seq DESC LIMIT 40",r.get("conversation_id"),input);
        List<ContextMessage> result = new ArrayList<>(); int budget=64000;
        for (var row:rows) {
            StringBuilder text = new StringBuilder();
            String sender = (String)row.get("sender_type");
            boolean current = r.get("input_message_id").equals(row.get("id"));
            if ("support_agent".equals(sender) || "expert".equals(sender))
                text.append("[Previous human ").append(sender).append(" reply]\n");
            for (JsonNode part:decode((String)row.get("parts_json"))) {
                if ("text".equals(part.path("type").asText())) text.append(part.path("text").asText()).append('\n');
                else if ("location".equals(part.path("type").asText())) text.append("User shared location: ").append(part).append('\n');
                else if (part.hasNonNull("attachment_id")) {
                    text.append(current ? "[Current attachment " : "[Previous attachment ")
                        .append(part.path("type").asText()).append(" id=").append(part.path("attachment_id").asText())
                        .append(current ? "; content follows from verified derivatives]\n" : "; original content omitted]\n");
                }
            }
            if (text.isEmpty()) continue;
            String content = text.substring(0,Math.min(text.length(),budget));
            result.add(new ContextMessage("user".equals(sender)?"user":"assistant",content,current ? currentRefs : null));
            budget-=content.length(); if (budget<=0) break;
        }
        Collections.reverse(result); return result;
    }
    private void terminal(Map<String,Object> r,String status,String error,String type) {
        jdbc.update("UPDATE ai_run SET status=?,error_code=?,lease_token=NULL,lease_owner=NULL,lease_expires_at=NULL,updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",status,error,r.get("id"));
        message(r,(String)r.get("output_text"),status);
        event(r,type,error==null?Map.of():Map.of("error_code",error));
    }
    private void message(Map<String,Object> r,String text,String status) {
        jdbc.update("UPDATE ai_message SET parts_json=?,status=? WHERE id=?",encode(text.isEmpty()?List.of():List.of(Map.of("type","text","text",text))),status,r.get("output_message_id"));
    }
    private void event(Map<String,Object> r,String type,Map<String,Object> extra) {
        String conv=(String)r.get("conversation_id");
        long seq=jdbc.queryForObject("SELECT last_event_seq FROM ai_conversation WHERE id=?",Long.class,conv)+1;
        var data=new LinkedHashMap<String,Object>(); data.put("run_id",r.get("id")); data.put("message_id",r.get("output_message_id"));
        data.put("attempt",number(r,"attempt")); data.putAll(extra);
        jdbc.update("INSERT INTO ai_conversation_event(id,conversation_id,seq,event_type,data_json) VALUES(?,?,?,?,?)",id(),conv,seq,type,encode(data));
        jdbc.update("UPDATE ai_conversation SET last_event_seq=?,updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",seq,conv);
    }
    private Map<String,Object> conversation(String id) {
        var rows=jdbc.queryForList("SELECT * FROM ai_conversation WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty()) throw problem(404,"CONVERSATION_NOT_FOUND"); return rows.getFirst();
    }
    private Map<String,Object> row(String id,boolean lock) {
        var rows=jdbc.queryForList("SELECT * FROM ai_run WHERE id=?"+(lock?" FOR UPDATE":""),id);
        if(rows.isEmpty()) throw problem(404,"RUN_NOT_FOUND"); return rows.getFirst();
    }
    private Map<String,Object> inConversation(String id,String conv) {
        var r=row(id,true); if(!conv.equals(r.get("conversation_id"))) throw problem(404,"RUN_NOT_FOUND"); return r;
    }
    private Run view(Map<String,Object> r) {
        return new Run((String)r.get("id"),number(r,"list_sequence"),(String)r.get("conversation_id"),(String)r.get("input_message_id"),
                (String)r.get("output_message_id"),(String)r.get("status"),number(r,"control_epoch"),(int)number(r,"attempt"),(String)r.get("model"),
                number(r,"last_chunk_sequence"),(String)r.get("error_code"),r.get("usage_json")==null?null:decode((String)r.get("usage_json")),
                instant(r.get("created_at")),instant(r.get("updated_at")));
    }
    static void validateUsage(Usage u) {
        if(u!=null && (u.inputTokens()==null || u.outputTokens()==null || u.totalTokens()==null || u.inputTokens()<0 || u.outputTokens()<0 || u.totalTokens()<0))
            throw problem(400,"INVALID_USAGE");
    }
    private void requireEnabled() { if(!enabled) throw problem(503,"AI_TEXT_DISABLED"); }
    private static boolean active(Map<String,Object> r) { return Set.of("queued","running","streaming").contains(r.get("status")); }
    private static long actor(Long actor) { if(actor==null||actor<=0)throw problem(401,"AUTHENTICATION_REQUIRED");return actor; }
    private static void validId(String id) {if(id==null||!id.matches("[a-f0-9]{32}"))throw problem(404,"RESOURCE_NOT_FOUND");}
    private static long number(Map<String,Object> r,String key) {return ((Number)r.get(key)).longValue();}
    private static Instant instant(Object v) {return v instanceof Timestamp t?t.toInstant():((java.time.LocalDateTime)v).toInstant(java.time.ZoneOffset.UTC);}
    private static ConversationProblem problem(int code,String text) {return new ConversationProblem(code,text);}
    private static String id() {return UUID.randomUUID().toString().replace("-","");}
    private static String hash(String text) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private String encode(Object v) {try{return json.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException(e);}}
    private JsonNode decode(String v) {try{return json.readTree(v);}catch(Exception e){throw new IllegalStateException(e);}}
}
