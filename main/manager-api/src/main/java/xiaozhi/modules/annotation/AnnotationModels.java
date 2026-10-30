package xiaozhi.modules.annotation;

import java.time.Instant;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

public final class AnnotationModels {
    private AnnotationModels() { }
    /** Override the legacy MVC Long-to-string serializer only for bounded time measurements. */
    public static final class MeasurementSerializer extends com.fasterxml.jackson.databind.JsonSerializer<Long> {
        @Override public void serialize(Long value,com.fasterxml.jackson.core.JsonGenerator generator,
                com.fasterxml.jackson.databind.SerializerProvider provider)throws java.io.IOException {generator.writeNumber(value);}
    }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CreateFrame(String conversationId,String clientFrameId,String sourceAttachmentId,Long timeMs) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CreateAnnotation(String conversationId,String clientAnnotationId,String frameId,String label,JsonNode scene) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Frame(String id,String conversationId,String sourceAttachmentId,String frameAttachmentId,
            @JsonSerialize(using=MeasurementSerializer.class) Long requestedTimeMs,@JsonSerialize(using=MeasurementSerializer.class) Long actualTimeMs,String capturePolicy,Integer width,Integer height,
            String status,String errorCode,Instant createdAt) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Annotation(String id,String conversationId,String frameId,String renderAttachmentId,String label,
            JsonNode scene,String status,String errorCode,Instant createdAt) { }
    public record Empty() { }
}
