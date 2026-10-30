package xiaozhi.modules.conversation;

public class ConversationProblem extends RuntimeException {
    private final int status;

    public ConversationProblem(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
