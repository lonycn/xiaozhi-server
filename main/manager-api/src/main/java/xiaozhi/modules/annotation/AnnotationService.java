package xiaozhi.modules.annotation;

import static xiaozhi.modules.attachment.AttachmentRules.problem;
import static xiaozhi.modules.attachment.AttachmentRules.id;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import xiaozhi.modules.annotation.AnnotationModels.*;
import xiaozhi.modules.attachment.*;
import xiaozhi.modules.attachment.AttachmentModels.View;
import xiaozhi.modules.conversation.*;
import xiaozhi.modules.conversation.ConversationModels.CursorPage;

/** Immutable, author-private drafts. Final publication is exclusively the existing message transaction. */
@Service
@ConditionalOnProperty(prefix="features",name={"conversation-enabled","attachments-enabled","annotations-enabled"},havingValue="true")
public class AnnotationService implements GeneratedMediaSupport {
    private final JdbcTemplate db;
    private final AttachmentService attachments;
    private final ConversationAccessService access;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    record F(long id,String conversation,long creator,String clientId,String hash,long source,long output,Long requested,Long actual,Instant created,String sourceType,Long mediaSession,Integer roomGeneration,Instant capturedAt) {
        F(long id,String conversation,long creator,String clientId,String hash,long source,long output,Long requested,Long actual,Instant created){this(id,conversation,creator,clientId,hash,source,output,requested,actual,created,"attachment",null,null,null);}
    }
    record N(long id,String conversation,long creator,String clientId,String hash,long frame,String label,String scene,long output,Instant created) { }
    public AnnotationService(JdbcTemplate db,AttachmentService attachments,ConversationAccessService access,ObjectMapper json,PlatformTransactionManager manager) {
        this.db=db;this.attachments=attachments;this.access=access;this.json=json;this.tx=new TransactionTemplate(manager);
    }
    private <T> T transaction(Supplier<T> work) {return tx.execute(status->work.get());}
    private static long actor(Long user){if(user==null || user<=0)throw problem(401,"AUTHENTICATION_REQUIRED");return user;}
    private static void conversation(String value){if(value==null || !value.matches("[a-f0-9]{32}"))throw problem(404,"CONVERSATION_NOT_FOUND");}
    private static void client(String value){if(value==null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}"))throw problem(400,"INVALID_SNAPSHOT_CLIENT_ID");}
    private static Long optionalLong(ResultSet r,String column)throws SQLException{long value=r.getLong(column);return r.wasNull()?null:value;}
    private static F mapF(ResultSet r,int ignored)throws SQLException{return new F(r.getLong("id"),r.getString("conversation_id"),r.getLong("creator_user_id"),r.getString("client_frame_id"),r.getString("request_hash"),r.getLong("source_attachment_id"),r.getLong("frame_attachment_id"),optionalLong(r,"requested_time_ms"),optionalLong(r,"actual_time_ms"),r.getTimestamp("created_at").toInstant(),optionalText(r,"source_type"),optionalColumnLong(r,"media_session_id"),optionalColumnLong(r,"room_generation")==null?null:optionalColumnLong(r,"room_generation").intValue(),optionalInstant(r,"captured_at"));}
    private static String optionalText(ResultSet r,String column){try{String value=r.getString(column);return value==null?"attachment":value;}catch(SQLException absent){return "attachment";}}
    private static Long optionalColumnLong(ResultSet r,String column){try{return optionalLong(r,column);}catch(SQLException absent){return null;}}
    private static Instant optionalInstant(ResultSet r,String column){try{var value=r.getTimestamp(column);return value==null?null:value.toInstant();}catch(SQLException absent){return null;}}
    private static N mapN(ResultSet r,int ignored)throws SQLException{return new N(r.getLong("id"),r.getString("conversation_id"),r.getLong("creator_user_id"),r.getString("client_annotation_id"),r.getString("request_hash"),r.getLong("frame_id"),r.getString("label"),r.getString("scene_json"),r.getLong("render_attachment_id"),r.getTimestamp("created_at").toInstant());}
    private F frame(long id){var rows=db.query("SELECT * FROM support_frame WHERE id=?",AnnotationService::mapF,id);if(rows.isEmpty())throw problem(404,"FRAME_NOT_FOUND");return rows.getFirst();}
    private F frameOutput(long output){var rows=db.query("SELECT * FROM support_frame WHERE frame_attachment_id=?",AnnotationService::mapF,output);if(rows.isEmpty())throw problem(404,"FRAME_NOT_FOUND");return rows.getFirst();}
    private N annotation(long id){var rows=db.query("SELECT * FROM support_annotation WHERE id=?",AnnotationService::mapN,id);if(rows.isEmpty())throw problem(404,"ANNOTATION_NOT_FOUND");return rows.getFirst();}
    private N annotationOutput(long output){var rows=db.query("SELECT * FROM support_annotation WHERE render_attachment_id=?",AnnotationService::mapN,output);if(rows.isEmpty())throw problem(404,"ANNOTATION_NOT_FOUND");return rows.getFirst();}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException("Cannot encode immutable scene");}}
    private JsonNode decode(String value){try{return json.readTree(value);}catch(Exception ex){throw new IllegalStateException("Invalid persisted scene");}}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}
    private static Integer dimension(View view,String name){Object value=view.metadata().get(name);return value instanceof Number number?number.intValue():null;}
    private static int width(View view){Integer value=dimension(view,"width");if(value==null || value<1 || value>8192)throw problem(409,"FRAME_DIMENSIONS_MISSING");return value;}
    private static int height(View view){Integer value=dimension(view,"height");if(value==null || value<1 || value>8192)throw problem(409,"FRAME_DIMENSIONS_MISSING");return value;}
    private Frame frameView(long reader,F f){View output=attachments.serverSnapshot(reader,Long.toString(f.output()),f.conversation());return new Frame(Long.toString(f.id()),f.conversation(),Long.toString(f.source()),Long.toString(f.output()),f.requested(),f.actual(),"first_at_or_after",dimension(output,"width"),dimension(output,"height"),output.status(),output.errorCode(),f.created());}
    private Annotation annotationView(long reader,N n){View output=attachments.serverSnapshot(reader,Long.toString(n.output()),n.conversation());return new Annotation(Long.toString(n.id()),n.conversation(),Long.toString(n.frame()),Long.toString(n.output()),n.label(),decode(n.scene()),output.status(),output.errorCode(),n.created());}
    private View source(long reader,F f){View source=attachments.serverSource(reader,Long.toString(f.source()),f.conversation(),!f.sourceType().equals("client_live_capture"));if(!Set.of("image","video").contains(source.kind()))throw problem(400,"INVALID_FRAME_SOURCE");return source;}
    public Frame createFrame(Long user,CreateFrame request) {
        long actor=actor(user);if(request==null)throw problem(400,"INVALID_REQUEST");conversation(request.conversationId());client(request.clientFrameId());id(request.sourceAttachmentId());
        String digest=hash(encode(List.of(request.conversationId(),request.sourceAttachmentId(),request.timeMs()==null?"image":request.timeMs())));
        try{return transaction(()->{
            attachments.serverRequireWrite(actor,request.conversationId());
            var previous=db.query("SELECT * FROM support_frame WHERE creator_user_id=? AND client_frame_id=? FOR UPDATE",AnnotationService::mapF,actor,request.clientFrameId());
            if(!previous.isEmpty()){F f=previous.getFirst();if(!f.hash().equals(digest))throw problem(409,"CLIENT_FRAME_ID_REUSED");return frameView(actor,f);}
            View source=attachments.serverSource(actor,request.sourceAttachmentId(),request.conversationId(),true);
            if(!Set.of("image","video").contains(source.kind()))throw problem(400,"INVALID_FRAME_SOURCE");
            if(source.kind().equals("image")){if(request.timeMs()!=null)throw problem(400,"IMAGE_TIME_NOT_ALLOWED");}
            else {Object duration=source.metadata().get("duration_ms");if(request.timeMs()==null || request.timeMs()<0 || !(duration instanceof Number n) || request.timeMs()>=n.longValue())throw problem(400,"INVALID_FRAME_TIME");}
            width(source);height(source);long frame=IdWorker.getId();String output=attachments.serverCreateGenerated(actor,request.conversationId(),frame,"capture_frame");
            db.update("INSERT INTO support_frame(id,conversation_id,creator_user_id,client_frame_id,request_hash,source_attachment_id,frame_attachment_id,requested_time_ms,capture_policy) VALUES(?,?,?,?,?,?,?,?,'first_at_or_after')",frame,request.conversationId(),actor,request.clientFrameId(),digest,id(request.sourceAttachmentId()),id(output),request.timeMs());
            attachments.serverQueueGenerated(output,"capture_frame");return frameView(actor,frame(frame));
        });}catch(DuplicateKeyException ex){throw problem(409,"CLIENT_FRAME_ID_REUSED");}catch(PessimisticLockingFailureException ex){throw problem(409,"SNAPSHOT_CONFLICT_RETRY");}
    }
    public Frame getFrame(Long user,String id){long reader=actor(user);return transaction(()->frameView(reader,frame(id(id))));}
    public Frame retryFrame(Long user,String id){long actor=actor(user);F f=transaction(()->{F row=frame(id(id));if(row.creator()!=actor)throw problem(404,"FRAME_NOT_FOUND");return row;});attachments.retry(actor,Long.toString(f.output()));return getFrame(actor,id);}
    public Annotation createAnnotation(Long user,CreateAnnotation request) {
        long actor=actor(user);if(request==null)throw problem(400,"INVALID_REQUEST");conversation(request.conversationId());client(request.clientAnnotationId());long frame=id(request.frameId());
        String label=request.label()==null?"":request.label();if(label.codePointCount(0,label.length())>200 || label.codePoints().anyMatch(c->c<32 || c==127))throw problem(400,"INVALID_ANNOTATION_LABEL");
        try{return transaction(()->{
            attachments.serverRequireWrite(actor,request.conversationId());F f=frame(frame);if(!f.conversation().equals(request.conversationId()))throw problem(404,"FRAME_NOT_FOUND");
            View original=attachments.serverSource(actor,Long.toString(f.output()),f.conversation(),false);source(actor,f);
            JsonNode scene=AnnotationScene.validate(request.scene(),request.frameId(),width(original),height(original),json);String encoded=encode(scene);
            String digest=hash(encode(List.of(request.conversationId(),request.frameId(),label,scene)));
            var previous=db.query("SELECT * FROM support_annotation WHERE creator_user_id=? AND client_annotation_id=? FOR UPDATE",AnnotationService::mapN,actor,request.clientAnnotationId());
            if(!previous.isEmpty()){N n=previous.getFirst();if(!n.hash().equals(digest))throw problem(409,"CLIENT_ANNOTATION_ID_REUSED");return annotationView(actor,n);}
            long annotation=IdWorker.getId();String output=attachments.serverCreateGenerated(actor,f.conversation(),annotation,"annotation_preview");
            db.update("INSERT INTO support_annotation(id,conversation_id,creator_user_id,client_annotation_id,request_hash,frame_id,label,format,scene_json,scene_sha256,render_attachment_id) VALUES(?,?,?,?,?,?,?,'annotation-v1',?,?,?)",annotation,f.conversation(),actor,request.clientAnnotationId(),digest,f.id(),label,encoded,hash(encoded),id(output));
            attachments.serverQueueGenerated(output,"annotation_preview");return annotationView(actor,annotation(annotation));
        });}catch(DuplicateKeyException ex){throw problem(409,"CLIENT_ANNOTATION_ID_REUSED");}catch(PessimisticLockingFailureException ex){throw problem(409,"SNAPSHOT_CONFLICT_RETRY");}
    }
    public Annotation getAnnotation(Long user,String id){long reader=actor(user);return transaction(()->annotationView(reader,annotation(id(id))));}
    public Annotation retryAnnotation(Long user,String id){long actor=actor(user);N n=transaction(()->{N row=annotation(id(id));if(row.creator()!=actor)throw problem(404,"ANNOTATION_NOT_FOUND");return row;});attachments.retry(actor,Long.toString(n.output()));return getAnnotation(actor,id);}
    public CursorPage<Annotation> list(Long user,String conversation,long after,int limit) {
        long reader=actor(user);conversation(conversation);if(after<0 || limit<1 || limit>100)throw problem(400,"INVALID_CURSOR_OR_LIMIT");
        return transaction(()->{
            var grant=access.read(reader,conversation,true);
            var rows=db.query("SELECT n.* FROM support_annotation n JOIN ai_attachment a ON a.id=n.render_attachment_id WHERE n.conversation_id=? AND n.id>? AND a.status<>'expired' AND ((a.linked_message_id IS NULL AND n.creator_user_id=? AND a.expires_at>CURRENT_TIMESTAMP(3)) OR (a.linked_message_id IS NOT NULL AND a.linked_seq<=?)) ORDER BY n.id LIMIT ?",AnnotationService::mapN,conversation,after,reader,grant.messageCutoff(),limit+1);
            var items=rows.stream().limit(limit).map(n->annotationView(reader,n)).toList();long next=items.isEmpty()?after:Long.parseLong(items.getLast().id());return new CursorPage<>(items,next,rows.size()>limit);
        });
    }
    @Override public void validate(long output,String type,long actor) {
        F f;
        if(type.equals("capture_frame")){f=frameOutput(output);if(f.creator()!=actor)throw problem(404,"FRAME_NOT_FOUND");}
        else if(type.equals("annotation_preview")){N n=annotationOutput(output);if(n.creator()!=actor)throw problem(404,"ANNOTATION_NOT_FOUND");f=frame(n.frame());}
        else throw problem(400,"INVALID_GENERATED_JOB");
        attachments.serverRequireWrite(actor,f.conversation());source(actor,f);
        if(type.equals("annotation_preview"))attachments.serverSource(actor,Long.toString(f.output()),f.conversation(),false);
    }
    @Override public Input input(long output,String type) {
        F f;N n=null;if(type.equals("capture_frame"))f=frameOutput(output);else if(type.equals("annotation_preview")){n=annotationOutput(output);f=frame(n.frame());}else throw problem(400,"INVALID_GENERATED_JOB");
        View source=attachments.serverSource(n==null?f.creator():n.creator(),Long.toString(n==null?f.source():f.output()),f.conversation(),n==null && !f.sourceType().equals("client_live_capture"));
        Map<String,Object> metadata=new LinkedHashMap<>();metadata.put("frame_id",Long.toString(f.id()));metadata.put("source_attachment_id",Long.toString(f.source()));metadata.put("expected_width",width(source));metadata.put("expected_height",height(source));
        if(n==null){metadata.put("requested_time_ms",f.requested()==null?null:Math.toIntExact(f.requested()));metadata.put("capture_policy","first_at_or_after");}
        else {metadata.put("annotation_id",Long.toString(n.id()));metadata.put("scene",decode(n.scene()));}
        return new Input(source.id(),n==null && source.kind().equals("video")?"original":"display",n==null && source.kind().equals("video")?source.filename():"frame.jpg",metadata);
    }
    @Override public Map<String,Object> complete(long output,String type,Map<String,Object> metadata) {
        F f;N n=null;if(type.equals("capture_frame"))f=frameOutput(output);else if(type.equals("annotation_preview")){n=annotationOutput(output);f=frame(n.frame());}else throw problem(400,"INVALID_GENERATED_JOB");
        long creator=n==null?f.creator():n.creator();View source=attachments.serverSource(creator,Long.toString(n==null?f.source():f.output()),f.conversation(),n==null && !f.sourceType().equals("client_live_capture"));
        boolean video=n==null && f.requested()!=null;Set<String> keys=video?Set.of("mime_type","width","height","actual_time_ms"):Set.of("mime_type","width","height");
        if(metadata==null || !metadata.keySet().equals(keys) || !"image/jpeg".equals(metadata.get("mime_type")))throw problem(400,"INVALID_MEDIA_METADATA");
        long width=number(metadata,"width",1,8192),height=number(metadata,"height",1,8192);
        if(width!=width(source) || height!=height(source) || width*height>24000000)throw problem(400,"PIXEL_LIMIT");
        Map<String,Object> result=new LinkedHashMap<>(metadata);result.put("width",Math.toIntExact(width));result.put("height",Math.toIntExact(height));result.put("frame_id",Long.toString(f.id()));result.put("source_attachment_id",Long.toString(f.source()));
        if(video){long duration=((Number)source.metadata().get("duration_ms")).longValue();long actual=number(metadata,"actual_time_ms",f.requested(),duration);db.update("UPDATE support_frame SET actual_time_ms=? WHERE id=? AND actual_time_ms IS NULL",actual,f.id());result.put("requested_time_ms",Math.toIntExact(f.requested()));result.put("actual_time_ms",Math.toIntExact(actual));}
        else if(f.requested()!=null){result.put("requested_time_ms",Math.toIntExact(f.requested()));result.put("actual_time_ms",f.actual()==null?null:Math.toIntExact(f.actual()));}
        if(f.sourceType().equals("client_live_capture")){result.put("source_type","client_live_capture");result.put("media_session_id",Long.toString(f.mediaSession()));result.put("room_generation",f.roomGeneration());result.put("captured_at",f.capturedAt().toString());result.put("verification","client_declared");}
        if(n!=null)result.put("annotation_id",Long.toString(n.id()));return result;
    }
    private static long number(Map<String,Object> map,String key,long minimum,long maximum){Object value=map.get(key);if(!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue()!=n.longValue() || n.longValue()<minimum || n.longValue()>maximum)throw problem(400,"INVALID_MEDIA_METADATA");return n.longValue();}
    @Override public void bind(long actor,String conversation,long output,String message,long seq) {
        var annotations=db.query("SELECT * FROM support_annotation WHERE render_attachment_id=?",AnnotationService::mapN,output);
        if(annotations.isEmpty()){F f=frameOutput(output);if(!f.conversation().equals(conversation) || f.creator()!=actor)throw problem(404,"FRAME_NOT_FOUND");validate(output,"capture_frame",actor);if(f.sourceType().equals("client_live_capture"))attachments.serverBindEvidence(actor,conversation,Long.toString(f.source()),message,seq);return;}
        N n=annotations.getFirst();if(!n.conversation().equals(conversation) || n.creator()!=actor)throw problem(404,"ANNOTATION_NOT_FOUND");validate(output,"annotation_preview",actor);F f=frame(n.frame());
        if(f.sourceType().equals("client_live_capture"))attachments.serverBindEvidence(actor,conversation,Long.toString(f.source()),message,seq);
        attachments.serverBindEvidence(actor,conversation,Long.toString(f.output()),message,seq);
    }
    /** Trusted B5 bridge: caller has locked and authorized an active snapshot-consented media generation. */
    public Frame createLiveFrame(long actor,String conversation,String client,String source,long session,int generation,Instant capturedAt) {
        conversation(conversation);client(client);id(source);String digest=hash(encode(List.of("client_live_capture",conversation,source,session,generation,capturedAt.toString())));
        return transaction(()->{attachments.serverRequireWrite(actor,conversation);
            var previous=db.query("SELECT * FROM support_frame WHERE creator_user_id=? AND client_frame_id=? FOR UPDATE",AnnotationService::mapF,actor,client);
            if(!previous.isEmpty()){F existing=previous.getFirst();if(!existing.hash().equals(digest))throw problem(409,"CLIENT_FRAME_ID_REUSED");return frameView(actor,existing);}
            attachments.serverUnsentImage(actor,source,conversation);
            long frame=IdWorker.getId();String output=attachments.serverCreateGenerated(actor,conversation,frame,"capture_frame");
            db.update("INSERT INTO support_frame(id,conversation_id,creator_user_id,client_frame_id,request_hash,source_attachment_id,frame_attachment_id,capture_policy,source_type,media_session_id,room_generation,captured_at) VALUES(?,?,?,?,?,?,?,'first_at_or_after','client_live_capture',?,?,?)",frame,conversation,actor,client,digest,id(source),id(output),session,generation,java.sql.Timestamp.from(capturedAt));
            attachments.serverQueueGenerated(output,"capture_frame");return frameView(actor,frame(frame));
        });
    }

}
