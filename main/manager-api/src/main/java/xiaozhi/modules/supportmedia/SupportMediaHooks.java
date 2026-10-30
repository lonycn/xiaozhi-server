package xiaozhi.modules.supportmedia;

/** B1 integration. SQL-only methods join the caller's existing transaction; never perform network IO. */
public interface SupportMediaHooks {
    int reservedTextSlots(long actor);
    void responsibilityEnded(String requestId);
    void accountChanged(long user);
}
