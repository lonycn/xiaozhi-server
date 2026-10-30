package xiaozhi.modules.conversation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import xiaozhi.modules.conversation.ConversationDao.StoredMessage;
import xiaozhi.modules.conversation.ConversationModels.*;

@Service
public class ConversationService {
    private final ConversationDao dao;
    private final ConversationAccessService access;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private xiaozhi.modules.ai.AiExecutionService aiExecution;
    private xiaozhi.modules.attachment.AttachmentService attachments;
    private xiaozhi.modules.realtime.RealtimeHooks realtime;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRealtime(xiaozhi.modules.realtime.RealtimeHooks service) { realtime = service; }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAttachments(xiaozhi.modules.attachment.AttachmentService service) { this.attachments = service; }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAiExecution(xiaozhi.modules.ai.AiExecutionService service) { this.aiExecution = service; }

    public ConversationService(ConversationDao dao, ObjectMapper json, PlatformTransactionManager manager) {
        this.dao = dao;
        this.access = new ConversationAccessService(dao);
        this.json = json;
        this.transactions = new TransactionTemplate(manager);
    }

    /** Strict endpoint-local parsing prevents silently discarding spoofed ownership/context fields. */
    public <T> T request(JsonNode body, Class<T> type) {
        if (body == null || !body.isObject()) {
            throw badRequest("INVALID_REQUEST");
        }
        try {
            return json.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT).readValue(body);
        } catch (JsonProcessingException e) {
            throw badRequest("INVALID_REQUEST");
        } catch (java.io.IOException e) {
            throw badRequest("INVALID_REQUEST");
        }
    }

    public Conversation create(Long userId, CreateConversation request) {
        long owner = authenticated(userId);
        if (request == null || request.title() == null || request.title().isBlank() || request.title().length() > 200) {
            throw badRequest("TITLE_REQUIRED_MAX_200");
        }
        if (request.agentId() != null && !request.agentId().matches("[a-zA-Z0-9_-]{1,32}")) {
            throw badRequest("INVALID_AGENT_ID");
        }
        return transactions.execute(tx -> {
            if (request.agentId() != null && !dao.ownsAgent(request.agentId(), owner)) {
                throw new ConversationProblem(403, "AGENT_ACCESS_DENIED");
            }
            String id = id();
            dao.insertConversation(id, owner, request.agentId(), request.title().strip());
            dao.insertEvent(id(), id, 1, "conversation.created", encode(Map.of("conversation_id", id)));
            dao.advance(id, 0, 1);
            return dao.owned(id, owner, false);
        });
    }

    public CursorPage<Conversation> list(Long userId, long before, int limit) {
        long owner = authenticated(userId);
        page(before, limit);
        var rows = dao.list(owner, before == 0 ? Long.MAX_VALUE : before, limit + 1);
        var items = rows.stream().limit(limit).toList();
        return new CursorPage<>(items, items.isEmpty() ? before : items.getLast().listSequence(), rows.size() > limit);
    }

    public Conversation get(Long userId, String id) {
        long actor = authenticated(userId);
        String validId = conversationId(id);
        return transactions.execute(tx -> access.detail(actor, validId, true));
    }

    public Message send(Long userId, String conversationId, SendMessage request) {
        long owner = authenticated(userId);
        String id = conversationId(conversationId);
        if (request == null || request.clientMessageId() == null
                || !request.clientMessageId().matches("[a-zA-Z0-9][a-zA-Z0-9_.:-]{0,63}")) {
            throw badRequest("INVALID_CLIENT_MESSAGE_ID");
        }
        validateParts(request.parts());
        // The wire shape is fixed and serialized in record order, independent of caller key order.
        String parts = encode(request.parts());
        String hash = hash(parts);
        return transactions.execute(tx -> {
            Conversation conversation = dao.owned(id, owner, true);
            StoredMessage previous = dao.duplicate(id, request.clientMessageId());
            if (previous != null) {
                if (!previous.requestHash().equals(hash) || !Long.toString(owner).equals(previous.senderId())
                        || !"user".equals(previous.senderType())) {
                    throw new ConversationProblem(409, "CLIENT_MESSAGE_ID_REUSED");
                }
                return view(previous);
            }
            long seq = conversation.lastSeq() + 1;
            long eventSeq = conversation.lastEventSeq() + 1;
            String messageId = id();
            dao.insertMessage(messageId, id, seq, request.clientMessageId(), owner, parts, hash);
            bindAttachments(owner, id, messageId, seq, request.parts());
            dao.insertEvent(id(), id, eventSeq, "message.accepted", encode(Map.of("message_id", messageId, "seq", seq)));
            dao.advance(id, seq, eventSeq);
            if ((realtime == null || !realtime.onUserMessage(id, messageId)) && aiExecution != null) aiExecution.onUserMessage(id, messageId);
            return view(dao.duplicate(id, request.clientMessageId()));
        });
    }

    /** The caller's type must agree with the locked, server-managed profile; it never selects identity. */
    public Message sendSupport(Long userId, String conversationId, SendMessage request, String senderType) {
        long actor = authenticated(userId);
        String id = conversationId(conversationId);
        if (request == null || request.clientMessageId() == null
                || !request.clientMessageId().matches("[a-zA-Z0-9][a-zA-Z0-9_.:-]{0,63}")) {
            throw badRequest("INVALID_CLIENT_MESSAGE_ID");
        }
        validateParts(request.parts());
        String parts = encode(request.parts());
        String hash = hash(parts);
        return transactions.execute(tx -> {
            String role = access.lockSenderRole(actor);
            if (!role.equals(senderType)) throw new ConversationProblem(403, "SUPPORT_SENDER_ROLE_MISMATCH");
            Conversation conversation = access.requireCurrentStaff(actor, id);
            StoredMessage previous = dao.duplicate(id, request.clientMessageId());
            if (previous != null) {
                if (!hash.equals(previous.requestHash()) || !Long.toString(actor).equals(previous.senderId())
                        || !role.equals(previous.senderType())) {
                    throw new ConversationProblem(409, "CLIENT_MESSAGE_ID_REUSED");
                }
                return view(previous);
            }
            long seq = conversation.lastSeq() + 1;
            long eventSeq = conversation.lastEventSeq() + 1;
            String messageId = id();
            dao.insertMessage(messageId, id, seq, request.clientMessageId(), actor, parts, hash, role, "completed");
            bindAttachments(actor, id, messageId, seq, request.parts());
            dao.insertEvent(id(), id, eventSeq, "support.message.created",
                    encode(Map.of("message_id", messageId, "seq", seq, "sender_type", role)));
            dao.advance(id, seq, eventSeq);
            return view(dao.duplicate(id, request.clientMessageId()));
        });
    }

    public CursorPage<Message> messages(Long userId, String id, long after, int limit) {
        long actor = authenticated(userId);
        String validId = conversationId(id);
        page(after, limit);
        return transactions.execute(tx -> {
            var grant = access.read(actor, validId, true);
            var rows = dao.messages(validId, after, limit + 1, grant.messageCutoff());
            var items = rows.stream().limit(limit).map(this::view).toList();
            return new CursorPage<>(items, items.isEmpty() ? after : items.getLast().seq(), rows.size() > limit);
        });
    }

    public CursorPage<Event> events(Long userId, String id, long after, int limit) {
        long actor = authenticated(userId);
        String validId = conversationId(id);
        page(after, limit);
        return transactions.execute(tx -> {
            var grant = access.read(actor, validId, true);
            var rows = dao.events(validId, after, limit + 1, grant.eventCutoff());
            var items = rows.stream().limit(limit).map(row -> new Event(row.id(), row.conversationId(),
                    row.seq(), row.type(), decode(row.dataJson(), JsonNode.class), row.createdAt())).toList();
            return new CursorPage<>(items, items.isEmpty() ? after : items.getLast().sequence(), rows.size() > limit);
        });
    }

    private Message view(StoredMessage row) {
        try {
            List<MessagePart> parts = json.readValue(row.partsJson(), new TypeReference<>() { });
            return new Message(row.id(), row.conversationId(), row.seq(), row.clientMessageId(), row.senderType(),
                    row.senderId(), parts, row.status(), row.createdAt());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid persisted message", e);
        }
    }

    private void validateParts(List<MessagePart> parts) {
        if (parts == null || parts.isEmpty() || parts.size() > 16) {
            throw badRequest("PARTS_REQUIRED_MAX_16");
        }
        int textLength = 0, mediaCount = 0, videoCount = 0, audioCount = 0;
        java.util.Set<String> attachmentIds = new java.util.HashSet<>();
        for (MessagePart part : parts) {
            if (part == null || part.type() == null) {
                throw badRequest("INVALID_MESSAGE_PART");
            }
            if ("text".equals(part.type())) {
                if (part.attachmentId() != null || part.text() == null || part.text().isBlank() || part.text().length() > 8000
                        || part.latitude() != null || part.longitude() != null || part.coordinateSystem() != null
                        || part.accuracyM() != null || part.source() != null || part.authorizedAt() != null) {
                    throw badRequest("INVALID_TEXT_PART");
                }
                textLength += part.text().length();
            } else if ("location".equals(part.type())) {
                if (part.attachmentId() != null || part.text() != null || !range(part.latitude(), -90, 90) || !range(part.longitude(), -180, 180)
                        || !("WGS84".equals(part.coordinateSystem()) || "GCJ02".equals(part.coordinateSystem()))
                        || !range(part.accuracyM(), 0, 100000) || !"user_shared".equals(part.source())
                        || part.authorizedAt() == null || part.authorizedAt().isAfter(Instant.now().plusSeconds(300))) {
                    throw badRequest("INVALID_LOCATION_PART");
                }
            } else if (java.util.Set.of("image", "video", "audio", "file").contains(part.type())) {
                if (attachments == null) throw new ConversationProblem(415, "ATTACHMENTS_DISABLED");
                if (part.attachmentId() == null || !part.attachmentId().matches("[1-9][0-9]{0,18}")
                        || !attachmentIds.add(part.attachmentId()) || part.text() != null
                        || part.latitude() != null || part.longitude() != null || part.coordinateSystem() != null
                        || part.accuracyM() != null || part.source() != null || part.authorizedAt() != null) {
                    throw badRequest("INVALID_ATTACHMENT_PART");
                }
                mediaCount++;
                if ("video".equals(part.type())) videoCount++;
                if ("audio".equals(part.type())) audioCount++;
                if (mediaCount > 10 || videoCount > 1 || audioCount > 1) throw badRequest("MESSAGE_MEDIA_LIMIT");
            } else {
                throw new ConversationProblem(415, "MESSAGE_PART_NOT_SUPPORTED");
            }
        }
        if (textLength > 32000) {
            throw badRequest("MESSAGE_TEXT_MAX_32000");
        }
    }

    private void bindAttachments(long actor, String conversation, String message, long seq, List<MessagePart> parts) {
        if (attachments != null) attachments.bind(actor, conversation, message, seq, parts);
    }

    private static boolean range(Double value, double min, double max) {
        return value != null && Double.isFinite(value) && value >= min && value <= max;
    }

    private static long authenticated(Long userId) {
        if (userId == null || userId <= 0) {
            throw new ConversationProblem(401, "AUTHENTICATION_REQUIRED");
        }
        return userId;
    }

    private static String conversationId(String id) {
        if (id == null || !id.matches("[a-f0-9]{32}")) {
            throw new ConversationProblem(404, "CONVERSATION_NOT_FOUND");
        }
        return id;
    }

    private static void page(long cursor, int limit) {
        if (cursor < 0 || limit < 1 || limit > 100) {
            throw badRequest("INVALID_CURSOR_OR_LIMIT");
        }
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static ConversationProblem badRequest(String message) {
        return new ConversationProblem(400, message);
    }

    private String encode(Object data) {
        try {
            return json.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot encode conversation data", e);
        }
    }

    private <T> T decode(String data, Class<T> type) {
        try {
            return json.readValue(data, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid persisted event", e);
        }
    }

    private static String hash(String data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
