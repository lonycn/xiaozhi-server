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
            String senderId, String partsJson, String requestHash, Instant createdAt) { }

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
        jdbc.update("""
                INSERT INTO ai_message
                    (id, conversation_id, seq, client_message_id, sender_type, sender_id, parts_json, request_hash, status)
                VALUES (?, ?, ?, ?, 'user', ?, ?, ?, 'accepted')
                """, id, conversationId, seq, clientMessageId, Long.toString(senderId), parts, hash);
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
        return jdbc.query("""
                SELECT * FROM ai_message WHERE conversation_id = ? AND seq > ? ORDER BY seq LIMIT ?
                """, ConversationDao::message, id, after, limit);
    }

    public List<StoredEvent> events(String id, long after, int limit) {
        return jdbc.query("""
                SELECT * FROM ai_conversation_event WHERE conversation_id = ? AND seq > ? ORDER BY seq LIMIT ?
                """, (rs, row) -> new StoredEvent(rs.getString("id"), rs.getString("conversation_id"),
                        rs.getLong("seq"), rs.getString("event_type"), rs.getString("data_json"),
                        rs.getTimestamp("created_at").toInstant()), id, after, limit);
    }

    private static Conversation conversation(ResultSet rs, int row) throws SQLException {
        return new Conversation(rs.getString("id"), rs.getLong("list_sequence"), rs.getString("agent_id"),
                rs.getString("title"), rs.getString("state"), rs.getLong("last_seq"), rs.getLong("last_event_seq"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private static StoredMessage message(ResultSet rs, int row) throws SQLException {
        return new StoredMessage(rs.getString("id"), rs.getString("conversation_id"), rs.getLong("seq"),
                rs.getString("client_message_id"), rs.getString("sender_id"), rs.getString("parts_json"),
                rs.getString("request_hash"), rs.getTimestamp("created_at").toInstant());
    }
}
