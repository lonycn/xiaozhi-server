package xiaozhi.modules.legacy;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import xiaozhi.common.utils.Result;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = LegacyController.class)
public class LegacyAdvice {
    @ExceptionHandler(LegacyProblem.class)
    public ResponseEntity<Result<Void>> problem(LegacyProblem error) {
        return ResponseEntity.status(error.status()).body(new Result<Void>().error(error.status(), error.getMessage()));
    }
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> invalid(Exception error) {
        return ResponseEntity.badRequest().body(new Result<Void>().error(400, "INVALID_REQUEST"));
    }
}
