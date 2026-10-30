package xiaozhi.modules.conversation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import xiaozhi.modules.conversation.ConversationModels.Conversation;

/** Parameterized SQL on the existing manager-api DataSource. No separate user database. */
@Repository
@RequiredArgsConstructor
public class ConversationDao {
    private final JdbcTemplate jdbc;

    public record StoredMessage(String id, String conversationId, long seq, String clientMessageId,
            String senderId, String partsJson, String requestHash, Instant createdAt,
            String senderType, String status) {
        public StoredMessage(String id, String conversationId, long seq, String clientMessageId,
                String senderId, String partsJson, String requestHash, Instant createdAt) {
            this(id, conversationId, seq, clientMessageId, senderId, partsJson, requestHash, createdAt,
                    "user", "accepted");
        }
    }

    public record StaffGrant(boolean current, long messageCutoff, long eventCutoff) { }

    /** Presence only affects new claims; existing assignment rights do not expire with heartbeats. */
    public StaffGrant staffGrant(String id, long actor) {
        List<StaffGrant> rows = jdbc.query("""
                SELECT MAX(CASE WHEN a.released_at IS NULL AND r.status = 'active'
                    AND r.assigned_agent_user_id = a.agent_user_id THEN 1 ELSE 0 END) AS current_grant,
                    COALESCE(MAX(a.read_through_seq), 0) AS message_cutoff,
                    COALESCE(MAX(a.read_through_event_seq), 0) AS event_cutoff
                FROM support_assignment a
                JOIN support_request r ON r.id = a.request_id AND r.conversation_id = a.conversation_id
                JOIN support_agent_profile p ON p.user_id = a.agent_user_id AND p.enabled = 1
                    AND p.role IN ('support_agent', 'expert')
                JOIN sys_user u ON u.id = p.user_id AND u.status = 1
                WHERE a.conversation_id = ? AND a.agent_user_id = ?
                    AND (a.released_at IS NOT NULL OR (r.status = 'active'
                        AND r.assigned_agent_user_id = a.agent_user_id))
                HAVING COUNT(*) > 0
                """, (rs, row) -> new StaffGrant(rs.getInt("current_grant") == 1,
                        rs.getLong("message_cutoff"), rs.getLong("event_cutoff")), id, actor);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public boolean currentAssignment(String id, long actor) {
        List<String> requests = jdbc.query("""
                SELECT id FROM support_request WHERE conversation_id = ?
                    AND status = 'active' AND assigned_agent_user_id = ? FOR UPDATE
                """, (rs, row) -> rs.getString("id"), id, actor);
        if (requests.isEmpty()) return false;
        return !jdbc.query("""
                SELECT id FROM support_assignment WHERE request_id = ? AND conversation_id = ?
                    AND agent_user_id = ? AND released_at IS NULL FOR UPDATE
                """, (rs, row) -> rs.getString("id"), requests.getFirst(), id, actor).isEmpty();
    }

    /** Match support's explicit profile -> account -> conversation -> request -> assignment order. */
    public String lockStaffRole(long actor) {
        List<String> roles = jdbc.query("""
                SELECT role FROM support_agent_profile WHERE user_id = ? AND enabled = 1
                    AND role IN ('support_agent', 'expert') FOR UPDATE
                """, (rs, row) -> rs.getString("role"), actor);
        if (roles.isEmpty()) return null;
        List<Long> accounts = jdbc.query("SELECT id FROM sys_user WHERE id = ? AND status = 1 FOR UPDATE",
                (rs, row) -> rs.getLong("id"), actor);
        return accounts.isEmpty() ? null : roles.getFirst();
    }

    public Conversation byId(String id, boolean lock) {
        List<Conversation> rows = jdbc.query("SELECT * FROM ai_conversation WHERE id = ?"
                + (lock ? " FOR UPDATE" : ""), ConversationDao::conversation, id);
        if (rows.isEmpty()) throw new ConversationProblem(404, "CONVERSATION_NOT_FOUND");
        return rows.getFirst();
    }

    public record StoredEvent(String id, String conversationId, long seq, String type,
            String dataJson, Instant createdAt) { }

    public boolean ownsAgent(String agentId, long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ai_agent WHERE id = ? AND user_id = ?",
                Long.class, agentId, userId) == 1L;
    }

    public void insertConversation(String id, long userId, String agentId, String title) {
        jdbc.update("""
                INSERT INTO ai_conversation (id, owner_user_id, agent_id, title)
                VALUES (?, ?, ?, ?)
                """, id, userId, agentId, title);
    }

    public Conversation owned(String id, long userId, boolean lock) {
        List<Conversation> rows = jdbc.query("""
                SELECT * FROM ai_conversation WHERE id = ? AND owner_user_id = ?
                """ + (lock ? " FOR UPDATE" : ""), ConversationDao::conversation, id, userId);
        // Do not reveal whether an inaccessible conversation exists.
        if (rows.isEmpty()) {
            throw new ConversationProblem(404, "CONVERSATION_NOT_FOUND");
        }
        return rows.getFirst();
    }

    public List<Conversation> list(long userId, long before, int limit) {
        return jdbc.query("""
                SELECT * FROM ai_conversation
                WHERE owner_user_id = ? AND list_sequence < ? ORDER BY list_sequence DESC LIMIT ?
                """, ConversationDao::conversation, userId, before, limit);
    }

    public StoredMessage duplicate(String conversationId, String clientMessageId) {
        List<StoredMessage> rows = jdbc.query("""
                SELECT * FROM ai_message WHERE conversation_id = ? AND client_message_id = ?
                """, ConversationDao::message, conversationId, clientMessageId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void insertMessage(String id, String conversationId, long seq, String clientMessageId,
            long senderId, String parts, String hash) {
        insertMessage(id, conversationId, seq, clientMessageId, senderId, parts, hash, "user", "accepted");
    }

    public void insertMessage(String id, String conversationId, long seq, String clientMessageId,
            long senderId, String parts, String hash, String senderType, String status) {
        jdbc.update("""
                INSERT INTO ai_message
                    (id, conversation_id, seq, client_message_id, sender_type, sender_id, parts_json, request_hash, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, conversationId, seq, clientMessageId, senderType, Long.toString(senderId), parts, hash, status);
    }

    public void insertEvent(String id, String conversationId, long seq, String type, String data) {
        jdbc.update("""
                INSERT INTO ai_conversation_event (id, conversation_id, seq, event_type, data_json)
                VALUES (?, ?, ?, ?, ?)
                """, id, conversationId, seq, type, data);
    }

    public void advance(String id, long messageSeq, long eventSeq) {
        jdbc.update("""
                UPDATE ai_conversation SET last_seq = ?, last_event_seq = ?, updated_at = CURRENT_TIMESTAMP(3)
                WHERE id = ?
                """, messageSeq, eventSeq, id);
    }

    public List<StoredMessage> messages(String id, long after, int limit) {
        return messages(id, after, limit, Long.MAX_VALUE);
    }

    public List<StoredMessage> messages(String id, long after, int limit, long cutoff) {
        return jdbc.query("""
                SELECT * FROM ai_message WHERE conversation_id = ? AND seq > ? AND seq <= ? ORDER BY seq LIMIT ?
                """, ConversationDao::message, id, after, cutoff, limit);
    }

    public List<StoredEvent> events(String id, long after, int limit) {
        return events(id, after, limit, Long.MAX_VALUE);
    }

    public List<StoredEvent> events(String id, long after, int limit, long cutoff) {
        return jdbc.query("""
                SELECT * FROM ai_conversation_event WHERE conversation_id = ? AND seq > ? AND seq <= ? ORDER BY seq LIMIT ?
                """, (rs, row) -> new StoredEvent(rs.getString("id"), rs.getString("conversation_id"),
                        rs.getLong("seq"), rs.getString("event_type"), rs.getString("data_json"),
                        rs.getTimestamp("created_at").toInstant()), id, after, cutoff, limit);
    }

    private static Conversation conversation(ResultSet rs, int row) throws SQLException {
        return new Conversation(rs.getString("id"), rs.getLong("list_sequence"), rs.getString("agent_id"),
                rs.getString("title"), rs.getString("state"), rs.getLong("last_seq"), rs.getLong("last_event_seq"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private static StoredMessage message(ResultSet rs, int row) throws SQLException {
        return new StoredMessage(rs.getString("id"), rs.getString("conversation_id"), rs.getLong("seq"),
                rs.getString("client_message_id"), rs.getString("sender_id"), rs.getString("parts_json"),
                rs.getString("request_hash"), rs.getTimestamp("created_at").toInstant(),
                rs.getString("sender_type"), rs.getString("status"));
    }
}
