package xiaozhi.modules.conversation;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;

import xiaozhi.common.utils.Result;

/** Actual HTTP error status for new APIs; preserve the legacy APIs' response behavior. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {ConversationController.class,
        xiaozhi.modules.ai.AiController.class, xiaozhi.modules.ai.AiWorkerController.class})
public class ConversationExceptionHandler {
    @ExceptionHandler(ConversationProblem.class)
    public ResponseEntity<Result<Void>> problem(ConversationProblem error) {
        return ResponseEntity.status(error.status()).contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(new Result<Void>().error(error.status(), error.getMessage()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Result<Void>> invalidRequest(Exception error) {
        return ResponseEntity.badRequest().contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(new Result<Void>().error(400, "INVALID_REQUEST"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> methodNotAllowed(Exception error) {
        return ResponseEntity.status(405).contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(new Result<Void>().error(405, "METHOD_NOT_ALLOWED"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<Void>> unsupportedMedia(Exception error) {
        return ResponseEntity.status(415).contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(new Result<Void>().error(415, "UNSUPPORTED_MEDIA_TYPE"));
    }
}
