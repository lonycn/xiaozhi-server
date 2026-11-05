package xiaozhi.modules.esp32gateway;

import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import xiaozhi.modules.security.user.SecurityUser;
import java.util.*;

@RestController
@ConditionalOnProperty(name="features.esp32-enabled",havingValue="true")
public class Esp32GatewayController {
    private final Esp32GatewayService service;
    public Esp32GatewayController(Esp32GatewayService service){this.service=service;}
    public record Command(String tool, Map<String,Object> arguments, String idempotencyKey, int timeoutSeconds){}
    @GetMapping("/api/v1/esp32/text-input/target") public Object inputTarget(@RequestParam Map<String,String> query){
        if(!query.keySet().equals(Set.of("agent_id","mac_address")))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"invalid_target");
        return service.inputTarget(SecurityUser.getUserId(),query.get("agent_id"),query.get("mac_address"));
    }
    @PostMapping("/api/v1/esp32/devices/{id}/text-input") public Object textInput(@PathVariable String id,jakarta.servlet.http.HttpServletRequest request) throws java.io.IOException {
        byte[] body=request.getAttribute(Esp32DebugInputBoundary.BODY) instanceof byte[] literal ? literal : request.getInputStream().readNBytes(16385);
        try {
            if(body.length>16384||request.getQueryString()!=null)throw new IllegalArgumentException();
            var mapper=new com.fasterxml.jackson.databind.ObjectMapper()
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            var node=mapper.readTree(body);
            if(node==null||!node.isObject()||node.size()!=3)throw new IllegalArgumentException();
            var fields=Set.of("conversationId","text","idempotencyKey");
            node.fieldNames().forEachRemaining(name->{if(!fields.contains(name)||!node.get(name).isTextual())throw new IllegalArgumentException();});
            return service.submitTextOwner(SecurityUser.getUserId(),id,node.get("conversationId").textValue(),node.get("text").textValue(),node.get("idempotencyKey").textValue());
        } catch(com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException e){
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"invalid_text_input");
        }
    }
    @PostMapping("/internal/esp32/register") public Object register(@RequestBody Esp32GatewayService.Register r){return service.register(r);}
    @PostMapping("/internal/esp32/heartbeat") public Object heartbeat(@RequestBody Esp32GatewayService.Fence f){return service.heartbeat(f);}
    @PostMapping("/internal/esp32/poll") public Object poll(@RequestBody Esp32GatewayService.Fence f){return service.poll(f);}
    @PostMapping("/internal/esp32/result") public Object result(@RequestBody Esp32GatewayService.Result r){return service.result(r);}
    @PostMapping("/internal/esp32/disconnect") public Object disconnect(@RequestBody Esp32GatewayService.Fence f){service.disconnect(f);return Map.of("disconnected",true);}
    @GetMapping("/api/v1/esp32/devices/{id}/status") public Object status(@PathVariable String id){return service.status(SecurityUser.getUserId(),id);}
    @GetMapping("/api/v1/esp32/devices/{id}/capabilities") public Object capabilities(@PathVariable String id){return service.status(SecurityUser.getUserId(),id);}
    @PostMapping("/api/v1/esp32/devices/{id}/commands") public Object command(@PathVariable String id,@RequestBody Command r){return service.submitOwner(SecurityUser.getUserId(),id,r.tool,r.arguments,r.idempotencyKey,r.timeoutSeconds);}
    @GetMapping("/api/v1/esp32/commands/{id}") public Object command(@PathVariable String id){return service.command(SecurityUser.getUserId(),id);}
}
