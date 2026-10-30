package xiaozhi.modules.supportmedia.livekit;

/** Sanitized classifications only: never retain provider bodies, URLs, credentials or causes. */
public final class LiveKitFailure extends RuntimeException {
    public enum Code { NOT_CONFIGURED, OWNERSHIP_DENIED, INVALID_REQUEST, AUTH, RATE_LIMIT,
        TIMEOUT, UNAVAILABLE, NOT_FOUND, PROTOCOL, INVALID_WEBHOOK }
    private final Code code;
    public LiveKitFailure(Code code) { super("LIVEKIT_" + code.name()); this.code=code; }
    public Code code() { return code; }
}
