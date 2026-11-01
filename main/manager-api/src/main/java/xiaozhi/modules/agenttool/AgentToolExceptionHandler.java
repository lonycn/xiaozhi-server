package xiaozhi.modules.agenttool;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import xiaozhi.common.utils.Result;

/** Tool ledgers fail closed without returning SQL, table names or database details. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes={AgentToolController.class,AgentToolWorkerController.class})
public class AgentToolExceptionHandler {
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Result<Void>> unavailable(DataAccessException error) {
        return ResponseEntity.status(503).body(new Result<Void>().error(503,"AGENT_TOOLS_UNAVAILABLE"));
    }
}
