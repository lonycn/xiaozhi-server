package xiaozhi.modules.modeltest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.net.*;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import static xiaozhi.modules.modeltest.ModelTestModels.*;

class ModelProbeTest {
    final ObjectMapper json=new ObjectMapper();final DefaultModelProbeExecutor executor=new DefaultModelProbeExecutor(json);
    HttpServer server;String origin;JsonNode sent;String route;AtomicInteger requests;
    @BeforeEach void start()throws Exception {
        requests=new AtomicInteger();server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            try{requests.incrementAndGet();route=exchange.getRequestURI().getPath();sent=json.readTree(exchange.getRequestBody());
                byte[] data=(route.contains("datasets")?"{\"code\":0,\"data\":[]}":"{\"choices\":[{\"message\":{\"content\":\"连接成功\"}}],\"usage\":{\"total_tokens\":8}}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,data.length);exchange.getResponseBody().write(data);
            }finally{exchange.close();}
        });server.start();origin="http://127.0.0.1:"+server.getAddress().getPort();
    }
    @AfterEach void stop(){server.stop(0);}
    Probe probe(String type,String provider){ObjectNode c=json.createObjectNode().put("type",provider).put("base_url",origin).put("model_name","fixture");return new Probe(type,c,null,null,null,null,null,null);}
    @Test void languageProbeCallsInferenceOnceAndBoundsOutput()throws Exception {
        Outcome result=executor.execute(probe("LLM","openai"),(a,b)->{});
        assertEquals("succeeded",result.status());assertEquals("连接成功",result.output());assertEquals(1,requests.get());
        assertEquals("/chat/completions",route);assertEquals(128,sent.path("max_tokens").asInt());assertFalse(sent.path("stream").asBoolean());
        assertFalse(sent.has("tools"));
    }
    @Test void visionProbeSendsActualImageContent()throws Exception {
        executor.execute(probe("VLLM","openai"),(a,b)->{});
        String image=sent.path("messages").path(0).path("content").path(1).path("image_url").path("url").asText();
        assertTrue(image.startsWith("data:image/png;base64,"));assertTrue(Base64.getDecoder().decode(image.split(",")[1]).length>100);
    }
    @Test void ollamaKeepsTheConfiguredOpenAiCompatiblePath()throws Exception {
        Probe probe=probe("LLM","ollama");probe.config().put("base_url",origin+"/v1");
        assertEquals("succeeded",executor.execute(probe,(a,b)->{}).status());assertEquals("/v1/chat/completions",route);
    }
    @Test void asrUsesRealBundledAudioAndReturnsTranscript()throws Exception {
        executor.execute(probe("ASR","qwen_workspace"),(a,b)->{});
        String audio=sent.path("messages").path(0).path("content").path(0).path("input_audio").path("data").asText();
        byte[] wav=Base64.getDecoder().decode(audio.split(",")[1]);assertEquals("RIFF",new String(wav,0,4,StandardCharsets.US_ASCII));assertTrue(wav.length>1000);
    }
    @Test void ragWithoutDatasetOnlyUsesReadOnlyList()throws Exception {
        var result=executor.execute(probe("RAG","ragflow"),(a,b)->{});assertEquals("connection",result.scope());assertEquals("/api/v1/datasets",route);assertTrue(sent==null||sent.isMissingNode());
    }
    @Test void localValidationAndUnsupportedProvidersNeverPretendInference()throws Exception {
        assertEquals("configuration",executor.execute(probe("VAD","silero"),(a,b)->{}).scope());
        assertEquals("checked",executor.execute(probe("Memory","nomem"),(a,b)->{}).status());
        assertEquals("unsupported",executor.execute(probe("TTS","edge"),(a,b)->{}).status());assertEquals(0,requests.get());
        Probe vad=probe("VAD","silero");vad.config().put("threshold",1.1);assertThrows(Failure.class,()->executor.execute(vad,(a,b)->{}));
    }
    @Test void maskedCredentialsAreReusedOnlyAtTheSavedOrigin()throws Exception {
        ObjectNode saved=(ObjectNode)json.readTree("{\"type\":\"openai\",\"base_url\":\"https://trusted.example/v1\",\"api_key\":\"real-private-value\"}");
        ObjectNode draft=saved.deepCopy().put("api_key","real****alue").put("model_name","changed");
        assertEquals("real-private-value",ModelTestService.merge(saved,draft).path("api_key").asText());
        draft.put("base_url","https://other.example/v1");assertThrows(Failure.class,()->ModelTestService.merge(saved,draft));
        draft.put("api_key","new-explicit-key");assertEquals("new-explicit-key",ModelTestService.merge(saved,draft).path("api_key").asText());
        assertFalse(saved.has("model_name"));
    }
    @Test void providerEchoesAndNestedSecretsAreRedacted()throws Exception {
        var redactor=new ModelTestService.Redactor(json);redactor.collect(json.readTree("{\"api_key\":\"actual-provider-key\",\"nested\":{\"access_token\":\"long-token-value\"}}"));
        String value=redactor.clean("actual-provider-key long-token-value Bearer random-credential api_key=echoed-key",1000);
        for(String secret:List.of("actual-provider-key","long-token-value","random-credential","echoed-key"))assertFalse(value.contains(secret));
        assertEquals("[已隐藏]",redactor.tree(json.readTree("{\"usage\":{\"api_key\":\"unrecognized\"}}")).path("usage").path("api_key").asText());
        assertEquals(4,redactor.tree(json.readTree("{\"usage\":{\"total_tokens\":4,\"prompt_tokens_details\":{\"cached_tokens\":2}}}")).path("usage").path("total_tokens").asInt());
        assertFalse(ModelTestService.secret("max_tokens"));
    }
    DefaultModelProbeExecutor.SpeechListener listener(){return new DefaultModelProbeExecutor.SpeechListener(json,"cosyvoice-v3.5-flash","longantai_v3","你好。",(a,b)->{});}
    WebSocket socket(){WebSocket ws=mock(WebSocket.class);when(ws.sendText(any(),anyBoolean())).thenReturn(CompletableFuture.completedFuture(ws));return ws;}
    String event(DefaultModelProbeExecutor.SpeechListener l,String kind,String extra){return "{\"header\":{\"task_id\":\""+l.id+"\",\"event\":\""+kind+"\""+extra+"},\"payload\":{}}";}
    @Test void cosyVoiceFailurePreservesProviderErrorAndNeverProducesAudio()throws Exception {
        var l=listener();var ws=socket();l.onOpen(ws);l.onText(ws,event(l,"task-started",""),true);
        l.onText(ws,event(l,"task-failed",",\"error_code\":\"InvalidParameter\",\"error_message\":\"Engine return error code: 418\""),true);
        ExecutionException error=assertThrows(ExecutionException.class,()->l.result.get(1,TimeUnit.SECONDS));assertInstanceOf(Failure.class,error.getCause());assertTrue(error.getCause().getMessage().contains("418"));
        verify(ws,times(3)).sendText(any(),eq(true));
    }
    @Test void cosyVoiceOnlyPublishesAfterMatchingCompletion()throws Exception {
        var l=listener();var ws=socket();l.onOpen(ws);l.onText(ws,event(l,"task-started",""),true);l.onBinary(ws,ByteBuffer.wrap(new byte[]{1,0,2,0}),true);
        assertFalse(l.result.isDone());l.onText(ws,event(l,"task-finished",""),true);assertEquals(48,DefaultModelProbeExecutor.wav(l.result.get()).length);
        var wrong=listener();wrong.onText(ws,"{\"header\":{\"task_id\":\"wrong\",\"event\":\"task-started\"}}",true);assertTrue(wrong.result.isCompletedExceptionally());
    }
    @Test void oversizedHttpBodyCancelsItsSubscription(){
        var subscriber=new DefaultModelProbeExecutor.LimitedSubscriber(2);var subscription=mock(java.util.concurrent.Flow.Subscription.class);subscriber.onSubscribe(subscription);subscriber.onNext(List.of(ByteBuffer.wrap(new byte[3])));verify(subscription).cancel();assertTrue(subscriber.result.isCompletedExceptionally());
    }
    @Test void endpointsAndMediaAreBounded(){
        assertThrows(Failure.class,()->DefaultModelProbeExecutor.endpoint("http://169.254.169.254","/chat/completions"));
        assertThrows(Failure.class,()->DefaultModelProbeExecutor.endpoint("https://user:password@example.test","/chat/completions"));
        assertThrows(Failure.class,()->ModelTestService.decode(Base64.getEncoder().encodeToString(new byte[9]),8));
        assertEquals("https://example.test/api/v1/retrieval",DefaultModelProbeExecutor.endpoint("https://example.test/api/v1","/api/v1/retrieval").toString());
    }
}
