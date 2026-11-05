package xiaozhi.modules.support;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

public final class SupportModels {
    private SupportModels() { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AgentUpdate(String role, Boolean enabled, Integer capacityText, List<String> roles, PublicProfile profile) {
        public AgentUpdate(String role, Boolean enabled, Integer capacityText) { this(role, enabled, capacityText, null, null); }
        public AgentUpdate(String role, Boolean enabled, Integer capacityText, List<String> roles) { this(role, enabled, capacityText, roles, null); }
    }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PublicProfile(String displayName, String avatarUrl, String title, String bio, List<String> specialties, Boolean listed) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Expert(long userId, String displayName, String avatarUrl, String title, String bio,
                         List<String> specialties, boolean online, boolean busy) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PresenceUpdate(Boolean accepting, String serviceType) {
        public PresenceUpdate(Boolean accepting) { this(accepting, null); }
    }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Presence(boolean accepting, Instant presenceExpiresAt) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Handoff(String clientRequestId, String requestedMode, Long targetAgentUserId, String serviceType) {
        public Handoff(String clientRequestId, String requestedMode, Long targetAgentUserId) { this(clientRequestId, requestedMode, targetAgentUserId, null); }
    }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Transfer(String clientRequestId, String serviceType, Long targetAgentUserId, String reason) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EndRequest(String reason) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Agent(long userId, String role, boolean enabled, int capacityText, boolean accepting,
                        Instant presenceExpiresAt, List<String> roles, Map<String,Presence> servicePresence) {
        public Agent(long userId, String role, boolean enabled, int capacityText, boolean accepting, Instant presenceExpiresAt, List<String> roles) {
            this(userId, role, enabled, capacityText, accepting, presenceExpiresAt, roles, Map.of(role, new Presence(accepting, presenceExpiresAt)));
        }
        public Agent(long userId, String role, boolean enabled, int capacityText, boolean accepting, Instant presenceExpiresAt) {
            this(userId, role, enabled, capacityText, accepting, presenceExpiresAt, List.of(role));
        }
    }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AgentSummary(long userId, String username, boolean accountEnabled, String role,
                               boolean enabled, int capacityText, boolean accepting, Instant presenceExpiresAt, List<String> roles, PublicProfile profile) {
        public AgentSummary(long userId,String username,boolean accountEnabled,String role,boolean enabled,int capacityText,boolean accepting,Instant presenceExpiresAt,List<String> roles) {
            this(userId,username,accountEnabled,role,enabled,capacityText,accepting,presenceExpiresAt,roles,null);
        }
        public AgentSummary(long userId,String username,boolean accountEnabled,String role,boolean enabled,int capacityText,boolean accepting,Instant presenceExpiresAt) {
            this(userId,username,accountEnabled,role,enabled,capacityText,accepting,presenceExpiresAt,List.of(role),null);
        }
    }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Request(String requestId, long listSequence, String conversationId, String clientRequestId,
                          String status, String requestedMode, Long targetAgentUserId, Long assignedAgentUserId,
                          Instant requestedAt, Instant assignedAt, Instant firstResponseAt, Instant endedAt,
                          String endReason, String serviceType) {
        public Request(String requestId, long listSequence, String conversationId, String clientRequestId, String status, String requestedMode, Long targetAgentUserId, Long assignedAgentUserId, Instant requestedAt, Instant assignedAt, Instant firstResponseAt, Instant endedAt, String endReason) {
            this(requestId,listSequence,conversationId,clientRequestId,status,requestedMode,targetAgentUserId,assignedAgentUserId,requestedAt,assignedAt,firstResponseAt,endedAt,endReason,"support_agent");
        }
    }
}
