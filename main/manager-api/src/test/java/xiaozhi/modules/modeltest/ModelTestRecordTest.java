package xiaozhi.modules.modeltest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import com.fasterxml.jackson.databind.ObjectMapper;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.model.entity.ModelConfigEntity;
class ModelTestRecordTest {
 @Test void aDifferentRequestCannotReceiveAnotherDraftsRunningTest()throws Exception {
  var db=mock(JdbcTemplate.class);var models=mock(ModelConfigService.class);var probes=mock(ModelProbeExecutor.class);var model=new ModelConfigEntity();model.setId("model");model.setModelType("LLM");when(models.selectById("model")).thenReturn(model);
  when(db.queryForObject("SELECT config_json FROM ai_model_config WHERE id=?",String.class,"model")).thenReturn("{\"type\":\"openai\"}");
  when(db.queryForList("SELECT id FROM ai_model_test_run WHERE actor_id=? AND model_id=? AND status IN ('queued','running') ORDER BY created_at DESC LIMIT 1",String.class,7L,"model")).thenReturn(List.of("other-running-test"));
  var service=new ModelTestService(db,new ObjectMapper(),models,probes,new MockEnvironment());
  try{var error=assertThrows(ModelTestModels.Failure.class,()->service.start(7L,"model",new ModelTestModels.Request(UUID.randomUUID().toString(),null,null,null,null,null,null,null)));assertEquals(409,error.status);assertEquals("TEST_ALREADY_RUNNING",error.code);verifyNoInteractions(probes);}finally{service.close();}
 }
 @Test void internalConfigDoesNotUseThePublicMaskingSerializer(){
  var mapper=new ObjectMapper();var module=new com.fasterxml.jackson.databind.module.SimpleModule();
  module.addSerializer(cn.hutool.json.JSONObject.class,new com.fasterxml.jackson.databind.JsonSerializer<cn.hutool.json.JSONObject>(){public void serialize(cn.hutool.json.JSONObject value,com.fasterxml.jackson.core.JsonGenerator out,com.fasterxml.jackson.databind.SerializerProvider serializers)throws java.io.IOException{out.writeStartObject();out.writeStringField("api_key","masked****key");out.writeEndObject();}});mapper.registerModule(module);
  var model=new ModelConfigEntity();model.setId("model");model.setConfigJson(new cn.hutool.json.JSONObject().set("api_key","masked****key").set("type","openai"));
  var db=mock(JdbcTemplate.class);when(db.queryForObject("SELECT config_json FROM ai_model_config WHERE id=?",String.class,"model")).thenReturn("{\"type\":\"openai\",\"api_key\":\"real-private-key\"}");
  var service=new ModelTestService(db,mapper,mock(ModelConfigService.class),mock(ModelProbeExecutor.class),new MockEnvironment());
  try{assertEquals("masked****key",mapper.valueToTree(model.getConfigJson()).path("api_key").asText());assertEquals("real-private-key",service.rawConfig(model).path("api_key").asText());}finally{service.close();}
 }
 @Test void readsMysqlDatetimeJsonAndTinyintRepresentations()throws Exception {
  var db=mock(JdbcTemplate.class);var models=mock(ModelConfigService.class);var model=new ModelConfigEntity();model.setId("model");when(models.selectById("model")).thenReturn(model);
  var service=new ModelTestService(db,new ObjectMapper(),models,mock(ModelProbeExecutor.class),new MockEnvironment());
  try{
   Map<String,Object> row=new HashMap<>();row.put("id","run");row.put("model_type","TTS");row.put("provider","alibl_stream");row.put("status","succeeded");row.put("scope","synthesis");row.put("summary","ok");row.put("created_at",LocalDateTime.of(2026,11,5,8,0));row.put("audio_available",true);row.put("result_json","{\"output\":\"ok\"}".getBytes());row.put("logs_json","[{\"time\":\"now\",\"elapsedMs\":\"12\",\"stage\":\"DONE\",\"level\":\"info\",\"message\":\"ok\"}]".getBytes());
   when(db.queryForList("SELECT * FROM ai_model_test_run WHERE id=? AND model_id=? AND actor_id=?","run","model",7L)).thenReturn(List.of(row));
   var result=service.get(7L,"model","run");assertTrue(result.audioAvailable());assertEquals("2026-11-05T08:00:00Z",result.createdAt());assertEquals("ok",result.result().path("output").asText());assertEquals(12,result.logs().getFirst().elapsedMs());
  }finally{service.close();}
 }
 @Test void anotherActorCannotReadTheRecord(){
  var db=mock(JdbcTemplate.class);var models=mock(ModelConfigService.class);var model=new ModelConfigEntity();when(models.selectById("model")).thenReturn(model);
  var service=new ModelTestService(db,new ObjectMapper(),models,mock(ModelProbeExecutor.class),new MockEnvironment());
  try{assertEquals(404,assertThrows(ModelTestModels.Failure.class,()->service.get(8,"model","run")).status);}finally{service.close();}
 }
}
