package xiaozhi.modules.attachment;

import java.util.Map;

/** Internal extension point. Values are resolved from server-owned immutable provenance rows. */
public interface GeneratedMediaSupport {
    record Input(String attachmentId, String variant, String filename, Map<String,Object> metadata) { }
    /** Current writer + source authorization, called for every claim and leased operation. */
    void validate(long outputAttachmentId, String jobType, long actor);
    Input input(long outputAttachmentId, String jobType);
    /** Validate worker-only media fields, persist actual PTS, and add trusted provenance. */
    Map<String,Object> complete(long outputAttachmentId, String jobType, Map<String,Object> metadata);
    /** Called in the existing message transaction, after conversation authorization and before publication. */
    void bind(long actor, String conversationId, long attachmentId, String messageId, long seq);
}
