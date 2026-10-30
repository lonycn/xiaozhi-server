package xiaozhi.modules.support;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import com.fasterxml.jackson.databind.json.JsonMapper;
import xiaozhi.modules.conversation.*;
import xiaozhi.modules.support.SupportModels.*;

class SupportServiceTest {
    private static final String ID = "a".repeat(32);
    private final Store jdbc = new Store();
    private final ConversationService conversations = mock(ConversationService.class);
    private final SupportService service = new SupportService(jdbc,JsonMapper.builder().build(),manager(),conversations);
    private static PlatformTransactionManager manager() {
        PlatformTransactionManager result = mock(PlatformTransactionManager.class);
        when(result.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return result;
    }
    private static int status(Runnable work) { return assertThrows(ConversationProblem.class,work::run).status(); }

    @Test void rejectsUnauthenticatedAndUnsupportedModesBeforeStorage() {
        assertEquals(401,status(() -> service.handoff(null,ID,new Handoff("key","text",null))));
        for(String mode : List.of("video","audio","TEXT",""))
            assertEquals(400,status(() -> service.handoff(1L,ID,new Handoff("key",mode,null))));
        assertEquals(400,status(() -> service.handoff(1L,ID,new Handoff("bad key","text",null))));
        assertEquals(400,status(() -> service.handoff(1L,ID,new Handoff("key","text",-1L))));
        assertEquals(0,jdbc.reads);
    }
    @Test void rejectsInvalidCapacityPresenceAndReasonBeforeStorage() {
        assertEquals(400,status(() -> service.upsertAgent(1L,2L,new AgentUpdate("admin",true,1))));
        for(int capacity : List.of(0,21))
            assertEquals(400,status(() -> service.upsertAgent(1L,2L,new AgentUpdate("expert",true,capacity))));
        assertEquals(400,status(() -> service.presence(2L,new PresenceUpdate(null))));
        assertEquals(400,status(() -> service.resolve(2L,ID,new EndRequest("x".repeat(501)))));
        assertEquals(0,jdbc.reads);
    }
    @Test void rejectsOwnerSelfClaimAndUnrelatedCancellation() {
        assertEquals(403,status(() -> service.claim(1L,ID)));
        assertEquals(404,status(() -> service.cancel(2L,ID,null)));
        assertTrue(jdbc.writes.isEmpty());
    }
    @Test void refusesOfflineAndFullAgentsWithoutChangingAssignment() {
        jdbc.online=false;
        assertEquals(409,status(() -> service.claim(2L,ID)));
        jdbc.online=true;
        jdbc.occupied=3;
        assertEquals(409,status(() -> service.claim(2L,ID)));
        assertTrue(jdbc.writes.isEmpty());
    }
    @Test void rejectsOtherTargetAndDisabledAccount() {
        jdbc.target=3L;
        assertEquals(403,status(() -> service.claim(2L,ID)));
        jdbc.target=null;
        jdbc.accountEnabled=false;
        assertEquals(403,status(() -> service.claim(2L,ID)));
        assertTrue(jdbc.writes.isEmpty());
    }
    @Test void presenceExpiryDoesNotRevokeCurrentClaimAndRetryAddsNoEvent() {
        jdbc.requestStatus="active";
        jdbc.assignee=2L;
        jdbc.online=false;
        assertEquals("active",service.claim(2L,ID).status());
        assertTrue(jdbc.writes.isEmpty());
    }
    @Test void onlyCurrentAssigneeCanResolveOrWrite() {
        jdbc.requestStatus="active";
        jdbc.assignee=3L;
        assertEquals(403,status(() -> service.resolve(2L,ID,null)));
        assertEquals(403,status(() -> service.send(2L,ID,null)));
        verifyNoInteractions(conversations);
        assertTrue(jdbc.writes.isEmpty());
    }
    @Test void resolvedRetryHasNoEventAndActiveCancelIsConflict() {
        jdbc.requestStatus="resolved";
        jdbc.assignee=2L;
        assertEquals("resolved",service.resolve(2L,ID,null).status());
        jdbc.requestStatus="active";
        assertEquals(409,status(() -> service.cancel(1L,ID,null)));
        assertTrue(jdbc.writes.isEmpty());
    }
    @Test void queueValidatesPaginationAndRequiresProfile() {
        assertEquals(400,status(() -> service.queue(2L,-1,50)));
        assertEquals(400,status(() -> service.queue(2L,0,101)));
        jdbc.profileEnabled=false;
        assertEquals(403,status(() -> service.queue(2L,0,50)));
        assertTrue(jdbc.writes.isEmpty());
    }

    @Test void requestMetadataIsOwnerOrEnabledCurrentAssigneeOnly() {
        assertEquals("queued",service.get(1L,ID).status());
        jdbc.requestStatus="active";
        jdbc.assignee=2L;
        assertEquals("active",service.get(2L,ID).status());
        jdbc.assignee=3L;
        assertEquals(404,status(() -> service.get(2L,ID)));
        jdbc.assignee=2L;
        jdbc.profileEnabled=false;
        assertEquals(403,status(() -> service.get(2L,ID)));
        jdbc.profileEnabled=true;
        jdbc.requestStatus="resolved";
        assertEquals(404,status(() -> service.get(2L,ID)));
    }

    @Test void strictBodiesRejectSpoofedIdentityAndContext() throws Exception {
        var json=JsonMapper.builder().build();
        var parser=new ConversationService(mock(ConversationDao.class),json,manager());
        for (String payload : List.of(
                "{\"client_request_id\":\"key\",\"requested_mode\":\"text\",\"tenant_id\":1}",
                "{\"client_request_id\":\"key\",\"requested_mode\":\"text\",\"project_id\":1}",
                "{\"client_request_id\":\"key\",\"requested_mode\":\"text\",\"owner_user_id\":1}"))
            assertEquals(400,status(() -> parser.request(parse(json,payload),Handoff.class)));
        assertEquals(400,status(() -> parser.request(parse(json,"{\"accepting\":true,\"user_id\":3}"),PresenceUpdate.class)));
        assertEquals(400,status(() -> parser.request(parse(json,"{\"reason\":\"done\",\"sender_type\":\"expert\"}"),EndRequest.class)));
        assertEquals(400,status(() -> parser.request(parse(json,"{\"agent_id\":3}"),SupportController.EmptyRequest.class)));
    }
    private static com.fasterxml.jackson.databind.JsonNode parse(com.fasterxml.jackson.databind.ObjectMapper json,String payload) {
        try { return json.readTree(payload); } catch(Exception e) { throw new AssertionError(e); }
    }
    @Test void successfulClaimEndsAndRequeuesWithExplicitStateAndReleaseCutoffs() {
        var claimed=service.claim(2L,ID);
        assertEquals("active",claimed.status());
        assertEquals(2L,claimed.assignedAgentUserId());
        assertEquals("HUMAN_ACTIVE",jdbc.conversationState);
        assertTrue(jdbc.writes.stream().anyMatch(sql -> sql.startsWith("INSERT INTO support_assignment")));
        service.requeue(2L,ID,new EndRequest("handover"),false);
        assertEquals("queued",jdbc.requestStatus);
        assertEquals("HUMAN_QUEUED",jdbc.conversationState);
        assertTrue(jdbc.writes.stream().anyMatch(sql -> sql.contains("read_through_seq=?") && sql.contains("read_through_event_seq=?")));
        service.claim(2L,ID);
        service.resolve(2L,ID,new EndRequest("finished"));
        assertEquals("resolved",jdbc.requestStatus);
        assertEquals("RESOLVED",jdbc.conversationState);
        verifyNoInteractions(conversations);
    }
    @Test void cancelLeavesAiPausedAndProfileManagementRequiresAdministrator() {
        assertEquals("canceled",service.cancel(1L,ID,null).status());
        assertEquals("RESOLVED",jdbc.conversationState);
        assertEquals(403,status(() -> service.upsertAgent(2L,3L,new AgentUpdate("expert",true,2))));
        assertEquals(403,status(() -> service.requeue(2L,ID,null,true)));
        verifyNoInteractions(conversations);
    }

    /** In-memory JDBC boundary only for authorization branches; real MySQL tests cover locking/SQL. */
    private static final class Store extends JdbcTemplate {
        int reads,occupied;
        boolean online=true,accountEnabled=true,profileEnabled=true;
        String requestStatus="queued",conversationState="HUMAN_QUEUED";
        Long assignee,target;
        List<String> writes=new ArrayList<>();
        @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object... args) {
            reads++;
            Map<String,Object> values=new HashMap<>();
            values.put("id",ID); values.put("conversation_id",ID); values.put("owner_user_id",1L);
            values.put("state",conversationState); values.put("last_seq",0L); values.put("last_event_seq",3L);
            values.put("user_id",2L); values.put("role","expert"); values.put("enabled",profileEnabled);
            values.put("capacity_text",3); values.put("accepting",true);
            values.put("list_sequence",1L); values.put("client_request_id","key");
            values.put("status",requestStatus); values.put("requested_mode","text");
            values.put("target_agent_user_id",target); values.put("assigned_agent_user_id",assignee);
            values.put("requested_at",Timestamp.from(Instant.parse("2026-10-30T00:00:00Z")));
            ResultSet rs=mock(ResultSet.class);
            try {
                when(rs.getString(anyString())).thenAnswer(a -> Objects.toString(values.get(a.getArgument(0)),null));
                when(rs.getLong(anyString())).thenAnswer(a -> ((Number)values.getOrDefault(a.getArgument(0),0L)).longValue());
                when(rs.getInt(anyString())).thenAnswer(a -> ((Number)values.getOrDefault(a.getArgument(0),0)).intValue());
                when(rs.getBoolean(anyString())).thenAnswer(a -> values.getOrDefault(a.getArgument(0),false));
                when(rs.getTimestamp(anyString())).thenAnswer(a -> values.get(a.getArgument(0)));
                when(rs.getObject(anyString(),eq(Long.class))).thenAnswer(a -> values.get(a.getArgument(0)));
                return List.of(mapper.mapRow(rs,0));
            } catch(java.sql.SQLException error) { throw new AssertionError(error); }
        }
        @Override public List<Map<String,Object>> queryForList(String sql,Object... args) {
            reads++;
            if(sql.contains("super_admin=1")) return accountEnabled && Long.valueOf(1).equals(args[0]) ? List.of(Map.of("id",1L)) : List.of();
            if(sql.contains("FROM sys_user")) return accountEnabled ? List.of(Map.of("id",2L)) : List.of();
            if(sql.contains("WHERE agent_user_id=?"))
                return java.util.stream.IntStream.range(0,occupied).mapToObj(i -> Map.<String,Object>of("id",ID+i)).toList();
            if(sql.contains("support_assignment")) return List.of(Map.of("id",ID));
            return List.of();
        }
        @Override public <T> T queryForObject(String sql,Class<T> type,Object... args) {
            reads++;
            return type.cast(online ? 1L : 0L);
        }
        @Override public int update(String sql,Object... args) {
            writes.add(sql);
            if(sql.startsWith("UPDATE ai_conversation SET state=?")) conversationState=(String)args[0];
            if(sql.contains("SET status='active'")) { requestStatus="active"; assignee=(Long)args[0]; }
            if(sql.contains("SET status='queued'")) { requestStatus="queued"; assignee=null; }
            if(sql.contains("SET status='resolved'")) requestStatus="resolved";
            if(sql.contains("SET status='canceled'")) requestStatus="canceled";
            return 1;
        }
    }
}
