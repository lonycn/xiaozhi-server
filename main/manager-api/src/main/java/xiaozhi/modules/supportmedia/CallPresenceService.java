package xiaozhi.modules.supportmedia;

import static xiaozhi.modules.supportmedia.MediaStore.*;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import xiaozhi.modules.externalidentity.ExternalMediaAuthority;
import xiaozhi.modules.externalidentity.ExternalModels.Principal;
import xiaozhi.modules.externalidentity.ExternalProblem;
import xiaozhi.modules.supportmedia.CallPresenceCache.Lease;

@Service
@ConditionalOnProperty(prefix="features",name={"external-identity-enabled","external-realtime-media-enabled","conversation-enabled","support-handoff-enabled","support-media-enabled"},havingValue="true")
public class CallPresenceService {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Update(String conversationId,@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=MediaModels.StrictBoolean.class) Boolean available,@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=MediaModels.StrictBoolean.class) Boolean video){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Availability(String conversationId,String status,boolean audio,boolean video,@com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) Instant expiresAt){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Receipt(String conversationId,String status,boolean audio,boolean video,@com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) Instant expiresAt,int heartbeatSeconds){}
    private final MediaStore store;
    private final MediaPolicy policy;
    private final ExternalMediaAuthority authority;
    private final CallPresenceCache cache;
    private final int ttlSeconds;
    public CallPresenceService(MediaStore store,MediaPolicy policy,ExternalMediaAuthority authority,CallPresenceCache cache,Environment environment){
        this.store=store;this.policy=policy;this.authority=authority;this.cache=cache;
        ttlSeconds=Math.max(10,Math.min(60,environment.getProperty("support-media.call-presence-seconds",Integer.class,15)));
    }
    public Receipt publish(Principal caller,Update body){
        if(caller==null)throw problem(401,"AUTHENTICATION_REQUIRED");
        if(body==null || body.conversationId()==null || !body.conversationId().matches("[a-f0-9]{32}") || body.available()==null || body.video()==null)
            throw problem(400,"INVALID_CALL_PRESENCE");
        Instant sourceExpiry=authority.validate(caller.sourceId(),caller.tokenId(),caller.userId());
        return store.tx(()->{
            policy.lockUsers(List.of(caller.userId()));
            // Persistent issuer/subject/tenant ownership, never a client-supplied customer or source.
            var owned=store.rows("""
                SELECT c.id FROM ai_conversation c
                JOIN external_access_token t ON t.id=? AND t.source_id=?
                JOIN external_source_session s ON s.id=t.source_id
                JOIN external_tenant_principal p ON p.id=t.principal_id AND p.user_id=c.owner_user_id
                JOIN external_identity i ON i.id=p.identity_id AND i.issuer=s.issuer AND i.external_subject=s.external_subject
                WHERE c.id=? AND c.owner_user_id=? AND p.tenant_type=? AND p.tenant_id=? AND i.issuer=? AND i.external_subject=?
                FOR UPDATE OF c
                """,caller.tokenId(),caller.sourceId(),body.conversationId(),caller.userId(),caller.tenant().type(),caller.tenant().id(),caller.issuer(),caller.subject());
            if(owned.size()!=1)throw problem(404,"CONVERSATION_NOT_FOUND");
            policy.account(caller.userId());
            if(!body.available()){
                cache.remove(caller.userId(),body.conversationId(),caller.sourceId(),caller.tokenId());
                return new Receipt(body.conversationId(),"offline",false,false,null,5);
            }
            Instant expiry=Instant.now().plusSeconds(ttlSeconds);if(sourceExpiry.isBefore(expiry))expiry=sourceExpiry;
            // Recheck source revocation after the account/conversation lock and before publishing.
            Instant fresh=authority.validate(caller.sourceId(),caller.tokenId(),caller.userId());if(fresh.isBefore(expiry))expiry=fresh;
            cache.publish(new Lease(caller.userId(),body.conversationId(),caller.sourceId(),caller.tokenId(),body.video(),expiry));
            return new Receipt(body.conversationId(),"online",true,body.video(),expiry,5);
        });
    }
    /** Call under the customer's account lock. An expired/revoked source is offline. */
    public Lease ready(long customer,String conversation){
        Lease lease=cache.get(customer,conversation);if(lease==null)return null;
        try{authority.validate(lease.sourceId(),lease.tokenId(),customer);}
        catch(ExternalProblem failure){if(failure.status()==403 || failure.status()==401)return null;throw failure;}
        return lease.expiresAt().isAfter(Instant.now())?lease:null;
    }
    public Lease requireReady(long customer,String conversation,String mode){
        Lease lease=ready(customer,conversation);if(lease==null)throw problem(409,"MEDIA_CUSTOMER_OFFLINE");
        if("video".equals(mode) && !lease.video())throw problem(409,"MEDIA_CUSTOMER_VIDEO_UNAVAILABLE");
        return lease;
    }
    public Availability availability(Long user,String request){
        long actor=MediaService.actor(user);
        if(request==null || !request.matches("[a-f0-9]{32}") || authority.caller()!=null)throw problem(404,"SUPPORT_REQUEST_NOT_FOUND");
        var hint=store.rows("SELECT r.*,c.owner_user_id FROM support_request r JOIN ai_conversation c ON c.id=r.conversation_id WHERE r.id=? AND r.assigned_agent_user_id=?",request,actor);
        if(hint.size()!=1)throw problem(404,"SUPPORT_REQUEST_NOT_FOUND");
        return store.tx(()->{
            long customer=number(hint.getFirst(),"owner_user_id");policy.lockUsers(List.of(customer,actor));
            var owned=store.rows("""
                SELECT r.*,c.owner_user_id FROM support_request r JOIN ai_conversation c ON c.id=r.conversation_id
                JOIN support_assignment a ON a.request_id=r.id AND a.agent_user_id=r.assigned_agent_user_id AND a.released_at IS NULL
                JOIN support_agent_profile p ON p.user_id=a.agent_user_id AND p.enabled=1
                JOIN sys_user u ON u.id=p.user_id AND u.status=1
                WHERE r.id=? AND r.assigned_agent_user_id=? AND r.status='active'
                AND JSON_CONTAINS(COALESCE(p.roles_json,JSON_ARRAY(p.role)),JSON_QUOTE(r.service_type))
                FOR UPDATE
                """,request,actor);
            if(owned.size()!=1)throw problem(404,"SUPPORT_REQUEST_NOT_FOUND");
            String conversation=string(owned.getFirst(),"conversation_id");
            if(store.rows("SELECT id FROM sys_user WHERE id=? AND status=1 FOR UPDATE",customer).isEmpty())return new Availability(conversation,"unavailable",false,false,null);
            if(busy(customer) || !store.rows("SELECT id FROM support_media_member WHERE user_id=? AND media_busy_slot=1 LIMIT 1 FOR UPDATE",actor).isEmpty())return new Availability(conversation,"busy",false,false,null);
            try{Lease lease=ready(customer,conversation);return lease==null?new Availability(conversation,"offline",false,false,null):new Availability(conversation,"online",true,lease.video(),lease.expiresAt());}
            catch(xiaozhi.modules.conversation.ConversationProblem failure){if(failure.status()!=503)throw failure;return new Availability(conversation,"unavailable",false,false,null);}
        });
    }
    private boolean busy(long customer){return !store.rows("""
        SELECT media.id FROM ai_conversation c STRAIGHT_JOIN support_media_session media
          ON media.conversation_id=c.id AND media.active_slot=1
        WHERE c.owner_user_id=? AND media.customer_user_id=?
          AND (media.status IN ('connecting','active','reconnecting','ending')
            OR (media.status='invited' AND media.expires_at>CURRENT_TIMESTAMP(3))) LIMIT 1 FOR UPDATE OF media
        """,customer,customer).isEmpty();}
}
