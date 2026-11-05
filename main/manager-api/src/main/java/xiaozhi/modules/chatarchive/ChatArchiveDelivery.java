package xiaozhi.modules.chatarchive;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import xiaozhi.modules.chatarchive.ChatArchiveConfiguration.Settings;
import xiaozhi.modules.chatarchive.ChatArchiveOutbox.Task;
import xiaozhi.modules.externalidentity.ExternalIdentityService;

@Component
@ConditionalOnProperty(prefix = "xiaozhi.archive", name = "enabled", havingValue = "true")
class ChatArchiveDelivery {
    private static final Logger LOG = LoggerFactory.getLogger(ChatArchiveDelivery.class);
    private static final int MAX_RESPONSE_BYTES = 8192;
    private static final Set<String> ACKS = Set.of("applied", "duplicate", "stale");
    private static final Set<Integer> RETRYABLE = Set.of(429, 500, 502, 503, 504);

    private final ChatArchiveOutbox outbox;
    private final Settings settings;
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();

    ChatArchiveDelivery(ChatArchiveOutbox outbox, Settings settings, ObjectMapper json) {
        this.outbox = outbox;
        this.settings = settings;
        this.json = json;
    }

    @Scheduled(fixedDelayString = "${xiaozhi.archive.poll-ms:1000}", scheduler = "chatArchiveScheduler")
    void deliverNext() {
        Task task = outbox.claim();
        if (task == null) return;
        // Enforce the wire limit again even when an operator requeues a failed row.
        if (task.payload().length > ChatArchiveConfiguration.MAX_PAYLOAD_BYTES) {
            fail(task, "PAYLOAD_LIMIT");
            return;
        }
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        var request = HttpRequest.newBuilder(settings.url()).timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-External-Key-Id", settings.keyId())
                .header("X-External-Timestamp", timestamp)
                .header("X-External-Nonce", nonce)
                .header("X-External-Signature", ExternalIdentityService.signature(
                        settings.key(), ChatArchiveConfiguration.PATH, timestamp, nonce, task.payload()))
                .POST(HttpRequest.BodyPublishers.ofByteArray(task.payload())).build();

        var body = new ByteArrayOutputStream();
        var pending = client.sendAsync(request, info -> info.statusCode() == 200
                ? HttpResponse.BodySubscribers.ofByteArrayConsumer(chunk -> chunk.ifPresent(bytes -> {
                    if (bytes.length > MAX_RESPONSE_BYTES - body.size()) throw new InvalidResponse();
                    body.writeBytes(bytes);
                }))
                : HttpResponse.BodySubscribers.discarding());
        HttpResponse<Void> response;
        try {
            response = pending.get(8, TimeUnit.SECONDS);
        } catch (InterruptedException shutdown) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            return; // The lease expires so another instance can resume it.
        } catch (TimeoutException timeout) {
            pending.cancel(true);
            retry(task, "NETWORK", 0);
            return;
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof InvalidResponse) fail(task, "INVALID_RESPONSE");
            else retry(task, "NETWORK", 0);
            return;
        }
        int code = response.statusCode();
        if (code == 200 && acknowledged(body.toByteArray())) {
            outbox.finish(task, "delivered", null, 0);
        } else if (RETRYABLE.contains(code)) {
            int wait = retryAfterSeconds(response.headers().firstValue("Retry-After").orElse(""));
            retry(task, "HTTP_" + code, wait);
        } else {
            fail(task, code == 200 ? "INVALID_RESPONSE" : "HTTP_" + code);
        }
    }

    private static int retryAfterSeconds(String header) {
        long seconds;
        try {
            seconds = Long.parseLong(header);
        } catch (NumberFormatException invalidSeconds) {
            try {
                Instant retryAt = ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                seconds = Duration.between(Instant.now(), retryAt).toSeconds();
            } catch (DateTimeParseException invalidDate) {
                return 0;
            }
        }
        return (int) Math.clamp(seconds, 0, 3600);
    }

    private boolean acknowledged(byte[] body) {
        try {
            String result = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(body).path("data").path("result").asText();
            return ACKS.contains(result);
        } catch (java.io.IOException invalid) {
            return false;
        }
    }

    private void retry(Task task, String error, int retryAfter) {
        int delay = Math.max(retryAfter, Math.min(300, 1 << Math.min(9, task.attempt())));
        outbox.finish(task, "pending", error, delay);
    }

    private void fail(Task task, String error) {
        outbox.finish(task, "failed", error, 0);
        LOG.warn("Chat archive delivery failed, task_id={}, code={}", task.id(), error);
    }

    private static final class InvalidResponse extends RuntimeException { }
}
