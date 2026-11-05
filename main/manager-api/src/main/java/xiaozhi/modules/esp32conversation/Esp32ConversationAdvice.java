package xiaozhi.modules.esp32conversation;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import xiaozhi.modules.conversation.ConversationProblem;
import java.util.Map;
@RestControllerAdvice(assignableTypes=Esp32ConversationController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class Esp32ConversationAdvice {
 @ExceptionHandler(ConversationProblem.class) public ResponseEntity<?> problem(ConversationProblem e){String code=e.getMessage();if(code==null||!code.matches("[A-Z0-9_]{1,64}"))code="ESP32_REQUEST_REJECTED";return ResponseEntity.status(e.status()).body(Map.of("code",e.status(),"msg",code));}
 @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<?> source(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).body(Map.of("code",e.getStatusCode().value(),"msg","ESP32_SOURCE_UNAVAILABLE"));}
 @ExceptionHandler(DataAccessException.class) public ResponseEntity<?> storage(DataAccessException e){return ResponseEntity.status(503).body(Map.of("code",503,"msg","ESP32_STORAGE_UNAVAILABLE"));}
}
