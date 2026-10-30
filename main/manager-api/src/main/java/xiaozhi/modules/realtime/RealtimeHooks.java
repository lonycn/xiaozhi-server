package xiaozhi.modules.realtime;

/** Hooks execute in the caller's existing conversation-lock transaction; they perform no external I/O. */
public interface RealtimeHooks {
    boolean onUserMessage(String conversationId,String messageId);
    boolean activeInConversation(String conversationId);
    void cancelInConversation(String conversationId,String reason);
}
