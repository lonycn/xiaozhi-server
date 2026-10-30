package xiaozhi.modules.legacy;

import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

public final class LegacyModels {
    private LegacyModels() { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Session(String sessionId, String firstMessageId, String lastMessageId,
                          long messageCount, Instant createdAt, Instant updatedAt) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Part(String type, String text) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Message(String sourceId, String sessionId, String senderType, List<Part> parts,
                          Instant createdAt, boolean readOnly, boolean hasAudio) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Page<T>(List<T> items, String nextCursor, boolean hasMore) { }
}
