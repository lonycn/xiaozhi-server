package xiaozhi.modules.realtime;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.*;
import xiaozhi.modules.conversation.ConversationModels.Message;

/** IDs are strings; measurements explicitly override the application-wide Long-as-string serializer. */
public final class RealtimeModels {
    private RealtimeModels() { }
    public static class NumberSerializer extends JsonSerializer<Long> {
        @Override public void serialize(Long value,JsonGenerator out,SerializerProvider provider)throws IOException { out.writeNumber(value); }
    }
    public static class IntegerReader extends JsonDeserializer<Long> {
        @Override public Long deserialize(JsonParser parser,DeserializationContext context)throws IOException {
            if(parser.currentToken()!=JsonToken.VALUE_NUMBER_INT) return (Long)context.handleUnexpectedToken(Long.class,parser);
            return parser.getLongValue();
        }
    }
    public static class BooleanReader extends JsonDeserializer<Boolean> {
        @Override public Boolean deserialize(JsonParser parser,DeserializationContext context)throws IOException {
            if(parser.currentToken()!=JsonToken.VALUE_TRUE && parser.currentToken()!=JsonToken.VALUE_FALSE)
                return (Boolean)context.handleUnexpectedToken(Boolean.class,parser);
            return parser.getBooleanValue();
        }
    }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Consent(@JsonDeserialize(using=BooleanReader.class) Boolean microphone,@JsonDeserialize(using=BooleanReader.class) Boolean camera) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Create(String conversationId,String clientSessionId,Consent consent) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Epoch(@JsonDeserialize(using=IntegerReader.class) Long expectedEpoch) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Send(@JsonDeserialize(using=IntegerReader.class) Long expectedEpoch,String clientMessageId,String text) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Interrupt(@JsonDeserialize(using=IntegerReader.class) Long expectedEpoch,String clientCommandId,String responseId) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Consume(String ticket,String workerId,String serverId) { @Override public String toString(){return "Consume[capability=REDACTED]";} }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Heartbeat(String leaseToken,@JsonDeserialize(using=BooleanReader.class) Boolean inputBusy) { @Override public String toString(){return "Heartbeat[lease=REDACTED]";} }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Usage(@JsonSerialize(using=NumberSerializer.class) @JsonDeserialize(using=IntegerReader.class) Long inputTokens,@JsonSerialize(using=NumberSerializer.class) @JsonDeserialize(using=IntegerReader.class) Long outputTokens,@JsonSerialize(using=NumberSerializer.class) @JsonDeserialize(using=IntegerReader.class) Long totalTokens) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ProviderEvent(String leaseToken,@JsonDeserialize(using=IntegerReader.class) Long sequence,String providerEventId,String type,String providerSessionId,
            String providerItemId,String responseId,String injectionId,String commandId,String text,String finishReason,Usage usage,String errorCode,String requestItemId) {
        public ProviderEvent(String leaseToken,Long sequence,String providerEventId,String type,String providerSessionId,
                String providerItemId,String responseId,String injectionId,String commandId,String text,String finishReason,Usage usage,String errorCode) {
            this(leaseToken,sequence,providerEventId,type,providerSessionId,providerItemId,responseId,injectionId,commandId,text,finishReason,usage,errorCode,null);
        }
        @Override public String toString(){return "ProviderEvent[type="+type+", lease=REDACTED]";}
    }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Session(String id,String conversationId,String status,@JsonSerialize(using=NumberSerializer.class) long controlEpoch,
            String model,Consent consent,String providerSessionId,@JsonSerialize(using=NumberSerializer.class) long lastEventSequence,
            String activeResponseId,String activeMessageId,String errorCode,@JsonFormat(shape=JsonFormat.Shape.STRING) Instant createdAt,
            @JsonFormat(shape=JsonFormat.Shape.STRING) Instant connectedAt,@JsonFormat(shape=JsonFormat.Shape.STRING) Instant endedAt) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Ticket(String sessionId,@JsonSerialize(using=NumberSerializer.class) long controlEpoch,String websocketUrl,String ticket,
            @JsonFormat(shape=JsonFormat.Shape.STRING) Instant expiresAt) {
        @Override public String toString(){return "Ticket[sessionId="+sessionId+", capability=REDACTED]";}
    }
    public record Context(String role,String content) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Limits(int heartbeatSeconds,int maxSessionSeconds,int maxAudioChunkBytes,int maxAudioTurnBytes,int maxFrameBytes,
            int maxFramePixels,int minFrameIntervalMs,int maxEventTextChars,int maxOutputChars) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AudioFormat(String encoding,int sampleRate,int channels) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Task(String sessionId,String conversationId,@JsonSerialize(using=NumberSerializer.class) long controlEpoch,String leaseToken,String serverId,
            int leaseSeconds,String model,Consent consent,List<Context> context,@JsonSerialize(using=NumberSerializer.class) long contextSeq,
            Limits limits,AudioFormat inputAudio,AudioFormat outputAudio) {
        @Override public String toString(){return "Task[sessionId="+sessionId+", lease=REDACTED]";}
    }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Injection(String id,String sessionId,String messageId,@JsonSerialize(using=NumberSerializer.class) long sequence,
            String status,String providerItemId,String errorCode,@JsonFormat(shape=JsonFormat.Shape.STRING) Instant createdAt,
            @JsonFormat(shape=JsonFormat.Shape.STRING) Instant acknowledgedAt) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Dispatch(String id,String messageId,String text,String providerItemId) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Command(String id,String sessionId,String responseId,String type,String status,String errorCode) { }
    public record Sent(Message message,Injection injection) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Pulse(boolean active,int leaseSeconds,List<Dispatch> injections,List<Command> commands) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Receipt(@JsonSerialize(using=NumberSerializer.class) long sequence,Session session,String messageId,String messageStatus) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Page<T>(List<T> items,@JsonSerialize(using=NumberSerializer.class) long nextCursor,boolean hasMore) { }
}
