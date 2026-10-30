package xiaozhi.modules.attachment;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

public final class AttachmentModels {
    private AttachmentModels() { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Init(String conversationId, String clientUploadId, String filename, String mimeType, Long size, String sha256) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record JobView(String id, String jobType, String status, int attempt, String errorCode) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record View(String id, String conversationId, String filename, String mimeType, String kind, long size,
            String sha256, String status, String errorCode, Map<String,Object> metadata, List<JobView> jobs, Instant createdAt) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Upload(String method, String url, Instant expiresAt, long maxBytes) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record InitView(String id, String conversationId, String filename, String mimeType, String kind, long size,
            String sha256, String status, String errorCode, Map<String,Object> metadata, List<JobView> jobs, Instant createdAt, Upload upload) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Access(String url, Instant expiresAt, String mimeType, String filename) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Claim(String workerId) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Lease(String leaseToken) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Complete(String leaseToken, Map<String,Object> metadata) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Fail(String leaseToken, String errorCode) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Task(String jobId, String attachmentId, String jobType, int attempt, String leaseToken,
            int leaseSeconds, String filename, String mimeType, long size, String sha256, Map<String,Object> metadata) { }
    public record Empty() { }
}
