package xiaozhi.modules.answerspeech;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import xiaozhi.modules.conversation.*;
import xiaozhi.modules.externalidentity.*;
import xiaozhi.modules.externalidentity.ExternalModels.Principal;
import static xiaozhi.modules.answerspeech.AnswerSpeechModels.*;

/** Version-bound answer synthesis. Provider IO is confined to the dedicated worker. */
@Service
public class AnswerSpeechService {
    private xiaozhi.modules.airuntime.AiRuntimeControl runtimeControl;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setRuntimeControl(xiaozhi.modules.airuntime.AiRuntimeControl value){runtimeControl=value;}
    private void admitRuntime(String resource,long owner){if(runtimeControl!=null)runtimeControl.admit(xiaozhi.modules.airuntime.AiRuntimeControl.Flow.SPEECH,resource,owner);}
    private void releaseRuntime(String resource){if(runtimeControl!=null)runtimeControl.release(xiaozhi.modules.airuntime.AiRuntimeControl.Flow.SPEECH,resource);}

    public static final int MAX_CHARS=8000,MAX_BYTES=5242880,MAX_DURATION=120000,LEASE_SECONDS=45;
    private static final Set<String> ERRORS=Set.of("SPEECH_PROVIDER_NOT_CONFIGURED","SPEECH_PROVIDER_AUTH","SPEECH_PROVIDER_ERROR","SPEECH_PROVIDER_TIMEOUT","SPEECH_AUDIO_INVALID","SPEECH_LIMIT_EXCEEDED","SPEECH_CANCELED");
    private final JdbcTemplate db;private final ObjectMapper json;private final TransactionTemplate tx,usageTx;
    private final Environment env;private final ConversationDao conversations;private final Path storage;private ExternalIdentityService identity;
    public AnswerSpeechService(JdbcTemplate db,ObjectMapper json,PlatformTransactionManager manager,Environment env){
        this.db=db;this.json=json;this.env=env;conversations=new ConversationDao(db);tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        usageTx=new TransactionTemplate(manager);usageTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);usageTx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        storage=Path.of(env.getProperty("answer-speech.storage-root","./data/answer-speech-private")).toAbsolutePath().normalize();
    }
    @Autowired(required=false) public void setIdentity(ExternalIdentityService value){identity=value;}
    public boolean enabled(){return env.getProperty("features.answer-speech-enabled",Boolean.class,false);}
    private void enabledRequired(){if(!enabled())throw problem(404,"ANSWER_SPEECH_DISABLED");}
    public Job create(Long actor,String conversation,String message,Create request,Principal current){
        enabledRequired();requireActor(actor);id(conversation);id(message);if(request==null||request.clientRequestId()==null||!request.clientRequestId().matches("[A-Za-z0-9_.:-]{1,64}"))throw problem(400,"INVALID_SPEECH_REQUEST");
        return tx.execute(status->{
            account(actor,true);conversations.owned(conversation,actor,true);Principal fresh=fresh(current,actor);var snapshot=snapshot(conversation,message);
            var duplicates=rows("SELECT * FROM ai_answer_speech_job WHERE owner_user_id=? AND client_request_id=? FOR UPDATE",actor,request.clientRequestId());
            if(!duplicates.isEmpty()) {var old=duplicates.getFirst();if(!conversation.equals(old.get("conversation_id"))||!message.equals(old.get("message_id"))||!snapshot.version.equals(old.get("message_version"))||!sameRequestBinding(old,fresh))throw problem(409,"SPEECH_IDEMPOTENCY_CONFLICT");return view(old,fresh);}
            expireOwner(actor);
            if(db.queryForObject("SELECT COUNT(*) FROM ai_answer_speech_job WHERE owner_user_id=? AND status IN ('queued','synthesizing')",Long.class,actor)>0)throw problem(409,"SPEECH_ALREADY_ACTIVE");
            if(db.queryForObject("SELECT COUNT(*) FROM ai_answer_speech_job WHERE owner_user_id=? AND created_at>DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 HOUR)",Long.class,actor)>=10)throw problem(429,"SPEECH_QUOTA_EXCEEDED");
            String model=setting("model","cosyvoice-v3.5-flash"),voice=setting("voice","longantai_v3"),job=uuid();
            db.update("INSERT INTO ai_answer_speech_job(id,conversation_id,message_id,owner_user_id,client_request_id,message_version,text_sha256,text_snapshot,principal_json,model,voice,status) VALUES(?,?,?,?,?,?,?,?,?,?,?,'queued')",job,conversation,message,actor,request.clientRequestId(),snapshot.version,snapshot.sha,snapshot.text,fresh==null?null:encode(fresh),model,voice);
            var row=job(job,false);audit(row,"queued",null,null);admitRuntime(job,actor);return view(row,fresh);
        });
    }
    public Job get(Long actor,String conversation,String id,Principal current){enabledRequired();requireActor(actor);return tx.execute(status->{owned(actor,conversation,id);expireOwner(actor);return view(job(id,false),current);});}
    public Job cancel(Long actor,String conversation,String id,Principal current){
        enabledRequired();requireActor(actor);return tx.execute(status->{account(actor,true);var row=owned(actor,conversation,id);if(active(row)){terminal(row,"canceled","SPEECH_CANCELED");row=job(id,false);}return view(row,current);});
    }
    public Task claim(Claim request){
        enabledRequired();if(request==null||request.workerId()==null||!request.workerId().matches("[A-Za-z0-9_.:-]{1,100}"))throw problem(400,"INVALID_SPEECH_REQUEST");
        // Expired dispatches are uncertain and are never automatically re-synthesized.
        maintain();
        for(String id:db.query("SELECT id FROM ai_answer_speech_job WHERE status='queued' ORDER BY created_at,id LIMIT 20",(r,n)->r.getString(1))){
            Task task=tx.execute(status->{var row=job(id,true);if(!"queued".equals(row.get("status")))return null;
                try{authority(row,null,false);}catch(ConversationProblem denied){terminal(row,"failed","SPEECH_AUTHORIZATION_CHANGED");return null;}
                String lease=uuid()+uuid();int attempt=(int)n(row,"attempt")+1;
                db.update("UPDATE ai_answer_speech_job SET status='synthesizing',attempt=?,updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",attempt,id);
                db.update("INSERT INTO ai_answer_speech_attempt(job_id,attempt,lease_hash,worker_id,lease_expires_at) VALUES(?,?,?,?,DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 45 SECOND))",id,attempt,hash(lease),request.workerId());
                row=job(id,false);audit(row,"dispatched",null,null);return new Task(id,(String)row.get("conversation_id"),(String)row.get("message_id"),(String)row.get("message_version"),(String)row.get("text_sha256"),(String)row.get("text_snapshot"),attempt,lease,LEASE_SECONDS,(String)row.get("model"),(String)row.get("voice"),new Audio("s16le",24000,1,"wav"),new Policy(MAX_CHARS,MAX_BYTES,MAX_DURATION));
            });if(task!=null)return task;
        }return null;
    }
    /** A worker outage must not keep a user's active slot and public pending state forever. */
    public void maintain(){if(!enabled())return;tx.execute(status->{for(var row:rows("SELECT j.* FROM ai_answer_speech_job j LEFT JOIN ai_answer_speech_attempt a ON a.job_id=j.id AND a.attempt=j.attempt WHERE j.status IN ('queued','synthesizing') AND (j.created_at<DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 5 MINUTE) OR a.lease_expires_at<CURRENT_TIMESTAMP(3) OR a.dispatched_at<DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 180 SECOND)) ORDER BY j.created_at LIMIT 50 FOR UPDATE"))terminal(row,"failed","SPEECH_PROVIDER_TIMEOUT");return null;});}
    public Map<String,Boolean> heartbeat(String id,Fence body){enabledRequired();return tx.execute(status->{var row=fenced(id,body,true);authority(row,null,false);db.update("UPDATE ai_answer_speech_attempt SET lease_expires_at=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 45 SECOND) WHERE job_id=? AND attempt=?",id,body.attempt());return Map.of("active",true);});}
    public Uploaded upload(String id,Fence body,String claimedSha,InputStream input,long declaredSize)throws IOException {
        enabledRequired();sha(claimedSha);if(declaredSize>MAX_BYTES)throw problem(413,"SPEECH_LIMIT_EXCEEDED");
        tx.execute(status->{authority(fenced(id,body,true),null,false);return null;});
        // Neither network reads nor filesystem writes happen under a SQL lock.
        byte[] bytes=input.readNBytes(MAX_BYTES+1);if(bytes.length>MAX_BYTES)throw problem(413,"SPEECH_LIMIT_EXCEEDED");if(declaredSize>=0&&declaredSize!=bytes.length)throw problem(400,"SPEECH_AUDIO_INVALID");
        Uploaded audio=validateWav(bytes);if(!audio.audioSha256().equals(claimedSha))throw problem(400,"SPEECH_AUDIO_INVALID");
        Path directory=storage.resolve(id).normalize();if(!directory.startsWith(storage))throw problem(400,"INVALID_SPEECH_REQUEST");Files.createDirectories(storage);
        if(Files.isSymbolicLink(storage)||Files.isSymbolicLink(directory))throw problem(503,"SPEECH_AUDIO_UNAVAILABLE");Files.createDirectories(directory);privatePermissions(storage,true);privatePermissions(directory,true);
        if(!directory.toRealPath().startsWith(storage.toRealPath()))throw problem(503,"SPEECH_AUDIO_UNAVAILABLE");
        Path file=directory.resolve(body.attempt()+"-"+claimedSha+".wav");
        try{Files.write(file,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);}catch(FileAlreadyExistsException duplicate){if(Files.isSymbolicLink(file)||!hash(Files.readAllBytes(file)).equals(claimedSha))throw problem(409,"SPEECH_AUDIO_CONFLICT");}privatePermissions(file,false);
        return tx.execute(status->{var row=fenced(id,body,true);authority(row,null,false);if(row.get("audio_sha256")!=null&&!claimedSha.equals(row.get("audio_sha256")))throw problem(409,"SPEECH_AUDIO_CONFLICT");
            if(row.get("audio_sha256")==null){db.update("UPDATE ai_answer_speech_job SET audio_path=?,audio_sha256=?,audio_bytes=?,duration_ms=?,updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",storage.relativize(file).toString(),claimedSha,audio.audioBytes(),audio.durationMs(),id);audit(row,"artifact_received",null,claimedSha);}return audio;});
    }
    public Job complete(String id,Finish body){
        if(body==null)throw problem(400,"INVALID_SPEECH_REQUEST");reportUsage(id,new UsageReport(body.leaseToken(),body.attempt(),body.messageVersion(),body.textSha256(),body.usage(),body.providerRequestId()));enabledRequired();sha(body.audioSha256());
        return tx.execute(status->{var row=fenced(id,new Fence(body.leaseToken(),body.attempt(),body.messageVersion(),body.textSha256()),false);authority(row,null,false);
            if(!body.audioSha256().equals(row.get("audio_sha256")))throw problem(409,"SPEECH_ARTIFACT_MISSING");
            if("completed".equals(row.get("status")))return view(row,null,false);
            live(row);terminal(row,"completed",null);return view(job(id,false),null,false);
        });
    }
    public Job fail(String id,Failed body){
        if(body==null||!ERRORS.contains(body.errorCode()==null?"":body.errorCode()))throw problem(400,"INVALID_SPEECH_REQUEST");reportUsage(id,new UsageReport(body.leaseToken(),body.attempt(),body.messageVersion(),body.textSha256(),body.usage(),body.providerRequestId()));enabledRequired();
        return tx.execute(status->{var row=fenced(id,new Fence(body.leaseToken(),body.attempt(),body.messageVersion(),body.textSha256()),false);if("failed".equals(row.get("status"))&&body.errorCode().equals(row.get("error_code")))return view(row,null,false);live(row);authority(row,null,false);terminal(row,"failed",body.errorCode());return view(job(id,false),null,false);});
    }
    /** Historical fee reporting cannot authorize output or resurrect a canceled/expired job. */
    public Map<String,Boolean> reportUsage(String id,UsageReport body){
        if(body==null)throw problem(400,"INVALID_SPEECH_REQUEST");validateFence(body.leaseToken(),body.attempt(),body.messageVersion(),body.textSha256());
        if(body.usage()!=null&&(body.usage().characters()==null||body.usage().characters()<0||body.usage().characters()>10000000)||body.providerRequestId()!=null&&!body.providerRequestId().matches("[A-Za-z0-9_.:/-]{1,200}"))throw problem(400,"INVALID_SPEECH_USAGE");
        String digest=hash(encode(Arrays.asList(body.usage(),body.providerRequestId())));
        return usageTx.execute(status->{var row=fenced(id,new Fence(body.leaseToken(),body.attempt(),body.messageVersion(),body.textSha256()),false);var old=rows("SELECT report_hash FROM ai_answer_speech_usage WHERE job_id=? AND attempt=?",id,body.attempt());
            if(!old.isEmpty()){if(!digest.equals(old.getFirst().get("report_hash")))throw problem(409,"SPEECH_USAGE_CONFLICT");return Map.of("recorded",true);}
            db.update("INSERT INTO ai_answer_speech_usage(job_id,attempt,report_hash,model,provider_request_id,characters) VALUES(?,?,?,?,?,?)",id,body.attempt(),digest,row.get("model"),body.providerRequestId(),body.usage()==null?null:body.usage().characters());return Map.of("recorded",true);
        });
    }
    public record Content(InputStream input,long size,Runnable guard) implements AutoCloseable{public void close()throws IOException{input.close();}}
    public Content audio(Long actor,String conversation,String id,Principal current)throws IOException {
        enabledRequired();requireActor(actor);var row=tx.execute(status->{var value=owned(actor,conversation,id);authority(value,current,true);if(!"completed".equals(value.get("status")))throw problem(409,"SPEECH_NOT_READY");return value;});
        Path file=storage.resolve((String)row.get("audio_path")).normalize();if(!file.startsWith(storage)||Files.isSymbolicLink(storage)||Files.isSymbolicLink(file.getParent())||Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)!=n(row,"audio_bytes"))throw problem(404,"SPEECH_AUDIO_UNAVAILABLE");
        // Verify private bytes, not just the worker's claimed digest, before opening a response.
        if(!hash(Files.readAllBytes(file)).equals(row.get("audio_sha256")))throw problem(409,"SPEECH_AUDIO_INVALID");
        Runnable guard=()->{enabledRequired();var now=owned(actor,conversation,id);authority(now,current,true);if(!"completed".equals(now.get("status")))throw problem(409,"SPEECH_NOT_READY");};guard.run();return new Content(Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS),n(row,"audio_bytes"),guard);
    }
    private static void privatePermissions(Path path,boolean directory)throws IOException{try{Files.setPosixFilePermissions(path,java.nio.file.attribute.PosixFilePermissions.fromString(directory?"rwx------":"rw-------"));}catch(UnsupportedOperationException ignored){}}
    static Uploaded validateWav(byte[] bytes){
        if(bytes.length<46||bytes.length>MAX_BYTES)throw problem(400,"SPEECH_AUDIO_INVALID");ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if(!ascii(bytes,0,"RIFF")||b.getInt(4)!=bytes.length-8||!ascii(bytes,8,"WAVE")||!ascii(bytes,12,"fmt ")||b.getInt(16)!=16||b.getShort(20)!=1||b.getShort(22)!=1||b.getInt(24)!=24000||b.getInt(28)!=48000||b.getShort(32)!=2||b.getShort(34)!=16||!ascii(bytes,36,"data")||b.getInt(40)!=bytes.length-44||(bytes.length-44)%2!=0)throw problem(400,"SPEECH_AUDIO_INVALID");
        int milliseconds=(int)(((long)bytes.length-44)*1000/48000);if(milliseconds<1||milliseconds>MAX_DURATION)throw problem(413,"SPEECH_LIMIT_EXCEEDED");return new Uploaded(hash(bytes),bytes.length,milliseconds);
    }
    private static boolean ascii(byte[] value,int start,String expected){return new String(value,start,expected.length(),StandardCharsets.US_ASCII).equals(expected);}
    private record Snapshot(String text,String version,String sha){}
    private Snapshot snapshot(String conversation,String message){
        var rows=rows("SELECT parts_json,sender_type,status FROM ai_message WHERE conversation_id=? AND id=?",conversation,message);if(rows.size()!=1||!"assistant".equals(rows.getFirst().get("sender_type"))||!"completed".equals(rows.getFirst().get("status")))throw problem(409,"SPEECH_MESSAGE_NOT_COMPLETED");
        String parts=(String)rows.getFirst().get("parts_json");JsonNode nodes=decode(parts);if(!nodes.isArray())throw problem(409,"SPEECH_MESSAGE_INVALID");List<String> text=new ArrayList<>();for(var node:nodes)if("text".equals(node.path("type").asText())&&node.path("text").isTextual())text.add(node.get("text").asText());String joined=String.join("\n",text);
        if(joined.isBlank()||joined.length()>MAX_CHARS)throw problem(413,"SPEECH_TEXT_LIMIT");if(joined.indexOf(0)>=0||!StandardCharsets.UTF_8.newEncoder().canEncode(joined))throw problem(400,"SPEECH_MESSAGE_INVALID");return new Snapshot(joined,hash(message+"\n"+parts),hash(joined));
    }
    private Principal authority(Map<String,Object> row,Principal supplied,boolean requireCurrent){
        long owner=n(row,"owner_user_id");account(owner,false);conversations.owned((String)row.get("conversation_id"),owner,false);Snapshot snapshot=snapshot((String)row.get("conversation_id"),(String)row.get("message_id"));if(!snapshot.version.equals(row.get("message_version"))||!snapshot.sha.equals(row.get("text_sha256")))throw problem(409,"SPEECH_MESSAGE_CHANGED");
        Principal original=principal(row);if(original==null){if(requireCurrent&&supplied!=null)throw problem(403,"SPEECH_SOURCE_CHANGED");return null;}
        if(requireCurrent&&(supplied==null||!ExternalIdentityService.sameBinding(original,supplied)))throw problem(403,"SPEECH_SOURCE_CHANGED");Principal fresh=fresh(original,owner);if(!ExternalIdentityService.sameBinding(original,fresh))throw problem(403,"SPEECH_SOURCE_CHANGED");return fresh;
    }
    private Principal fresh(Principal supplied,long owner){if(supplied==null)return null;if(identity==null||supplied.userId()!=owner)throw problem(403,"SPEECH_SOURCE_CHANGED");Principal fresh=identity.authenticateHash(supplied.tokenHash());if(!ExternalIdentityService.sameBinding(supplied,fresh))throw problem(403,"SPEECH_SOURCE_CHANGED");return fresh;}
    private boolean sameRequestBinding(Map<String,Object> row,Principal current){Principal original=principal(row);return original==null?current==null:ExternalIdentityService.sameBinding(original,current);}
    private Principal principal(Map<String,Object> row){if(row.get("principal_json")==null)return null;try{return json.readValue((String)row.get("principal_json"),Principal.class);}catch(Exception invalid){throw problem(403,"SPEECH_SOURCE_CHANGED");}}
    private Job view(Map<String,Object> row,Principal current){return view(row,current,true);}
    private Job view(Map<String,Object> row,Principal current,boolean requireCurrent){
        boolean allowed;Principal fresh=null;try{fresh=authority(row,current,requireCurrent);allowed=true;}catch(ConversationProblem denied){allowed=false;}
        Instant until=allowed&&"completed".equals(row.get("status"))?Instant.now().plusSeconds(5):null;if(until!=null&&fresh!=null&&until.isAfter(fresh.expiresAt()))until=fresh.expiresAt();
        return new Job((String)row.get("id"),(String)row.get("conversation_id"),(String)row.get("message_id"),(String)row.get("message_version"),(String)row.get("text_sha256"),(String)row.get("status"),(String)row.get("error_code"),row.get("audio_sha256")==null?null:"audio/wav",row.get("audio_bytes")==null?null:n(row,"audio_bytes"),row.get("duration_ms")==null?null:(int)n(row,"duration_ms"),allowed?"available":"restricted",until,time(row.get("created_at")));
    }
    private Map<String,Object> owned(long actor,String conversation,String id){id(conversation);id(id);account(actor,false);conversations.owned(conversation,actor,false);var row=job(id,false);if(n(row,"owner_user_id")!=actor||!conversation.equals(row.get("conversation_id")))throw problem(404,"SPEECH_JOB_NOT_FOUND");return row;}
    private void account(long actor,boolean lock){if(rows("SELECT id FROM sys_user WHERE id=? AND status=1"+(lock?" FOR UPDATE":""),actor).size()!=1)throw problem(401,"ACCOUNT_DISABLED");}
    private Map<String,Object> fenced(String id,Fence body,boolean active){id(id);if(body==null)throw problem(400,"INVALID_SPEECH_REQUEST");validateFence(body.leaseToken(),body.attempt(),body.messageVersion(),body.textSha256());var row=job(id,true);var attempt=rows("SELECT * FROM ai_answer_speech_attempt WHERE job_id=? AND attempt=?",id,body.attempt());if(attempt.size()!=1||!hash(body.leaseToken()).equals(attempt.getFirst().get("lease_hash"))||!body.messageVersion().equals(row.get("message_version"))||!body.textSha256().equals(row.get("text_sha256")))throw problem(409,"SPEECH_LEASE_CONFLICT");if(active){live(row);liveLease(attempt.getFirst());}return row;}
    private void live(Map<String,Object> row){if(!"synthesizing".equals(row.get("status")))throw problem(409,"SPEECH_JOB_NOT_ACTIVE");var attempt=rows("SELECT * FROM ai_answer_speech_attempt WHERE job_id=? AND attempt=?",row.get("id"),row.get("attempt"));if(attempt.size()!=1)throw problem(409,"SPEECH_LEASE_CONFLICT");liveLease(attempt.getFirst());}
    private static void liveLease(Map<String,Object> attempt){Instant now=Instant.now();if(!time(attempt.get("lease_expires_at")).isAfter(now)||!time(attempt.get("dispatched_at")).plusSeconds(180).isAfter(now))throw problem(409,"SPEECH_LEASE_EXPIRED");}
    private static void validateFence(String token,Integer attempt,String version,String sha){if(token==null||!token.matches("[a-f0-9]{64}")||attempt==null||attempt<1||attempt>100)throw problem(400,"INVALID_SPEECH_REQUEST");sha(version);sha(sha);}
    private void expireOwner(long owner){for(var row:rows("SELECT j.* FROM ai_answer_speech_job j LEFT JOIN ai_answer_speech_attempt a ON a.job_id=j.id AND a.attempt=j.attempt WHERE j.owner_user_id=? AND j.status IN ('queued','synthesizing') AND (j.created_at<DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 5 MINUTE) OR a.lease_expires_at<CURRENT_TIMESTAMP(3) OR a.dispatched_at<DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 180 SECOND)) FOR UPDATE",owner))terminal(row,"failed","SPEECH_PROVIDER_TIMEOUT");}
    private void terminal(Map<String,Object> row,String state,String error){db.update("UPDATE ai_answer_speech_job SET status=?,error_code=?,updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",state,error,row.get("id"));audit(row,state,error,(String)row.get("audio_sha256"));releaseRuntime((String)row.get("id"));}
    private void audit(Map<String,Object> row,String state,String error,String sha){db.update("INSERT INTO ai_answer_speech_audit(job_id,attempt,state,error_code,audio_sha256) VALUES(?,?,?,?,?)",row.get("id"),row.get("attempt"),state,error,sha);}
    private Map<String,Object> job(String id,boolean lock){var rows=rows("SELECT * FROM ai_answer_speech_job WHERE id=?"+(lock?" FOR UPDATE":""),id);if(rows.size()!=1)throw problem(404,"SPEECH_JOB_NOT_FOUND");return rows.getFirst();}
    private List<Map<String,Object>> rows(String sql,Object... args){return db.query(sql,new xiaozhi.common.jdbc.TimestampColumnMapRowMapper(),args);}
    private static boolean active(Map<String,Object> row){return Set.of("queued","synthesizing").contains(row.get("status"));}
    private String setting(String key,String fallback){String value=env.getProperty("answer-speech."+key,fallback);if(!value.matches("[A-Za-z0-9_.:-]{1,100}"))throw problem(503,"SPEECH_PROVIDER_NOT_CONFIGURED");return value;}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception invalid){throw problem(400,"INVALID_SPEECH_REQUEST");}}
    private JsonNode decode(String value){try{return json.readTree(value);}catch(Exception invalid){throw problem(409,"SPEECH_MESSAGE_INVALID");}}
    static String hash(String value){return hash(value.getBytes(StandardCharsets.UTF_8));}
    static String hash(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private static long n(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static Instant time(Object value){return ((Timestamp)value).toInstant();}
    private static void requireActor(Long actor){if(actor==null||actor<1)throw problem(401,"AUTHENTICATION_REQUIRED");}
    private static void sha(String value){if(value==null||!value.matches("[a-f0-9]{64}"))throw problem(400,"INVALID_SPEECH_REQUEST");}
    private static void id(String value){if(value==null||!value.matches("[a-f0-9]{32}"))throw problem(400,"INVALID_SPEECH_REQUEST");}
    private static String uuid(){return UUID.randomUUID().toString().replace("-","");}
    private static ConversationProblem problem(int status,String code){return new ConversationProblem(status,code);}
}
