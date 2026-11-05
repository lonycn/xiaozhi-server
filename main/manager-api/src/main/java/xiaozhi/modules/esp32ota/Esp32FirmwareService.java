package xiaozhi.modules.esp32ota;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import xiaozhi.modules.device.dto.*;
import xiaozhi.modules.esp32gateway.Esp32GatewayService;
import xiaozhi.modules.sys.service.SysParamsService;

/** Immutable release metadata and explicit device assignments. No AI-triggered upgrades. */
@Service
@ConditionalOnProperty(name="features.esp32-ota-enabled",havingValue="true")
public class Esp32FirmwareService {
    public record Artifact(String sha256,int size) {}
    public record ReleaseRequest(String idempotencyKey,String version,String boardType,String boardName,String chip,
            int minFlashSize,Artifact app,Artifact assets,String assetsVersion,String sourceSha,String idfVersion) {}
    public record Assignment(String deviceId,String updatePolicy,boolean allowDowngrade,int validSeconds) {}
    public record Receipt(String release_id,String event_key,String status,String version) {}
    /** Administrative USB readback evidence, never a device download receipt or hardware attestation. */
    public record UsbBootstrap(String previousVersion,String factoryMac,String softwareMac,int targetSlot,
            String snapshotSha256,String partitionSha256,String originalAppSha256,
            String nvsBeforeSha256,String nvsAfterSha256,boolean nvsPreserved,boolean oldApplicationPreserved,
            Artifact appReadback,Artifact assetsReadback) {}
    private record Binding(String device,long owner,String mac,String stamp,long tokenNotBefore) {}
    public static class Manifest extends DeviceReportRespDTO.Firmware {
        private final Map<String,Object> fields;
        Manifest(String version,String url,Map<String,Object> fields){setVersion(version);setUrl(url);this.fields=fields;}
        @JsonAnyGetter public Map<String,Object> attributes(){return fields;}
    }
    public record Download(Path path,int size,String filename) {}
    private final JdbcTemplate db;
    private final SysParamsService params;
    private final ObjectMapper json;
    private final Path directory;
    private final String publicBase,key;
    private final boolean development;
    private final boolean usbBootstrapEnabled;
    private Esp32GatewayService gatewayAuthorization;
    @Autowired void setGatewayAuthorization(Esp32GatewayService gateway){this.gatewayAuthorization=gateway;}
    public Esp32FirmwareService(JdbcTemplate db,SysParamsService params,ObjectMapper json,Environment env)throws IOException {
        this.db=db;this.params=params;this.json=json;
        directory=Path.of(env.getProperty("esp32.ota.directory","./data/esp32-firmware")).toAbsolutePath().normalize();
        Files.createDirectories(directory);
        publicBase=env.getProperty("esp32.ota.public-base-url","").replaceAll("/+$","");
        key=env.getProperty("esp32.ota.download-key","");
        URI address;try{address=URI.create(publicBase);}catch(Exception e){throw new IllegalStateException("ESP32 firmware public base URL required");}
        boolean loopback=address.getHost()!=null&&Set.of("127.0.0.1","localhost","[::1]","::1").contains(address.getHost());
        boolean developmentLan=privateV4(address.getHost())&&env.getProperty("esp32.ota.allow-lan-http",Boolean.class,false);
        development=env.getProperty("esp32.ota.development-only",Boolean.class,"http".equals(address.getScheme()));
        usbBootstrapEnabled=env.getProperty("esp32.ota.usb-bootstrap-enabled",Boolean.class,false);
        if(address.getHost()==null||address.getRawQuery()!=null||address.getRawFragment()!=null||address.getRawUserInfo()!=null
                ||!("https".equals(address.getScheme())||("http".equals(address.getScheme())&&(developmentLan||loopback&&env.getProperty("esp32.ota.allow-loopback-http",Boolean.class,false))))
                ||("http".equals(address.getScheme())&&!development)||key.length()<32||key.length()>512)throw new IllegalStateException("ESP32 firmware trusted HTTPS address and independent download key required");
    }
    private static ResponseStatusException error(HttpStatus status,String code){return new ResponseStatusException(status,code);}
    private static boolean privateV4(String host){
        if(host==null||!host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))return false;String[] p=host.split("\\.");int[] n=new int[4];for(int i=0;i<4;i++){n[i]=Integer.parseInt(p[i]);if(n[i]>255)return false;}return n[0]==10||n[0]==192&&n[1]==168||n[0]==172&&n[1]>=16&&n[1]<=31;
    }
    private static void require(boolean valid,String code){if(!valid)throw error(HttpStatus.BAD_REQUEST,code);}
    private static void identifier(String value,int max){require(value!=null&&value.length()<=max&&value.matches("[A-Za-z0-9._-]+"),"invalid_identifier");}
    private static void version(String value){require(value!=null&&value.matches("[0-9]{1,5}\\.[0-9]{1,5}\\.[0-9]{1,5}"),"invalid_version");}
    private static int compare(String a,String b){version(a);version(b);var x=a.split("\\.");var y=b.split("\\.");for(int i=0;i<3;i++){int n=Integer.compare(Integer.parseInt(x[i]),Integer.parseInt(y[i]));if(n!=0)return n;}return 0;}
    private static String id(){return UUID.randomUUID().toString().replace("-","");}
    private static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    private static String hash(String text){return sha(text.getBytes(StandardCharsets.UTF_8));}
    private static String digest(Path file)throws IOException{try{var h=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(file)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)h.update(b,0,n);}return HexFormat.of().formatHex(h.digest());}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private Path file(String hash){require(hash!=null&&hash.matches("[a-f0-9]{64}"),"invalid_sha256");Path path=directory.resolve(hash+".bin");require(!Files.isSymbolicLink(path),"invalid_artifact");return path;}
    private Path checked(Artifact a,int limit)throws IOException {
        require(a!=null&&a.size>0&&a.size<=limit,"invalid_artifact_size");var p=file(a.sha256);
        if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.size(p)!=a.size||!digest(p).equals(a.sha256))throw error(HttpStatus.CONFLICT,"artifact_mismatch");return p;
    }
    public Artifact upload(String kind,InputStream input)throws IOException {
        require(kind!=null&&Set.of("app","assets").contains(kind),"invalid_artifact_kind");int max=kind.equals("app")?0x3f0000:8*1024*1024;
        Path temporary=Files.createTempFile(directory,"incoming-",".part");int size=0;
        try {
            try(var out=Files.newOutputStream(temporary)){byte[] b=new byte[8192];int n;while((n=input.read(b))!=-1){size+=n;if(size>max)throw error(HttpStatus.PAYLOAD_TOO_LARGE,"artifact_too_large");out.write(b,0,n);}}
            require(size>0,"empty_artifact");String hash=digest(temporary);Path target=file(hash);
            if(Files.exists(target)){require(Files.size(target)==size&&digest(target).equals(hash),"artifact_conflict");}
            else try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);}catch(FileAlreadyExistsException e){require(Files.size(target)==size&&digest(target).equals(hash),"artifact_conflict");}
            return new Artifact(hash,size);
        } finally {Files.deleteIfExists(temporary);}
    }
    private void validateImage(ReleaseRequest r)throws IOException {
        byte[] bytes;try(var in=Files.newInputStream(checked(r.app,0x3f0000))){bytes=in.readNBytes(288);}
        require(bytes.length>=288&&(bytes[0]&255)==0xe9&&(bytes[12]&255)==9&&(bytes[13]&255)==0,"invalid_esp32s3_image");
        require((bytes[32]&255)==0x32&&(bytes[33]&255)==0x54&&(bytes[34]&255)==0xcd&&(bytes[35]&255)==0xab,"invalid_app_descriptor");
        require(string(bytes,48,32).equals(r.version)&&string(bytes,80,32).equals("xiaozhi"),"image_version_mismatch");
        checked(r.assets,8*1024*1024);
    }
    private static String string(byte[] b,int offset,int max){int n=0;while(n<max&&b[offset+n]!=0)n++;return new String(b,offset,n,StandardCharsets.UTF_8);}
    @Transactional public Map<String,Object> create(long actor,ReleaseRequest r)throws IOException {
        require(actor>0&&r!=null,"invalid_release");
        identifier(r.idempotencyKey,100);version(r.version);identifier(r.boardType,80);identifier(r.boardName,120);identifier(r.assetsVersion,64);identifier(r.idfVersion,40);
        require("esp32s3".equals(r.chip)&&r.minFlashSize==16*1024*1024,"unsupported_hardware");
        require(r.sourceSha!=null&&r.sourceSha.matches("[a-f0-9]{40}"),"invalid_source_sha");validateImage(r);
        if(development)require(r.boardName.endsWith("-ai-lan"),"development_variant_required");
        String fingerprint;try{fingerprint=hash(json.writeValueAsString(r));}catch(Exception e){throw error(HttpStatus.BAD_REQUEST,"invalid_release");}
        var prior=db.queryForList("SELECT * FROM esp32_firmware_release WHERE idempotency_key=?",r.idempotencyKey);
        if(!prior.isEmpty()){if(!fingerprint.equals(prior.getFirst().get("request_hash")))throw error(HttpStatus.CONFLICT,"release_key_reused");return publicRelease(prior.getFirst());}
        if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM esp32_firmware_release WHERE board_name=? AND version=?)",Boolean.class,r.boardName,r.version)))throw error(HttpStatus.CONFLICT,"version_already_exists");
        String id=id();db.update("INSERT INTO esp32_firmware_release(id,idempotency_key,request_hash,version,board_type,board_name,chip,min_flash_size,app_sha256,app_size,assets_sha256,assets_size,assets_version,source_sha,idf_version,environment,state,created_by,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'draft',?,?)",id,r.idempotencyKey,fingerprint,r.version,r.boardType,r.boardName,r.chip,r.minFlashSize,r.app.sha256,r.app.size,r.assets.sha256,r.assets.size,r.assetsVersion,r.sourceSha,r.idfVersion,development||r.boardName.endsWith("-ai-lan")?"development":"production",actor,System.currentTimeMillis());return publicRelease(release(id,false));
    }
    private Map<String,Object> release(String id,boolean lock){identifier(id,32);var rows=db.queryForList("SELECT * FROM esp32_firmware_release WHERE id=?"+(lock?" FOR UPDATE":""),id);if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"release_unavailable");return rows.getFirst();}
    private static Map<String,Object> publicRelease(Map<String,Object> r){var out=new LinkedHashMap<>(r);out.remove("request_hash");out.remove("idempotency_key");return out;}
    public List<Map<String,Object>> releases(){return db.queryForList("SELECT * FROM esp32_firmware_release ORDER BY created_at DESC,id DESC LIMIT 100").stream().map(Esp32FirmwareService::publicRelease).toList();}
    public Map<String,Object> settings(){return Map.of("public_base_url",publicBase,"development_only",development,"manifest_schema",1,"resource_update","matching_installed_assets_only");}
    @Transactional public Map<String,Object> state(String id,String state)throws IOException {
        require(state!=null&&Set.of("published","paused").contains(state),"invalid_release_state");var r=release(id,true);
        if(state.equals("published")){checked(artifact(r,"app"),0x3f0000);checked(artifact(r,"assets"),8*1024*1024);}
        db.update("UPDATE esp32_firmware_release SET state=? WHERE id=?",state,id);return publicRelease(release(id,false));
    }
    private Binding binding(String device,boolean lock) {
        deviceIdentifier(device);
        if(gatewayAuthorization!=null)gatewayAuthorization.prepareFirmwareAuthorization(device);
        var rows=db.queryForList("SELECT d.id,d.user_id,d.agent_id,d.mac_address,u.status,a.user_id AS agent_owner,COALESCE(e.revision,0) AS revision,COALESCE(e.token_not_before,0) AS cutoff FROM ai_device d JOIN sys_user u ON u.id=d.user_id JOIN ai_agent a ON a.id=d.agent_id LEFT JOIN esp32_authorization e ON BINARY e.device_id=BINARY d.id WHERE d.id=?"+(lock?" FOR UPDATE":""),device);
        if(rows.size()!=1)throw error(HttpStatus.NOT_FOUND,"device_unavailable");var r=rows.getFirst();long owner=((Number)r.get("user_id")).longValue();
        if(((Number)r.get("status")).intValue()!=1||owner!=((Number)r.get("agent_owner")).longValue())throw error(HttpStatus.FORBIDDEN,"device_authorization_revoked");
        return new Binding(device,owner,(String)r.get("mac_address"),hash(device+"|"+owner+"|"+r.get("agent_id")+"|"+r.get("mac_address")+"|"+r.get("revision")),((Number)r.get("cutoff")).longValue());
    }
    private Binding credentials(String mac,String client,String authorization){
        require(mac!=null&&mac.length()<=50&&client!=null&&client.length()<=128,"invalid_device_identity");
        var rows=db.queryForList("SELECT id FROM ai_device WHERE mac_address=?",mac);if(rows.size()!=1)throw error(HttpStatus.UNAUTHORIZED,"device_authentication_required");
        Binding b=binding((String)rows.getFirst().get("id"),false);
        if(authorization==null||!authorization.startsWith("Bearer "))throw error(HttpStatus.UNAUTHORIZED,"device_authentication_required");
        long issued=Esp32GatewayService.verifyToken(authorization.substring(7),client,mac,params.getValue("server.secret",false));
        if(issued<b.tokenNotBefore)throw error(HttpStatus.UNAUTHORIZED,"credential_revoked");return b;
    }
    @Transactional public Map<String,Object> assign(long actor,String releaseId,Assignment request){
        require(actor>0&&request!=null,"invalid_assignment");require(request.updatePolicy!=null&&Set.of("automatic","manual").contains(request.updatePolicy),"invalid_update_policy");require(request.validSeconds>=60&&request.validSeconds<=30*86400,"invalid_assignment_lifetime");
        Binding b=binding(request.deviceId,true);var release=release(releaseId,true);if(!"published".equals(release.get("state")))throw error(HttpStatus.CONFLICT,"release_not_published");
        db.update("UPDATE esp32_firmware_assignment SET enabled=FALSE WHERE device_id=? AND enabled=TRUE",request.deviceId);
        String id=id();long now=System.currentTimeMillis();db.update("INSERT INTO esp32_firmware_assignment(id,release_id,device_id,owner_id,binding_stamp,update_policy,allow_downgrade,expires_at,created_by,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",id,releaseId,b.device,b.owner,b.stamp,request.updatePolicy,request.allowDowngrade,now+request.validSeconds*1000L,actor,now);
        return db.queryForMap("SELECT * FROM esp32_firmware_assignment WHERE id=?",id);
    }
    private static void deviceIdentifier(String device){require(device!=null&&device.length()<=32&&device.matches("[A-Za-z0-9._:-]+"),"invalid_device_identifier");}
    public List<Map<String,Object>> assignments(String device){deviceIdentifier(device);return db.queryForList("SELECT * FROM esp32_firmware_assignment WHERE device_id=? ORDER BY created_at DESC,id DESC LIMIT 100",device);}
    @Transactional public void pauseAssignment(String id){identifier(id,32);require(db.update("UPDATE esp32_firmware_assignment SET enabled=FALSE WHERE id=?",id)==1,"assignment_unavailable");}
    public List<Map<String,Object>> events(String id){identifier(id,32);return db.queryForList("SELECT * FROM esp32_firmware_event WHERE assignment_id=? ORDER BY created_at,id LIMIT 100",id);}
    /** First-board maintenance only: grant boot confirmation after an operator verifies inactive-slot readback. */
    @Transactional public Map<String,Object> usbBootstrap(long actor,String assignmentId,UsbBootstrap proof)throws IOException {
        require(actor>0&&proof!=null,"invalid_usb_bootstrap");
        if(!development||!usbBootstrapEnabled)throw error(HttpStatus.FORBIDDEN,"usb_bootstrap_disabled");
        identifier(assignmentId,32);version(proof.previousVersion);
        require(proof.factoryMac!=null&&proof.factoryMac.matches("[0-9a-f]{2}(:[0-9a-f]{2}){5}")&&proof.softwareMac!=null,"invalid_usb_identity");
        require(proof.targetSlot==1&&proof.nvsPreserved&&proof.oldApplicationPreserved,"usb_preservation_required");
        for(String digest:List.of(proof.snapshotSha256==null?"":proof.snapshotSha256,proof.partitionSha256==null?"":proof.partitionSha256,
                proof.originalAppSha256==null?"":proof.originalAppSha256,proof.nvsBeforeSha256==null?"":proof.nvsBeforeSha256,proof.nvsAfterSha256==null?"":proof.nvsAfterSha256))
            require(digest.matches("[a-f0-9]{64}")&&!digest.equals("0".repeat(64)),"invalid_usb_digest");
        require("4811619cacae08ef2e0e71b7220c6033a346ca5da7ca179082408c963ef530b5".equals(proof.partitionSha256),"unsupported_usb_partition_table");
        var rows=db.queryForList("SELECT device_id FROM esp32_firmware_assignment WHERE id=?",assignmentId);
        if(rows.size()!=1)throw error(HttpStatus.NOT_FOUND,"assignment_unavailable");
        Binding b=binding((String)rows.getFirst().get("device_id"),true);var a=assignment(b,true);
        if(a==null||!assignmentId.equals(a.get("id")))throw error(HttpStatus.CONFLICT,"stale_usb_assignment");
        var r=release((String)a.get("release_id"),false);
        require("published".equals(r.get("state"))&&"development".equals(r.get("environment")),"usb_release_unavailable");
        require(b.mac.equalsIgnoreCase(proof.softwareMac)&&compare((String)r.get("version"),proof.previousVersion)>0,"usb_identity_or_version_mismatch");
        require(artifact(r,"app").equals(proof.appReadback)&&artifact(r,"assets").equals(proof.assetsReadback),"usb_readback_mismatch");
        checked(artifact(r,"app"),0x3f0000);checked(artifact(r,"assets"),8*1024*1024);
        String encoded=json.writeValueAsString(proof),fingerprint=hash(encoded),eventKey="usb-verified:"+fingerprint;
        Path evidence=directory.resolve("usb-bootstrap-"+assignmentId+".json");
        String prior=Files.exists(evidence)?Files.readString(evidence):null;
        if(prior!=null&&!json.readTree(prior).get("evidence_sha256").asText().equals(fingerprint))throw error(HttpStatus.CONFLICT,"usb_bootstrap_evidence_reused");
        boolean recorded=Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM esp32_firmware_event WHERE assignment_id=? AND event_key=?)",Boolean.class,assignmentId,eventKey));
        if(!recorded){
            require(Set.of("assigned","offered").contains(a.get("last_state")),"usb_bootstrap_out_of_order");
            if(prior==null){
                String record=json.writeValueAsString(Map.of("source","administrator_usb_readback","actor",actor,"assignment_id",assignmentId,"evidence_sha256",fingerprint,"proof",proof));
                try{Files.writeString(evidence,record,StandardOpenOption.CREATE_NEW);try{Files.setPosixFilePermissions(evidence,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}}
                catch(FileAlreadyExistsException conflict){throw error(HttpStatus.CONFLICT,"usb_bootstrap_evidence_reused");}
            }
            event(a,"offered:"+r.get("id"),"offered",(String)r.get("version"));
            event(a,eventKey,"verified",(String)r.get("version"));
        }
        String state=db.queryForObject("SELECT last_state FROM esp32_firmware_assignment WHERE id=?",String.class,assignmentId);
        return Map.of("assignment_id",assignmentId,"release_id",r.get("id"),"version",r.get("version"),"state",state,"evidence_sha256",fingerprint,"source","administrator_usb_readback");
    }
    private static Artifact artifact(Map<String,Object> r,String kind){return new Artifact((String)r.get(kind+"_sha256"),((Number)r.get(kind+"_size")).intValue());}
    private Map<String,Object> assignment(Binding b){return assignment(b,false);}
    private Map<String,Object> assignment(Binding b,boolean lock){var rows=db.queryForList("SELECT * FROM esp32_firmware_assignment WHERE device_id=? AND enabled=TRUE AND expires_at>? ORDER BY created_at DESC,id DESC LIMIT 1"+(lock?" FOR UPDATE":""),b.device,System.currentTimeMillis());if(rows.isEmpty())return null;var a=rows.getFirst();return b.stamp.equals(a.get("binding_stamp"))&&b.owner==((Number)a.get("owner_id")).longValue()?a:null;}
    /** OTA report fields are compatibility claims, not hardware attestation. */
    @Transactional public DeviceReportRespDTO.Firmware manifest(String mac,String client,DeviceReportReqDTO report)throws IOException {
        if(report==null||report.getBoard()==null||report.getApplication()==null)return null;
        var devices=db.queryForList("SELECT id FROM ai_device WHERE mac_address=?",mac);if(devices.size()!=1)return null;
        Binding b;try{b=binding((String)devices.getFirst().get("id"),false);}catch(ResponseStatusException e){return null;}
        var a=assignment(b,true);if(a==null||Set.of("healthy","failed","rolled_back").contains(a.get("last_state")))return null;
        var r=release((String)a.get("release_id"),false);if(!"published".equals(r.get("state")))return null;
        if(!Objects.equals(r.get("board_type"),report.getBoard().getType())||!Objects.equals(r.get("board_name"),report.getBoard().getName())||report.getFlashSize()==null||report.getFlashSize()<((Number)r.get("min_flash_size")).intValue())return null;
        String current=report.getApplication().getVersion();int order;try{order=compare((String)r.get("version"),current);}catch(ResponseStatusException e){return null;}
        boolean downgrade=Boolean.TRUE.equals(a.get("allow_downgrade"))||a.get("allow_downgrade") instanceof Number n&&n.intValue()==1;if(order<0&&!downgrade)return null;
        checked(artifact(r,"app"),0x3f0000);checked(artifact(r,"assets"),8*1024*1024);
        String releaseId=(String)r.get("id");Map<String,Object> fields=new LinkedHashMap<>();fields.put("schema_version",1);fields.put("release_id",releaseId);
        for(String f:List.of("board_type","board_name","chip"))fields.put(f,r.get(f));fields.put("min_flash_size",((Number)r.get("min_flash_size")).intValue());
        fields.put("size",((Number)r.get("app_size")).intValue());fields.put("sha256",r.get("app_sha256"));fields.put("update_policy",a.get("update_policy"));if(order<0)fields.put("force",1);
        fields.put("development_only","development".equals(r.get("environment")));
        fields.put("assets",Map.of("version",r.get("assets_version"),"sha256",r.get("assets_sha256"),"size",((Number)r.get("assets_size")).intValue(),"url",ticketUrl(r,a,b,client,"assets"),"required",false));
        event(a,"offered:"+releaseId,"offered",(String)r.get("version"));return new Manifest((String)r.get("version"),ticketUrl(r,a,b,client,"app"),fields);
    }
    /** Enrolled controlled devices never fall back to a generic board-type image. */
    public boolean enrolled(String mac){return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM esp32_firmware_assignment f JOIN ai_device d ON BINARY d.id=BINARY f.device_id WHERE d.mac_address=?)",Boolean.class,mac));}
    private String sign(String payload){try{Mac h=Mac.getInstance("HmacSHA256");h.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return Base64.getUrlEncoder().withoutPadding().encodeToString(h.doFinal(payload.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private String ticketUrl(Map<String,Object> r,Map<String,Object> a,Binding b,String client,String kind){
        require(client!=null&&client.length()<=128&&!client.contains("|"),"invalid_client");String expiry=Long.toString(System.currentTimeMillis()/1000+600);
        String plain=String.join("|",(String)a.get("id"),(String)r.get("id"),b.device,b.stamp,client,kind,expiry);
        String payload=Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8));return publicBase+"/ota/esp32/releases/"+r.get("id")+"/"+kind+"?ticket="+payload+"."+sign(payload);
    }
    public Download download(String releaseId,String kind,String ticket)throws IOException {
        identifier(releaseId,32);require(kind!=null&&Set.of("app","assets").contains(kind),"invalid_artifact_kind");
        if(ticket==null||ticket.length()>2048)throw error(HttpStatus.UNAUTHORIZED,"invalid_download_ticket");String[] parts=ticket.split("\\.",-1);
        if(parts.length!=2||!MessageDigest.isEqual(sign(parts[0]).getBytes(StandardCharsets.UTF_8),parts[1].getBytes(StandardCharsets.UTF_8)))throw error(HttpStatus.UNAUTHORIZED,"invalid_download_ticket");
        String[] p;try{p=new String(Base64.getUrlDecoder().decode(parts[0]),StandardCharsets.UTF_8).split("\\|",-1);if(p.length!=7||Long.parseLong(p[6])<System.currentTimeMillis()/1000||Long.parseLong(p[6])>System.currentTimeMillis()/1000+600)throw new IllegalArgumentException();}catch(Exception e){throw error(HttpStatus.UNAUTHORIZED,"invalid_download_ticket");}
        if(!releaseId.equals(p[1])||!kind.equals(p[5]))throw error(HttpStatus.UNAUTHORIZED,"wrong_download_purpose");Binding b=binding(p[2],false);var a=assignment(b);
        if(a==null||!p[0].equals(a.get("id"))||!b.stamp.equals(p[3]))throw error(HttpStatus.FORBIDDEN,"download_authorization_revoked");
        if(Set.of("healthy","failed","rolled_back").contains(a.get("last_state")))throw error(HttpStatus.FORBIDDEN,"firmware_assignment_terminal");
        var r=release(releaseId,false);if(!releaseId.equals(a.get("release_id"))||!"published".equals(r.get("state")))throw error(HttpStatus.FORBIDDEN,"release_paused");
        Artifact artifact=artifact(r,kind);return new Download(checked(artifact,kind.equals("app")?0x3f0000:8*1024*1024),artifact.size,"esp32-"+r.get("version")+"-"+kind+".bin");
    }
    @Transactional public void receipt(String mac,String client,String authorization,Receipt r){
        require(r!=null,"invalid_receipt");Binding b=credentials(mac,client,authorization);identifier(r.release_id,32);identifier(r.event_key,100);version(r.version);
        require(r.status!=null&&Set.of("downloading","verified","booted","healthy","failed","rolled_back").contains(r.status),"invalid_receipt_state");var a=assignment(b,true);
        if(a==null||!r.release_id.equals(a.get("release_id")))throw error(HttpStatus.CONFLICT,"stale_firmware_receipt");var release=release(r.release_id,false);
        if(!Set.of("failed","rolled_back").contains(r.status)&&!r.version.equals(release.get("version")))throw error(HttpStatus.CONFLICT,"receipt_version_mismatch");
        var prior=db.queryForList("SELECT state,version FROM esp32_firmware_event WHERE assignment_id=? AND event_key=?",a.get("id"),r.event_key);
        if(!prior.isEmpty()){if(!r.status.equals(prior.getFirst().get("state"))||!r.version.equals(prior.getFirst().get("version")))throw error(HttpStatus.CONFLICT,"receipt_key_reused");return;}
        if(Set.of("healthy","failed","rolled_back").contains(a.get("last_state")))throw error(HttpStatus.CONFLICT,"firmware_assignment_terminal");
        var predecessor=Map.of("downloading","offered","verified","downloading","booted","verified","healthy","booted");
        if(predecessor.containsKey(r.status)&&!predecessor.get(r.status).equals(a.get("last_state")))throw error(HttpStatus.CONFLICT,"firmware_receipt_out_of_order");
        event(a,r.event_key,r.status,r.version);
    }
    private void event(Map<String,Object> a,String key,String state,String version){
        if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM esp32_firmware_event WHERE assignment_id=? AND event_key=?)",Boolean.class,a.get("id"),key)))return;
        if(db.queryForObject("SELECT COUNT(*) FROM esp32_firmware_event WHERE assignment_id=?",Integer.class,a.get("id"))>=32)throw error(HttpStatus.TOO_MANY_REQUESTS,"firmware_receipt_limit");
        int n=db.update("INSERT IGNORE INTO esp32_firmware_event(id,assignment_id,event_key,state,version,created_at) VALUES(?,?,?,?,?,?)",id(),a.get("id"),key,state,version,System.currentTimeMillis());if(n>0)db.update("UPDATE esp32_firmware_assignment SET last_state=? WHERE id=?",state,a.get("id"));
    }
}
