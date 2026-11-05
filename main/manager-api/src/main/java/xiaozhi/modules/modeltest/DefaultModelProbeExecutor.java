package xiaozhi.modules.modeltest;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Flow;
import java.util.function.BiConsumer;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import static xiaozhi.modules.modeltest.ModelTestModels.*;

/** Small, bounded inference probes. No business messages, tool execution, memory writes or retries. */
@Component
public class DefaultModelProbeExecutor implements ModelProbeExecutor {
    static final int MAX_RESPONSE = 1024 * 1024;
    private final ObjectMapper json;
    private final HttpClient client;
    public DefaultModelProbeExecutor(ObjectMapper json) {
        this.json = json;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public Outcome execute(Probe p, BiConsumer<String,String> log) throws Exception {
        String provider = text(p.config(), "type", "").toLowerCase(Locale.ROOT);
        log.accept("PLAN", "类型 " + p.modelType() + "；供应器 " + provider + "；使用当前编辑参数，未保存模型配置。");
        return switch (p.modelType().toUpperCase(Locale.ROOT)) {
            case "LLM", "VLLM" -> chat(p, log, false);
            case "TTS" -> switch (provider) {
                case "alibl_stream" -> cosyVoice(p, log);
                case "openai", "siliconflow" -> speechHttp(p, log);
                default -> Outcome.unsupported(provider);
            };
            case "ASR" -> asr(p, log);
            case "INTENT" -> provider.equals("nointent") ? Outcome.checked("意图识别已关闭；此配置不调用模型。") : chat(p, log, p.config().path("_model_test_tool_probe").asBoolean());
            case "MEMORY" -> p.config().path("_model_test_memory_llm").asBoolean() ? chat(p,log,false) : switch (provider) {
                case "nomem", "mem_report_only" -> Outcome.checked("此记忆模式不调用独立模型；不会写入或清理现有记忆。");
                case "mem_local_short" -> chat(p, log, false);
                case "mem0ai" -> mem0(p, log);
                default -> Outcome.unsupported(provider);
            };
            case "RAG" -> rag(p, log);
            case "VAD" -> vad(p);
            default -> Outcome.unsupported(provider);
        };
    }

    Outcome chat(Probe p, BiConsumer<String,String> log, boolean toolTest) throws Exception {
        ObjectNode c = p.config();
        String provider = text(c,"type","");
        if (!Set.of("openai", "siliconflow", "ollama", "function_call", "intent_llm", "mem_local_short").contains(provider)) return Outcome.unsupported(provider);
        String model = required(c, "model_name", "model");
        String prompt = p.text();
        if (prompt == null || prompt.isBlank()) prompt = switch (p.modelType().toUpperCase(Locale.ROOT)) {
            case "VLLM" -> "请说出图片中图形的颜色和形状。";
            case "INTENT" -> "请判断这句话的意图，只返回意图名称：请告诉我今天的天气。";
            case "MEMORY" -> "仅概括以下测试对话：用户说代号是蓝鲸，助手表示已知悉。";
            default -> "请只回复：连接成功";
        };
        ObjectNode body = json.createObjectNode().put("model", model).put("stream", false);
        body.put("max_tokens", 128);
        ArrayNode messages = body.putArray("messages");
        ObjectNode message = messages.addObject().put("role", "user");
        if (p.modelType().equalsIgnoreCase("VLLM")) {
            byte[] image = p.image() == null ? testImage() : p.image();
            ArrayNode content = message.putArray("content");
            content.addObject().put("type","text").put("text",prompt);
            content.addObject().put("type","image_url").putObject("image_url")
                    .put("url","data:" + (p.imageMime() == null ? "image/png" : p.imageMime()) + ";base64," + Base64.getEncoder().encodeToString(image));
            log.accept("INPUT", "发送测试图片和问题，未读取设备摄像头或业务附件。");
        } else message.put("content", toolTest ? "请调用 connection_test，参数 value 为 ok。" : prompt);
        if (toolTest) {
            ObjectNode function = body.putArray("tools").addObject().put("type","function").putObject("function");
            function.put("name","connection_test").put("description","仅用于检验工具调用格式的虚拟工具，不执行任何操作。");
            ObjectNode parameters = function.putObject("parameters").put("type","object");
            parameters.putObject("properties").putObject("value").put("type","string");
            parameters.putArray("required").add("value");
            body.putObject("tool_choice").put("type","function").putObject("function").put("name","connection_test");
        }
        String base=provider.equals("ollama")?text(c,"base_url","http://localhost:11434"):required(c,"base_url","url");
        if(provider.equals("ollama")&&!base.replaceAll("/+$","").endsWith("/v1"))base=base.replaceAll("/+$","")+"/v1";
        URI uri = endpoint(base,"/chat/completions");
        JsonNode response = jsonRequest(uri, "POST", auth(c), body, log);
        JsonNode answer = response.path("choices").path(0).path("message");
        String output;
        if (toolTest) {
            JsonNode call = answer.path("tool_calls").path(0).path("function");
            if (!call.path("name").asText().equals("connection_test")) throw new Failure("INVALID_TOOL_RESPONSE", "模型未返回预期工具调用；测试没有执行任何工具。");
            output = call.toString();
        } else {
            output = answer.path("content").asText("");
            if (output.isBlank()) throw new Failure("EMPTY_MODEL_RESPONSE", "接口响应成功，但没有返回有效文本。");
        }
        log.accept("VALIDATE", toolTest ? "已收到预期工具调用结构，未执行工具。" : "已收到非空模型文本。");
        return success("inference", toolTest ? "工具调用测试成功" : "模型实际调用成功", output, response.path("usage"), null, null);
    }

    Outcome asr(Probe p, BiConsumer<String,String> log) throws Exception {
        String provider = text(p.config(),"type","");
        if (!provider.equals("qwen_workspace")) return Outcome.unsupported(provider);
        byte[] audio = p.audio();
        if (audio == null) {
            try (var input = getClass().getResourceAsStream("/model-test/hello.wav")) {
                if (input == null) throw new Failure("TEST_AUDIO_MISSING", "默认测试音频不可用，请上传 WAV 或 MP3 音频。");
                audio = input.readNBytes(MAX_RESPONSE);
            }
        }
        ObjectNode body = json.createObjectNode().put("model",required(p.config(),"model_name","model"));
        body.putArray("messages").addObject().put("role","user").putArray("content").addObject()
                .put("type","input_audio").putObject("input_audio").put("data","data:" + (p.audioMime()==null?"audio/wav":p.audioMime()) + ";base64," + Base64.getEncoder().encodeToString(audio));
        body.putObject("asr_options").put("enable_itn",p.config().path("enable_itn").asBoolean(true)).put("language",text(p.config(),"language","zh"));
        log.accept("INPUT", "发送" + (p.audio()==null?"内置普通话样音":"上传的测试音频") + "，" + audio.length + " 字节。");
        JsonNode response = jsonRequest(endpoint(required(p.config(),"base_url"),"/chat/completions"),"POST",auth(p.config()),body,log);
        String output = response.path("choices").path(0).path("message").path("content").asText("");
        if (output.isBlank()) throw new Failure("EMPTY_TRANSCRIPT", "服务没有返回识别文字。");
        return success("transcription", "语音识别测试成功", output, response.path("usage"),null,null);
    }

    Outcome speechHttp(Probe p, BiConsumer<String,String> log) throws Exception {
        ObjectNode c=p.config();
        ObjectNode body=json.createObjectNode().put("model",required(c,"model","model_name"))
                .put("voice",required(c,"private_voice","voice")).put("input",defaultText(p.text())).put("response_format","wav");
        double speed=c.hasNonNull("speed")&&!c.path("speed").asText().isBlank()?c.path("speed").asDouble(Double.NaN):1;
        if(!Double.isFinite(speed)||speed<.25||speed>4)throw new Failure("INVALID_AUDIO_CONFIG","语速应为 0.25～4。");
        body.put("speed",speed);
        String direct=text(c,"api_url","");String base=text(c,"base_url",text(c,"url",""));
        URI uri=!direct.isBlank()?endpoint(direct,""):!base.isBlank()?endpoint(base,"/audio/speech"):
                endpoint(text(c,"type","").equals("siliconflow")?"https://api.siliconflow.cn/v1/audio/speech":"https://api.openai.com/v1/audio/speech","");
        Reply reply=request(uri,"POST",auth(c),json.writeValueAsBytes(body),log);
        checkHttp(reply);
        validateWav(reply.body());
        return success("synthesis","语音合成测试成功，可试听完整音频。","",null,reply.body(),"audio/wav");
    }

    Outcome cosyVoice(Probe p, BiConsumer<String,String> log) throws Exception {
        ObjectNode c=p.config();
        URI uri=URI.create(required(c,"ws_url"));
        String host=uri.getHost()==null?"":uri.getHost().toLowerCase(Locale.ROOT);
        if (!"wss".equals(uri.getScheme()) || !(host.equals("dashscope.aliyuncs.com") || host.equals("dashscope-intl.aliyuncs.com") || host.matches("[a-z0-9][a-z0-9.-]*\\.maas\\.aliyuncs\\.com"))
                || !uri.getPath().replaceAll("/+$","").equals("/api-ws/v1/inference") || uri.getRawQuery()!=null || uri.getUserInfo()!=null || uri.getFragment()!=null || uri.getPort()!=-1&&uri.getPort()!=443)
            throw new Failure("INVALID_PROVIDER_ENDPOINT","CosyVoice 需要阿里云官方 WSS 推理地址。");
        String model=required(c,"model"),voice=required(c,"private_voice","voice"),key=required(c,"api_key");
        log.accept("CONNECT","建立 CosyVoice WebSocket 连接；模型 " + model + "；音色 " + voice);
        int sampleRate=c.has("sample_rate")?c.path("sample_rate").asInt(0):24000;
        double volume=c.has("volume")?c.path("volume").asDouble(Double.NaN):50;
        double rate=c.has("rate")?c.path("rate").asDouble(Double.NaN):1;
        double pitch=c.has("pitch")?c.path("pitch").asDouble(Double.NaN):1;
        if(!Set.of(8000,16000,22050,24000,44100,48000).contains(sampleRate)||!Double.isFinite(volume)||volume<0||volume>100||!Double.isFinite(rate)||rate<.5||rate>2||!Double.isFinite(pitch)||pitch<.5||pitch>2)throw new Failure("INVALID_AUDIO_CONFIG","采样率、音量、语速或音调配置无效。");
        log.accept("INPUT","测试输出使用 PCM/WAV；采样率、音量、语速和音调沿用当前编辑值。");
        SpeechListener listener=new SpeechListener(json,model,voice,defaultText(p.text()),log,sampleRate,volume,rate,pitch);
        var builder=client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).header("Authorization","Bearer "+key)
                .header("X-DashScope-DataInspection","enable");
        String workspace=text(c,"workspace_id","");
        if (!workspace.isBlank()) builder.header("X-DashScope-WorkSpace",workspace);
        WebSocket socket=null;
        CompletableFuture<WebSocket> opening=builder.buildAsync(uri,listener);
        try {
            socket=opening.get(6,TimeUnit.SECONDS);
            byte[] pcm=listener.result.get(22,TimeUnit.SECONDS);
            return success("synthesis","语音合成测试成功，可试听完整音频。","",listener.usage,wav(pcm,sampleRate),"audio/wav");
        } catch (ExecutionException error) {
            if (error.getCause() instanceof Failure f) throw f;
            if (error.getCause() instanceof WebSocketHandshakeException handshake) {
                int status=handshake.getResponse().statusCode();
                throw new Failure(status==401||status==403?"PROVIDER_AUTH":"PROVIDER_ERROR","WebSocket 握手失败，HTTP "+status);
            }
            throw new Failure("PROVIDER_CONNECTION_FAILED","WebSocket 连接失败："+error.getCause().getClass().getSimpleName());
        } finally {
            opening.cancel(true);
            if(socket!=null)socket.abort();
        }
    }

    Outcome rag(Probe p, BiConsumer<String,String> log) throws Exception {
        if (!text(p.config(),"type","").equals("ragflow")) return Outcome.unsupported(text(p.config(),"type",""));
        String base=required(p.config(),"base_url");
        if (p.datasetId()!=null&&!p.datasetId().isBlank()) {
            if(!p.datasetId().matches("[A-Za-z0-9_-]{1,100}"))throw new Failure("INVALID_DATASET","知识库 ID 格式无效。");
            ObjectNode body=json.createObjectNode().put("question",p.text()==null||p.text().isBlank()?"模型连接测试":p.text()).put("page_size",1).put("top_k",1);
            body.putArray("dataset_ids").add(p.datasetId());
            JsonNode result=jsonRequest(endpoint(base,"/api/v1/retrieval"),"POST",auth(p.config()),body,log);
            validateRag(result);
            return success("retrieval","检索接口测试成功",result.path("data").toString(),null,null,null);
        }
        JsonNode result=jsonRequest(endpoint(base,"/api/v1/datasets?page=1&page_size=1"),"GET",auth(p.config()),null,log);
        validateRag(result);
        return success("connection","已连接 RAGFlow 并读取知识库列表；指定知识库 ID 后可测试检索。","本次未检索文档，也未修改知识库。",null,null,null);
    }
    private void validateRag(JsonNode result) {
        if (!result.has("code") || result.path("code").asInt(-1)!=0) throw new Failure("RAG_PROVIDER_ERROR",result.path("message").asText("RAGFlow 返回非成功状态。"));
    }
    Outcome mem0(Probe p, BiConsumer<String,String> log) throws Exception {
        Map<String,String> headers=Map.of("Authorization","Token "+required(p.config(),"api_key"));
        ObjectNode body=json.createObjectNode().put("query",p.text()==null||p.text().isBlank()?"模型连接测试":p.text()).put("top_k",1);
        body.putObject("filters").put("user_id","xiaozhi-model-test-isolated");
        JsonNode result=jsonRequest(endpoint(text(p.config(),"base_url","https://api.mem0.ai"),"/v3/memories/search/"),"POST",headers,body,log);
        if (!result.isArray()&&!result.has("results"))throw new Failure("INVALID_MEMORY_RESPONSE","记忆检索返回了无法识别的响应。");
        return success("retrieval","记忆服务只读检索成功，未写入任何记忆。","检索结果数量："+(result.isArray()?result.size():result.path("results").size()),null,null,null);
    }
    Outcome vad(Probe p) {
        if(!text(p.config(),"type","").equals("silero"))return Outcome.unsupported(text(p.config(),"type",""));
        double threshold=p.config().has("threshold")?p.config().path("threshold").asDouble(Double.NaN):.5;
        double silence=p.config().has("min_silence_duration_ms")?p.config().path("min_silence_duration_ms").asDouble(Double.NaN):700;
        if(!Double.isFinite(threshold)||threshold<0||threshold>1||!Double.isFinite(silence)||silence%1!=0||silence<0||silence>30000)throw new Failure("INVALID_VAD_CONFIG","VAD 阈值应为 0～1，静音时长应为 0～30000ms。");
        return Outcome.checked("VAD 参数检查通过；模型在语音网关运行，本次没有执行音频推理。");
    }

    JsonNode jsonRequest(URI uri,String method,Map<String,String> headers,JsonNode body,BiConsumer<String,String> log)throws Exception {
        Reply reply=request(uri,method,headers,body==null?null:json.writeValueAsBytes(body),log);
        checkHttp(reply);
        try{return json.readTree(reply.body());}catch(Exception error){throw new Failure("INVALID_PROVIDER_JSON","服务未返回有效 JSON。");}
    }
    record Reply(int status,byte[] body){}
    Reply request(URI uri,String method,Map<String,String> headers,byte[] body,BiConsumer<String,String> log)throws Exception {
        log.accept("REQUEST",method+" "+uri.getScheme()+"://"+uri.getAuthority()+uri.getPath());
        HttpRequest.Builder builder=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20));
        headers.forEach(builder::header);
        if(body==null)builder.GET();else builder.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofByteArray(body));
        CompletableFuture<HttpResponse<byte[]>> future=client.sendAsync(builder.build(),info->new LimitedSubscriber(MAX_RESPONSE));
        try {
            var response=future.get(22,TimeUnit.SECONDS);
            log.accept("RESPONSE","HTTP "+response.statusCode()+"；响应 "+response.body().length+" 字节"+response.headers().firstValue("x-request-id").map(id->"；请求编号 "+id).orElse(""));
            return new Reply(response.statusCode(),response.body());
        } finally {future.cancel(true);}
    }
    void checkHttp(Reply reply)throws Exception {
        if(reply.status()>=200&&reply.status()<300)return;
        String message="服务返回 HTTP "+reply.status();
        try{JsonNode root=json.readTree(reply.body());message+="："+root.path("error").path("message").asText(root.path("message").asText(""));}catch(Exception ignored){}
        throw new Failure(reply.status()==401||reply.status()==403?"PROVIDER_AUTH":reply.status()==429?"PROVIDER_RATE_LIMIT":"PROVIDER_ERROR",message);
    }
    static URI endpoint(String base,String suffix) {
        URI root;
        try{root=URI.create(base.trim());}catch(Exception e){throw new Failure("INVALID_ENDPOINT","服务地址格式不正确。");}
        if(!Set.of("https","http").contains(root.getScheme())||root.getHost()==null||root.getUserInfo()!=null||root.getRawQuery()!=null||root.getFragment()!=null)
            throw new Failure("INVALID_ENDPOINT","服务地址必须为 HTTP(S)，不能包含账号、查询参数或片段。");
        String host=root.getHost().toLowerCase(Locale.ROOT);
        if(host.equals("169.254.169.254")||host.startsWith("169.254.")||host.equals("metadata.google.internal")||host.equals("[::ffff:169.254.169.254]"))throw new Failure("INVALID_ENDPOINT","不允许使用元数据服务地址。");
        String value=base.replaceAll("/+$","");
        if(value.endsWith(suffix))return URI.create(value);
        if(suffix.startsWith("/api/v1/")&&value.endsWith("/api/v1"))suffix=suffix.substring(7);
        if(suffix.equals("/api/chat")&&value.endsWith("/api"))suffix="/chat";
        return URI.create(value+suffix);
    }
    static String text(JsonNode node,String key,String fallback){String v=node.path(key).asText("");return v.isBlank()?fallback:v;}
    static String required(JsonNode node,String... keys){for(String key:keys){String v=text(node,key,"");if(!v.isBlank()&&!v.contains("****")&&!v.startsWith("你的"))return v;}throw new Failure("CONFIG_MISSING","缺少有效配置："+String.join(" / ",keys));}
    static Map<String,String> auth(ObjectNode c){String key=text(c,"api_key",text(c,"access_token",""));return key.isBlank()?Map.of():Map.of("Authorization","Bearer "+required(c,"api_key","access_token"));}
    static String defaultText(String text){return text==null||text.isBlank()?"你好，这是模型语音合成测试。":text;}
    Outcome success(String scope,String summary,String output,JsonNode usage,byte[] audio,String mime){return new Outcome("succeeded",scope,summary,output,usage,audio,mime);}
    static byte[] testImage()throws Exception {
        BufferedImage image=new BufferedImage(160,160,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();
        g.setColor(Color.WHITE);g.fillRect(0,0,160,160);g.setColor(Color.RED);g.fillOval(35,35,90,90);g.dispose();
        var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);return bytes.toByteArray();
    }
    static byte[] wav(byte[] pcm){return wav(pcm,24000);}
    static byte[] wav(byte[] pcm,int sampleRate){
        if(pcm.length==0||(pcm.length&1)!=0)throw new Failure("INVALID_AUDIO","服务没有返回完整 PCM 音频。");
        ByteBuffer b=ByteBuffer.allocate(pcm.length+44).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length+36).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short)1).putShort((short)1).putInt(sampleRate).putInt(sampleRate*2).putShort((short)2).putShort((short)16).put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length).put(pcm);return b.array();
    }
    static void validateWav(byte[] bytes){if(bytes.length<44||!new String(bytes,0,4,StandardCharsets.US_ASCII).equals("RIFF")||!new String(bytes,8,4,StandardCharsets.US_ASCII).equals("WAVE"))throw new Failure("INVALID_AUDIO","服务返回的内容不是有效 WAV 音频。");}

    static final class LimitedSubscriber implements HttpResponse.BodySubscriber<byte[]> {
        final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
        final CompletableFuture<byte[]> result=new CompletableFuture<>();final int limit;int size;Flow.Subscription subscription;
        LimitedSubscriber(int limit){this.limit=limit;delegate.getBody().whenComplete((body,error)->{if(error==null)result.complete(body);else result.completeExceptionally(error);});}
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription s){subscription=s;delegate.onSubscribe(s);}
        public void onNext(List<ByteBuffer> chunks){int n=chunks.stream().mapToInt(ByteBuffer::remaining).sum();if(size+n>limit){subscription.cancel();result.completeExceptionally(new Failure("RESPONSE_TOO_LARGE","测试响应超过 1 MiB 限制。"));return;}size+=n;delegate.onNext(chunks);}
        public void onError(Throwable error){delegate.onError(error);result.completeExceptionally(error);}
        public void onComplete(){delegate.onComplete();}
    }

    static final class SpeechListener implements WebSocket.Listener {
        final ObjectMapper json;final String id=UUID.randomUUID().toString(),model,voice,text;final BiConsumer<String,String> log;
        final CompletableFuture<byte[]> result=new CompletableFuture<>();final ByteArrayOutputStream pcm=new ByteArrayOutputStream();final StringBuilder fragments=new StringBuilder();
        final AtomicBoolean started=new AtomicBoolean();JsonNode usage;
        final int sampleRate;final double volume,rate,pitch;
        SpeechListener(ObjectMapper json,String model,String voice,String text,BiConsumer<String,String> log){this(json,model,voice,text,log,24000,50,1,1);}
        SpeechListener(ObjectMapper json,String model,String voice,String text,BiConsumer<String,String> log,int sampleRate,double volume,double rate,double pitch){this.json=json;this.model=model;this.voice=voice;this.text=text;this.log=log;this.sampleRate=sampleRate;this.volume=volume;this.rate=rate;this.pitch=pitch;}
        String command(String action,JsonNode payload)throws Exception {ObjectNode body=json.createObjectNode();body.putObject("header").put("action",action).put("task_id",id).put("streaming","duplex");body.set("payload",payload);return json.writeValueAsString(body);}
        @Override public void onOpen(WebSocket ws){
            try{ObjectNode payload=json.createObjectNode().put("task_group","audio").put("task","tts").put("function","SpeechSynthesizer").put("model",model);
                payload.putObject("parameters").put("text_type","PlainText").put("voice",voice).put("format","pcm").put("sample_rate",sampleRate).put("volume",volume).put("rate",rate).put("pitch",pitch).put("enable_ssml",false);payload.putObject("input");
                ws.sendText(command("run-task",payload),true).exceptionally(e->{result.completeExceptionally(e);return null;});ws.request(1);
            }catch(Exception e){result.completeExceptionally(e);}
        }
        @Override public CompletionStage<?> onText(WebSocket ws,CharSequence data,boolean last){
            try {
                if(fragments.length()+data.length()>65536)throw new Failure("PROVIDER_PROTOCOL","服务端事件过大。");fragments.append(data);
                if(last){JsonNode event=json.readTree(fragments.toString());fragments.setLength(0);JsonNode h=event.path("header");
                    if(!h.path("task_id").asText().equals(id))throw new Failure("PROVIDER_PROTOCOL","服务端任务编号不匹配。");
                    String kind=h.path("event").asText();
                    if(event.path("payload").has("usage"))usage=event.path("payload").path("usage");
                    String request=h.path("attributes").path("request_uuid").asText("");
                    log.accept("PROVIDER",kind+(request.isBlank()?"":"；请求编号 "+request));
                    switch(kind){
                        case "task-started" -> {
                            if(!started.compareAndSet(false,true))throw new Failure("PROVIDER_PROTOCOL","服务重复确认任务。");
                            ObjectNode input=json.createObjectNode();input.putObject("input").put("text",text);
                            ObjectNode end=json.createObjectNode();end.putObject("input");
                            String finish=command("finish-task",end);
                            ws.sendText(command("continue-task",input),true).thenCompose(w->w.sendText(finish,true)).exceptionally(e->{result.completeExceptionally(e);return null;});
                        }
                        case "task-failed" -> throw new Failure(h.path("error_code").asText("PROVIDER_ERROR"),h.path("error_message").asText("语音合成服务拒绝任务。"));
                        case "task-finished" -> {if(!started.get()||pcm.size()==0)throw new Failure("INVALID_AUDIO","任务结束但没有有效音频。");result.complete(pcm.toByteArray());}
                        case "result-generated" -> {}
                        default -> throw new Failure("PROVIDER_PROTOCOL","无法识别的服务端事件。");
                    }
                }
            }catch(Exception e){result.completeExceptionally(e);}
            ws.request(1);return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<?> onBinary(WebSocket ws,ByteBuffer data,boolean last){
            if(!started.get()||pcm.size()+data.remaining()>MAX_RESPONSE)result.completeExceptionally(new Failure("INVALID_AUDIO","音频顺序无效或超过 1 MiB 限制。"));
            else{byte[] chunk=new byte[data.remaining()];data.get(chunk);pcm.writeBytes(chunk);}ws.request(1);return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<?> onClose(WebSocket ws,int code,String reason){if(!result.isDone())result.completeExceptionally(new Failure("PROVIDER_CLOSED","服务提前关闭连接（"+code+"）："+reason));return CompletableFuture.completedFuture(null);}
        @Override public void onError(WebSocket ws,Throwable error){result.completeExceptionally(new Failure("PROVIDER_CONNECTION_FAILED","WebSocket 通信失败："+error.getClass().getSimpleName()));}
    }
}
