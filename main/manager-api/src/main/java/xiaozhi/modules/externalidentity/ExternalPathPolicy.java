package xiaozhi.modules.externalidentity;

import java.util.Set;

/** Explicit end-user routes only. New endpoints never become externally accessible by prefix. */
public final class ExternalPathPolicy {
    private ExternalPathPolicy(){}
    private static final String C="[a-f0-9]{32}",N="[1-9][0-9]{0,18}";
    public static boolean publicEntry(String path) {
        return Set.of(ExternalIdentityService.ISSUE,ExternalIdentityService.REVOKE,"/api/v1/external/identity/exchange").contains(path)
            || path.matches("/api/v1/external/attachments/"+N+"/content");
    }
    public static boolean rtc(String path){return path.startsWith("/api/v1/support/media-") || path.contains("/media-transfers") || path.endsWith("/token");}
    public static boolean allowed(String method,String path) {
        if(path.contains("%") || path.contains(";") || path.contains("\\") || path.contains("//"))return false;
        if(method.equals("GET")) {
            return path.equals("/api/v1/ai/conversations")
                || path.matches("/api/v1/ai/conversations/"+C+"(?:/(?:messages|events|runs|stream))?")
                || path.matches("/api/v1/ai/conversations/"+C+"/runs/"+C)
                || path.matches("/api/v1/ai/attachments/"+N)
                || path.matches("/api/v1/support/requests/"+C)
                || path.matches("/api/v1/support/(?:frames|annotations)/"+N)
                || path.equals("/api/v1/support/annotations")
                || path.equals("/api/v1/external/identity/me")
                || path.matches("/api/v1/external/attachments/"+N+"/access")
                || path.matches("/api/v1/external/business/(?:devices|work-orders)/[A-Za-z0-9_.:-]{1,100}")
                || path.equals("/api/v1/external/business/knowledge");
        }
        if(method.equals("POST"))return path.equals("/api/v1/ai/conversations")
            || path.matches("/api/v1/ai/conversations/"+C+"/(?:messages|handoff|resume-ai)")
            || path.matches("/api/v1/ai/conversations/"+C+"/runs/"+C+"/(?:cancel|retry)")
            || path.equals("/api/v1/ai/uploads/init")
            || path.matches("/api/v1/ai/uploads/"+N+"/complete")
            || path.matches("/api/v1/ai/attachments/"+N+"/retry")
            || path.matches("/api/v1/support/requests/"+C+"/cancel")
            || path.matches("/api/v1/support/(?:frames|annotations)")
            || path.matches("/api/v1/support/(?:frames|annotations)/"+N+"/retry")
            || path.equals("/api/v1/external/identity/logout");
        return method.equals("PUT") && path.matches("/api/v1/ai/uploads/"+N+"/content");
    }
    public static Set<String> queryKeys(String method,String path) {
        if(!method.equals("GET"))return Set.of();
        if(path.endsWith("/access"))return Set.of("variant");
        if(path.equals("/api/v1/external/business/knowledge"))return Set.of("q");
        if(path.equals("/api/v1/support/annotations"))return Set.of("conversation_id","after","limit");
        if(path.endsWith("/messages") || path.endsWith("/events"))return Set.of("after","limit");
        if(path.endsWith("/stream"))return Set.of("after");
        if(path.equals("/api/v1/ai/conversations") || path.endsWith("/runs"))return Set.of("before","limit");
        return Set.of();
    }
}
