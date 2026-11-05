package xiaozhi.modules.answerspeech;
import java.io.IOException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import xiaozhi.modules.conversation.ConversationProblem;
final class AnswerSpeechJson {
    private final ObjectMapper json;
    AnswerSpeechJson(ObjectMapper json){this.json=json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);}
    <T>T read(HttpServletRequest request,Class<T> type)throws IOException {noQuery(request);if(request.getContentLengthLong()>16384)throw new ConversationProblem(413,"SPEECH_BODY_LIMIT");byte[] bytes=request.getInputStream().readNBytes(16385);if(bytes.length>16384)throw new ConversationProblem(413,"SPEECH_BODY_LIMIT");try{T value=json.readValue(bytes,type);if(value==null)throw new ConversationProblem(400,"INVALID_SPEECH_REQUEST");return value;}catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new ConversationProblem(400,"INVALID_SPEECH_REQUEST");}}
    static void noQuery(HttpServletRequest request){if(request.getQueryString()!=null)throw new ConversationProblem(400,"INVALID_SPEECH_REQUEST");}
}
