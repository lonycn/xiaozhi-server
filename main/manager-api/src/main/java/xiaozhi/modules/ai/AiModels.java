package xiaozhi.modules.ai;

import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

public final class AiModels {
    private AiModels() { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Run(String runId, long listSequence, String conversationId, String inputMessageId,
            String outputMessageId, String status, long controlEpoch, int attempt, String model,
            long lastChunkSequence, String errorCode, JsonNode usage, Instant createdAt, Instant updatedAt) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Claim(String workerId) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Lease(String leaseToken) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Delta(String leaseToken, Long sequence, String text) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Usage(Long inputTokens, Long outputTokens, Long totalTokens,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            Long inputAudioTokens) {
        public Usage(Long inputTokens,Long outputTokens,Long totalTokens) { this(inputTokens,outputTokens,totalTokens,null); }
    }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ProviderRequest(Integer round, String providerRequestId, Usage usage, String finishReason) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Complete(String leaseToken, Long lastSequence, String finishReason, Usage usage, String providerRequestId,
            List<ProviderRequest> providerRequests, Integer toolCount) {
        public Complete(String leaseToken, Long lastSequence, String finishReason, Usage usage, String providerRequestId) {
            this(leaseToken,lastSequence,finishReason,usage,providerRequestId,null,null);
        }
    }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Fail(String leaseToken, String errorCode, Usage usage, List<ProviderRequest> providerRequests, Integer toolCount) {
        public Fail(String leaseToken,String errorCode) { this(leaseToken,errorCode,null,null,null); }
    }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MediaRef(String attachmentId, String variant, String mimeType, String kind, Long timeMs) { }
    public record ContextMessage(String role, String content,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
            List<MediaRef> attachments) {
        public ContextMessage(String role, String content) { this(role, content, null); }
    }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ToolPolicy(int maxRounds,int maxCalls,int maxArgumentBytes,int maxResultBytes,int timeoutSeconds,int maxOutputChars) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Task(String runId, String conversationId, String inputMessageId, String outputMessageId,
            long controlEpoch, int attempt, String leaseToken, int leaseSeconds, String model, List<ContextMessage> messages,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY) List<JsonNode> tools,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) ToolPolicy toolPolicy) {
        public Task(String runId,String conversationId,String inputMessageId,String outputMessageId,long controlEpoch,
                int attempt,String leaseToken,int leaseSeconds,String model,List<ContextMessage> messages) {
            this(runId,conversationId,inputMessageId,outputMessageId,controlEpoch,attempt,leaseToken,leaseSeconds,model,messages,null,null);
        }
    }
    public record Empty() { }
}
