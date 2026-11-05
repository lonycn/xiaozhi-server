package xiaozhi.modules.esp32conversation;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import xiaozhi.modules.conversation.ConversationProblem;

@RestControllerAdvice(assignableTypes={Esp32ConversationController.class, Esp32IntentController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class Esp32ConversationExceptionHandler {
    @ExceptionHandler(ConversationProblem.class)
    public ResponseEntity<java.util.Map<String,Object>> problem(ConversationProblem e){return ResponseEntity.status(e.status()).body(java.util.Map.of("error",e.getMessage()));}
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<java.util.Map<String,Object>> gateway(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).body(java.util.Map.of("error",e.getReason()==null?"DEVICE_UNAVAILABLE":e.getReason()));}
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<java.util.Map<String,Object>> invalid(Exception e){return ResponseEntity.badRequest().body(java.util.Map.of("error","INVALID_REQUEST"));}
}
