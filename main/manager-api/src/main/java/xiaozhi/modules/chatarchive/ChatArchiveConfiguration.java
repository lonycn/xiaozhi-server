package xiaozhi.modules.chatarchive;

import java.net.URI;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import xiaozhi.modules.externalidentity.ExternalSettings;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "xiaozhi.archive", name = "enabled", havingValue = "true")
class ChatArchiveConfiguration {
    static final String PATH = "/api/integrations/xiaozhi/archive";
    static final int MAX_PAYLOAD_BYTES = 512 * 1024;
    private static final Pattern PRIVATE_IPV4 = Pattern.compile(
            "(?:10\\.[0-9]{1,3}|172\\.(?:1[6-9]|2[0-9]|3[01])|192\\.168)\\.[0-9]{1,3}\\.[0-9]{1,3}");

    record Settings(URI url, String sourceIssuer, String archiveIssuer, String keyId, byte[] key) {
        @Override public String toString() { return "ChatArchiveSettings[redacted]"; }
    }

    @Bean
    Settings chatArchiveSettings(ExternalSettings external,
            @Value("${xiaozhi.archive.url}") String url,
            @Value("${xiaozhi.archive.allow-insecure-lan:false}") boolean allowInsecureLan,
            @Value("${xiaozhi.archive.source-issuer}") String sourceIssuer,
            @Value("${xiaozhi.archive.issuer}") String archiveIssuer,
            @Value("${xiaozhi.archive.key-id}") String keyId,
            @Value("${xiaozhi.archive.key-b64}") String keyB64) {
        URI endpoint;
        byte[] key;
        try {
            endpoint = URI.create(url);
            key = Base64.getDecoder().decode(keyB64);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("Invalid chat archive URL or key encoding");
        }
        String host = endpoint.getHost();
        boolean allowedScheme = "https".equals(endpoint.getScheme()) || allowInsecureLan
                && "http".equals(endpoint.getScheme()) && host != null && PRIVATE_IPV4.matcher(host).matches();
        if (!allowedScheme || host == null
                || !PATH.equals(endpoint.getRawPath()) || endpoint.getRawUserInfo() != null
                || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
                || !archiveIssuer.matches("[A-Za-z0-9_-]{1,80}") || !keyId.matches("[A-Za-z0-9_-]{1,80}")
                || key.length < 32 || !Base64.getEncoder().encodeToString(key).equals(keyB64)) {
            throw new IllegalStateException("Invalid chat archive configuration");
        }
        var identityKeys = external.keys().values();
        if (identityKeys.stream().noneMatch(identity -> identity.issuer().equals(sourceIssuer))
                || identityKeys.stream().anyMatch(identity -> MessageDigest.isEqual(identity.secret(), key))) {
            throw new IllegalStateException("Chat archive requires a registered source and a separate signing key");
        }
        return new Settings(endpoint, sourceIssuer, archiveIssuer, keyId, key);
    }

    // Keep the HTTP round trip off the shared scheduler used for session and media maintenance.
    @Bean(defaultCandidate = false)
    ThreadPoolTaskScheduler chatArchiveScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("chat-archive-");
        return scheduler;
    }
}
