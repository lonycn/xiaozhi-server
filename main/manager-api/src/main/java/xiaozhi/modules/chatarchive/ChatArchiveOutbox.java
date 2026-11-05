package xiaozhi.modules.chatarchive;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import xiaozhi.modules.chatarchive.ChatArchiveConfiguration.Settings;
import xiaozhi.modules.conversation.ConversationDao;
import xiaozhi.modules.conversation.ConversationDao.StoredMessage;
import xiaozhi.modules.conversation.ConversationModels.MessagePart;
import xiaozhi.modules.externalidentity.ExternalIdentityService;
import xiaozhi.modules.externalidentity.ExternalModels.Principal;

@Service
@ConditionalOnProperty(prefix = "xiaozhi.archive", name = "enabled", havingValue = "true")
public class ChatArchiveOutbox {
    private static final Pattern LARAVEL_ID = Pattern.compile("[1-9][0-9]{0,17}");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ConversationDao conversations;
    private final Settings settings;

    ChatArchiveOutbox(JdbcTemplate jdbc, ObjectMapper json, ConversationDao conversations, Settings settings) {
        this.jdbc = jdbc;
        this.json = json;
        this.conversations = conversations;
        this.settings = settings;
    }

    record Owner(String issuer, String tenantType, String tenantId, String userId) { }
    record Task(long id, String lease, int attempt, byte[] payload) { }

    /** Called only inside the message.accepted transaction, with the conversation locked. */
    public void accepted(long actor, String title, StoredMessage message, long version) {
        var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet)
                || !(servlet.getRequest().getAttribute(ExternalIdentityService.ATTRIBUTE) instanceof Principal principal)
                || principal.userId() != actor || !settings.sourceIssuer().equals(principal.issuer())
                || !("customer".equals(principal.tenant().type()) || "account".equals(principal.tenant().type()))
                || !LARAVEL_ID.matcher(principal.tenant().id()).matches()
                || !LARAVEL_ID.matcher(principal.subject()).matches()) return;

        insert(new Owner(settings.archiveIssuer(), principal.tenant().type(), principal.tenant().id(), principal.subject()), title, message, version);
    }

    /** A worker callback inherits the identity captured with its input, even after token expiry. */
    public void terminal(String conversationId, String inputId, String outputId, long version) {
        List<Owner> owners = jdbc.query("""
                SELECT archive_issuer, COALESCE(tenant_type, 'customer'), tenant_id, user_id FROM xiaozhi_chat_archive_outbox
                WHERE message_id=? AND conversation_id=?
                """, (rs, row) -> new Owner(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)), inputId, conversationId);
        if (owners.isEmpty()) return;
        insert(owners.getFirst(), conversations.byId(conversationId, false).title(),
                conversations.messageById(conversationId, outputId), version);
    }

    private void insert(Owner owner, String title, StoredMessage message, long version) {
        byte[] payload;
        boolean withinLimits;
        try {
            // The ledger type includes stable attachment IDs and has no URL or credential fields.
            var parts = json.readValue(message.partsJson(), MessagePart[].class);
            Map<String, Object> body = Map.of(
                    "conversation_id", message.conversationId(), "title", title,
                    "tenant_id", owner.tenantId(), "user_id", owner.userId(), "version", Long.toString(version),
                    "message", Map.of("id", message.id(), "seq", Long.toString(message.seq()),
                            "sender_type", message.senderType(), "status", message.status(),
                            "parts", parts, "created_at", message.createdAt().toString()));
            if (!"customer".equals(owner.tenantType())) {
                body = new java.util.LinkedHashMap<>(body);
                body.put("tenant_type", owner.tenantType());
            }
            payload = json.writeValueAsBytes(body);
            withinLimits = parts.length <= 32 && payload.length <= ChatArchiveConfiguration.MAX_PAYLOAD_BYTES;
        } catch (JsonProcessingException invalidLedger) {
            throw new IllegalStateException("Cannot snapshot archive message", invalidLedger);
        }
        jdbc.update("""
                INSERT INTO xiaozhi_chat_archive_outbox
                    (archive_issuer, conversation_id, message_id, event_version, tenant_type, tenant_id, user_id,
                     payload, status, last_error, available_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3))
                """, owner.issuer(), message.conversationId(), message.id(), version, owner.tenantType(), owner.tenantId(), owner.userId(),
                payload, withinLimits ? "pending" : "failed", withinLimits ? null : "PAYLOAD_LIMIT");
    }

    Task claim() {
        List<Long> candidates = jdbc.queryForList("""
                SELECT id FROM xiaozhi_chat_archive_outbox
                WHERE archive_issuer=? AND status='pending' AND available_at<=UTC_TIMESTAMP(3)
                ORDER BY available_at, id LIMIT 1
                """, Long.class, settings.archiveIssuer());
        if (candidates.isEmpty()) return null;
        long id = candidates.getFirst();
        String lease = UUID.randomUUID().toString();
        int claimed = jdbc.update("""
                UPDATE xiaozhi_chat_archive_outbox
                SET lease_token=?, available_at=TIMESTAMPADD(SECOND,30,UTC_TIMESTAMP(3)), attempts=attempts+1
                WHERE id=? AND status='pending' AND available_at<=UTC_TIMESTAMP(3)
                """, lease, id);
        if (claimed == 0) return null;
        return jdbc.queryForObject("""
                SELECT attempts, payload FROM xiaozhi_chat_archive_outbox WHERE id=? AND lease_token=?
                """, (rs, row) -> new Task(id, lease, rs.getInt("attempts"), rs.getBytes("payload")), id, lease);
    }

    void finish(Task task, String status, String error, int delaySeconds) {
        jdbc.update("""
                UPDATE xiaozhi_chat_archive_outbox
                SET status=?, last_error=?, available_at=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(3)), lease_token=NULL
                WHERE id=? AND lease_token=?
                """, status, error, delaySeconds, task.id(), task.lease());
    }
}
