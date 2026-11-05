package xiaozhi.modules.esp32ota;

import java.util.Map;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import xiaozhi.modules.device.controller.OTAController;

@RestControllerAdvice(assignableTypes={Esp32FirmwareController.class,OTAController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class Esp32FirmwareAdvice {
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<?> rejected(ResponseStatusException e){String code=e.getReason();if(code==null||!code.matches("[a-z0-9_]{1,64}"))code="firmware_request_rejected";return ResponseEntity.status(e.getStatusCode()).body(Map.of("code",e.getStatusCode().value(),"msg",code));}
    @ExceptionHandler(DataIntegrityViolationException.class) public ResponseEntity<?> conflict(DataIntegrityViolationException e){return ResponseEntity.status(409).body(Map.of("code",409,"msg","firmware_conflict"));}
    @ExceptionHandler({DataAccessException.class,IOException.class}) public ResponseEntity<?> unavailable(Exception e){return ResponseEntity.status(503).body(Map.of("code",503,"msg","firmware_temporarily_unavailable"));}
    @ExceptionHandler({IllegalArgumentException.class,NullPointerException.class}) public ResponseEntity<?> malformed(Exception e){return ResponseEntity.badRequest().body(Map.of("code",400,"msg","invalid_firmware_request"));}
}
