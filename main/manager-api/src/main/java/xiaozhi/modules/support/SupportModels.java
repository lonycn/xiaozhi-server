package xiaozhi.modules.support;

import java.time.Instant;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

public final class SupportModels {
    private SupportModels() { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AgentUpdate(String role, Boolean enabled, Integer capacityText) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PresenceUpdate(Boolean accepting) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Handoff(String clientRequestId, String requestedMode, Long targetAgentUserId) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EndRequest(String reason) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Agent(long userId, String role, boolean enabled, int capacityText, boolean accepting,
                        Instant presenceExpiresAt) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AgentSummary(long userId, String username, boolean accountEnabled, String role,
                               boolean enabled, int capacityText, boolean accepting, Instant presenceExpiresAt) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Request(String requestId, long listSequence, String conversationId, String clientRequestId,
                          String status, String requestedMode, Long targetAgentUserId, Long assignedAgentUserId,
                          Instant requestedAt, Instant assignedAt, Instant firstResponseAt, Instant endedAt,
                          String endReason) { }
}
