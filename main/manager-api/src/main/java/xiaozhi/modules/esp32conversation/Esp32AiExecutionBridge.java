package xiaozhi.modules.esp32conversation;

/** Called inside the accepted input transaction; implementations enqueue the normal AI worker only. */
public interface Esp32AiExecutionBridge {
    void submit(Esp32ConversationService.TrustedContext context, String conversationId, String messageId, String turnId);
    default void submit(Esp32ConversationService.TrustedContext context, String conversationId, String messageId, String turnId, String rolePrompt) {
        submit(context, conversationId, messageId, turnId);
    }
    default java.util.Map<String,Object> reply(String conversationId, String messageId) { return java.util.Map.of("status", "pending"); }
}
