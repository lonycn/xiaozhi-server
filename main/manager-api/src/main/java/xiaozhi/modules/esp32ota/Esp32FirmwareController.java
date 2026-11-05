package xiaozhi.modules.esp32ota;

import java.io.IOException;
import java.util.Map;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.security.user.SecurityUser;

@RestController
@ConditionalOnProperty(name="features.esp32-ota-enabled",havingValue="true")
public class Esp32FirmwareController {
    private final Esp32FirmwareService service;
    private final ObjectMapper json;
    public Esp32FirmwareController(Esp32FirmwareService service,ObjectMapper mapper){this.service=service;this.json=mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);}
    static long admin(){var user=SecurityUser.getUser();if(user.getId()==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"authentication_required");if(!Integer.valueOf(1).equals(user.getSuperAdmin()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"firmware_admin_required");return user.getId();}
    private <T>T body(HttpServletRequest request,Class<T> type)throws IOException {
        if(request.getContentType()==null||!request.getContentType().toLowerCase(java.util.Locale.ROOT).matches("application/json(?:\\s*;.*)?"))throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"json_required");
        byte[] bytes=request.getInputStream().readNBytes(16385);if(bytes.length>16384)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"firmware_request_limit");
        try{T value=json.readValue(bytes,type);if(value==null)throw new IllegalArgumentException();return value;}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid_firmware_request");}
    }
    @PostMapping(value="/api/v1/esp32/firmware/artifacts/{kind}",consumes=MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public Object upload(@PathVariable String kind,HttpServletRequest request)throws IOException{admin();return new Result<>().ok(service.upload(kind,request.getInputStream()));}
    @PostMapping("/api/v1/esp32/firmware/releases") public Object create(HttpServletRequest request)throws IOException{long actor=admin();return new Result<>().ok(service.create(actor,body(request,Esp32FirmwareService.ReleaseRequest.class)));}
    @GetMapping("/api/v1/esp32/firmware/releases") public Object releases(){admin();return new Result<>().ok(service.releases());}
    @GetMapping("/api/v1/esp32/firmware/settings") public Object settings(){admin();return new Result<>().ok(service.settings());}
    @PostMapping("/api/v1/esp32/firmware/releases/{id}/{state:published|paused}") public Object state(@PathVariable String id,@PathVariable String state)throws IOException{admin();return new Result<>().ok(service.state(id,state));}
    @PostMapping("/api/v1/esp32/firmware/releases/{id}/assignments") public Object assign(@PathVariable String id,HttpServletRequest request)throws IOException{long actor=admin();return new Result<>().ok(service.assign(actor,id,body(request,Esp32FirmwareService.Assignment.class)));}
    @GetMapping("/api/v1/esp32/firmware/devices/{device}/assignments") public Object assignments(@PathVariable String device){admin();return new Result<>().ok(service.assignments(device));}
    @PostMapping("/api/v1/esp32/firmware/assignments/{id}/pause") public Object pause(@PathVariable String id){admin();service.pauseAssignment(id);return new Result<>().ok(Map.of("paused",true));}
    @GetMapping("/api/v1/esp32/firmware/assignments/{id}/events") public Object events(@PathVariable String id){admin();return new Result<>().ok(service.events(id));}
    @PostMapping("/api/v1/esp32/firmware/assignments/{id}/usb-bootstrap") public Object usbBootstrap(@PathVariable String id,HttpServletRequest request)throws IOException{long actor=admin();return new Result<>().ok(service.usbBootstrap(actor,id,body(request,Esp32FirmwareService.UsbBootstrap.class)));}
    @GetMapping("/ota/esp32/releases/{id}/{kind}") public ResponseEntity<FileSystemResource> download(@PathVariable String id,@PathVariable String kind,@RequestParam String ticket,HttpServletRequest request)throws IOException{
        if(request.getHeader("Range")!=null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"range_not_supported");var item=service.download(id,kind,ticket);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(item.size()).header("Cache-Control","private, no-store").header("X-Content-Type-Options","nosniff").header("Content-Disposition","attachment; filename=\""+item.filename()+"\"").body(new FileSystemResource(item.path()));
    }
    @PostMapping("/ota/esp32/receipts") public ResponseEntity<Void> receipt(HttpServletRequest request)throws IOException{
        var r=body(request,Esp32FirmwareService.Receipt.class);service.receipt(request.getHeader("Device-Id"),request.getHeader("Client-Id"),request.getHeader("Authorization"),r);return ResponseEntity.noContent().build();
    }
}
