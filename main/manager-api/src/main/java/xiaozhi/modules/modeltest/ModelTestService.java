package xiaozhi.modules.modeltest;

import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import jakarta.annotation.PreDestroy;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelConfigService;
import static xiaozhi.modules.modeltest.ModelTestModels.*;

@Service
public class ModelTestService {
    private final JdbcTemplate db; private final ObjectMapper json; private final ModelConfigService models;
    private final ModelProbeExecutor probes; private final Path audioRoot;
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{Thread t=new Thread(r,"model-test");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    public ModelTestService(JdbcTemplate db,ObjectMapper json,ModelConfigService models,ModelProbeExecutor probes,Environment env) {
        this.db=db;this.json=json;this.models=models;this.probes=probes;
        audioRoot=Path.of(env.getProperty("model-test.audio-dir", "data/model-tests")).toAbsolutePath().normalize();
    }
    @PreDestroy public void close(){pool.shutdownNow();}

    public synchronized Run start(long actor,String modelId,Request request) throws Exception {
        ModelConfigEntity model=model(modelId);
        if(request==null||request.requestId()==null||!request.requestId().matches("[a-fA-F0-9-]{32,36}"))throw new Failure("INVALID_REQUEST","缺少有效的测试请求编号。");
        if(request.text()!=null&&request.text().length()>500)throw new Failure("INVALID_INPUT","测试文本最多 500 字。");
        if(request.datasetId()!=null&&request.datasetId().length()>100)throw new Failure("INVALID_INPUT","知识库 ID 过长。");
        ObjectNode saved=rawConfig(model);
        ObjectNode config=merge(saved,request.configJson());
        if(json.writeValueAsBytes(config).length>32768)throw new Failure("CONFIG_TOO_LARGE","测试配置超过 32 KiB。");
        Redactor redactor=new Redactor(json);redactor.collect(saved);redactor.collect(config);
        String provider=config.path("type").asText("");
        if(!provider.matches("[A-Za-z0-9_-]{1,80}"))throw new Failure("CONFIG_MISSING","请选择有效的供应器。");
        byte[] audio=decode(request.audioBase64(),1024*1024), image=decode(request.imageBase64(),2*1024*1024);
        if(audio!=null&&(request.audioMime()==null||!Set.of("audio/wav","audio/mpeg").contains(request.audioMime())))throw new Failure("INVALID_MEDIA","ASR 测试支持 WAV 或 MP3 音频。");
        if(image!=null&&(request.imageMime()==null||!Set.of("image/png","image/jpeg").contains(request.imageMime())))throw new Failure("INVALID_MEDIA","视觉测试支持 PNG 或 JPEG 图片。");
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(request)));
        List<Map<String,Object>> previous=db.queryForList("SELECT id,model_id,request_hash FROM ai_model_test_run WHERE actor_id=? AND request_id=?",actor,request.requestId());
        if(!previous.isEmpty()){
            var old=previous.getFirst();
            if(!modelId.equals(old.get("model_id"))||!hash.equals(old.get("request_hash")))throw new Failure(409,"REQUEST_CONFLICT","此请求编号已用于其他参数，请重新发起测试。");
            return get(actor,modelId,(String)old.get("id"));
        }
        expire(actor,modelId);
        List<String> active=db.queryForList("SELECT id FROM ai_model_test_run WHERE actor_id=? AND model_id=? AND status IN ('queued','running') ORDER BY created_at DESC LIMIT 1",String.class,actor,modelId);
        if(!active.isEmpty())throw new Failure(409,"TEST_ALREADY_RUNNING","该模型已有测试进行中，请查看历史或等待完成后重试。");
        String id=UUID.randomUUID().toString().replace("-","");
        String type=model.getModelType().toUpperCase(Locale.ROOT);
        db.update("INSERT INTO ai_model_test_run(id,model_id,actor_id,request_id,request_hash,model_type,provider,status,scope,summary,logs_json,created_at,updated_at) VALUES(?,?,?,?,?,?,?,'queued','none','等待测试执行','[]',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",id,modelId,actor,request.requestId(),hash,type,provider);
        Probe probe=new Probe(type,config,request.text(),audio,request.audioMime(),image,request.imageMime(),request.datasetId());
        try{pool.execute(()->execute(id,probe,redactor));}
        catch(RejectedExecutionException busy){db.update("UPDATE ai_model_test_run SET status='failed',error_code='TEST_BUSY',summary='测试任务较多，请稍后重试',updated_at=UTC_TIMESTAMP(3) WHERE id=?",id);}
        return get(actor,modelId,id);
    }

    void execute(String id,Probe probe,Redactor redactor) {
        if(db.update("UPDATE ai_model_test_run SET status='running',summary='测试执行中',started_at=UTC_TIMESTAMP(3),updated_at=UTC_TIMESTAMP(3) WHERE id=? AND status='queued'",id)!=1)return;
        long began=System.nanoTime();List<Log> logs=new ArrayList<>();
        BiConsumer<String,String> log=(stage,message)->{
            synchronized(logs){
                if(logs.size()>=32)return;
                logs.add(new Log(Instant.now().toString(),TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began),stage.equals("ERROR")?"error":"info",stage,redactor.clean(message,1800)));
                db.update("UPDATE ai_model_test_run SET logs_json=?,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND status='running'",write(logs),id);
            }
        };
        Path audioFile=null;
        try {
            log.accept("CONFIG","读取服务端凭据并合并当前编辑内容；不会保存模型配置，也不会更换默认模型。");
            Probe actual=resolveDependency(probe,redactor,log);
            Outcome outcome=probes.execute(actual,log);
            ObjectNode result=json.createObjectNode().put("output",redactor.clean(outcome.output(),16000));
            result.putObject("configuration").put("type",probe.config().path("type").asText()).put("model",actual.config().path("model").asText(actual.config().path("model_name").asText(""))).put("voice",actual.config().path("private_voice").asText(actual.config().path("voice").asText("")));
            if(outcome.details()!=null&&!outcome.details().isMissingNode())result.set("usage",redactor.tree(outcome.details()));
            boolean hasAudio=outcome.audio()!=null;
            if(hasAudio){
                DefaultModelProbeExecutor.validateWav(outcome.audio());
                Files.createDirectories(audioRoot);if(Files.isSymbolicLink(audioRoot))throw new Failure("AUDIO_STORAGE_ERROR","测试音频存储路径无效。");
                Files.setPosixFilePermissions(audioRoot,PosixFilePermissions.fromString("rwx------"));
                audioFile=audioRoot.resolve(id+".wav");
                try(var stream=Files.newOutputStream(audioFile,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){stream.write(outcome.audio());}
                Files.setPosixFilePermissions(audioFile,PosixFilePermissions.fromString("rw-------"));
                result.put("audioBytes",outcome.audio().length);
            }
            log.accept("DONE",outcome.summary());
            int changed=db.update("UPDATE ai_model_test_run SET status=?,scope=?,summary=?,result_json=?,duration_ms=?,audio_available=?,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND status='running'",outcome.status(),outcome.scope(),redactor.clean(outcome.summary(),1000),write(result),TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began),hasAudio?1:0,id);
            if(changed==0&&audioFile!=null)Files.deleteIfExists(audioFile);
        } catch(Exception error) {
            Throwable actual=error;
            while((actual instanceof ExecutionException||actual instanceof CompletionException)&&actual.getCause()!=null)actual=actual.getCause();
            String code=actual instanceof Failure f?f.code:actual instanceof TimeoutException||actual instanceof java.net.http.HttpTimeoutException?"PROVIDER_TIMEOUT":"TEST_EXECUTION_FAILED";
            String message=actual instanceof Failure?actual.getMessage():actual instanceof TimeoutException?"测试等待超时；未自动重试模型调用。":"测试未完成："+actual.getClass().getSimpleName();
            try{log.accept("ERROR",code+"："+message);db.update("UPDATE ai_model_test_run SET status='failed',scope='none',summary=?,error_code=?,duration_ms=?,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND status='running'",redactor.clean(message,1800),redactor.clean(code,100),TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began),id);}catch(RuntimeException ignored){}
            if(audioFile!=null)try{Files.deleteIfExists(audioFile);}catch(Exception ignored){}
        }
    }

    Probe resolveDependency(Probe probe,Redactor redactor,BiConsumer<String,String> log) {
        String provider=probe.config().path("type").asText();
        boolean intent=probe.modelType().equals("INTENT")&&Set.of("function_call","intent_llm").contains(provider);
        boolean memory=probe.modelType().equals("MEMORY")&&provider.equals("mem_local_short");
        if(!intent&&!memory)return probe;
        String reference=probe.config().path("llm").asText("");
        ModelConfigEntity dependency;
        if(reference.isBlank())dependency=models.getEnabledModelsByType("LLM").stream().filter(m->Objects.equals(m.getIsDefault(),1)).findFirst().orElseThrow(()->new Failure("LLM_NOT_CONFIGURED","请配置依赖的语言模型或启用默认语言模型。"));
        else dependency=models.selectById(reference);
        if(dependency==null||!"LLM".equalsIgnoreCase(dependency.getModelType()))throw new Failure("LLM_NOT_CONFIGURED","依赖的语言模型不存在或类型不匹配。");
        ObjectNode config=rawConfig(dependency);redactor.collect(config);
        config.put("_model_test_tool_probe",provider.equals("function_call"));config.put("_model_test_memory_llm",memory);
        log.accept("DEPENDENCY","通过配置的语言模型 "+dependency.getModelName()+" 测试；不会执行工具或保存记忆。");
        return new Probe(probe.modelType(),config,probe.text(),probe.audio(),probe.audioMime(),probe.image(),probe.imageMime(),probe.datasetId());
    }
    public Run get(long actor,String modelId,String id) {
        model(modelId);expire(actor,modelId);
        List<Map<String,Object>> rows=db.queryForList("SELECT * FROM ai_model_test_run WHERE id=? AND model_id=? AND actor_id=?",id,modelId,actor);
        if(rows.isEmpty())throw new Failure(404,"TEST_NOT_FOUND","测试记录不存在。");
        var row=rows.getFirst();
        try{return new Run((String)row.get("id"),modelId,(String)row.get("model_type"),(String)row.get("provider"),(String)row.get("status"),(String)row.get("scope"),(String)row.get("summary"),(String)row.get("error_code"),row.get("duration_ms")==null?null:((Number)row.get("duration_ms")).longValue(),createdAt(row.get("created_at")),row.get("result_json")==null?json.createObjectNode():databaseJson(row.get("result_json")),Arrays.asList(json.treeToValue(databaseJson(row.get("logs_json")),Log[].class)),Boolean.TRUE.equals(row.get("audio_available"))||row.get("audio_available") instanceof Number n&&n.intValue()==1);}catch(Exception error){throw new Failure(500,"TEST_RECORD_INVALID","测试记录暂时不可读取。");}
    }
    public List<Run> history(long actor,String modelId) {
        model(modelId);expire(actor,modelId);
        return db.queryForList("SELECT id FROM ai_model_test_run WHERE model_id=? AND actor_id=? ORDER BY created_at DESC LIMIT 10",String.class,modelId,actor).stream().map(id->get(actor,modelId,id)).toList();
    }
    public byte[] audio(long actor,String modelId,String id)throws Exception {
        Run run=get(actor,modelId,id);
        if(!run.audioAvailable()||!run.status().equals("succeeded")||!id.matches("[a-f0-9]{32}"))throw new Failure(404,"AUDIO_NOT_AVAILABLE","测试音频不可用。");
        Path path=audioRoot.resolve(id+".wav");
        if(Files.isSymbolicLink(audioRoot)||Files.isSymbolicLink(path)||Files.size(path)>DefaultModelProbeExecutor.MAX_RESPONSE+44)throw new Failure(404,"AUDIO_NOT_AVAILABLE","测试音频不可用。");
        return Files.readAllBytes(path);
    }
    void expire(long actor,String modelId){db.update("UPDATE ai_model_test_run SET status='failed',error_code='TEST_INTERRUPTED',summary='测试超时或服务已重启；请重新发起测试',updated_at=UTC_TIMESTAMP(3) WHERE actor_id=? AND model_id=? AND ((status='running' AND started_at<DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 40 SECOND)) OR (status='queued' AND created_at<DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 100 SECOND)))",actor,modelId);}
    ModelConfigEntity model(String id){if(id==null||id.length()>32)throw new Failure("INVALID_MODEL","模型 ID 无效。");ModelConfigEntity value=models.selectById(id);if(value==null)throw new Failure(404,"MODEL_NOT_FOUND","请先保存模型，再进行测试。");return value;}
    ObjectNode rawConfig(ModelConfigEntity model){
        // Public model lookups mask credentials. This private read never reaches a response or log.
        try{String value=db.queryForObject("SELECT config_json FROM ai_model_config WHERE id=?",String.class,model.getId());return value==null?json.createObjectNode():(ObjectNode)json.readTree(value);}
        catch(Exception error){throw new Failure("INVALID_CONFIG","模型配置无法读取。");}
    }
    String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("model test serialization failed");}}
    JsonNode databaseJson(Object value)throws Exception {return value instanceof byte[] bytes?json.readTree(bytes):value instanceof JsonNode node?node:json.readTree(String.valueOf(value));}
    static String createdAt(Object value){
        if(value instanceof java.time.LocalDateTime date)return date.toInstant(java.time.ZoneOffset.UTC).toString();
        if(value instanceof java.sql.Timestamp date)return date.toLocalDateTime().toInstant(java.time.ZoneOffset.UTC).toString();
        if(value instanceof Instant instant)return instant.toString();
        return java.time.LocalDateTime.parse(String.valueOf(value).replace(' ','T')).toInstant(java.time.ZoneOffset.UTC).toString();
    }
    static byte[] decode(String value,int max){if(value==null||value.isBlank())return null;if(value.length()>max*4L/3+8)throw new Failure("MEDIA_TOO_LARGE","测试文件超过大小限制。");try{byte[] bytes=Base64.getDecoder().decode(value);if(bytes.length>max||bytes.length==0)throw new IllegalArgumentException();return bytes;}catch(IllegalArgumentException error){throw new Failure("INVALID_MEDIA","测试文件格式或大小无效。");}}

    static boolean secret(String name){
        String key=name.toLowerCase(Locale.ROOT).replace("_","").replace("-","");
        return key.contains("apikey")||key.contains("accesskey")||key.contains("secret")||key.contains("password")||key.contains("appkey")||key.equals("authorization")
                ||key.endsWith("token")&&!Set.of("maxtoken","inputtoken","outputtoken","prompttoken","completiontoken").contains(key);
    }
    static ObjectNode merge(ObjectNode saved,JsonNode draft) {
        if(draft==null||draft.isNull())return saved.deepCopy();
        if(!draft.isObject())throw new Failure("INVALID_CONFIG","测试配置应为对象。");
        boolean same=saved.path("type").asText().equals(draft.path("type").asText());
        for(String field:List.of("base_url","url","ws_url","api_url","endpoint"))if(!origin(saved.path(field).asText("")).equals(origin(draft.path(field).asText(""))))same=false;
        return mergeNode(saved,(ObjectNode)draft,same);
    }
    static ObjectNode mergeNode(ObjectNode saved,ObjectNode draft,boolean reuse) {
        ObjectNode result=draft.deepCopy();
        draft.fields().forEachRemaining(entry->{String key=entry.getKey();JsonNode value=entry.getValue();
            if(secret(key)&&value.isTextual()&&(value.asText().contains("****")||value.asText().startsWith("你的"))){
                if(!reuse||!saved.hasNonNull(key)||saved.path(key).asText().isBlank())throw new Failure("CREDENTIAL_REQUIRED","服务地址或供应器已更改，请填写对应凭据后测试。");
                result.set(key,saved.get(key).deepCopy());
            }else if(value.isObject())result.set(key,mergeNode(saved.path(key).isObject()?(ObjectNode)saved.path(key):saved.objectNode(),(ObjectNode)value,reuse));
        });return result;
    }
    static String origin(String value){if(value.isBlank())return "";try{URI uri=URI.create(value);return uri.getScheme()+"://"+uri.getAuthority();}catch(Exception e){return value;}}
    static final class Redactor {
        final ObjectMapper json;final Set<String> values=new HashSet<>();Redactor(ObjectMapper json){this.json=json;}
        void collect(JsonNode node){if(node==null)return;if(node.isObject())node.fields().forEachRemaining(e->{if(secret(e.getKey())&&e.getValue().isTextual()&&!e.getValue().asText().isBlank())values.add(e.getValue().asText());else collect(e.getValue());});else if(node.isArray())node.forEach(this::collect);}
        String clean(String input,int max){String value=input==null?"":input;for(String secret:values.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList())value=value.replace(secret,"[已隐藏]");value=value.replaceAll("(?i)(Bearer|Token)\\s+[A-Za-z0-9._~+/-]+=*","$1 [已隐藏]").replaceAll("(?i)(api[_-]?key|access[_-]?token|secret(?:[_-]?key)?|password)[\\\"']?\\s*[:=]\\s*[\\\"']?[^\\s,;\\\"'}]+","$1=[已隐藏]");return value.substring(0,Math.min(value.length(),max));}
        JsonNode tree(JsonNode node){if(node.isObject()){ObjectNode value=json.createObjectNode();node.fields().forEachRemaining(e->value.set(e.getKey(),secret(e.getKey())?TextNode.valueOf("[已隐藏]"):tree(e.getValue())));return value;}if(node.isArray()){ArrayNode value=json.createArrayNode();node.forEach(n->value.add(tree(n)));return value;}return node.isTextual()?TextNode.valueOf(clean(node.asText(),2000)):node;}
    }
}
