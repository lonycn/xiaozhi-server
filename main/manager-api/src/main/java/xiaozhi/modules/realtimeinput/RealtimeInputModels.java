package xiaozhi.modules.realtimeinput;

import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.*;
import xiaozhi.modules.realtime.RealtimeModels.*;
import xiaozhi.modules.conversation.ConversationModels.*;

public final class RealtimeInputModels {
    private RealtimeInputModels(){}
    public interface Fenced {String leaseToken();Long controlEpoch();Long turnGeneration();}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Policy(int maxImages,int maxImageBytes,int maxTextChars,int maxSessionMaterials,int maxSessionTokens,String perceptionModel){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PauseRequest(@JsonDeserialize(using=IntegerReader.class) Long expectedEpoch,String clientPauseId){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ResumeRequest(@JsonDeserialize(using=IntegerReader.class) Long expectedEpoch,String clientResumeId){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CancelRequest(@JsonDeserialize(using=IntegerReader.class) Long expectedEpoch,String clientCancelId){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MaterialRequest(@JsonDeserialize(using=IntegerReader.class) Long expectedEpoch,String pauseId,String clientMaterialId,List<MessagePart> parts){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PauseView(String id,String sessionId,String status,@JsonSerialize(using=NumberSerializer.class) long turnGeneration,@JsonFormat(shape=JsonFormat.Shape.STRING) Instant createdAt){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MaterialView(String id,String sessionId,String pauseId,String messageId,String status,@JsonSerialize(using=NumberSerializer.class) long turnGeneration,String errorCode,boolean contextTruncated,@JsonFormat(shape=JsonFormat.Shape.STRING) Instant createdAt){}
    public record Submitted(Message message,MaterialView material){}
    public record State(String phase,PauseView pause,MaterialView material){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Control(String pauseId,String action,String clearRequestId,String resumeRequestId,@JsonSerialize(using=NumberSerializer.class) long turnGeneration,boolean respond,String materialId){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Image(String attachmentId,String variant){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Dispatch(String id,String messageId,@JsonSerialize(using=NumberSerializer.class) long turnGeneration,List<Image> images,String perceptionModel,String prompt){}
    public record Commands(Control inputControl,Dispatch material){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Cleared(String leaseToken,@JsonDeserialize(using=IntegerReader.class) Long controlEpoch,@JsonDeserialize(using=IntegerReader.class) Long turnGeneration,String providerSessionId,String providerEventId,String clearRequestId) implements Fenced{}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Observation(String attachmentId,String model,String text,Usage usage,String providerRequestId){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record UsageReport(String attachmentId,String model,Usage usage,String providerRequestId){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PerceptionUsage(String leaseToken,@JsonDeserialize(using=IntegerReader.class) Long controlEpoch,@JsonDeserialize(using=IntegerReader.class) Long turnGeneration,List<UsageReport> images) implements Fenced{}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Prepare(String leaseToken,@JsonDeserialize(using=IntegerReader.class) Long controlEpoch,@JsonDeserialize(using=IntegerReader.class) Long turnGeneration,List<Observation> images) implements Fenced{}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Prepared(String materialId,String messageId,String contextItemId,String contextText,@JsonSerialize(using=NumberSerializer.class) long turnGeneration){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ContextAck(String leaseToken,@JsonDeserialize(using=IntegerReader.class) Long controlEpoch,@JsonDeserialize(using=IntegerReader.class) Long turnGeneration,String providerSessionId,String providerEventId,String requestItemId,String providerItemId) implements Fenced{}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Resumed(String leaseToken,@JsonDeserialize(using=IntegerReader.class) Long controlEpoch,@JsonDeserialize(using=IntegerReader.class) Long turnGeneration,String resumeRequestId) implements Fenced{}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ResumePermission(@JsonSerialize(using=NumberSerializer.class) long turnGeneration,boolean respond,String pauseId,String materialId){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Failed(String leaseToken,@JsonDeserialize(using=IntegerReader.class) Long controlEpoch,@JsonDeserialize(using=IntegerReader.class) Long turnGeneration,String errorCode) implements Fenced{}
}
