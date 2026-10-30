package xiaozhi.modules.supportsummary;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

public final class SummaryModels {
    private SummaryModels() { }
    public static final List<String> FIELDS = List.of("user_goal", "project/equipment", "symptoms",
            "evidence_attachments", "verified_facts", "hypotheses", "attempted_steps", "missing_information", "safety_flags");
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Evidence(String messageId, long seq, String attachmentId, String quote) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Item(String text, String attribution, List<Evidence> evidence, String verificationNote) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Edit(long baseVersion, Map<String,List<Item>> fields) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Revision(String conversationId, long version, Long parentVersion, String origin,
            Long editedBy, long sourceThroughSeq, boolean sourceTruncated, Instant createdAt,
            Map<String,List<Item>> fields) { }
}
