package xiaozhi.modules.legacy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import xiaozhi.modules.legacy.LegacyModels.*;

@Service
@RequiredArgsConstructor
public class LegacyService {
    private final JdbcTemplate jdbc;

    private static long cursor(String value) {
        if (value == null || !value.matches("[0-9]{1,19}")) throw new LegacyProblem(400, "INVALID_CURSOR");
        try { return Long.parseLong(value); }
        catch (NumberFormatException e) { throw new LegacyProblem(400, "INVALID_CURSOR"); }
    }
    private static void validate(String agent, String session, int limit, int max) {
        if (agent == null || !agent.matches("[a-zA-Z0-9_-]{1,32}") || limit < 1 || limit > max ||
                (session != null && (session.isBlank() || session.length() > 50 || session.chars().anyMatch(c -> Character.isISOControl(c) || c == '/'))))
            throw new LegacyProblem(400, "INVALID_REQUEST");
    }
    private void requireOwner(Long userId, String agent) {
        if (userId == null ||  !java.util.Objects.equals(jdbc.queryForObject("""
                SELECT COUNT(*) FROM ai_agent a JOIN sys_user u ON u.id = a.user_id
                WHERE a.id = ? AND a.user_id = ? AND u.status = 1
                """, Long.class, agent, userId), 1L)) throw new LegacyProblem(404, "AGENT_NOT_FOUND");
    }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    public Page<Session> sessions(Long userId, String agent, String before, int limit) {
        validate(agent, null, limit, 100); long cursor = cursor(before); requireOwner(userId, agent);
        List<Session> rows = jdbc.query("""
                SELECT session_id, MIN(id) first_id, MAX(id) last_id, COUNT(*) message_count,
                       MIN(created_at) created_at, MAX(updated_at) updated_at
                FROM ai_agent_chat_history WHERE agent_id = ? AND session_id IS NOT NULL AND session_id <> ''
                GROUP BY session_id HAVING (? = 0 OR MIN(id) < ?)
                ORDER BY MIN(id) DESC LIMIT ?
                """, (rs, n) -> new Session(rs.getString("session_id"), rs.getString("first_id"),
                    rs.getString("last_id"), rs.getLong("message_count"), instant(rs.getTimestamp("created_at")),
                    instant(rs.getTimestamp("updated_at"))), agent, cursor, cursor, limit + 1);
        boolean more = rows.size() > limit;
        List<Session> items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        return new Page<>(items, items.isEmpty() ? before : items.getLast().firstMessageId(), more);
    }
    public Page<Message> messages(Long userId, String agent, String session, String after, int limit) {
        validate(agent, session, limit, 100); long cursor = cursor(after); requireOwner(userId, agent);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM ai_agent_chat_history WHERE agent_id = ? AND session_id = ?",
                Long.class, agent, session);
        if (count == null || count == 0) throw new LegacyProblem(404, "SESSION_NOT_FOUND");
        List<Message> rows = jdbc.query("""
                SELECT id, session_id, chat_type, content, audio_id, created_at FROM ai_agent_chat_history
                WHERE agent_id = ? AND session_id = ? AND id > ? ORDER BY id ASC LIMIT ?
                """, (rs, n) -> {
                    int role = rs.getInt("chat_type");
                    String sender = role == 1 ? "user" : role == 2 ? "assistant" : "unknown";
                    String audio = rs.getString("audio_id");
                    return new Message(rs.getString("id"), rs.getString("session_id"), sender,
                            List.of(new Part("text", rs.getString("content"))), instant(rs.getTimestamp("created_at")),
                            true, audio != null && !audio.isBlank());
                }, agent, session, cursor, limit + 1);
        boolean more = rows.size() > limit;
        List<Message> items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        return new Page<>(items, items.isEmpty() ? after : items.getLast().sourceId(), more);
    }
}
