package xiaozhi.modules.conversation;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Wire contracts for the persistent ledger; callers never select their sender or owner. */
public final class ConversationModels {
    private ConversationModels() { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CreateConversation(String title, String agentId) { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SendMessage(String clientMessageId, List<MessagePart> parts) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MessagePart(String type, String text, Double latitude, Double longitude,
            String coordinateSystem, Double accuracyM, String source, Instant authorizedAt) { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Conversation(String id, long listSequence, String agentId, String title,
            String state, long lastSeq, long lastEventSeq, Instant createdAt, Instant updatedAt) { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Message(String id, String conversationId, long seq, String clientMessageId,
            String senderType, String senderId, List<MessagePart> parts, String status, Instant createdAt) { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Event(String eventId, String conversationId, long sequence, String type,
            JsonNode data, Instant timestamp) { }

    /** The next cursor always advances to the last returned item, even on the final page. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CursorPage<T>(List<T> items, long nextCursor, boolean hasMore) { }
}
