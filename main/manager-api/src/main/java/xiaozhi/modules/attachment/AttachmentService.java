package xiaozhi.modules.attachment;

import static xiaozhi.modules.attachment.AttachmentRules.*;
import java.io.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import xiaozhi.modules.attachment.AttachmentModels.*;
import xiaozhi.modules.conversation.*;
import xiaozhi.modules.conversation.ConversationModels.MessagePart;

/** Private evidence ledger. Network object IO is always outside the SQL transaction. */
@Service
@ConditionalOnProperty(prefix="features",name={"conversation-enabled","attachments-enabled"},havingValue="true")
public class AttachmentService {
    private final JdbcTemplate db;
    private final ConversationDao conversations;
    private final ConversationAccessService access;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final PrivateObjectStore store;
    private final int leaseSeconds;
    private final long retention;
    private final String ticketKey;
    private final boolean transcription;
    private org.springframework.beans.factory.ObjectProvider<GeneratedMediaSupport> generatedProvider;
    private GeneratedMediaSupport generatedForTests;
    @org.springframework.beans.factory.annotation.Autowired
    public void setGeneratedProvider(org.springframework.beans.factory.ObjectProvider<GeneratedMediaSupport> provider) {this.generatedProvider=provider;}
    public void setGeneratedSupportForTests(GeneratedMediaSupport support) {this.generatedForTests=support;}
    private static boolean generatedType(String type) {return Set.of("capture_frame","annotation_preview").contains(type);}
    private GeneratedMediaSupport generated() {
        GeneratedMediaSupport support=generatedForTests!=null?generatedForTests:generatedProvider==null?null:generatedProvider.getIfAvailable();
        if(support==null)throw problem(404,"ANNOTATIONS_DISABLED");return support;
    }
    private boolean generatedAttachment(A a) {return a.clientId().startsWith("_b4_");}

    record A(long id,String conversationId,long actor,String clientId,String filename,String mime,String kind,long size,String hash,
             String status,Long original,String message,Long seq,String metadata,String error,Instant created,Instant expires) { }
    record J(long id,long attachment,String type,String status,int attempt,String token,Instant expiry,String error,String metadata) { }
    record O(long id,long attachment,Long job,Integer attempt,String variant,String key,String status,long size,String hash,String mime) { }
    public record Content(InputStream input,long size,String mimeType,String filename,boolean inline) implements AutoCloseable {
        public void close() throws IOException {input.close();}
    }
    @org.springframework.beans.factory.annotation.Autowired
    public AttachmentService(JdbcTemplate db,ConversationDao conversations,ObjectMapper json,PlatformTransactionManager manager,Environment e) {
        this(db,conversations,json,manager,e,PrivateObjectStore.configured(e));
    }
    public AttachmentService(JdbcTemplate db,ConversationDao conversations,ObjectMapper json,PlatformTransactionManager manager,Environment e,PrivateObjectStore store) {
        this.db=db;this.conversations=conversations;this.access=new ConversationAccessService(conversations);this.json=json;
        this.tx=new TransactionTemplate(manager);this.store=store;
        this.leaseSeconds=Math.max(10,Math.min(300,e.getProperty("media.lease-seconds",Integer.class,90)));
        this.retention=Math.max(600,e.getProperty("media.retention-seconds",Long.class,86400L));
        this.ticketKey=e.getProperty("media.ticket-key","");this.transcription=e.getProperty("media.transcription-enabled",Boolean.class,false);
    }
    private <T> T transaction(Supplier<T> work) {return tx.execute(s->work.get());}
    private static long nextId(){return IdWorker.getId();}
    private static String eventId(){return UUID.randomUUID().toString().replace("-","");}
    private static Timestamp timestamp(Instant instant){return Timestamp.from(instant);}
    private static Long nullableLong(ResultSet r,String key)throws SQLException {long n=r.getLong(key);return r.wasNull()?null:n;}
    private static A mapA(ResultSet r,int ignored)throws SQLException {
        return new A(r.getLong("id"),r.getString("conversation_id"),r.getLong("uploader_user_id"),r.getString("client_upload_id"),r.getString("filename"),r.getString("mime_type"),r.getString("kind"),r.getLong("size"),r.getString("sha256"),r.getString("status"),nullableLong(r,"original_object_id"),r.getString("linked_message_id"),nullableLong(r,"linked_seq"),r.getString("metadata_json"),r.getString("error_code"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("expires_at").toInstant());
    }
    private static J mapJ(ResultSet r,int ignored)throws SQLException {
        var expiry=r.getTimestamp("lease_expires_at");return new J(r.getLong("id"),r.getLong("attachment_id"),r.getString("job_type"),r.getString("status"),r.getInt("attempt"),r.getString("lease_token"),expiry==null?null:expiry.toInstant(),r.getString("error_code"),r.getString("metadata_json"));
    }
    private static O mapO(ResultSet r,int ignored)throws SQLException {
        Long job=nullableLong(r,"job_id"),attempt=nullableLong(r,"attempt");return new O(r.getLong("id"),r.getLong("attachment_id"),job,attempt==null?null:attempt.intValue(),r.getString("variant"),r.getString("storage_key"),r.getString("status"),r.getLong("size"),r.getString("sha256"),r.getString("mime_type"));
    }
    private A attachment(long id,boolean lock) {
        var rows=db.query("SELECT * FROM ai_attachment WHERE id=?"+(lock?" FOR UPDATE":""),AttachmentService::mapA,id);
        if(rows.isEmpty())throw problem(404,"ATTACHMENT_NOT_FOUND");return rows.getFirst();
    }
    private J job(long id,boolean lock) {
        var rows=db.query("SELECT * FROM ai_media_job WHERE id=?"+(lock?" FOR UPDATE":""),AttachmentService::mapJ,id);
        if(rows.isEmpty())throw problem(404,"MEDIA_JOB_NOT_FOUND");return rows.getFirst();
    }
    private O object(long id) {
        var rows=db.query("SELECT * FROM ai_attachment_object WHERE id=?",AttachmentService::mapO,id);
        if(rows.isEmpty())throw problem(409,"MEDIA_OBJECT_MISSING");return rows.getFirst();
    }
    private static long actor(Long actor){if(actor==null || actor<=0)throw problem(401,"AUTHENTICATION_REQUIRED");return actor;}
    private void account(long actor){if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM sys_user WHERE id=? AND status=1)",Boolean.class,actor)))throw problem(404,"ATTACHMENT_NOT_FOUND");}
    private void write(long actor,String conversation) {
        account(actor);
        try {conversations.owned(conversation,actor,false);conversations.owned(conversation,actor,true);}
        catch(ConversationProblem p) {if(p.status()!=404)throw p;access.lockSenderRole(actor);access.requireCurrentStaff(actor,conversation);}
    }
    private void writable(long actor,A a) {
        write(actor,a.conversationId());if(a.actor()!=actor)throw problem(404,"ATTACHMENT_NOT_FOUND");
        if(a.status().equals("expired") || (a.message()==null && !a.expires().isAfter(Instant.now())))throw problem(410,"ATTACHMENT_EXPIRED");
    }
    private void readable(long actor,A a) {
        account(actor);
        if(a.status().equals("expired") || (a.message()==null && !a.expires().isAfter(Instant.now())))throw problem(404,"ATTACHMENT_NOT_FOUND");
        if(a.message()==null) {if(a.actor()!=actor)throw problem(404,"ATTACHMENT_NOT_FOUND");conversations.byId(a.conversationId(),true);}
        else {var grant=access.read(actor,a.conversationId(),true);if(a.seq()==null || a.seq()>grant.messageCutoff())throw problem(404,"ATTACHMENT_NOT_FOUND");}
    }
    private A lock(long id) {A a=attachment(id,false);conversations.byId(a.conversationId(),true);return attachment(id,true);}
    private Map<String,Object> metadata(String encoded) {
        try{return json.readValue(encoded,new TypeReference<LinkedHashMap<String,Object>>(){});}catch(Exception e){throw new IllegalStateException("Invalid persisted attachment metadata");}
    }
    private String encode(Object value) {try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Cannot encode attachment metadata");}}
    private List<O> artifacts(A a) {
        return db.query("SELECT o.* FROM ai_attachment_artifact f JOIN ai_attachment_object o ON o.id=f.object_id JOIN ai_media_job j ON j.id=f.job_id WHERE f.attachment_id=? AND j.status='completed' AND f.attempt=j.attempt AND o.status='referenced' ORDER BY f.variant",AttachmentService::mapO,a.id());
    }
    private View view(A a) {
        Map<String,Object> meta=new LinkedHashMap<>(metadata(a.metadata()));
        meta.put("artifacts",artifacts(a).stream().map(O::variant).toList());
        var jobs=db.query("SELECT * FROM ai_media_job WHERE attachment_id=? ORDER BY created_at,id",AttachmentService::mapJ,a.id()).stream().map(j->new JobView(Long.toString(j.id()),j.type(),j.status(),j.attempt(),j.error())).toList();
        return new View(Long.toString(a.id()),a.conversationId(),a.filename(),a.mime(),a.kind(),a.size(),a.hash(),a.status(),a.error(),meta,jobs,a.created());
    }
    public InitView init(Long user,Init request,String context) {
        long actor=actor(user);
        if(request==null || request.conversationId()==null || !request.conversationId().matches("[a-f0-9]{32}") || request.clientUploadId()==null || !request.clientUploadId().matches("[a-zA-Z0-9][a-zA-Z0-9_.:-]{0,63}") || request.size()==null || request.size()<=0 || request.sha256()==null || !request.sha256().matches("[a-f0-9]{64}"))throw problem(400,"INVALID_UPLOAD_REQUEST");
        String kind=kind(request.mimeType());filename(request.filename(),request.mimeType());if(request.size()>max(request.mimeType()))throw problem(413,"MEDIA_SIZE_LIMIT");
        try { return transaction(()->{
            write(actor,request.conversationId());
            var previous=db.query("SELECT * FROM ai_attachment WHERE uploader_user_id=? AND client_upload_id=? FOR UPDATE",AttachmentService::mapA,actor,request.clientUploadId());
            A a;
            if(!previous.isEmpty()) {
                a=previous.getFirst();if(!a.conversationId().equals(request.conversationId()) || !a.filename().equals(request.filename()) || !a.mime().equals(request.mimeType()) || a.size()!=request.size() || !a.hash().equals(request.sha256()))throw problem(409,"CLIENT_UPLOAD_ID_REUSED");
            }else {
                long id=nextId();db.update("INSERT INTO ai_attachment(id,conversation_id,uploader_user_id,client_upload_id,filename,mime_type,kind,size,sha256,metadata_json,expires_at) VALUES(?,?,?,?,?,?,?,?,?,'{}',?)",id,request.conversationId(),actor,request.clientUploadId(),request.filename(),request.mimeType(),kind,request.size(),request.sha256(),timestamp(Instant.now().plusSeconds(retention)));a=attachment(id,false);
            }
            View v=view(a);return new InitView(v.id(),v.conversationId(),v.filename(),v.mimeType(),v.kind(),v.size(),v.sha256(),v.status(),v.errorCode(),v.metadata(),v.jobs(),v.createdAt(),new Upload("PUT",context+"/api/v1/ai/uploads/"+a.id()+"/content",a.expires(),a.size()));
        }); } catch (DuplicateKeyException race) {
            // Another conversation can use the same uploader's idempotency key concurrently.
            // The transaction has rolled back; return a conflict without exposing its record.
            throw problem(409,"CLIENT_UPLOAD_ID_REUSED");
        } catch (PessimisticLockingFailureException race) {
            // InnoDB can choose this transaction as the loser of concurrent unique-key inserts.
            // Its writes have rolled back; the caller keeps the same upload id for a safe retry.
            throw problem(409,"UPLOAD_CONFLICT_RETRY");
        }
    }
    public View get(Long user,String id) {long actor=actor(user);return transaction(()->{A a=attachment(id(id),false);readable(actor,a);return view(attachment(a.id(),true));});}
    public View upload(Long user,String id,String mime,long declaredSize,InputStream input) {
        long actor=actor(user),attachmentId=id(id);
        A initial=transaction(()->{A a=attachment(attachmentId,false);writable(actor,a);if(generatedAttachment(a))throw problem(409,"GENERATED_ATTACHMENT_IMMUTABLE");return attachment(attachmentId,true);});
        if(!initial.mime().equals(mime))throw problem(415,"TYPE_MISMATCH");if(declaredSize>=0 && declaredSize!=initial.size())throw problem(409,"SIZE_MISMATCH");
        try(Staged staged=stage(input,initial.size())) {
            if(staged.size()!=initial.size() || !staged.sha256().equals(initial.hash()))throw problem(409,"CONTENT_HASH_MISMATCH");
            O reserved=transaction(()->{
                A a=attachment(attachmentId,false);writable(actor,a);a=attachment(attachmentId,true);
                if(a.original()!=null)return object(a.original());
                if(!a.status().equals("pending_upload"))throw problem(409,"UPLOAD_NOT_PENDING");return reserve(a,null,"original",staged,mime);
            });
            if(reserved.status().equals("referenced"))return get(user,id);
            persist(reserved,staged);
            return transaction(()->{
                A a=attachment(attachmentId,false);writable(actor,a);a=attachment(attachmentId,true);
                if(a.original()!=null){abandon(reserved.id());return view(a);}
                if(!a.status().equals("pending_upload"))throw problem(409,"UPLOAD_NOT_PENDING");
                int changed=db.update("UPDATE ai_attachment SET original_object_id=?,status='uploaded',updated_at=CURRENT_TIMESTAMP(3) WHERE id=? AND status='pending_upload' AND original_object_id IS NULL",reserved.id(),a.id());
                if(changed!=1)throw problem(409,"UPLOAD_STATE_CHANGED");reference(reserved.id());return view(attachment(a.id(),false));
            });
        }catch(IOException ex){throw problem(503,"STORAGE_UNAVAILABLE");}
    }
    private O reserve(A a,J j,String variant,Staged bytes,String mime) {
        long id=nextId();String key=a.id()+"/"+UUID.randomUUID().toString().replace("-","")+".bin";
        db.update("INSERT INTO ai_attachment_object(id,attachment_id,job_id,attempt,variant,storage_key,status,size,sha256,mime_type,expires_at) VALUES(?,?,?,?,?,?,'reserved',?,?,?,?)",id,a.id(),j==null?null:j.id(),j==null?null:j.attempt(),variant,key,bytes.size(),bytes.sha256(),mime,timestamp(Instant.now().plusSeconds(retention)));
        return object(id);
    }
    private void persist(O o,Staged staged) {
        try {store.put(o.key(),staged.file(),o.mime());int n=db.update("UPDATE ai_attachment_object SET status='stored' WHERE id=? AND status='reserved'",o.id());if(n!=1)throw problem(409,"OBJECT_STATE_CHANGED");}
        catch(IOException ex){abandon(o.id());throw problem(503,"STORAGE_UNAVAILABLE");}
    }
    private void reference(long id){if(db.update("UPDATE ai_attachment_object SET status='referenced' WHERE id=? AND status='stored'",id)!=1)throw problem(409,"OBJECT_STATE_CHANGED");}
    private void abandon(long id){db.update("UPDATE ai_attachment_object SET status='abandoned' WHERE id=? AND status IN ('stored','reserved')",id);}
    private void exists(O object){try{if(!store.exists(object.key()))throw problem(409,"MEDIA_OBJECT_MISSING");}catch(IOException ex){throw problem(503,"STORAGE_UNAVAILABLE");}}
    public View completeUpload(Long user,String id) {
        long actor=actor(user);long attachment=id(id);
        O original=transaction(()->{A a=attachment(attachment,false);writable(actor,a);if(a.original()==null)throw problem(409,"UPLOAD_INCOMPLETE");return object(a.original());});
        exists(original);
        return transaction(()->{
            A a=attachment(attachment,false);writable(actor,a);a=attachment(attachment,true);
            if(a.status().equals("uploaded")) {queue(a,"inspect_media");db.update("UPDATE ai_attachment SET status='processing',updated_at=CURRENT_TIMESTAMP(3) WHERE id=? AND status='uploaded'",a.id());event(a,"attachment.processing");}
            return view(attachment(attachment,false));
        });
    }
    private J queue(A a,String type) {
        var rows=db.query("SELECT * FROM ai_media_job WHERE attachment_id=? AND job_type=? FOR UPDATE",AttachmentService::mapJ,a.id(),type);
        if(!rows.isEmpty())return rows.getFirst();long id=nextId();db.update("INSERT INTO ai_media_job(id,attachment_id,job_type,metadata_json) VALUES(?,?,?,'{}')",id,a.id(),type);event(a,"media.queued");return job(id,false);
    }
    public View retry(Long user,String id) {
        long actor=actor(user);long attachment=id(id);
        O original=transaction(()->{
            A a=attachment(attachment,false);writable(actor,a);
            if(generatedAttachment(a)) {
                var jobs=db.query("SELECT * FROM ai_media_job WHERE attachment_id=?",AttachmentService::mapJ,a.id());
                if(jobs.size()!=1 || !generatedType(jobs.getFirst().type()))throw problem(409,"INVALID_GENERATED_JOB");
                generated().validate(a.id(),jobs.getFirst().type(),actor);return input(a,jobs.getFirst());
            }
            if(a.original()==null)throw problem(409,"UPLOAD_INCOMPLETE");return object(a.original());
        });exists(original);
        return transaction(()->{
            A a=attachment(attachment,false);writable(actor,a);a=attachment(attachment,true);
            var jobs=db.query("SELECT * FROM ai_media_job WHERE attachment_id=? FOR UPDATE",AttachmentService::mapJ,a.id());
            for(J j:jobs)if(generatedType(j.type()))generated().validate(a.id(),j.type(),actor);
            if(jobs.stream().anyMatch(j->Set.of("queued","running").contains(j.status())))throw problem(409,"MEDIA_JOB_ACTIVE");
            var retryable=Set.of("PROCESSING_TIMEOUT","PROCESSING_FAILED","STORAGE_UNAVAILABLE","TRANSCRIPTION_NOT_CONFIGURED","PROVIDER_AUTH","PROVIDER_RATE_LIMIT","PROVIDER_TIMEOUT","PROVIDER_ERROR");
            var failed=jobs.stream().filter(j->j.status().equals("failed") && j.attempt()<3 && retryable.contains(j.error())).toList();if(failed.isEmpty())throw problem(409,"MEDIA_NOT_RETRYABLE");
            for(J j:failed){db.update("UPDATE ai_media_job SET status='queued',error_code=NULL,lease_token=NULL,lease_expires_at=NULL,updated_at=CURRENT_TIMESTAMP(3) WHERE id=? AND status='failed' AND attempt<3",j.id());event(a,"media.queued");if(j.type().equals("inspect_media") || generatedType(j.type()))db.update("UPDATE ai_attachment SET status='processing',error_code=NULL WHERE id=?",a.id());}
            return view(attachment(a.id(),false));
        });
    }
    /** Called by ConversationService only after it has locked the conversation, inside message transaction. */
    public void bind(long actor,String conversation,String message,long seq,List<MessagePart> parts) {
        for(MessagePart part:parts)if(part.attachmentId()!=null) {
            A a=attachment(id(part.attachmentId()),true);account(actor);
            if(a.actor()!=actor || !a.conversationId().equals(conversation))throw problem(404,"ATTACHMENT_NOT_FOUND");
            if(!a.status().equals("ready") || (a.message()==null && !a.expires().isAfter(Instant.now())))throw problem(409,"ATTACHMENT_NOT_READY");
            if(!a.kind().equals(part.type()) || (a.message()!=null && !a.message().equals(message)))throw problem(409,"ATTACHMENT_ALREADY_LINKED_OR_TYPE_MISMATCH");
            if(generatedAttachment(a))generated().bind(actor,conversation,a.id(),message,seq);
            if(a.message()==null && db.update("UPDATE ai_attachment SET linked_message_id=?,linked_seq=?,updated_at=CURRENT_TIMESTAMP(3) WHERE id=? AND linked_message_id IS NULL AND status='ready'",message,seq,a.id())!=1)throw problem(409,"ATTACHMENT_ALREADY_LINKED");
        }
    }
    private void event(A a,String type) {
        // Draft attachments, including staff drafts, never leak through public conversation events.
        if(a.message()==null)return;
        var conversation=conversations.byId(a.conversationId(),true);long seq=conversation.lastEventSeq()+1;
        conversations.insertEvent(eventId(),a.conversationId(),seq,type,encode(Map.of("attachment_id",Long.toString(a.id()))));
        conversations.advance(a.conversationId(),conversation.lastSeq(),seq);
    }
    private J lease(long jobId,String token) {
        J first=job(jobId,false);A a=attachment(first.attachment(),false);
        // Support write lock order is profile -> account -> conversation; validate before locking output.
        if(generatedType(first.type()))generated().validate(a.id(),first.type(),a.actor());
        a=lock(first.attachment());account(a.actor());J j=job(jobId,true);
        if(token==null || token.length()>128 || !"running".equals(j.status()) || !token.equals(j.token()) || j.expiry()==null || !j.expiry().isAfter(Instant.now()) || a.status().equals("expired"))throw problem(409,"MEDIA_LEASE_INACTIVE");
        return j;
    }
    public Task claim(Claim request) {
        if(request==null || request.workerId()==null || !request.workerId().matches("[a-zA-Z0-9_.:-]{1,128}"))throw problem(400,"INVALID_WORKER_ID");
        // Candidates are merely hints; every candidate is rechecked after the conversation lock.
        var candidates=db.queryForList("SELECT j.id FROM ai_media_job j JOIN ai_attachment a ON a.id=j.attachment_id JOIN sys_user u ON u.id=a.uploader_user_id AND u.status=1 WHERE a.status<>'expired' AND (j.status='queued' OR (j.status='running' AND j.lease_expires_at<=CURRENT_TIMESTAMP(3))) ORDER BY j.created_at,j.id LIMIT 30",Long.class);
        for(Long candidate:candidates) {
            Task result=transaction(()->{
                J hint=job(candidate,false);A a=attachment(hint.attachment(),false);
                if(generatedType(hint.type())) {
                    try {generated().validate(a.id(),hint.type(),a.actor());}
                    catch(ConversationProblem denied) {
                        if(denied.status()!=403 && denied.status()!=404 && denied.status()!=409 && denied.status()!=410)throw denied;
                        a=lock(a.id());J inactive=job(candidate,true);
                        if(inactive.status().equals("queued") || (inactive.status().equals("running") && inactive.expiry()!=null && !inactive.expiry().isAfter(Instant.now())))failLocked(a,inactive,"PROCESSING_FAILED");
                        return null;
                    }
                }
                a=lock(hint.attachment());account(a.actor());J j=job(candidate,true);
                if(a.status().equals("expired") || (!j.status().equals("queued") && !(j.status().equals("running") && j.expiry()!=null && !j.expiry().isAfter(Instant.now()))))return null;
                if(j.attempt()>=3) {failLocked(a,j,"PROCESSING_TIMEOUT");return null;}
                String token=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");
                db.update("UPDATE ai_media_job SET status='running',attempt=attempt+1,lease_token=?,lease_owner=?,lease_expires_at=?,error_code=NULL,updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",token,request.workerId(),timestamp(Instant.now().plusSeconds(leaseSeconds)),j.id());
                j=job(candidate,false);event(a,"media.started");O input=input(a,j);
                GeneratedMediaSupport.Input details=generatedType(j.type())?generated().input(a.id(),j.type()):null;
                return new Task(Long.toString(j.id()),Long.toString(a.id()),j.type(),j.attempt(),j.token(),leaseSeconds,details!=null?details.filename():j.type().equals("audio_transcription")?"audio.wav":a.filename(),input.mime(),input.size(),input.hash(),details!=null?details.metadata():metadata(a.metadata()));
            });if(result!=null)return result;
        }return null;
    }
    public Map<String,Boolean> heartbeat(String id,Lease request) {
        if(request==null)throw problem(400,"INVALID_REQUEST");return transaction(()->{J j=lease(id(id),request.leaseToken());db.update("UPDATE ai_media_job SET lease_expires_at=?,updated_at=CURRENT_TIMESTAMP(3) WHERE id=? AND lease_token=?",timestamp(Instant.now().plusSeconds(leaseSeconds)),j.id(),j.token());return Map.of("active",true);});
    }
    private O input(A a,J j) {
        if(generatedType(j.type())) {
            GeneratedMediaSupport.Input input=generated().input(a.id(),j.type());
            A source=attachment(id(input.attachmentId()),false);
            if(!source.conversationId().equals(a.conversationId()))throw problem(404,"ATTACHMENT_NOT_FOUND");
            return variant(source,input.variant());
        }
        if(j.type().equals("audio_transcription"))return variant(a,"audio");
        if(a.original()==null)throw problem(409,"MEDIA_OBJECT_MISSING");return object(a.original());
    }
    public Content workerInput(String id,String token) {
        O selected=transaction(()->{J j=lease(id(id),token);return input(attachment(j.attachment(),false),j);});return content(selected,"input",false);
    }
    public Map<String,Boolean> artifact(String id,String variant,String token,String mime,InputStream input) {
        String expected=artifactMime(variant);if(!expected.equals(mime))throw problem(415,"TYPE_MISMATCH");
        transaction(()->{J j=lease(id(id),token);allowedArtifact(attachment(j.attachment(),false),j,variant);return null;});
        try(Staged staged=stage(input,artifactMax(variant),variant.equals("text") || variant.equals("transcript"))) {
            if (variant.equals("text") || variant.equals("transcript")) {
                try {
                    String text = java.nio.file.Files.readString(staged.file(), java.nio.charset.StandardCharsets.UTF_8);
                    if (text.indexOf('\0') >= 0 || text.codePointCount(0, text.length()) > 60000) throw problem(400,"INVALID_MEDIA_TEXT");
                } catch (java.nio.charset.CharacterCodingException ex) { throw problem(400,"INVALID_MEDIA_TEXT"); }
            }
            O reserved=transaction(()->{
                J j=lease(id(id),token);A a=attachment(j.attachment(),false);allowedArtifact(a,j,variant);
                var old=db.query("SELECT o.* FROM ai_attachment_artifact f JOIN ai_attachment_object o ON o.id=f.object_id WHERE f.attachment_id=? AND f.variant=?",AttachmentService::mapO,a.id(),variant);
                if(!old.isEmpty() && Objects.equals(old.getFirst().job(),j.id()) && Objects.equals(old.getFirst().attempt(),j.attempt())) {
                    O o=old.getFirst();if(o.size()!=staged.size() || !o.hash().equals(staged.sha256()))throw problem(409,"ARTIFACT_IMMUTABLE");return o;
                }return reserve(a,j,variant,staged,mime);
            });
            if(reserved.status().equals("referenced"))return Map.of("stored",true);
            persist(reserved,staged);
            return transaction(()->{
                J j=lease(id(id),token);A a=attachment(j.attachment(),false);
                var old=db.query("SELECT o.* FROM ai_attachment_artifact f JOIN ai_attachment_object o ON o.id=f.object_id WHERE f.attachment_id=? AND f.variant=? FOR UPDATE",AttachmentService::mapO,a.id(),variant);
                if(!old.isEmpty() && Objects.equals(old.getFirst().job(),j.id()) && Objects.equals(old.getFirst().attempt(),j.attempt())) {
                    O o=old.getFirst();abandon(reserved.id());if(o.size()!=staged.size() || !o.hash().equals(staged.sha256()))throw problem(409,"ARTIFACT_IMMUTABLE");return Map.of("stored",true);
                }
                db.update("INSERT INTO ai_attachment_artifact(attachment_id,variant,object_id,job_id,attempt) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE object_id=VALUES(object_id),job_id=VALUES(job_id),attempt=VALUES(attempt)",a.id(),variant,reserved.id(),j.id(),j.attempt());
                reference(reserved.id());return Map.of("stored",true);
            });
        }catch(IOException ex){throw problem(503,"STORAGE_UNAVAILABLE");}
    }
    private void allowedArtifact(A a,J j,String variant) {
        boolean allowed=generatedType(j.type())?Set.of("display","thumbnail").contains(variant):j.type().equals("audio_transcription")?variant.equals("transcript"):switch(a.kind()) {
            case "image" -> Set.of("display","thumbnail").contains(variant);case "file" -> variant.equals("text");case "audio" -> variant.equals("audio");case "video" -> variant.equals("poster") || variant.equals("audio") || variant.matches("frame_[0-5]");default -> false;};
        if(!allowed)throw problem(400,"INVALID_VARIANT");
    }
    public View completeJob(String id,Complete request) {
        if(request==null)throw problem(400,"INVALID_REQUEST");
        return transaction(()->{
            J j=lease(id(id),request.leaseToken());A a=attachment(j.attachment(),false);Map<String,Object> meta=generatedType(j.type())?generated().complete(a.id(),j.type(),request.metadata()):validateMetadata(a,j,request.metadata());
            Set<String> required=new HashSet<>();if(j.type().equals("audio_transcription"))required.add("transcript");else switch(a.kind()) {
                case "image" -> required.addAll(Set.of("display","thumbnail"));case "file" -> required.add("text");case "audio" -> required.add("audio");case "video" -> {required.add("poster");if(Boolean.TRUE.equals(meta.get("has_audio")))required.add("audio");for(Object value:(List<?>)meta.get("frames"))required.add((String)((Map<?,?>)value).get("artifact"));}default->throw problem(400,"INVALID_MEDIA");
            }
            for(String name:required) {var count=db.queryForObject("SELECT COUNT(*) FROM ai_attachment_artifact f JOIN ai_attachment_object o ON o.id=f.object_id WHERE f.attachment_id=? AND f.variant=? AND f.job_id=? AND f.attempt=? AND o.status='referenced'",Long.class,a.id(),name,j.id(),j.attempt());if(count==null || count!=1)throw problem(409,"REQUIRED_ARTIFACT_MISSING");}
            // Every pointer was committed only after storage success; IO does not hold this transaction.
            db.update("UPDATE ai_media_job SET status='completed',metadata_json=?,error_code=NULL,lease_token=NULL,lease_expires_at=NULL,updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",encode(meta),j.id());
            Map<String,Object> merged=new LinkedHashMap<>(metadata(a.metadata()));
            if(generatedType(j.type())) {
                O display=db.query("SELECT o.* FROM ai_attachment_artifact f JOIN ai_attachment_object o ON o.id=f.object_id WHERE f.attachment_id=? AND f.variant='display' AND f.job_id=? AND f.attempt=? AND o.status='referenced'",AttachmentService::mapO,a.id(),j.id(),j.attempt()).getFirst();
                merged.putAll(meta);
                if(db.update("UPDATE ai_attachment SET status='ready',error_code=NULL,metadata_json=?,original_object_id=?,size=?,sha256=?,updated_at=CURRENT_TIMESTAMP(3) WHERE id=? AND original_object_id IS NULL AND status='processing'",encode(merged),display.id(),display.size(),display.hash(),a.id())!=1)throw problem(409,"GENERATED_ATTACHMENT_IMMUTABLE");
                event(a,"attachment.ready");
            }
            else if(j.type().equals("inspect_media")) {merged.putAll(meta);db.update("UPDATE ai_attachment SET status='ready',error_code=NULL,metadata_json=?,updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",encode(merged),a.id());event(a,"attachment.ready");if(transcription && (a.kind().equals("audio") || (a.kind().equals("video") && Boolean.TRUE.equals(meta.get("has_audio")))))queue(a,"audio_transcription");}
            else {merged.put("transcription",meta);db.update("UPDATE ai_attachment SET metadata_json=?,updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",encode(merged),a.id());}
            event(a,"media.completed");return view(attachment(a.id(),false));
        });
    }
    public View failJob(String id,Fail request) {
        if(request==null || !ERRORS.contains(request.errorCode()))throw problem(400,"INVALID_MEDIA_ERROR_CODE");
        return transaction(()->{J j=lease(id(id),request.leaseToken());A a=attachment(j.attachment(),false);failLocked(a,j,request.errorCode());return view(attachment(a.id(),false));});
    }
    private void failLocked(A a,J j,String code) {
        db.update("UPDATE ai_media_job SET status='failed',error_code=?,lease_token=NULL,lease_expires_at=NULL,updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",code,j.id());
        if(j.type().equals("inspect_media") || generatedType(j.type())){db.update("UPDATE ai_attachment SET status='failed',error_code=?,updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",code,a.id());event(a,"attachment.failed");}event(a,"media.failed");
    }
    private Map<String,Object> validateMetadata(A a,J j,Map<String,Object> source) {
        if(source==null || source.size()>12)throw problem(400,"INVALID_MEDIA_METADATA");
        Set<String> allowed=j.type().equals("audio_transcription")?Set.of("model","usage","text_available","text_truncated"):switch(a.kind()) {
            case "image" -> Set.of("mime_type","width","height");
            case "file" -> Set.of("mime_type","text_available","text_truncated");
            case "audio" -> Set.of("mime_type","duration_ms","has_audio");
            case "video" -> Set.of("mime_type","width","height","duration_ms","has_audio","frames");
            default -> Set.of();
        };
        if(!allowed.containsAll(source.keySet()))throw problem(400,"INVALID_MEDIA_METADATA");
        Map<String,Object> m=new LinkedHashMap<>(source);
        for(String name:List.of("has_audio","text_available","text_truncated"))if(m.containsKey(name) && !(m.get(name) instanceof Boolean))throw problem(400,"INVALID_MEDIA_METADATA");
        if(j.type().equals("audio_transcription")) {
            if(!(m.get("model") instanceof String model) || model.isBlank() || model.length()>128 || model.chars().anyMatch(c->c<32))throw problem(400,"INVALID_MEDIA_METADATA");
            if(m.containsKey("usage")) {
                if(!(m.get("usage") instanceof Map<?,?> usage) || usage.size()>8 || !Set.of("input_tokens","output_tokens","total_tokens","audio_seconds","duration_ms").containsAll(usage.keySet()))throw problem(400,"INVALID_MEDIA_METADATA");
                for(Object v:usage.values())if(!(v instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue()<0 || n.doubleValue()>1e12)throw problem(400,"INVALID_MEDIA_METADATA");
            }
            return m;
        }
        if(!a.mime().equals(m.get("mime_type")))throw problem(400,"TYPE_MISMATCH");
        if(a.kind().equals("image") || a.kind().equals("video")) {
            long width=number(m,"width",1,a.kind().equals("image")?8192:3840),height=number(m,"height",1,a.kind().equals("image")?8192:2160);
            if(a.kind().equals("image") && width*height>24000000)throw problem(400,"PIXEL_LIMIT");
        }
        if(a.kind().equals("video") || a.kind().equals("audio"))number(m,"duration_ms",1,a.kind().equals("video")?180000:600000);
        if(a.kind().equals("file") && (!(m.get("text_available") instanceof Boolean) || !(m.get("text_truncated") instanceof Boolean)))throw problem(400,"INVALID_MEDIA_METADATA");
        if(a.kind().equals("video")) {
            if(!(m.get("has_audio") instanceof Boolean) || !(m.get("frames") instanceof List<?> frames) || frames.isEmpty() || frames.size()>6)throw problem(400,"INVALID_MEDIA_METADATA");
            Set<String> names=new HashSet<>();long duration=number(m,"duration_ms",1,180000);
            for(Object item:(List<?>)m.get("frames")) {
                if(!(item instanceof Map<?,?> frame) || !frame.keySet().equals(Set.of("artifact","time_ms")) || !(frame.get("artifact") instanceof String name) || !name.matches("frame_[0-5]") || !names.add(name))throw problem(400,"INVALID_MEDIA_METADATA");
                number(frame,"time_ms",0,duration);
            }
        }
        return m;
    }
    private static long number(Map<?,?> m,String name,long minimum,long maximum) {
        Object v=m.get(name);if(!(v instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue()!=n.longValue() || n.longValue()<minimum || n.longValue()>maximum)throw problem(400,"INVALID_MEDIA_METADATA");return n.longValue();
    }
    private O variant(A a,String variant) {
        if(!VARIANTS.contains(variant))throw problem(400,"INVALID_VARIANT");
        if(variant.equals("original")) {if(a.original()==null)throw problem(409,"UPLOAD_INCOMPLETE");return object(a.original());}
        if(!a.status().equals("ready"))throw problem(409,"ATTACHMENT_NOT_READY");
        return artifacts(a).stream().filter(o->o.variant().equals(variant)).findFirst().orElseThrow(()->problem(404,"ARTIFACT_NOT_FOUND"));
    }
    public Access access(Long user,String id,String variant,String context) {
        long actor=actor(user);return transaction(()->{
            A a=attachment(id(id),false);readable(actor,a);O o=variant(a,variant);long exp=Instant.now().getEpochSecond()+120;
            return new Access(context+"/api/v1/ai/attachments/"+a.id()+"/content?ticket="+ticket(ticketKey,actor,a.id(),variant,exp),Instant.ofEpochSecond(exp),o.mime(),downloadName(a,o));
        });
    }
    public Content ticketContent(String id,String ticket) {
        long expected=id(id);Ticket capability=verify(ticketKey,ticket,expected,Instant.now().getEpochSecond());
        record Selection(A attachment,O object) { }
        Selection selected=transaction(()->{A a=attachment(expected,false);readable(capability.actor(),a);return new Selection(a,variant(a,capability.variant()));});
        return content(selected.object(),downloadName(selected.attachment(),selected.object()),inline(selected.object()));
    }
    private static boolean inline(O o){return !o.variant().equals("original") && o.mime().equals("image/jpeg");}
    private static String downloadName(A a,O o) {
        if(o.variant().equals("original"))return a.filename();
        return "attachment-"+a.id()+"-"+o.variant()+switch(o.mime()){case "image/jpeg"->".jpg";case "audio/wav"->".wav";default->".txt";};
    }
    private Content content(O o,String filename,boolean inline) {
        try {return new Content(store.read(o.key()),o.size(),o.mime(),filename,inline);}
        catch(IOException e){throw problem(503,"STORAGE_UNAVAILABLE");}
    }
    /** Server-only generation bridges. Callers operate in a short existing SQL transaction. */
    public void serverRequireWrite(long actor,String conversation) {write(actor,conversation);}
    /** Use current reads after an idempotency-key lock can observe another transaction's new snapshot. */
    public View serverSnapshot(long actor,String attachmentId,String conversation) {
        conversations.byId(conversation,true);A a=attachment(id(attachmentId),true);
        if(!a.conversationId().equals(conversation))throw problem(404,"ATTACHMENT_NOT_FOUND");readable(actor,a);return view(a);
    }
    public View serverSource(long actor,String attachmentId,String conversation,boolean mustBeSent) {
        A a=attachment(id(attachmentId),false);if(!a.conversationId().equals(conversation))throw problem(404,"ATTACHMENT_NOT_FOUND");
        readable(actor,a);a=attachment(a.id(),true);
        if(!a.status().equals("ready") || (mustBeSent && a.message()==null))throw problem(409,"ANNOTATION_SOURCE_NOT_READY");
        return view(a);
    }
    public View serverUnsentImage(long actor,String attachmentId,String conversation) {
        View view=serverSource(actor,attachmentId,conversation,false);A a=attachment(id(attachmentId),true);
        if(a.actor()!=actor || a.message()!=null || !a.kind().equals("image"))throw problem(409,"LIVE_CAPTURE_SOURCE_INVALID");return view;
    }
    public String serverCreateGenerated(long actor,String conversation,long snapshotId,String type) {
        if(!generatedType(type))throw problem(400,"INVALID_GENERATED_JOB");write(actor,conversation);long id=nextId();
        db.update("INSERT INTO ai_attachment(id,conversation_id,uploader_user_id,client_upload_id,filename,mime_type,kind,size,sha256,status,metadata_json,expires_at) VALUES(?,?,?,?,?,'image/jpeg','image',0,?,'processing','{}',?)",id,conversation,actor,"_b4_"+type+"_"+snapshotId,"snapshot-"+snapshotId+".jpg","0".repeat(64),timestamp(Instant.now().plusSeconds(retention)));
        return Long.toString(id);
    }
    public void serverQueueGenerated(String outputAttachmentId,String type) {
        if(!generatedType(type))throw problem(400,"INVALID_GENERATED_JOB");A a=attachment(id(outputAttachmentId),true);
        generated().validate(a.id(),type,a.actor());queue(a,type);
    }
    public void serverBindEvidence(long actor,String conversation,String attachmentId,String message,long seq) {
        A a=attachment(id(attachmentId),true);
        if(!a.conversationId().equals(conversation))throw problem(404,"ATTACHMENT_NOT_FOUND");readable(actor,a);
        if(!a.status().equals("ready"))throw problem(409,"ANNOTATION_SOURCE_NOT_READY");
        if(a.message()==null && db.update("UPDATE ai_attachment SET linked_message_id=?,linked_seq=?,updated_at=CURRENT_TIMESTAMP(3) WHERE id=? AND linked_message_id IS NULL AND status='ready'",message,seq,a.id())!=1)throw problem(409,"ANNOTATION_SOURCE_STATE_CHANGED");
    }
    /** Internal AI bridge. Caller must already have validated the current run lease. No object key escapes. */
    public View aiDescriptor(String id,String conversationId,long inputSeq) {
        return transaction(()->view(aiAttachment(id,conversationId,inputSeq)));
    }
    public Content aiContent(String id,String conversationId,long inputSeq,String variant) {
        record Selection(A a,O o) { }
        Selection selected=transaction(()->{A a=aiAttachment(id,conversationId,inputSeq);return new Selection(a,variant(a,variant));});
        return content(selected.o(),downloadName(selected.a(),selected.o()),false);
    }
    private A aiAttachment(String id,String conversationId,long inputSeq) {
        A a=attachment(id(id),false);if(!a.conversationId().equals(conversationId))throw problem(404,"ATTACHMENT_NOT_FOUND");
        conversations.byId(conversationId,true);a=attachment(a.id(),true);account(a.actor());
        if(!a.status().equals("ready") || a.message()==null || a.seq()==null || a.seq()>inputSeq)throw problem(404,"ATTACHMENT_NOT_FOUND");return a;
    }
    /** Bounded compensation scans only our logged keys. No business row is deleted or bucket listed. */
    @Scheduled(fixedDelayString="${media.cleanup-delay-ms:60000}")
    public void cleanup() {
        var expired=db.queryForList("SELECT id FROM ai_attachment WHERE linked_message_id IS NULL AND status<>'expired' AND expires_at<CURRENT_TIMESTAMP(3) ORDER BY expires_at LIMIT 50",Long.class);
        for(Long id:expired)transaction(()->{A a=lock(id);if(a.message()==null && !a.expires().isAfter(Instant.now())){db.update("UPDATE ai_attachment SET status='expired',updated_at=CURRENT_TIMESTAMP(3) WHERE id=? AND linked_message_id IS NULL",id);db.update("UPDATE ai_media_job SET status='failed',error_code='PROCESSING_TIMEOUT',lease_token=NULL,lease_expires_at=NULL WHERE attachment_id=? AND status IN ('queued','running')",id);}return null;});
        var candidates=db.query("SELECT o.* FROM ai_attachment_object o JOIN ai_attachment a ON a.id=o.attachment_id WHERE a.linked_message_id IS NULL AND o.status<>'deleted' AND o.expires_at<CURRENT_TIMESTAMP(3) AND (a.status='expired' OR (o.status IN ('reserved','stored','abandoned','deleting') AND NOT EXISTS(SELECT 1 FROM ai_attachment_artifact f WHERE f.object_id=o.id) AND (a.original_object_id IS NULL OR a.original_object_id<>o.id))) ORDER BY o.expires_at LIMIT 50",AttachmentService::mapO);
        for(O candidate:candidates) {
            boolean deletion=transaction(()->{
                A a=lock(candidate.attachment());O current=object(candidate.id());if(a.message()!=null || current.status().equals("deleted"))return false;
                boolean referenced=Objects.equals(a.original(),current.id()) || Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM ai_attachment_artifact WHERE object_id=?)",Boolean.class,current.id()));
                if(!a.status().equals("expired") && referenced)return false;
                return db.update("UPDATE ai_attachment_object SET status='deleting' WHERE id=? AND status<>'deleted' AND expires_at<CURRENT_TIMESTAMP(3)",current.id())==1;
            });
            if(deletion)try {store.delete(candidate.key());db.update("UPDATE ai_attachment_object SET status='deleted' WHERE id=? AND status='deleting'",candidate.id());}catch(IOException ignored){/* Retry this same logged key on the next bounded pass. */}
        }
    }
}
