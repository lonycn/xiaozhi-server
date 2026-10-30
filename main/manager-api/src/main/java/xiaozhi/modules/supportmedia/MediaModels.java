package xiaozhi.modules.supportmedia;

import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.annotation.JsonFormat;

public final class MediaModels {
    private MediaModels() { }
    public static final class NumericLong extends com.fasterxml.jackson.databind.JsonSerializer<Long> {
        @Override public void serialize(Long value,com.fasterxml.jackson.core.JsonGenerator gen,com.fasterxml.jackson.databind.SerializerProvider provider)throws java.io.IOException{gen.writeNumber(value);}
    }
    public static final class StrictBoolean extends com.fasterxml.jackson.databind.JsonDeserializer<Boolean> {
        @Override public Boolean deserialize(com.fasterxml.jackson.core.JsonParser parser,com.fasterxml.jackson.databind.DeserializationContext context)throws java.io.IOException {
            if(parser.currentToken()==com.fasterxml.jackson.core.JsonToken.VALUE_TRUE)return true;
            if(parser.currentToken()==com.fasterxml.jackson.core.JsonToken.VALUE_FALSE)return false;
            throw com.fasterxml.jackson.databind.JsonMappingException.from(parser,"Expected a JSON boolean");
        }
    }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Consent(@JsonDeserialize(using=StrictBoolean.class) Boolean microphone,@JsonDeserialize(using=StrictBoolean.class) Boolean camera,@JsonDeserialize(using=StrictBoolean.class) Boolean allowSnapshots) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Create(String requestId,String clientSessionId,String mode,Consent consent) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Epoch(Long expectedEpoch) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Accept(Long expectedEpoch,Consent consent) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TokenRequest(Long expectedEpoch,Integer roomGeneration) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Ready(Long expectedEpoch,Integer roomGeneration,String participantSid,String peerSid,
            @JsonDeserialize(using=StrictBoolean.class) Boolean transportReady,@JsonDeserialize(using=StrictBoolean.class) Boolean receivingAudio,@JsonDeserialize(using=StrictBoolean.class) Boolean receivingVideo) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record End(Long expectedEpoch,String reason) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CreateTransfer(Long expectedEpoch,String clientTransferId,Long targetAgentUserId) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Capture(Long expectedEpoch,Integer roomGeneration,String clientFrameId,String sourceAttachmentId,Instant capturedAt) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Member(String userId,String role,int roomGeneration,String participantIdentity,String status,
            Consent consent,boolean ready,boolean receivingAudio,boolean receivingVideo) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Transfer(String id,String sessionId,String targetAgentUserId,int roomGeneration,String status,
            @JsonSerialize(using=NumericLong.class) long controlEpoch,boolean customerApproved,boolean targetAccepted,
            String errorCode,@JsonFormat(shape=JsonFormat.Shape.STRING) Instant expiresAt,@JsonFormat(shape=JsonFormat.Shape.STRING) Instant createdAt) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Session(String id,String conversationId,String requestId,String mode,String status,
            @JsonSerialize(using=NumericLong.class) long controlEpoch,int currentGeneration,Integer preparingGeneration,
            String assignmentId,String agentUserId,List<Member> participants,Transfer pendingTransfer,
            String cleanupStatus,String errorCode,@JsonFormat(shape=JsonFormat.Shape.STRING) Instant expiresAt,@JsonFormat(shape=JsonFormat.Shape.STRING) Instant createdAt,@JsonFormat(shape=JsonFormat.Shape.STRING) Instant endedAt) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Token(String serverUrl,String participantToken,@JsonFormat(shape=JsonFormat.Shape.STRING) Instant expiresAt,String sessionId,
            @JsonSerialize(using=NumericLong.class) long controlEpoch,int roomGeneration,String participantIdentity,List<String> publishSources) {
        @Override public String toString(){return "MediaToken[redacted]";}
    }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Invitation(String id,String type,String sessionId,String conversationId,String fromUserId,String toUserId,String status,@JsonFormat(shape=JsonFormat.Shape.STRING) Instant expiresAt) { }
}
