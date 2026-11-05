package xiaozhi.modules.esp32gateway;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

/** Keep HTTP semantics and never return SQL, credentials or exception messages. */
@RestControllerAdvice(assignableTypes=Esp32GatewayController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class Esp32GatewayAdvice {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> rejected(ResponseStatusException e){String code=e.getReason();if(code==null||!code.matches("[a-z0-9_]{1,64}"))code="gateway_request_rejected";return ResponseEntity.status(e.getStatusCode()).body(Map.of("code",code));}
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> unavailable(DataAccessException e){return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("code","gateway_storage_unavailable"));}
    @ExceptionHandler({HttpMessageNotReadableException.class,IllegalArgumentException.class})
    public ResponseEntity<?> malformed(Exception e){return ResponseEntity.badRequest().body(Map.of("code","invalid_gateway_request"));}
}
