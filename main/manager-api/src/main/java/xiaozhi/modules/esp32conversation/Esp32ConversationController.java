package xiaozhi.modules.esp32conversation;

import java.util.*;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import xiaozhi.modules.esp32gateway.Esp32GatewayService;
import xiaozhi.modules.esp32gateway.Esp32GatewayService.Fence;
import xiaozhi.modules.conversation.ConversationProblem;
import xiaozhi.modules.security.user.SecurityUser;

@RestController
@ConditionalOnProperty(name="features.esp32-enabled",havingValue="true")
public class Esp32ConversationController {
    private final Esp32ConversationService sessions;
    private final Esp32GatewayService gateway;
    private final ObjectMapper json;
    public Esp32ConversationController(Esp32ConversationService sessions,Esp32GatewayService gateway,ObjectMapper json){this.sessions=sessions;this.gateway=gateway;this.json=json;}
    public record Connect(Fence fence,String executionMode){}
    public record Input(Fence fence,String sessionId,String inputKey,String text,String rolePrompt){}
    public record Output(Fence fence,String sessionId,String turnId,String text){}
    public record Control(Fence fence,String sessionId){}
    public record AbortTurn(Fence fence,String sessionId,String turnId,long controlVersion){}
    public record Report(Fence fence,String sessionId,String turnId,String eventKey,String eventType,String data){}
    public record Reply(Fence fence,String sessionId,String turnId){}
    private <T>T parse(String body,Class<T> type){
        if(body==null||body.length()>(type==Input.class?120000:20000))throw new ConversationProblem(400,"INVALID_REQUEST");
        try {
            ObjectMapper strict=json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            JsonNode node=strict.readTree(body);
            if(node==null||!node.isObject())throw new IllegalArgumentException();
            Set<String> fields=new HashSet<>();
            for(var component:type.getRecordComponents())fields.add(component.getName());
            if(type==Input.class && !node.has("rolePrompt"))fields.remove("rolePrompt");
            exact(node,fields);
            JsonNode fence=node.get("fence");
            exact(fence,Set.of("deviceId","connectionId","generation","gatewayId","bindingVersion"));
            if(!fence.get("generation").isIntegralNumber()||!fence.get("generation").canConvertToLong()||fence.get("generation").longValue()<1)throw new IllegalArgumentException();
            for(String name:List.of("deviceId","connectionId","gatewayId","bindingVersion"))wireText(fence.get(name),128);
            for(String name:fields)if(!name.equals("fence")){
                if(name.equals("controlVersion")){var version=node.get(name);if(!version.isIntegralNumber()||!version.canConvertToLong()||version.longValue()<0)throw new IllegalArgumentException();}
                else wireText(node.get(name),(name.equals("text")||name.equals("data")||name.equals("rolePrompt"))?16000:64);
            }
            return strict.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT).readValue(node);
        } catch(Exception e){throw new ConversationProblem(400,"INVALID_REQUEST");}
    }
    private static void exact(JsonNode node,Set<String> fields){
        if(node==null||!node.isObject()||node.size()!=fields.size())throw new IllegalArgumentException();
        node.fieldNames().forEachRemaining(name->{if(!fields.contains(name))throw new IllegalArgumentException();});
    }
    private static void wireText(JsonNode node,int max){if(node==null||!node.isTextual()||node.textValue().isBlank()||node.textValue().length()>max)throw new IllegalArgumentException();}
    private Esp32ConversationService.TrustedContext trusted(Fence f){if(f==null)throw new ConversationProblem(400,"FENCE_REQUIRED");var c=gateway.current(f.deviceId());if(!Esp32GatewayService.fence(c).equals(f))throw new ConversationProblem(409,"STALE_CONNECTION");return Esp32ContextGuard.trusted(c);}
    @PostMapping("/internal/esp32/sessions/connect") public Map<String,Object> connect(@RequestBody String body){var r=parse(body,Connect.class);return sessions.connect(trusted(r.fence()),r.executionMode());}
    @PostMapping("/internal/esp32/sessions/input") public Map<String,Object> input(@RequestBody String body){var r=parse(body,Input.class);return sessions.input(trusted(r.fence()),r.sessionId(),r.inputKey(),r.text(),r.rolePrompt());}
    @PostMapping("/internal/esp32/sessions/output") public Map<String,Object> output(@RequestBody String body){var r=parse(body,Output.class);return sessions.output(trusted(r.fence()),r.sessionId(),r.turnId(),r.text());}
    @PostMapping("/internal/esp32/sessions/event") public Map<String,Object> report(@RequestBody String body){var r=parse(body,Report.class);return sessions.report(trusted(r.fence()),r.sessionId(),r.turnId(),r.eventKey(),r.eventType(),r.data());}
    @PostMapping("/internal/esp32/sessions/abort") public void abort(@RequestBody String body){var r=parse(body,Control.class);sessions.abort(trusted(r.fence()),r.sessionId());}
    @PostMapping("/internal/esp32/sessions/abort-turn") public void abortTurn(@RequestBody String body){var r=parse(body,AbortTurn.class);sessions.abortTurn(trusted(r.fence()),r.sessionId(),r.turnId(),r.controlVersion());}
    @PostMapping("/internal/esp32/sessions/reply") public Map<String,Object> reply(@RequestBody String body){var r=parse(body,Reply.class);return sessions.reply(trusted(r.fence()),r.sessionId(),r.turnId());}
    @GetMapping("/api/v1/esp32/devices/{deviceId}/sessions") public List<Map<String,Object>> list(@PathVariable String deviceId,@RequestParam(defaultValue="50")int limit){return sessions.list(SecurityUser.getUserId(),deviceId,limit);}
    @GetMapping("/api/v1/esp32/sessions/{sessionId}/turns") public List<Map<String,Object>> turns(@PathVariable String sessionId,@RequestParam(defaultValue="0")long after,@RequestParam(defaultValue="50")int limit){return sessions.turns(SecurityUser.getUserId(),sessionId,after,limit);}
}
