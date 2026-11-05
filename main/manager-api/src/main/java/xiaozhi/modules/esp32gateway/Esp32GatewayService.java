package xiaozhi.modules.esp32gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import xiaozhi.modules.realtime.RealtimeModels.NumberSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import xiaozhi.modules.sys.service.SysParamsService;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;

/** DB authoritative fencing. Lock order: device, agent, user, connection, commands. */
@Service
public class Esp32GatewayService {
    public static final String TEXT_INPUT = "gateway.text_input";
    public record DeviceContext(String deviceId, Long ownerId, String agentId, String bindingVersion,
                                String connectionId, @JsonSerialize(using=NumberSerializer.class) long generation, String gatewayId, @JsonSerialize(using=NumberSerializer.class) long leaseUntil) {}
    public record Register(String deviceId, String deviceMac, String clientId, String token,
                           String gatewayId, List<String> capabilities) {}
    public record Fence(String deviceId, String connectionId, @JsonSerialize(using=NumberSerializer.class) long generation, String gatewayId, String bindingVersion) {
        public Fence { text(deviceId,32);text(connectionId,36);text(gatewayId,64);text(bindingVersion,64);if(generation<1)throw error(HttpStatus.BAD_REQUEST,"invalid_fence"); }
    }
    public record Result(Fence fence, String commandId, Boolean success, Map<String,Object> result, String status) {
        public Result(Fence fence,String commandId,boolean success,Map<String,Object> result){this(fence,commandId,success,result,null);}
        public Result {
            if(fence==null||result==null)throw error(HttpStatus.BAD_REQUEST,"invalid_result");text(commandId,36);
            if(status==null){if(success==null)throw error(HttpStatus.BAD_REQUEST,"invalid_result");}
            else if(!Set.of("succeeded","failed","unknown").contains(status)
                    ||(Boolean.TRUE.equals(success)&&!"succeeded".equals(status))
                    ||(Boolean.FALSE.equals(success)&&"succeeded".equals(status)))throw error(HttpStatus.BAD_REQUEST,"invalid_result_status");
        }
        public String outcome(){return status!=null?status:Boolean.TRUE.equals(success)?"succeeded":"failed";}
    }
    private record Binding(String deviceId, Long ownerId, String agentId, String mac, String version) {}
    private final JdbcTemplate db;
    private final SysParamsService params;
    private final ObjectMapper json;
    private final boolean enabled;
    private final Clock credentialClock;
    @Autowired
    public Esp32GatewayService(JdbcTemplate db, SysParamsService params, ObjectMapper json,
                               @Value("${features.esp32-enabled:false}") boolean enabled) {
        this(db,params,json,enabled,Clock.systemUTC());
    }
    Esp32GatewayService(JdbcTemplate db,SysParamsService params,ObjectMapper json,boolean enabled,Clock credentialClock) {
        this.db=db;this.params=params;this.json=json;this.enabled=enabled;this.credentialClock=Objects.requireNonNull(credentialClock);
    }
    private void active() { if(!enabled) throw error(HttpStatus.SERVICE_UNAVAILABLE,"esp32_disabled"); }
    private static ResponseStatusException error(HttpStatus status,String code) { return new ResponseStatusException(status,code); }
    private static void text(String s,int max) { if(s==null||s.isBlank()||s.length()>max||s.indexOf('|')>=0) throw error(HttpStatus.BAD_REQUEST,"invalid_field"); }
    /** Initialize the same durable identity before a first firmware assignment; no socket is registered. */
    @Transactional public void prepareFirmwareAuthorization(String id) { binding(id); }
    private Binding binding(String id) {
        text(id,32);
        var devices=db.queryForList("SELECT id,user_id,agent_id,mac_address,update_date FROM ai_device WHERE id=? FOR UPDATE",id);
        if(devices.size()!=1) throw error(HttpStatus.NOT_FOUND,"device_unavailable");
        var d=devices.getFirst(); if(!(d.get("user_id") instanceof Number)||d.get("agent_id")==null)throw error(HttpStatus.FORBIDDEN,"binding_revoked"); Long owner=((Number)d.get("user_id")).longValue(); String agent=(String)d.get("agent_id");
        var agents=db.queryForList("SELECT user_id,updated_at FROM ai_agent WHERE id=? FOR UPDATE",agent);
        var users=db.queryForList("SELECT status,update_date FROM sys_user WHERE id=? FOR UPDATE",owner);
        if(agents.size()!=1||users.size()!=1||!(agents.getFirst().get("user_id") instanceof Number)||!(users.getFirst().get("status") instanceof Number)||!owner.equals(((Number)agents.getFirst().get("user_id")).longValue())||((Number)users.getFirst().get("status")).intValue()!=1)
            throw error(HttpStatus.FORBIDDEN,"binding_revoked");
        String version=hash(owner+"|"+agent+"|"+d.get("mac_address")+"|"+agents.getFirst().get("user_id")+"|"+users.getFirst().get("status"));
        String credentialVersion=hash(owner+"|"+agent+"|"+d.get("mac_address")+"|"+agents.getFirst().get("user_id"));
        db.update("INSERT IGNORE INTO esp32_authorization(device_id,fingerprint,credential_fingerprint,revision) VALUES(?,?,?,1)",id,version,credentialVersion);
        var auth=db.queryForMap("SELECT fingerprint,credential_fingerprint,revision FROM esp32_authorization WHERE device_id=? FOR UPDATE",id);
        long revision=((Number)auth.get("revision")).longValue();
        boolean credentialChanged=auth.get("credential_fingerprint")!=null&&!credentialVersion.equals(auth.get("credential_fingerprint"));
        if(!version.equals(auth.get("fingerprint"))||credentialChanged){revision++;db.update("UPDATE esp32_authorization SET fingerprint=?,credential_fingerprint=?,revision=?,token_not_before=CASE WHEN ? THEN GREATEST(token_not_before,UNIX_TIMESTAMP()+31) ELSE token_not_before END WHERE device_id=?",version,credentialVersion,revision,credentialChanged,id);}
        else if(auth.get("credential_fingerprint")==null)db.update("UPDATE esp32_authorization SET credential_fingerprint=? WHERE device_id=?",credentialVersion,id);
        return new Binding(id,owner,agent,(String)d.get("mac_address"),hash(version+"|"+revision));
    }
    private static String hash(String value) { try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);} }
    private String encode(Object value,int max) {try {String s=json.writeValueAsString(value);if(s.length()>max)throw error(HttpStatus.BAD_REQUEST,"payload_too_large");return s;}catch(ResponseStatusException e){throw e;}catch(Exception e){throw error(HttpStatus.BAD_REQUEST,"invalid_json");}}
    private Map<String,Object> connection(String id) {
        var rows=db.queryForList("SELECT * FROM esp32_connection WHERE device_id=? FOR UPDATE",id);
        if(rows.isEmpty()) throw error(HttpStatus.CONFLICT,"device_offline"); return rows.getFirst();
    }
    private DeviceContext context(Binding b,Map<String,Object> c) { return new DeviceContext(b.deviceId,b.ownerId,b.agentId,b.version,(String)c.get("connection_id"),((Number)c.get("generation")).longValue(),(String)c.get("gateway_id"),((Number)c.get("lease_until")).longValue()); }
    private void check(Binding b,Map<String,Object> c,Fence f) {
        if(f==null)throw error(HttpStatus.BAD_REQUEST,"invalid_fence");
        text(f.deviceId,32);text(f.connectionId,36);text(f.gatewayId,64);text(f.bindingVersion,64);
        if(f.generation<1)throw error(HttpStatus.BAD_REQUEST,"invalid_fence");
        if(!Objects.equals(c.get("connection_id"),f.connectionId)||((Number)c.get("generation")).longValue()!=f.generation||!Objects.equals(c.get("gateway_id"),f.gatewayId)||!b.version.equals(f.bindingVersion)||!b.version.equals(c.get("binding_version"))||((Number)c.get("lease_until")).longValue()<System.currentTimeMillis()||!"online".equals(c.get("state")))
            throw error(HttpStatus.CONFLICT,"stale_connection");
    }
    @Transactional public DeviceContext register(Register r) {
        active(); text(r.deviceMac,50);text(r.clientId,128);text(r.gatewayId,64);text(r.token,128);
        String deviceId=r.deviceId; if(deviceId==null||deviceId.isBlank()){var matches=db.queryForList("SELECT id FROM ai_device WHERE mac_address=?",r.deviceMac);if(matches.size()!=1)throw error(HttpStatus.FORBIDDEN,"credential_invalid");deviceId=(String)matches.getFirst().get("id");}
        Binding b=binding(deviceId); if(!b.mac.equals(r.deviceMac))throw error(HttpStatus.FORBIDDEN,"credential_invalid");
        long issuedAt=verifyToken(r.token,r.clientId,r.deviceMac,params.getValue("server.secret",false),credentialClock.instant().getEpochSecond());
        long notBefore=db.queryForObject("SELECT token_not_before FROM esp32_authorization WHERE device_id=?",Long.class,b.deviceId);
        if(issuedAt<notBefore)throw error(HttpStatus.UNAUTHORIZED,"credential_revoked");
        if(r.capabilities==null||r.capabilities.size()>32||r.capabilities.stream().anyMatch(s->s==null||s.length()>100))throw error(HttpStatus.BAD_REQUEST,"invalid_capabilities");
        String caps=encode(r.capabilities.stream().filter(s->Set.of("self.get_device_status","self.audio_speaker.set_volume",TEXT_INPUT).contains(s)).distinct().toList(),4096);
        db.update("INSERT IGNORE INTO esp32_connection(device_id,connection_id,generation,gateway_id,binding_version,lease_until,state,capabilities) VALUES(?, '',0,'','',0,'offline','[]')",b.deviceId);
        Map<String,Object> old=connection(b.deviceId); long gen=((Number)old.get("generation")).longValue()+1;
        long lease=System.currentTimeMillis()+30000; String cid=UUID.randomUUID().toString();
        cancel(b.deviceId,"canceled");
        db.update("UPDATE esp32_connection SET connection_id=?,generation=?,gateway_id=?,binding_version=?,lease_until=?,state='online',capabilities=? WHERE device_id=?",cid,gen,r.gatewayId,b.version,lease,caps,b.deviceId);
        audit(b.deviceId,null,"connected");return new DeviceContext(b.deviceId,b.ownerId,b.agentId,b.version,cid,gen,r.gatewayId,lease);
    }
    /** Verifies the stock OTA HMAC format, not hardware attestation or provisioning identity.
     * The trusted OTA issuer and verifier must share a reliable clock; legacy proof has no nonce.
     * Timestamp validation retains the existing bounded 30-second future-clock tolerance.
     * Revocation advances the DB cutoff by 31 seconds to cover that entire existing window.
     * After credential_revoked (401), wait until the cutoff and obtain a fresh OTA token;
     * retrying the revoked token will never restore access. Initial credentials remain compatible.
     */
    public static long verifyToken(String token,String client,String mac,String secret) {
        return verifyToken(token,client,mac,secret,System.currentTimeMillis()/1000);
    }
    private static long verifyToken(String token,String client,String mac,String secret,long now) {
        try {if(secret==null||secret.length()<16)throw new Exception(); String[] p=token.split("\\.",-1);if(p.length!=2)throw new Exception();long ts=Long.parseLong(p[1]);if(ts>now+30||ts<now-2592000)throw new Exception();
            Mac h=Mac.getInstance("HmacSHA256");h.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));byte[] actual=Base64.getUrlDecoder().decode(p[0]); if(!MessageDigest.isEqual(actual,h.doFinal((client+"|"+mac+"|"+ts).getBytes(StandardCharsets.UTF_8))))throw new Exception();
            return ts;
        } catch(Exception e){throw error(HttpStatus.UNAUTHORIZED,"credential_invalid");}
    }
    @Transactional public DeviceContext current(String deviceId) {active();Binding b=binding(deviceId);var c=connection(deviceId);DeviceContext ctx=context(b,c);check(b,c,fence(ctx));return ctx;}
    @Transactional public DeviceContext assertCurrent(DeviceContext ctx) {active();Binding b=binding(ctx.deviceId);var c=connection(ctx.deviceId);check(b,c,fence(ctx));if(!b.ownerId.equals(ctx.ownerId)||!b.agentId.equals(ctx.agentId))throw error(HttpStatus.FORBIDDEN,"binding_revoked");return context(b,c);}
    @Transactional public void assertBinding(DeviceContext ctx) {
        active(); Binding b=binding(ctx.deviceId);
        if(!b.ownerId.equals(ctx.ownerId)||!b.agentId.equals(ctx.agentId)||!b.version.equals(ctx.bindingVersion))throw error(HttpStatus.FORBIDDEN,"binding_revoked");
    }
    public static Fence fence(DeviceContext c){return new Fence(c.deviceId,c.connectionId,c.generation,c.gatewayId,c.bindingVersion);}
    @Transactional public DeviceContext heartbeat(Fence f) {active();Binding b=binding(f.deviceId);var c=connection(f.deviceId);check(b,c,f);long lease=System.currentTimeMillis()+30000;db.update("UPDATE esp32_connection SET lease_until=? WHERE device_id=?",lease,f.deviceId);return new DeviceContext(b.deviceId,b.ownerId,b.agentId,b.version,f.connectionId,f.generation,f.gatewayId,lease);}
    @Transactional public void disconnect(Fence f) {active();Binding b=binding(f.deviceId);check(b,connection(f.deviceId),f);db.update("UPDATE esp32_connection SET state='offline',lease_until=0 WHERE device_id=?",f.deviceId);cancel(f.deviceId,"canceled");audit(f.deviceId,null,"disconnected");}
    /** Invoke in the same transaction as binding/authorization mutation; independent of feature flag. */
    @Transactional public void invalidateDevice(String deviceId) {
        text(deviceId,32);
        db.queryForList("SELECT id FROM ai_device WHERE id=? FOR UPDATE",deviceId);
        db.update("INSERT INTO esp32_authorization(device_id,fingerprint,revision,token_not_before) VALUES(?,'revoked',1,UNIX_TIMESTAMP()+31) ON DUPLICATE KEY UPDATE revision=revision+1,token_not_before=GREATEST(token_not_before,UNIX_TIMESTAMP()+31)",deviceId);
        db.queryForList("SELECT device_id FROM esp32_connection WHERE device_id=? FOR UPDATE",deviceId);
        db.update("UPDATE esp32_connection SET generation=generation+1,state='offline',lease_until=0,binding_version=? WHERE device_id=?",UUID.randomUUID().toString(),deviceId);
        cancel(deviceId,"canceled");audit(deviceId,null,"authorization_revoked");
    }
    private void cancel(String device,String status){db.update("UPDATE esp32_command SET state=CASE WHEN state='dispatched' THEN 'unknown' ELSE ? END WHERE device_id=? AND state IN ('pending','dispatched')",status,device);}
    private void audit(String device,String command,String event){db.update("INSERT INTO esp32_gateway_audit(id,device_id,command_id,event,created_at) VALUES(?,?,?,?,?)",UUID.randomUUID().toString(),device,command,event,System.currentTimeMillis());}
    @Transactional public Map<String,Object> submit(DeviceContext ctx,String tool,Map<String,Object> args,String key,int timeoutSeconds) {
        active();assertCurrent(ctx);text(key,128); if(timeoutSeconds<1||timeoutSeconds>60)throw error(HttpStatus.BAD_REQUEST,"invalid_timeout");
        validateCommand(tool,args);
        requireCapability(ctx,tool);
        return enqueue(ctx,tool,args,key,timeoutSeconds);
    }
    private void requireCapability(DeviceContext ctx,String tool) {
        var caps=(String)connection(ctx.deviceId).get("capabilities");
        try { if(!json.readValue(caps,List.class).contains(tool))throw error(HttpStatus.CONFLICT,"capability_unavailable"); }
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
    }
    private Map<String,Object> enqueue(DeviceContext ctx,String tool,Map<String,Object> args,String key,int timeoutSeconds) {
        String payload=encode(args,TEXT_INPUT.equals(tool)?16000:1024);String fingerprint=hash(tool+"|"+payload);
        var prior=db.queryForList("SELECT * FROM esp32_command WHERE device_id=? AND owner_id=? AND idempotency_key=?",ctx.deviceId,ctx.ownerId,key);
        if(!prior.isEmpty()){var c=prior.getFirst();if(!fingerprint.equals(c.get("fingerprint")))throw error(HttpStatus.CONFLICT,"idempotency_conflict");return publicCommand(c);}
        expire(ctx.deviceId);Integer count=db.queryForObject("SELECT COUNT(*) FROM esp32_command WHERE device_id=? AND state IN ('pending','dispatched')",Integer.class,ctx.deviceId);if(count!=null&&count>=16)throw error(HttpStatus.TOO_MANY_REQUESTS,"queue_full");
        String id=UUID.randomUUID().toString();long now=System.currentTimeMillis();db.update("INSERT INTO esp32_command(id,device_id,owner_id,agent_id,binding_version,connection_id,generation,gateway_id,idempotency_key,fingerprint,tool,arguments,state,deadline,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'pending',?,?)",id,ctx.deviceId,ctx.ownerId,ctx.agentId,ctx.bindingVersion,ctx.connectionId,ctx.generation,ctx.gatewayId,key,fingerprint,tool,payload,now+timeoutSeconds*1000L,now);audit(ctx.deviceId,id,"submitted");return command(ctx.ownerId,id);
    }
    @Transactional public Map<String,Object> submitOwner(Long owner,String device,String tool,Map<String,Object> args,String key,int timeout) { DeviceContext c=current(device); if(!c.ownerId.equals(owner))throw error(HttpStatus.NOT_FOUND,"device_unavailable");return submit(c,tool,args,key,timeout); }
    /** Resolve an owned physical target; historical rows never supply a socket or binding fence. */
    @Transactional public Map<String,Object> inputTarget(Long owner,String agent,String mac) {
        active();text(agent,32);text(mac,50);
        var devices=db.queryForList("SELECT d.id FROM ai_device d JOIN ai_agent a ON a.id=d.agent_id AND a.user_id=d.user_id WHERE d.user_id=? AND d.agent_id=? AND d.mac_address=?",owner,agent,mac);
        if(devices.size()!=1)throw error(HttpStatus.NOT_FOUND,"device_unavailable");
        String id=(String)devices.getFirst().get("id");
        var status=status(owner,id);var out=new LinkedHashMap<String,Object>(status);
        out.put("agentId",agent);out.put("macAddress",mac);out.put("available",false);
        out.put("reason","device_offline");
        if(!"online".equals(status.get("state")))return out;
        DeviceContext ctx=current(id);
        try { requireCapability(ctx,TEXT_INPUT); }
        catch(ResponseStatusException e){out.put("reason","capability_unavailable");return out;}
        var rows=inputSessions(ctx);
        if(rows.size()!=1){out.put("reason","session_unavailable");return out;}
        var session=rows.getFirst();
        out.put("available",true);out.put("reason","");out.put("conversationId",session.get("conversation_id"));
        return out;
    }
    private List<Map<String,Object>> inputSessions(DeviceContext ctx) {
        return db.queryForList("SELECT id,conversation_id FROM esp32_device_session WHERE device_id=? AND binding_version=? AND owner_user_id=? AND agent_id=? AND connection_id=? AND generation=? AND gateway_id=? AND execution_mode='ai_worker' AND interaction_mode='ai_only' FOR UPDATE",ctx.deviceId,ctx.bindingVersion,ctx.ownerId,ctx.agentId,ctx.connectionId,ctx.generation,ctx.gatewayId);
    }
    static void validateInput(String conversation,String input,String key) {
        text(conversation,32);text(key,128);
        if(!conversation.matches("[a-f0-9]{32}")||input==null||input.isBlank()||input.length()>2000||input.codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\r'&&c!='\t'))throw error(HttpStatus.BAD_REQUEST,"invalid_text_input");
    }
    @Transactional public Map<String,Object> submitTextOwner(Long owner,String device,String conversation,String input,String key) {
        active();validateInput(conversation,input,key);
        DeviceContext ctx=current(device);
        if(!ctx.ownerId.equals(owner))throw error(HttpStatus.NOT_FOUND,"device_unavailable");
        requireCapability(ctx,TEXT_INPUT);
        var rows=inputSessions(ctx);
        if(rows.size()!=1||!conversation.equals(rows.getFirst().get("conversation_id")))throw error(HttpStatus.CONFLICT,"session_changed");
        var args=new LinkedHashMap<String,Object>();args.put("text",input);args.put("session_id",rows.getFirst().get("id"));
        return enqueue(ctx,TEXT_INPUT,args,key,30);
    }
    static void validateCommand(String tool,Map<String,Object> args){if(args==null)throw error(HttpStatus.BAD_REQUEST,"invalid_arguments");if("self.get_device_status".equals(tool)&&args.isEmpty())return;if("self.audio_speaker.set_volume".equals(tool)&&args.size()==1&&args.get("volume") instanceof Integer v&&v>=0&&v<=100)return;throw error(HttpStatus.BAD_REQUEST,"command_not_allowed");}
    private void expire(String device){long now=System.currentTimeMillis();db.update("UPDATE esp32_command SET state=CASE WHEN state='dispatched' THEN 'unknown' ELSE 'timed_out' END WHERE device_id=? AND deadline<? AND state IN ('pending','dispatched')",device,now);}
    @Transactional public List<Map<String,Object>> poll(Fence f) {active();Binding b=binding(f.deviceId);check(b,connection(f.deviceId),f);expire(f.deviceId);var rows=db.queryForList("SELECT id,tool,arguments,deadline FROM esp32_command WHERE device_id=? AND generation=? AND connection_id=? AND binding_version=? AND state='pending' ORDER BY created_at,id LIMIT 8 FOR UPDATE",f.deviceId,f.generation,f.connectionId,f.bindingVersion);for(var row:rows){db.update("UPDATE esp32_command SET state='dispatched' WHERE id=? AND state='pending'",row.get("id"));audit(f.deviceId,(String)row.get("id"),"dispatched");}return rows;}
    @Transactional public Map<String,Object> result(Result r){active();Binding b=binding(r.fence.deviceId);check(b,connection(b.deviceId),r.fence);text(r.commandId,36);if(r.result==null)throw error(HttpStatus.BAD_REQUEST,"invalid_result");String result=encode(r.result,8192);expire(b.deviceId);var rows=db.queryForList("SELECT * FROM esp32_command WHERE id=? AND device_id=? FOR UPDATE",r.commandId,b.deviceId);if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"command_unknown");var c=rows.getFirst();if(!Objects.equals(c.get("connection_id"),r.fence.connectionId)||!Objects.equals(c.get("binding_version"),b.version))throw error(HttpStatus.CONFLICT,"stale_command");String state=r.outcome();if(!"dispatched".equals(c.get("state"))){if(state.equals(c.get("state"))&&result.equals(c.get("result_json")))return publicCommand(c);throw error(HttpStatus.CONFLICT,"command_terminal");}db.update("UPDATE esp32_command SET state=?,result_json=? WHERE id=?",state,result,r.commandId);audit(b.deviceId,r.commandId,state);return command(b.ownerId,r.commandId);}
    @Transactional public Map<String,Object> command(Long owner,String id){active();text(id,36);var rows=db.queryForList("SELECT * FROM esp32_command WHERE id=?",id);if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"command_unknown");var c=rows.getFirst();Binding b=binding((String)c.get("device_id"));if(!b.ownerId.equals(owner)||!owner.equals(((Number)c.get("owner_id")).longValue()))throw error(HttpStatus.NOT_FOUND,"command_unknown");expire(b.deviceId);return publicCommand(db.queryForMap("SELECT * FROM esp32_command WHERE id=?",id));}
    /** Trusted AI adapter only; result remains untrusted device data, never instructions. */
    @Transactional public Map<String,Object> trustedCommand(DeviceContext ctx,String id) {
        assertCurrent(ctx); command(ctx.ownerId,id);
        var c=db.queryForMap("SELECT * FROM esp32_command WHERE id=?",id);
        if(!ctx.connectionId.equals(c.get("connection_id"))||!ctx.bindingVersion.equals(c.get("binding_version")))throw error(HttpStatus.CONFLICT,"stale_command");
        Map<String,Object> out=new LinkedHashMap<>(publicCommand(c));
        if(c.get("result_json")!=null)try{out.put("result",json.readValue((String)c.get("result_json"),Map.class));}catch(Exception e){throw new IllegalStateException(e);}
        return out;
    }
    private Map<String,Object> publicCommand(Map<String,Object> c){Map<String,Object> out=new LinkedHashMap<>();for(String key:List.of("id","device_id","tool","state","deadline","created_at"))out.put(key,c.get(key));return out;}
    @Transactional public Map<String,Object> status(Long owner,String id){active();Binding b=binding(id);if(!b.ownerId.equals(owner))throw error(HttpStatus.NOT_FOUND,"device_unavailable");var rows=db.queryForList("SELECT * FROM esp32_connection WHERE device_id=?",id);if(rows.isEmpty())return Map.of("deviceId",id,"state","unknown");var c=rows.getFirst();boolean online="online".equals(c.get("state"))&&b.version.equals(c.get("binding_version"))&&((Number)c.get("lease_until")).longValue()>System.currentTimeMillis();return Map.of("deviceId",id,"state",online?"online":"offline","generation",c.get("generation"),"capabilities",online?c.get("capabilities"):"[]");}
}
