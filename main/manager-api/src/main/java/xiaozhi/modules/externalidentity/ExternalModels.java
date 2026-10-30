package xiaozhi.modules.externalidentity;

import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

public final class ExternalModels {
    private ExternalModels(){}
    public record Tenant(String type,String id){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Scope(List<String> projectIds,List<String> deviceIds){}
    public record Verified(String keyId,String issuer,String nonce,String path,String bodyHash){}
    public record Principal(long tokenId,String tokenHash,long sourceId,long userId,String issuer,String subject,Tenant tenant,Scope scope,Instant expiresAt) {
        @Override public String toString(){return "ExternalPrincipal[redacted]";}
    }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Issued(String ticket,int expiresIn){@Override public String toString(){return "Issued[redacted]";}}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Exchanged(String token,int expiresIn,String userId,Tenant tenant,Scope scope) {
        @Override public String toString(){return "Exchanged[redacted]";}
    }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MediaAccess(String url,Instant expiresAt,String mimeType,String filename) {
        @Override public String toString(){return "MediaAccess[redacted]";}
    }
}
