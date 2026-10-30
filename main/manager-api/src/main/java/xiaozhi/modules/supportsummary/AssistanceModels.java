package xiaozhi.modules.supportsummary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static xiaozhi.modules.supportsummary.SummaryModels.*;

public final class AssistanceModels {
    private AssistanceModels() { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Generate(String clientRequestId,long baseVersion) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Job(String id,String conversationId,String status,long baseVersion,long sourceThroughSeq,
        int attempt,String model,String errorCode,Long resultVersion,Instant createdAt,Instant updatedAt,
        JsonNode usage,String providerRequestId,List<Item> suggestions) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Task(String id,String leaseToken,int leaseSeconds,String model,JsonNode source) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Complete(String leaseToken,Map<String,List<Item>> fields,List<Item> suggestions,
        Usage usage,String providerRequestId) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Usage(long inputTokens,long outputTokens,long totalTokens) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Fail(String leaseToken,String errorCode,Usage usage,String providerRequestId) {
        public Fail(String leaseToken,String errorCode){this(leaseToken,errorCode,null,null);}
    }
}
