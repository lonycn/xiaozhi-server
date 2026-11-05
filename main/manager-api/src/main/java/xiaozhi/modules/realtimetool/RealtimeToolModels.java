package xiaozhi.modules.realtimetool;

import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.*;
import xiaozhi.modules.realtime.RealtimeModels.IntegerReader;
import xiaozhi.modules.realtime.RealtimeModels.NumberSerializer;

public final class RealtimeToolModels {
    private RealtimeToolModels(){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Policy(int maxRounds,int maxCalls,int maxArgumentBytes,int maxResultBytes,int timeoutSeconds,
            int maxOutputChars,int maxSessionCalls,int maxSessionResultBytes,int maxSessionProviderSteps,int maxSessionTokens){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Catalog(List<JsonNode> tools,Policy toolPolicy,@JsonSerialize(using=NumberSerializer.class) Long turnGeneration){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Call(String providerItemId,String providerCallId,String name,JsonNode arguments){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Proposal(String leaseToken,@JsonDeserialize(using=IntegerReader.class) Long controlEpoch,
            @JsonDeserialize(using=IntegerReader.class) Long turnGeneration,String providerSessionId,String responseId,
            int round,List<Call> calls){@Override public String toString(){return "RealtimeToolProposal[redacted]";}}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ToolResult(String providerCallId,String providerItemId,String resultItemId,String name,String status,JsonNode output,String errorCode){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Results(List<ToolResult> results,int remainingCalls,@JsonSerialize(using=NumberSerializer.class) long turnGeneration){}
}
