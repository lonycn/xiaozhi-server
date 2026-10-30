package xiaozhi.modules.legacy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class LegacyServiceTest {
    @Test void onlyOwnerWithActiveAccountCanReadEvenForAdmin() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(0L);
        var service = new LegacyService(jdbc);
        var problem = assertThrows(LegacyProblem.class, () -> service.sessions(99L, "agent", "0", 50));
        assertEquals(404, problem.status());
        verify(jdbc, never()).query(anyString(), any(RowMapper.class), any(), any(), any(), any());
    }
    @Test void sessionMustBelongToTheAuthorizedAgent() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(1L, 0L);
        var problem = assertThrows(LegacyProblem.class, () -> new LegacyService(jdbc).messages(7L, "agent", "other-session", "0", 10));
        assertEquals("SESSION_NOT_FOUND", problem.getMessage());
    }
    @Test void rejectsInvalidCursorsPathsAndLimitsBeforeQuery() {
        var jdbc = mock(JdbcTemplate.class); var service = new LegacyService(jdbc);
        assertThrows(LegacyProblem.class, () -> service.sessions(7L, "agent", "9223372036854775808", 50));
        assertThrows(LegacyProblem.class, () -> service.messages(7L, "agent", "x", "-1", 10));
        assertThrows(LegacyProblem.class, () -> service.sessions(7L, "agent", "0", 101));
        assertThrows(LegacyProblem.class, () -> service.messages(7L, "agent", "x".repeat(51), "0", 10));
        verifyNoInteractions(jdbc);
    }
    @Test void mapsRolesAndPreservesSourceCursor() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(1L);
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any())).thenAnswer(inv -> {
            RowMapper<?> mapper = inv.getArgument(1); var rows = new java.util.ArrayList<Object>();
            for (int role : new int[] {1, 2, 7}) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getInt("chat_type")).thenReturn(role);
                when(rs.getString("id")).thenReturn(Integer.toString(role));
                when(rs.getString("session_id")).thenReturn("s");
                when(rs.getString("content")).thenReturn("original");
                when(rs.getString("audio_id")).thenReturn(role == 1 ? "audio" : null);
                when(rs.getTimestamp("created_at")).thenReturn(Timestamp.valueOf("2026-01-01 00:00:00"));
                rows.add(mapper.mapRow(rs, role));
            }
            return rows;
        });
        var page = new LegacyService(jdbc).messages(7L, "a", "s", "0", 2);
        assertEquals(List.of("user", "assistant"), page.items().stream().map(LegacyModels.Message::senderType).toList());
        assertTrue(page.items().getFirst().hasAudio()); assertTrue(page.items().getFirst().readOnly());
        assertEquals("original", page.items().getFirst().parts().getFirst().text());
        assertEquals("2", page.nextCursor()); assertTrue(page.hasMore());
        verify(jdbc).query(contains("ORDER BY id ASC LIMIT"), any(RowMapper.class), eq("a"), eq("s"), eq(0L), eq(3));
    }
    @Test void sessionCursorUsesFirstSourceIdAndDescendingOrder() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(1L);
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any())).thenReturn(List.of(
                new LegacyModels.Session("s1", "90", "99", 2, null, null),
                new LegacyModels.Session("s2", "70", "80", 2, null, null)));
        var page = new LegacyService(jdbc).sessions(7L, "agent", "100", 1);
        assertEquals("90", page.nextCursor()); assertTrue(page.hasMore());
        verify(jdbc).query(contains("ORDER BY MIN(id) DESC LIMIT"), any(RowMapper.class), eq("agent"), eq(100L), eq(100L), eq(2));
    }

}
