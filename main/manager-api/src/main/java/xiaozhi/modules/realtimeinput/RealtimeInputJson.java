package xiaozhi.modules.realtimeinput;

import java.io.IOException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import xiaozhi.modules.conversation.ConversationProblem;

final class RealtimeInputJson {
    private final ObjectMapper json;
    RealtimeInputJson(ObjectMapper value){json=value.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);}
    <T>T read(HttpServletRequest request,Class<T> type)throws IOException {
        if(request.getQueryString()!=null)throw new ConversationProblem(400,"INVALID_MATERIAL_REQUEST");
        if(request.getContentLengthLong()>32768)throw new ConversationProblem(413,"MATERIAL_BODY_LIMIT");byte[] bytes=request.getInputStream().readNBytes(32769);if(bytes.length>32768)throw new ConversationProblem(413,"MATERIAL_BODY_LIMIT");
        try{return json.readValue(bytes,type);}catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new ConversationProblem(400,"INVALID_MATERIAL_REQUEST");}
    }
}
