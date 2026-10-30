package xiaozhi.modules.externalidentity;

import java.time.Instant;
import com.fasterxml.jackson.databind.JsonNode;
import xiaozhi.modules.externalidentity.ExternalModels.Principal;

/**
 * Future source-specific implementation must authenticate the real delegated source user/session,
 * check live tenant and resource ownership, issue only bounded fixed read operations, and retain
 * provenance/time/units. Current Laravel Workbench credentials are NOT user delegation.
 * No implementation or outbound HTTP is installed until those real endpoints are confirmed.
 */
public interface ExternalBusinessAdapter {
    record Read(String kind,String resourceId,String query){}
    record Evidence(String issuer,String tenantType,String tenantId,String resourceType,String sourceRecordId,
                    Instant observedAt,JsonNode data){}
    Evidence read(Principal trustedContext,Read request);
}
