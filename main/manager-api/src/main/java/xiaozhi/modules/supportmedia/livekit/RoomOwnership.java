package xiaozhi.modules.supportmedia.livekit;

/**
 * Must be implemented by the caller against its persisted session/generation/room ledger.
 * A prefix or a syntactically valid OwnedRoom is never evidence of ownership.
 * TOKEN also requires the supplied identity to be server-created for this current generation.
 * Cleanup may authorize a persisted older generation, but never another module's room.
 */
@FunctionalInterface
public interface RoomOwnership {
    boolean allowed(LiveKitAdapter.OwnedRoom room, LiveKitAdapter.Operation operation, String identity);
}
