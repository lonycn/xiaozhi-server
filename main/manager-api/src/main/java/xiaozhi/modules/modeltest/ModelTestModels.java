package xiaozhi.modules.modeltest;

import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class ModelTestModels {
    private ModelTestModels() {}
    public record Request(String requestId, JsonNode configJson, String text, String audioBase64,
                          String audioMime, String imageBase64, String imageMime, String datasetId) {}
    public record Probe(String modelType, ObjectNode config, String text, byte[] audio, String audioMime,
                        byte[] image, String imageMime, String datasetId) {}
    public record Log(String time, long elapsedMs, String level, String stage, String message) {}
    public record Outcome(String status, String scope, String summary, String output, JsonNode details,
                          byte[] audio, String audioMime) {
        public static Outcome checked(String summary) {
            return new Outcome("checked", "configuration", summary, "", null, null, null);
        }
        public static Outcome unsupported(String provider) {
            return new Outcome("unsupported", "none", "暂未适配供应器 " + provider + " 的实际调用测试，未发起推理。", "", null, null, null);
        }
    }
    public record Run(String id, String modelId, String modelType, String provider, String status,
                      String scope, String summary, String errorCode, Long durationMs, String createdAt,
                      JsonNode result, List<Log> logs, boolean audioAvailable) {}
    public static final class Failure extends RuntimeException {
        public final String code;
        public final int status;
        public Failure(String code, String message) { this(400, code, message); }
        public Failure(int status, String code, String message) { super(message); this.status = status; this.code = code; }
    }
}
