package xiaozhi.modules.esp32gateway;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class Esp32GatewayValidationTest {
 @Test void strictAllowlistAndIntegerVolume(){
  Esp32GatewayService.validateCommand("self.get_device_status",Map.of());
  Esp32GatewayService.validateCommand("self.audio_speaker.set_volume",Map.of("volume",100));
  for(Object volume:List.of(-1,101,1.5,"50",50L))assertThrows(ResponseStatusException.class,()->Esp32GatewayService.validateCommand("self.audio_speaker.set_volume",Map.of("volume",volume)));
  assertThrows(ResponseStatusException.class,()->Esp32GatewayService.validateCommand("self.reboot",Map.of()));
  assertThrows(ResponseStatusException.class,()->Esp32GatewayService.validateCommand("self.get_device_status",Map.of("url","http://localhost")));
 }
 @Test void fenceNumbersOverrideGlobalLongStringSerializer()throws Exception{
  var mapper=new com.fasterxml.jackson.databind.ObjectMapper();var module=new com.fasterxml.jackson.databind.module.SimpleModule();module.addSerializer(Long.class,com.fasterxml.jackson.databind.ser.std.ToStringSerializer.instance);module.addSerializer(Long.TYPE,com.fasterxml.jackson.databind.ser.std.ToStringSerializer.instance);mapper.registerModule(module);
  var ctx=new Esp32GatewayService.DeviceContext("device",1L,"agent","b".repeat(64),"connection",9,"gw",123456789L);
  var tree=mapper.readTree(mapper.writeValueAsString(ctx));assertTrue(tree.get("generation").isIntegralNumber());assertTrue(tree.get("leaseUntil").isIntegralNumber());assertTrue(mapper.readTree(mapper.writeValueAsString(Esp32GatewayService.fence(ctx))).get("generation").isIntegralNumber());
 }
 @Test void resultStatusPreservesLegacyBooleanButRejectsContradictions(){
  var f=new Esp32GatewayService.Fence("device","connection",1,"gateway","b".repeat(64));
  assertEquals("succeeded",new Esp32GatewayService.Result(f,"command",true,Map.of()).outcome());assertEquals("failed",new Esp32GatewayService.Result(f,"command",false,Map.of()).outcome());
  assertEquals("unknown",new Esp32GatewayService.Result(f,"command",null,Map.of(),"unknown").outcome());
  assertThrows(Exception.class,()->new Esp32GatewayService.Result(f,"command",true,Map.of(),"unknown"));assertThrows(Exception.class,()->new Esp32GatewayService.Result(f,"command",false,Map.of(),"succeeded"));assertThrows(Exception.class,()->new Esp32GatewayService.Result(f,"command",null,Map.of(),null));
 }
 @Test void otaProofIsBoundToMacClientAndTime() throws Exception {
  String secret="isolated-test-secret-with-enough-entropy",client="sim",device="aa:bb:cc:dd:ee:ff";
  long now=System.currentTimeMillis()/1000;
  String token=token(secret,client,device,now);
  Esp32GatewayService.verifyToken(token,client,device,secret);
  assertThrows(ResponseStatusException.class,()->Esp32GatewayService.verifyToken(token,"other",device,secret));
  assertThrows(ResponseStatusException.class,()->Esp32GatewayService.verifyToken(token,client,"other",secret));
  assertThrows(ResponseStatusException.class,()->Esp32GatewayService.verifyToken(token(secret,client,device,now+120),client,device,secret));
  assertThrows(ResponseStatusException.class,()->Esp32GatewayService.verifyToken(token(secret,client,device,now-2592100),client,device,secret));
 }
 static String token(String secret,String client,String device,long ts)throws Exception{Mac h=Mac.getInstance("HmacSHA256");h.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return Base64.getUrlEncoder().withoutPadding().encodeToString(h.doFinal((client+"|"+device+"|"+ts).getBytes(StandardCharsets.UTF_8)))+"."+ts;}
}
