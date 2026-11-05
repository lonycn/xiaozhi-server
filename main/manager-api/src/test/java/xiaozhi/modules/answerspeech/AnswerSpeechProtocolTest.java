package xiaozhi.modules.answerspeech;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import org.springframework.transaction.PlatformTransactionManager;
import xiaozhi.modules.conversation.ConversationProblem;
import static xiaozhi.modules.answerspeech.AnswerSpeechModels.*;
class AnswerSpeechProtocolTest {
    final com.fasterxml.jackson.databind.ObjectMapper json=new xiaozhi.modules.security.config.WebMvcConfig().jackson2HttpMessageConverter().getObjectMapper();
    MockHttpServletRequest request(String text){var request=new MockHttpServletRequest();request.setContent(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));return request;}
    @Test void strictCallbackRejectsDuplicateUnknownTrailingAndNumberCoercion()throws Exception{var reader=new AnswerSpeechJson(json);String body="{\"lease_token\":\""+"a".repeat(64)+"\",\"attempt\":1,\"message_version\":\""+"b".repeat(64)+"\",\"text_sha256\":\""+"c".repeat(64)+"\"}";assertEquals(1,reader.read(request(body),Fence.class).attempt());for(String invalid:new String[]{body.replace("\"attempt\":1","\"attempt\":\"1\""),body.replace("\"attempt\":1","\"attempt\":1.2"),body.replace("\"attempt\":1","\"attempt\":1,\"attempt\":2"),body.replace("\"attempt\":1","\"unknown\":1,\"attempt\":1"),body+" {}"})assertThrows(ConversationProblem.class,()->reader.read(request(invalid),Fence.class));var queried=request(body);queried.setQueryString("ignored=1");assertThrows(ConversationProblem.class,()->reader.read(queried,Fence.class));assertThrows(ConversationProblem.class,()->reader.read(request("x".repeat(16385)),Fence.class));}
    @Test void defaultFeatureOffDoesNotQueryAnyDatabaseOrOpenStorage(){var db=mock(JdbcTemplate.class);var service=new AnswerSpeechService(db,json,mock(PlatformTransactionManager.class),new MockEnvironment());assertFalse(service.enabled());new AnswerSpeechMaintenance(service).tick();assertThrows(ConversationProblem.class,()->service.claim(new Claim("worker")));assertThrows(ConversationProblem.class,()->service.create(1L,"a".repeat(32),"b".repeat(32),new Create("key"),null));verifyNoInteractions(db);}
    @Test void malformedWavCannotBeAcceptedFromClaims(){assertThrows(ConversationProblem.class,()->AnswerSpeechService.validateWav(new byte[100]));byte[] wave=AnswerSpeechPersistenceTest.wav();assertEquals(1000,AnswerSpeechService.validateWav(wave).durationMs());wave[22]=2;assertThrows(ConversationProblem.class,()->AnswerSpeechService.validateWav(wave));}
    @Test void actualWebMapperSerializesQueuedAndCompletedResultEnvelopes()throws Exception{for(Long size:new Long[]{null,121484L}){var job=new Job("a".repeat(32),"b".repeat(32),"c".repeat(32),"d".repeat(64),"e".repeat(64),size==null?"queued":"completed",null,size==null?null:"audio/wav",size,size==null?null:2530,"available",size==null?null:java.time.Instant.now().plusSeconds(5),java.time.Instant.now());var result=json.readTree(json.writeValueAsBytes(new xiaozhi.common.utils.Result<Job>().ok(job)));assertEquals(0,result.path("code").asInt());if(size==null)assertTrue(result.path("data").path("audio_bytes").isNull());else assertTrue(result.path("data").path("audio_bytes").isIntegralNumber());assertEquals(job.messageVersion(),result.path("data").path("message_version").asText());}}
}
