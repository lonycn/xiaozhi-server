package xiaozhi.modules.realtime;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.conversation.ConversationProblem;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes={RealtimeController.class,RealtimeWorkerController.class})
public class RealtimeExceptionHandler {
    @ExceptionHandler(ConversationProblem.class) public ResponseEntity<Result<Void>> problem(ConversationProblem error){return ResponseEntity.status(error.status()).body(new Result<Void>().error(error.status(),error.getMessage()));}
    @ExceptionHandler({HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,org.springframework.web.bind.MissingServletRequestParameterException.class}) public ResponseEntity<Result<Void>> invalid(Exception error){return ResponseEntity.badRequest().body(new Result<Void>().error(400,"INVALID_REQUEST"));}
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class) public ResponseEntity<Result<Void>> method(Exception error){return ResponseEntity.status(405).body(new Result<Void>().error(405,"METHOD_NOT_ALLOWED"));}
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class) public ResponseEntity<Result<Void>> media(Exception error){return ResponseEntity.status(415).body(new Result<Void>().error(415,"UNSUPPORTED_MEDIA_TYPE"));}
}
