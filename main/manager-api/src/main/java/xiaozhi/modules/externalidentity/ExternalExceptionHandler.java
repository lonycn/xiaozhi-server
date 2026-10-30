package xiaozhi.modules.externalidentity;

import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.dao.DataAccessException;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.conversation.ConversationProblem;

@RestControllerAdvice(basePackageClasses=ExternalIdentityController.class)
@Order(-20)
public class ExternalExceptionHandler {
    @ExceptionHandler(ConversationProblem.class) public ResponseEntity<Result<Void>> problem(ConversationProblem error){return failure(error.status(),error.getMessage());}
    @ExceptionHandler(DataAccessException.class) public ResponseEntity<Result<Void>> unavailable(DataAccessException ignored){return failure(503,"IDENTITY_UNAVAILABLE");}
    private ResponseEntity<Result<Void>> failure(int status,String code){return ResponseEntity.status(status).header("Cache-Control","no-store").body(new Result<Void>().error(status,code));}
}
