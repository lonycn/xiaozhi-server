package xiaozhi.modules.externalidentity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import xiaozhi.modules.externalidentity.ExternalModels.*;
import xiaozhi.modules.security.password.PasswordUtils;
import xiaozhi.common.jdbc.TimestampColumnMapRowMapper;

@Service
@ConditionalOnProperty(prefix="features",name="external-identity-enabled",havingValue="true")
public class ExternalIdentityService {
    public static final String ISSUE="/api/v1/external/identity/issue",REVOKE="/api/v1/external/identity/revoke";
    public static final String ATTRIBUTE="xiaozhi.external.principal";
    private static final SecureRandom RANDOM=new SecureRandom();
    private final JdbcTemplate db;private final ObjectMapper json;private final TransactionTemplate tx;
    private final ExternalSettings settings;private final Clock clock;
    @Autowired public ExternalIdentityService(JdbcTemplate db,ObjectMapper json,PlatformTransactionManager manager,ExternalSettings settings) {
        this(db,json,manager,settings,Clock.systemUTC());
    }
    public ExternalIdentityService(JdbcTemplate db,ObjectMapper json,PlatformTransactionManager manager,ExternalSettings settings,Clock clock) {
        this.db=db;this.json=json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.tx=new TransactionTemplate(manager);this.settings=settings;this.clock=clock;
    }
    public static boolean externalToken(String value){return value!=null && value.startsWith("xext_");}
    public static String signature(byte[] secret,String path,String timestamp,String nonce,byte[] body) {
        try {
            String canonical=String.join("\n","POST",path,timestamp,nonce,hash(body));
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch(Exception ignored){throw new ExternalProblem(503,"IDENTITY_UNAVAILABLE");}
    }
    public Verified verify(String path,byte[] raw,Map<String,List<String>> headers) {
        if(path==null || path.length()>256 || !(canonicalOperation(path,ISSUE)||canonicalOperation(path,REVOKE)) || raw==null || raw.length>32768)throw problem(400,"INVALID_SIGNED_REQUEST");
        var keys=settings.keys();List<String> names=List.of("x-external-key-id","x-external-timestamp","x-external-nonce","x-external-signature");
        for(String name:names)if(headers.get(name)==null || headers.get(name).size()!=1)throw problem(401,"SIGNATURE_INVALID");
        String keyId=headers.get(names.get(0)).getFirst(),timestamp=headers.get(names.get(1)).getFirst(),
            nonce=headers.get(names.get(2)).getFirst(),supplied=headers.get(names.get(3)).getFirst();
        var key=keys.get(keyId);if(key==null)throw problem(401,"KEY_UNKNOWN");
        if(!timestamp.matches("[0-9]{1,12}") || !nonce.matches("[a-f0-9]{32}") || !supplied.matches("[a-f0-9]{64}"))throw problem(401,"SIGNATURE_INVALID");
        String expected=signature(key.secret(),path,timestamp,nonce,raw);
        if(!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),supplied.getBytes(StandardCharsets.US_ASCII)))throw problem(401,"SIGNATURE_INVALID");
        if(Math.abs(clock.instant().getEpochSecond()-Long.parseLong(timestamp))>60)throw problem(401,"SIGNATURE_EXPIRED");
        return new Verified(keyId,key.issuer(),nonce,path,hash(raw));
    }
    private static boolean canonicalOperation(String fullPath,String route) {
        if(fullPath==null || !fullPath.endsWith(route))return false;
        String context=fullPath.substring(0,fullPath.length()-route.length());
        return context.isEmpty() || context.matches("(?:/[A-Za-z0-9_-]+)+");
    }
    private JsonNode request(byte[] raw,Set<String> fields) {
        try {JsonNode value=json.readTree(raw);exact(value,fields);return value;}
        catch(ExternalProblem failure){throw failure;}catch(Exception ignored){throw problem(400,"INVALID_EXTERNAL_REQUEST");}
    }
    private void verified(Verified signed,String path,byte[] body) {
        var keys=settings.keys();
        if(signed==null || !canonicalOperation(signed.path(),path) || !hash(body).equals(signed.bodyHash())
                || !keys.containsKey(signed.keyId()) || !keys.get(signed.keyId()).issuer().equals(signed.issuer()))throw problem(401,"SIGNATURE_INVALID");
    }
    public Issued issue(Verified signed,byte[] raw) {
        verified(signed,ISSUE,raw);
        JsonNode input=request(raw,Set.of("external_subject","source_session_id","source_expires_at","display_name","tenant","scope"));
        String subject=text(input,"external_subject",100),source=text(input,"source_session_id",100),name=text(input,"display_name",80);
        long sourceExpiry=integer(input.get("source_expires_at"));long now=clock.instant().getEpochSecond();
        if(sourceExpiry<=now || sourceExpiry>now+86400)throw problem(400,"INVALID_SOURCE_EXPIRY");
        Tenant tenant=tenant(input.get("tenant"));Scope scope=scope(input.get("scope"));
        return tx.execute(status->{
            nonce(signed);Map<String,Object> origin=lockSource(signed.issuer(),subject,source,Instant.ofEpochSecond(sourceExpiry));
            sourceActive(origin);
            db.update("UPDATE external_source_session SET expires_at=LEAST(expires_at,?) WHERE id=?",ts(Instant.ofEpochSecond(sourceExpiry)),origin.get("id"));
            origin=source(number(origin,"id"),true);sourceActive(origin);
            db.update("INSERT INTO external_identity(issuer,external_subject) VALUES(?,?) ON DUPLICATE KEY UPDATE id=id",signed.issuer(),subject);
            long identity=db.queryForObject("SELECT id FROM external_identity WHERE issuer=? AND external_subject=? FOR UPDATE",Long.class,signed.issuer(),subject);
            var existing=rows("SELECT * FROM external_tenant_principal WHERE identity_id=? AND tenant_type=? AND tenant_id=? FOR UPDATE",identity,tenant.type(),tenant.id());
            long principal;
            if(existing.isEmpty()) {
                long actor=IdWorker.getId();
                db.update("INSERT INTO sys_user(id,username,password,super_admin,status,create_date) VALUES(?,?,?,0,1,?)",actor,"ext_"+UUID.randomUUID().toString().replace("-",""),PasswordUtils.encode(random("")),ts(clock.instant()));
                db.update("INSERT INTO external_tenant_principal(identity_id,tenant_type,tenant_id,user_id,display_name) VALUES(?,?,?,?,?)",identity,tenant.type(),tenant.id(),actor,name);
                principal=db.queryForObject("SELECT id FROM external_tenant_principal WHERE identity_id=? AND tenant_type=? AND tenant_id=?",Long.class,identity,tenant.type(),tenant.id());
            } else {principal=number(existing.getFirst(),"id");db.update("UPDATE external_tenant_principal SET display_name=? WHERE id=?",name,principal);}
            var p=principal(principal);account(number(p,"user_id"),true);
            String token=random("xzi_ticket_");Instant expiry=min(clock.instant().plusSeconds(60),instant(origin.get("expires_at")));
            db.update("INSERT INTO external_launch_ticket(token_hash,source_id,principal_id,scope_json,expires_at) VALUES(?,?,?,?,?)",hash(token),origin.get("id"),principal,encode(scope),ts(expiry));
            return new Issued(token,(int)Math.max(0,Duration.between(clock.instant(),expiry).getSeconds()));
        });
    }
    public Exchanged exchange(String ticket) {
        settings.keys();if(ticket==null || !ticket.matches("xzi_ticket_[a-f0-9]{64}"))throw problem(400,"TICKET_INVALID");
        var hints=rows("SELECT source_id FROM external_launch_ticket WHERE token_hash=?",hash(ticket));
        if(hints.isEmpty())throw problem(400,"TICKET_INVALID");
        return tx.execute(status->{
            var origin=source(number(hints.getFirst(),"source_id"),true);sourceActive(origin);
            var rows=rows("SELECT * FROM external_launch_ticket WHERE token_hash=? FOR UPDATE",hash(ticket));
            if(rows.isEmpty())throw problem(400,"TICKET_INVALID");var row=rows.getFirst();
            if(row.get("revoked_at")!=null)throw problem(401,"SOURCE_SESSION_REVOKED");
            if(row.get("consumed_at")!=null)throw problem(409,"TICKET_USED");
            if(!instant(row.get("expires_at")).isAfter(clock.instant()))throw problem(410,"TICKET_EXPIRED");
            var p=principal(number(row,"principal_id"),true);long actor=number(p,"user_id");account(actor,true);
            String token=random("xext_");Instant expiry=min(clock.instant().plusSeconds(settings.tokenSeconds()),instant(origin.get("expires_at")));
            db.update("UPDATE external_launch_ticket SET consumed_at=? WHERE token_hash=?",ts(clock.instant()),hash(ticket));
            db.update("INSERT INTO external_access_token(token_hash,source_id,principal_id,scope_json,expires_at) VALUES(?,?,?,?,?)",hash(token),origin.get("id"),p.get("id"),row.get("scope_json"),ts(expiry));
            return new Exchanged(token,(int)Math.max(0,Duration.between(clock.instant(),expiry).getSeconds()),Long.toString(actor),
                new Tenant((String)p.get("tenant_type"),(String)p.get("tenant_id")),scope(decode((String)row.get("scope_json"))));
        });
    }
    public void revoke(Verified signed,byte[] raw) {
        verified(signed,REVOKE,raw);JsonNode input=request(raw,Set.of("external_subject","source_session_id"));
        String subject=text(input,"external_subject",100),session=text(input,"source_session_id",100);
        tx.executeWithoutResult(status->{
            nonce(signed);var row=lockSource(signed.issuer(),subject,session,clock.instant());
            db.update("UPDATE external_source_session SET revoked_at=COALESCE(revoked_at,?) WHERE id=?",ts(clock.instant()),row.get("id"));
            db.update("UPDATE external_launch_ticket SET revoked_at=COALESCE(revoked_at,?) WHERE source_id=?",ts(clock.instant()),row.get("id"));
            db.update("UPDATE external_access_token SET revoked_at=COALESCE(revoked_at,?) WHERE source_id=?",ts(clock.instant()),row.get("id"));
        });
    }
    private void nonce(Verified signed) {
        try {db.update("INSERT INTO external_signature_nonce(key_id,nonce,expires_at) VALUES(?,?,?)",signed.keyId(),signed.nonce(),ts(clock.instant().plusSeconds(120)));}
        catch(DuplicateKeyException ignored){throw problem(409,"NONCE_REPLAY");}
    }
    private Map<String,Object> lockSource(String issuer,String subject,String session,Instant expiry) {
        db.update("INSERT INTO external_source_session(issuer,external_subject,source_session_id,expires_at) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE id=id",issuer,subject,session,ts(expiry));
        return one("SELECT * FROM external_source_session WHERE issuer=? AND external_subject=? AND source_session_id=? FOR UPDATE",issuer,subject,session);
    }
    private Map<String,Object> source(long id,boolean lock) {return one("SELECT * FROM external_source_session WHERE id=?"+(lock?" FOR UPDATE":""),id);}
    private Map<String,Object> principal(long id){return principal(id,false);}
    private Map<String,Object> principal(long id,boolean lock){return one("SELECT * FROM external_tenant_principal WHERE id=?"+(lock?" FOR UPDATE":""),id);}
    private void sourceActive(Map<String,Object> source) {
        if(!settings.issuerActive((String)source.get("issuer")))throw problem(401,"ISSUER_REVOKED");
        if(source.get("revoked_at")!=null)throw problem(401,"SOURCE_SESSION_REVOKED");
        if(!instant(source.get("expires_at")).isAfter(clock.instant()))throw problem(401,"SOURCE_SESSION_EXPIRED");
    }
    private void account(long actor,boolean lock) {
        var rows=rows("SELECT CAST(status AS SIGNED) AS status,CAST(super_admin AS SIGNED) AS super_admin FROM sys_user WHERE id=?"+(lock?" FOR UPDATE":""),actor);
        if(rows.isEmpty() || rows.getFirst().get("status")==null || ((Number)rows.getFirst().get("status")).intValue()!=1)throw problem(401,"ACCOUNT_DISABLED");
        if(rows.getFirst().get("super_admin")==null || ((Number)rows.getFirst().get("super_admin")).intValue()!=0)throw problem(403,"IDENTITY_PRIVILEGE_CONFLICT");
    }
    public boolean mediaEnabled(){return settings.mediaEnabled();}
    public Principal authenticate(String token) {
        if(token==null || !token.matches("xext_[a-f0-9]{64}"))throw problem(401,"EXTERNAL_TOKEN_INVALID");
        return authenticateHash(hash(token));
    }
    public Principal authenticateHash(String digest) {
        settings.keys();
        var rows=rows("SELECT * FROM external_access_token WHERE token_hash=?",digest);
        if(rows.isEmpty())throw problem(401,"EXTERNAL_TOKEN_INVALID");var row=rows.getFirst();
        if(row.get("revoked_at")!=null || !instant(row.get("expires_at")).isAfter(clock.instant()))throw problem(401,"EXTERNAL_TOKEN_REVOKED_OR_EXPIRED");
        var origin=source(number(row,"source_id"),false);sourceActive(origin);
        var p=principal(number(row,"principal_id"));long actor=number(p,"user_id");account(actor,false);
        return new Principal(number(row,"id"),digest,number(origin,"id"),actor,(String)origin.get("issuer"),(String)origin.get("external_subject"),
            new Tenant((String)p.get("tenant_type"),(String)p.get("tenant_id")),scope(decode((String)row.get("scope_json"))),instant(row.get("expires_at")));
    }
    public void logout(Principal actor){db.update("UPDATE external_access_token SET revoked_at=COALESCE(revoked_at,?) WHERE id=?",ts(clock.instant()),actor.tokenId());}
    public Runnable longLivedGuard(HttpServletRequest request) {
        Object value=request.getAttribute(ATTRIBUTE);
        if(!(value instanceof Principal principal))return ()->{};
        return ()->authenticateHash(principal.tokenHash());
    }
    public Principal required(HttpServletRequest request) {
        if(!(request.getAttribute(ATTRIBUTE) instanceof Principal actor))throw problem(401,"EXTERNAL_BEARER_REQUIRED");
        return authenticateHash(actor.tokenHash());
    }
    public Map<String,Object> view(Principal actor) {
        return Map.of("user_id",Long.toString(actor.userId()),"issuer",actor.issuer(),"external_subject",actor.subject(),
                "tenant",actor.tenant(),"scope",actor.scope(),"expires_at",actor.expiresAt(),"rtc_enabled",mediaEnabled(),"business_connected",false);
    }
    private Tenant tenant(JsonNode node) {
        exact(node,Set.of("type","id"));String type=text(node,"type",16),id=text(node,"id",19);
        if(!Set.of("customer","company").contains(type) || !id.matches("[1-9][0-9]{0,18}"))throw problem(400,"INVALID_TENANT");
        try {if(Long.parseLong(id)<1)throw new NumberFormatException();}catch(NumberFormatException ignored){throw problem(400,"INVALID_TENANT");}
        return new Tenant(type,id);
    }
    private Scope scope(JsonNode node) {exact(node,Set.of("project_ids","device_ids"));return new Scope(ids(node.get("project_ids")),ids(node.get("device_ids")));}
    private List<String> ids(JsonNode node) {
        if(node==null || !node.isArray() || node.size()>128)throw problem(400,"INVALID_SCOPE");Set<String> values=new LinkedHashSet<>();
        for(JsonNode item:node){if(!item.isTextual() || !item.asText().matches("[A-Za-z0-9_.:-]{1,100}") || !values.add(item.asText()))throw problem(400,"INVALID_SCOPE");}
        return List.copyOf(values);
    }
    static void exact(JsonNode node,Set<String> fields) {
        if(node==null || !node.isObject() || node.size()!=fields.size())throw problem(400,"INVALID_EXTERNAL_REQUEST");
        var names=node.fieldNames();while(names.hasNext())if(!fields.contains(names.next()))throw problem(400,"INVALID_EXTERNAL_REQUEST");
    }
    static String text(JsonNode node,String field,int max) {
        JsonNode value=node.get(field);
        if(value==null || !value.isTextual() || value.asText().isBlank() || value.asText().codePointCount(0,value.asText().length())>max
                || value.asText().codePoints().anyMatch(c->c<32 || c==127 || c>=0xD800&&c<=0xDFFF))throw problem(400,"INVALID_EXTERNAL_REQUEST");
        return value.asText();
    }
    static long integer(JsonNode node) {
        if(node!=null && node.isIntegralNumber() && node.canConvertToLong())return node.longValue();
        if(node!=null && node.isTextual() && node.asText().matches("0|[1-9][0-9]{0,18}"))try{return Long.parseLong(node.asText());}catch(NumberFormatException ignored){}
        throw problem(400,"INVALID_EXTERNAL_REQUEST");
    }
    static String random(String prefix){byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);return prefix+HexFormat.of().formatHex(bytes);}
    static String hash(String value){return hash(value.getBytes(StandardCharsets.UTF_8));}
    static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception ignored){throw problem(503,"IDENTITY_UNAVAILABLE");}}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception ignored){throw problem(503,"IDENTITY_UNAVAILABLE");}}
    private JsonNode decode(String value){try{return json.readTree(value);}catch(Exception ignored){throw problem(503,"IDENTITY_UNAVAILABLE");}}
    static Timestamp ts(Instant value){return Timestamp.from(value);}
    static Instant instant(Object value){if(value instanceof Timestamp stamp)return stamp.toInstant();if(value instanceof Instant instant)return instant;throw problem(503,"IDENTITY_TIMESTAMP_UNAVAILABLE");}
    private List<Map<String,Object>> rows(String sql,Object... args){return db.query(sql,new TimestampColumnMapRowMapper(),args);}
    private Map<String,Object> one(String sql,Object... args){var rows=rows(sql,args);if(rows.size()!=1)throw problem(401,"EXTERNAL_RESOURCE_NOT_FOUND");return rows.getFirst();}
    static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    static Instant min(Instant a,Instant b){return a.isBefore(b)?a:b;}
    static ExternalProblem problem(int code,String message){return new ExternalProblem(code,message);}
}
