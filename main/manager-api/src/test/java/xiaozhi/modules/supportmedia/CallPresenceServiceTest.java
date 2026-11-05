package xiaozhi.modules.supportmedia;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import xiaozhi.modules.conversation.ConversationProblem;
import xiaozhi.modules.externalidentity.*;
import xiaozhi.modules.externalidentity.ExternalModels.*;
import xiaozhi.modules.supportmedia.CallPresenceCache.Lease;
import xiaozhi.modules.supportmedia.CallPresenceService.*;

class CallPresenceServiceTest {
    final MediaStore store=mock(MediaStore.class);final MediaPolicy policy=mock(MediaPolicy.class);
    final ExternalMediaAuthority authority=mock(ExternalMediaAuthority.class);final CallPresenceCache cache=mock(CallPresenceCache.class);
    final String conversation="a".repeat(32),request="b".repeat(32);
    final Principal caller=new Principal(17,"redacted",23,42,"issuer","subject",new Tenant("org","tenant"),new Scope(List.of(),List.of()),Instant.now().plusSeconds(100));
    final CallPresenceService service=new CallPresenceService(store,policy,authority,cache,new MockEnvironment());
    @BeforeEach void setup(){when(store.tx(any())).thenAnswer(call->((Supplier<?>)call.getArgument(0)).get());when(authority.validate(23,17,42)).thenReturn(Instant.now().plusSeconds(100));}
    @Test void heartbeatOwnsExactTenantAndSourceAndExpiresWithinFifteenSeconds(){
        when(store.rows(anyString(),any(Object[].class))).thenReturn(List.of(Map.of("id",conversation)));
        Instant before=Instant.now();var receipt=service.publish(caller,new Update(conversation,true,true));
        assertEquals("online",receipt.status());assertTrue(receipt.audio());assertTrue(receipt.video());assertEquals(5,receipt.heartbeatSeconds());
        assertTrue(receipt.expiresAt().isAfter(before.plusSeconds(14)));assertFalse(receipt.expiresAt().isAfter(Instant.now().plusSeconds(15)));
        verify(store).rows(argThat(sql->sql.contains("p.tenant_type=?") && sql.contains("i.external_subject=?") && sql.contains("c.owner_user_id=?")),eq(17L),eq(23L),eq(conversation),eq(42L),eq("org"),eq("tenant"),eq("issuer"),eq("subject"));
        verify(authority,times(2)).validate(23,17,42);verify(cache).publish(argThat(lease->lease.userId()==42 && lease.sourceId()==23 && lease.tokenId()==17));
    }
    @Test void foreignConversationCannotPublishOrClearPresence(){
        when(store.rows(anyString(),any(Object[].class))).thenReturn(List.of());
        assertEquals(404,assertThrows(ConversationProblem.class,()->service.publish(caller,new Update(conversation,true,false))).status());verifyNoInteractions(cache);
    }
    @Test void revokedSourceCannotPublish(){
        when(authority.validate(23,17,42)).thenThrow(new ExternalProblem(403,"EXTERNAL_MEDIA_BINDING_DENIED"));
        assertThrows(ExternalProblem.class,()->service.publish(caller,new Update(conversation,true,false)));verifyNoInteractions(cache,store);
    }
    @Test void offlineRemovesOnlyAuthenticatedSourceToken(){
        when(store.rows(anyString(),any(Object[].class))).thenReturn(List.of(Map.of("id",conversation)));
        assertEquals("offline",service.publish(caller,new Update(conversation,false,false)).status());verify(cache).remove(42,conversation,23,17);verify(cache,never()).publish(any());
    }
    @Test void expiredRevokedAndVideoIncapablePresenceCannotStartCalls(){
        assertEquals("MEDIA_CUSTOMER_OFFLINE",assertThrows(ConversationProblem.class,()->service.requireReady(42,conversation,"audio")).getMessage());
        when(cache.get(42,conversation)).thenReturn(new Lease(42,conversation,23,17,false,Instant.now().plusSeconds(15)));
        assertEquals("MEDIA_CUSTOMER_VIDEO_UNAVAILABLE",assertThrows(ConversationProblem.class,()->service.requireReady(42,conversation,"video")).getMessage());
        when(authority.validate(23,17,42)).thenThrow(new ExternalProblem(403,"EXTERNAL_MEDIA_BINDING_DENIED"));
        assertNull(service.ready(42,conversation));
        doReturn(Instant.now().plusSeconds(50)).when(authority).validate(23,17,42);
        when(cache.get(42,conversation)).thenReturn(new Lease(42,conversation,23,17,true,Instant.now().minusSeconds(1)));assertNull(service.ready(42,conversation));
    }
    @Test void cacheAndAuthorityInfrastructureFailureNeverBecomeOnline(){
        when(cache.get(42,conversation)).thenThrow(new ConversationProblem(503,"CALL_PRESENCE_UNAVAILABLE"));
        assertEquals(503,assertThrows(ConversationProblem.class,()->service.requireReady(42,conversation,"audio")).status());
    }
    @Test void unassignedAndExternalStaffCannotReadCustomerAvailability(){
        when(store.rows(anyString(),any(Object[].class))).thenReturn(List.of());
        assertEquals(404,assertThrows(ConversationProblem.class,()->service.availability(71L,request)).status());verifyNoInteractions(cache);
        when(authority.caller()).thenReturn(caller);assertEquals(404,assertThrows(ConversationProblem.class,()->service.availability(71L,request)).status());
    }
    @Test void bothStaffRolesReadBusyOfflineOnlineWithoutLeakingSourceIdentity(){
        for(String role:List.of("support_agent","expert")){
            when(store.rows(anyString(),any(Object[].class))).thenAnswer(call->{String sql=call.getArgument(0);if(sql.contains("support_request"))return List.of(Map.of("owner_user_id",42L,"conversation_id",conversation,"service_type",role));if(sql.contains("SELECT id FROM sys_user"))return List.of(Map.of("id",42L));return List.of();});
            when(cache.get(42,conversation)).thenReturn(null);assertEquals("offline",service.availability(71L,request).status());
            when(cache.get(42,conversation)).thenReturn(new Lease(42,conversation,23,17,true,Instant.now().plusSeconds(15)));var online=service.availability(71L,request);assertEquals("online",online.status());assertTrue(online.video());
            when(cache.get(42,conversation)).thenThrow(new ConversationProblem(503,"CALL_PRESENCE_UNAVAILABLE"));assertEquals("unavailable",service.availability(71L,request).status());reset(cache);
        }
        when(store.rows(contains("STRAIGHT_JOIN"),any(Object[].class))).thenReturn(List.of(Map.of("id",123L)));assertEquals("busy",service.availability(71L,request).status());verifyNoInteractions(cache);
    }
}
