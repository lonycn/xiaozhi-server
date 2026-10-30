package xiaozhi.modules.externalidentity;

/** Sanitized external-boundary errors; no source bodies, keys or tokens become exception text. */
public final class ExternalProblem extends xiaozhi.modules.conversation.ConversationProblem {
    public ExternalProblem(int status,String code) { super(status,code); }
}
