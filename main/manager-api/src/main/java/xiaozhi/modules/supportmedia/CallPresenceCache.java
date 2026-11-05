package xiaozhi.modules.supportmedia;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Ephemeral RTC readiness only; Redis never owns assignment or consent. */
@Component
public class CallPresenceCache {
    public record Lease(long userId,String conversationId,long sourceId,long tokenId,boolean video,Instant expiresAt) {
        @Override public String toString(){return "CallPresenceLease[redacted]";}
    }
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private static final DefaultRedisScript<Long> REMOVE=new DefaultRedisScript<>(
            "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end",Long.class);
    public CallPresenceCache(StringRedisTemplate redis,ObjectMapper json){this.redis=redis;this.json=json;}
    static String key(long user,String conversation){return "xz:call-ready:v1:"+user+":"+conversation;}
    public void publish(Lease lease){
        try{long ttl=Duration.between(Instant.now(),lease.expiresAt()).toMillis();
            if(ttl<=0)throw new IllegalArgumentException("Expired call presence");
            redis.opsForValue().set(key(lease.userId(),lease.conversationId()),json.writeValueAsString(lease),Duration.ofMillis(ttl));
        }catch(Exception failure){throw MediaStore.problem(503,"CALL_PRESENCE_UNAVAILABLE");}
    }
    public Lease get(long user,String conversation){
        try{String value=redis.opsForValue().get(key(user,conversation));if(value==null)return null;
            Lease lease=json.readValue(value,Lease.class);
            return lease.userId()==user && conversation.equals(lease.conversationId()) && lease.expiresAt().isAfter(Instant.now())?lease:null;
        }catch(Exception failure){throw MediaStore.problem(503,"CALL_PRESENCE_UNAVAILABLE");}
    }
    public void remove(long user,String conversation,long source,long token){
        try{String key=key(user,conversation),value=redis.opsForValue().get(key);if(value==null)return;
            Lease lease=json.readValue(value,Lease.class);
            // A disposed old source/token must never clear the current foreground source.
            if(lease.userId()==user && conversation.equals(lease.conversationId()) && lease.sourceId()==source && lease.tokenId()==token)
                redis.execute(REMOVE,List.of(key),value);
        }catch(Exception failure){throw MediaStore.problem(503,"CALL_PRESENCE_UNAVAILABLE");}
    }
}
