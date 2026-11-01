package xiaozhi.modules.agenttool;

import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

public final class AgentToolModels {
    private AgentToolModels() { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Catalog(List<JsonNode> tools,int maxRounds,int maxCalls) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Proposal(String leaseToken,int attempt,long controlEpoch,int round,List<Call> calls) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Call(String providerCallId,String name,JsonNode arguments) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ToolResult(String providerCallId,String name,String status,JsonNode output,String errorCode) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record BatchResult(List<ToolResult> results,int remainingCalls) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record History(@JsonSerialize(using=ToStringSerializer.class) long sequence,String callId,String runId,String providerCallId,String name,int attempt,int round,
                          String status,String errorCode,@JsonSerialize(using=ToStringSerializer.class) long eventSequence,String createdAt) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record HistoryPage(List<History> items,@JsonSerialize(using=ToStringSerializer.class) long nextCursor,boolean hasMore) { }
}
