package xiaozhi.modules.supportmedia;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import com.fasterxml.jackson.databind.json.JsonMapper;
import xiaozhi.modules.conversation.*;
import xiaozhi.modules.supportmedia.MediaModels.*;
import xiaozhi.modules.supportmedia.livekit.LiveKitAdapter;

class MediaOutboundCallTest {
    final MediaStore store=mock(MediaStore.class);final MediaPolicy policy=mock(MediaPolicy.class);
    final CallPresenceService presence=mock(CallPresenceService.class);final LiveKitAdapter livekit=mock(LiveKitAdapter.class);
    final JdbcTemplate db=mock(JdbcTemplate.class);final String request="a".repeat(32),conversation="b".repeat(32);
    final MediaService service=spy(new MediaService(store,policy,livekit,mock(ConversationDao.class),JsonMapper.builder().build(),new MockEnvironment().withProperty("features.support-media-enabled","true")));
    final Create body=new Create(request,"outbound-fixture","audio",new Consent(true,false,false));
    final Map<String,Object> q=Map.of("id",request,"conversation_id",conversation,"owner_user_id",42L,"assigned_agent_user_id",71L,"status","active","service_type","expert");
    @BeforeEach void setup(){
        when(livekit.configured()).thenReturn(true);when(store.jdbc()).thenReturn(db);when(store.tx(any())).thenAnswer(call->((Supplier<?>)call.getArgument(0)).get());
        when(store.one(contains("support_request"),any(Object[].class))).thenReturn(q);
        when(store.rows(anyString(),any(Object[].class))).thenAnswer(call->{String sql=call.getArgument(0);if(sql.contains("JOIN ai_conversation c"))return List.of(q);if(sql.contains("support_assignment a"))return List.of(Map.of("id","assignment"));return List.of();});
        service.setCallPresence(presence);
    }
    @Test void staffCreateFailsClosedOfflineAndBusyBeforeInsert(){
        when(presence.requireReady(42,conversation,"audio")).thenThrow(new ConversationProblem(409,"MEDIA_CUSTOMER_OFFLINE"));
        assertEquals("MEDIA_CUSTOMER_OFFLINE",assertThrows(ConversationProblem.class,()->service.create(71L,body)).getMessage());verifyNoInteractions(db);
        when(store.rows(contains("STRAIGHT_JOIN"),any(Object[].class))).thenReturn(List.of(Map.of("id",123L)));
        assertEquals("MEDIA_CUSTOMER_BUSY",assertThrows(ConversationProblem.class,()->service.create(71L,body)).getMessage());verifyNoInteractions(db);
    }
    @Test void staffAlreadyReservedInAnotherCallCannotDial(){when(store.rows(contains("media_busy_slot=1"),any(Object[].class))).thenReturn(List.of(Map.of("id",123L)));assertEquals("MEDIA_AGENT_BUSY",assertThrows(ConversationProblem.class,()->service.create(71L,body)).getMessage());verifyNoInteractions(presence,db);}
    @Test void missingProductionPresenceCannotCreateStaffInvitation(){service.setCallPresence(null);assertEquals(503,assertThrows(ConversationProblem.class,()->service.create(71L,body)).status());verifyNoInteractions(db);}
    @Test void idempotentRetryReturnsOriginalSessionAfterPresenceGoesOffline(){
        String digest=MediaService.hash("[\""+request+"\",\"audio\",true,false,false]");
        var prior=Map.<String,Object>of("id",123L,"request_hash",digest);when(store.rows(contains("client_session_id"),any(Object[].class))).thenReturn(List.of(prior));
        Session expected=mock(Session.class);doReturn(expected).when(service).view(prior);
        assertSame(expected,service.create(71L,body));verifyNoInteractions(presence,db);
    }
    @Test void staffInviteBindsReadySourceBeforeCreatingCustomerMember(){
        var lease=new CallPresenceCache.Lease(42,conversation,23,17,false,Instant.now().plusSeconds(15));when(presence.requireReady(42,conversation,"audio")).thenReturn(lease);
        var room=Map.<String,Object>of("id",123L,"session_id",456L);var session=Map.<String,Object>of("id",456L,"external_source_id",23L,"external_token_id",17L);
        when(store.room(anyLong(),eq(true))).thenReturn(room);when(store.session(anyLong(),anyBoolean())).thenReturn(session);
        when(store.rows(contains("support_media_member WHERE room_id"),any(Object[].class))).thenReturn(List.of(new HashMap<>(Map.of("id",111L,"user_id",71L))));
        doNothing().when(service).event(anyMap(),eq("media.invited"),anyMap());Session expected=mock(Session.class);doReturn(expected).when(service).view(session);
        assertSame(expected,service.create(71L,body));
        var ordered=inOrder(db);ordered.verify(db).update(startsWith("INSERT INTO support_media_session"),any(Object[].class));
        ordered.verify(db).update(eq("UPDATE support_media_session SET external_source_id=?,external_token_id=? WHERE id=?"),eq(23L),eq(17L),anyLong());
        verify(db).update(contains("UPDATE support_media_member SET external_source_id"),eq(23L),eq(17L),eq(123L),eq(42L));
    }
}
