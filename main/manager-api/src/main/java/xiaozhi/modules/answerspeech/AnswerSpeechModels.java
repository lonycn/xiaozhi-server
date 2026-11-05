package xiaozhi.modules.answerspeech;

import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.*;

public final class AnswerSpeechModels {
    private AnswerSpeechModels(){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Create(String clientRequestId){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Claim(String workerId){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Fence(String leaseToken,Integer attempt,String messageVersion,String textSha256){}
    public record Usage(@JsonDeserialize(using=StrictLong.class) Long characters){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Finish(String leaseToken,Integer attempt,String messageVersion,String textSha256,String audioSha256,Usage usage,String providerRequestId){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Failed(String leaseToken,Integer attempt,String messageVersion,String textSha256,String errorCode,Usage usage,String providerRequestId){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record UsageReport(String leaseToken,Integer attempt,String messageVersion,String textSha256,Usage usage,String providerRequestId){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Policy(int maxTextChars,int maxAudioBytes,int maxDurationMs){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Audio(String encoding,int sampleRate,int channels,String container){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Task(String jobId,String conversationId,String messageId,String messageVersion,String textSha256,String text,int attempt,String leaseToken,int leaseSeconds,String model,String voice,Audio audio,Policy policy){@Override public String toString(){return "AnswerSpeechTask[redacted]";}}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Job(String id,String conversationId,String messageId,String messageVersion,String textSha256,String status,String errorCode,String audioMime,@JsonSerialize(using=LongNumber.class) Long audioBytes,Integer durationMs,String resultAccess,@JsonFormat(shape=JsonFormat.Shape.STRING) Instant playbackAuthorizedUntil,@JsonFormat(shape=JsonFormat.Shape.STRING) Instant createdAt){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Uploaded(String audioSha256,long audioBytes,int durationMs){}
    public static class LongNumber extends com.fasterxml.jackson.databind.JsonSerializer<Long>{
        @Override public void serialize(Long value,com.fasterxml.jackson.core.JsonGenerator generator,com.fasterxml.jackson.databind.SerializerProvider provider)throws java.io.IOException{generator.writeNumber(value);}
    }
    public static class StrictLong extends com.fasterxml.jackson.databind.JsonDeserializer<Long>{
        @Override public Long deserialize(com.fasterxml.jackson.core.JsonParser p,com.fasterxml.jackson.databind.DeserializationContext context)throws java.io.IOException {
            if(p.currentToken()!=com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT)throw context.weirdStringException("",Long.class,"Integral JSON number required");return p.getLongValue();
        }
    }
}
