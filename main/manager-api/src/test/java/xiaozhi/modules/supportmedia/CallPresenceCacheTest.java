package xiaozhi.modules.supportmedia;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.RedisScript;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import xiaozhi.modules.conversation.ConversationProblem;

class CallPresenceCacheTest {
    final StringRedisTemplate redis=mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked") final ValueOperations<String,String> values=mock(ValueOperations.class);
    final com.fasterxml.jackson.databind.ObjectMapper json=JsonMapper.builder().addModule(new JavaTimeModule()).build();
    final CallPresenceCache cache=new CallPresenceCache(redis,json);
    @Test void falseFromOldSourceOrTokenNeverClearsNewSource()throws Exception{
        when(redis.opsForValue()).thenReturn(values);
        var current=new CallPresenceCache.Lease(42,"conversation",24,18,true,Instant.now().plusSeconds(15));String raw=json.writeValueAsString(current);
        when(values.get(anyString())).thenReturn(raw);
        cache.remove(42,"conversation",23,17);cache.remove(42,"conversation",24,17);verify(redis,never()).execute(any(RedisScript.class),anyList(),any(Object[].class));
        cache.remove(42,"conversation",24,18);verify(redis).execute(any(RedisScript.class),eq(java.util.List.of(CallPresenceCache.key(42,"conversation"))),eq(raw));
    }
    @Test void heartbeatUsesAtomicSetWithExpiryAndStaleLeaseIsOffline()throws Exception{
        when(redis.opsForValue()).thenReturn(values);var lease=new CallPresenceCache.Lease(42,"conversation",23,17,true,Instant.now().plusSeconds(15));cache.publish(lease);
        verify(values).set(eq(CallPresenceCache.key(42,"conversation")),anyString(),argThat((Duration duration)->duration.toMillis()>0 && duration.toMillis()<=15000));
        when(values.get(anyString())).thenReturn(json.writeValueAsString(new CallPresenceCache.Lease(42,"conversation",23,17,true,Instant.now().minusSeconds(1))));assertNull(cache.get(42,"conversation"));
    }
    @Test void redisFailureFailsClosed(){when(redis.opsForValue()).thenThrow(new IllegalStateException("fixture unavailable"));assertEquals(503,assertThrows(ConversationProblem.class,()->cache.get(42,"conversation")).status());}
}
