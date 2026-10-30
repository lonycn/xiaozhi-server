package xiaozhi.modules.support;

import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authz.AuthorizationException;
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
import xiaozhi.modules.conversation.ConversationProblem;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=SupportController.class)
public class SupportExceptionHandler {
    @ExceptionHandler(ConversationProblem.class)
    public ResponseEntity<Result<Void>> problem(ConversationProblem error) { return error(error.status(),error.getMessage()); }
    @ExceptionHandler(AuthorizationException.class)
    public ResponseEntity<Result<Void>> forbidden(Exception ignored) { return error(403,"SUPPORT_PERMISSION_REQUIRED"); }
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Result<Void>> unauthenticated(Exception ignored) { return error(401,"AUTHENTICATION_REQUIRED"); }
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Result<Void>> invalid(Exception ignored) { return error(400,"INVALID_REQUEST"); }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> method(Exception ignored) { return error(405,"METHOD_NOT_ALLOWED"); }
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<Void>> media(Exception ignored) { return error(415,"UNSUPPORTED_MEDIA_TYPE"); }
    private static ResponseEntity<Result<Void>> error(int status,String code) {
        return ResponseEntity.status(status).body(new Result<Void>().error(status,code));
    }
}
