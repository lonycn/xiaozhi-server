package xiaozhi.modules.chatarchive;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import xiaozhi.modules.ai.AiExecutionService;
import xiaozhi.modules.ai.AiModels.*;
import xiaozhi.modules.chatarchive.ChatArchiveConfiguration.Settings;
import xiaozhi.modules.conversation.ConversationDao;
import xiaozhi.modules.conversation.ConversationModels.*;
import xiaozhi.modules.conversation.ConversationService;
import xiaozhi.modules.externalidentity.ExternalIdentityService;
import xiaozhi.modules.externalidentity.ExternalModels.*;

@EnabledIfEnvironmentVariable(named = "CHAT_ARCHIVE_TEST_JDBC_URL", matches = ".+")
class ChatArchivePersistenceTest {
    private record Capture(byte[] body, String nonce, boolean validSignature) { }

    @Test
    void snapshotsAcceptedAndTerminalVersionsAndReplaysSameBytes() throws Exception {
        String url = System.getenv("CHAT_ARCHIVE_TEST_JDBC_URL");
        if (!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/xiaozhi_archive_test_[a-z0-9_]+(\\?.*)?")) {
            throw new IllegalArgumentException("Use an isolated loopback xiaozhi_archive_test_* database");
        }
        var source = new DriverManagerDataSource(url, "root", System.getenv("CHAT_ARCHIVE_TEST_DB_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()", Integer.class) != 0) {
            throw new IllegalArgumentException("Chat archive tests require an empty disposable database");
        }
        var manager = new DataSourceTransactionManager(source);
        var json = new ObjectMapper();
        jdbc.execute("CREATE TABLE sys_user(id BIGINT PRIMARY KEY,status TINYINT NOT NULL)");
        jdbc.update("INSERT INTO sys_user VALUES(99,1)");
        for (String path : List.of("conversation", "support", "ai-execution", "ai-provider-request-usage", "chat-archive")) {
            try (var connection = source.getConnection(); var migration = new Liquibase(
                    "db/changelog/" + path + ".changelog.yaml", new ClassLoaderResourceAccessor(), new JdbcConnection(connection))) {
                migration.update(new Contexts());
                migration.update(new Contexts());
            }
        }
        var receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] key = new byte[48];
        Arrays.fill(key, (byte) 42);
        var received = new CopyOnWriteArrayList<Capture>();
        var code = new AtomicInteger(503);
        receiver.createContext(ChatArchiveConfiguration.PATH, exchange -> {
            byte[] payload = exchange.getRequestBody().readAllBytes();
            var headers = exchange.getRequestHeaders();
            String nonce = headers.getFirst("X-External-Nonce");
            String timestamp = headers.getFirst("X-External-Timestamp");
            boolean valid = "archive-fixture".equals(headers.getFirst("X-External-Key-Id"))
                    && nonce.matches("[a-f0-9]{32}")
                    && Math.abs(Instant.now().getEpochSecond() - Long.parseLong(timestamp)) < 10
                    && ExternalIdentityService.signature(key, exchange.getRequestURI().getRawPath(), timestamp, nonce, payload)
                        .equals(headers.getFirst("X-External-Signature"));
            received.add(new Capture(payload, nonce, valid));
            byte[] ack = "{\"data\":{\"result\":\"duplicate\"}}".getBytes(StandardCharsets.UTF_8);
            int status = code.get();
            exchange.sendResponseHeaders(status, ack.length);
            try (var body = exchange.getResponseBody()) { body.write(ack); }
        });
        receiver.start();
        try {
            var settings = new Settings(URI.create("http://127.0.0.1:" + receiver.getAddress().getPort()
                    + ChatArchiveConfiguration.PATH), "xiaozhi-test", "test-archive", "archive-fixture", key);
            var dao = new ConversationDao(jdbc);
            var conversations = new ConversationService(dao, json, manager);
            var ai = new AiExecutionService(jdbc, json, manager);
            ai.setEnabled(true);
            var archive = new ChatArchiveOutbox(jdbc, json, dao, settings);
            conversations.setChatArchive(archive);
            conversations.setAiExecution(ai);
            ai.setChatArchive(archive);
            String conversation = conversations.create(99L, new CreateConversation("归档联调", null)).id();

            var request = new MockHttpServletRequest();
            request.setAttribute(ExternalIdentityService.ATTRIBUTE, new Principal(1, "fixture", 1, 99,
                    "xiaozhi-test", "42", new Tenant("customer", "17"), new Scope(List.of(), List.of()), Instant.now().plusSeconds(300)));
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            Message input;
            try {
                var transactions = new TransactionTemplate(manager);
                assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(tx -> {
                    conversations.send(99L, conversation, text("rollback"));
                    throw new IllegalStateException("fixture rollback");
                }));
                assertEquals(0, count(jdbc, conversation));
                input = conversations.send(99L, conversation, text("client-1"));
                assertEquals(input.id(), conversations.send(99L, conversation, text("client-1")).id());
                assertEquals(1, count(jdbc, conversation));
            } finally {
                RequestContextHolder.resetRequestAttributes();
            }

            var run = ai.claim(new Claim("archive-worker"));
            ai.fail(run.runId(), new Fail(run.leaseToken(), "PROVIDER_TIMEOUT"));
            ai.retry(99L, conversation, run.runId());
            run = ai.claim(new Claim("archive-worker"));
            ai.delta(run.runId(), new Delta(run.leaseToken(), 1L, "你好"));
            ai.complete(run.runId(), new Complete(run.leaseToken(), 1L, "stop", null, null));
            var bodies = jdbc.queryForList("""
                    SELECT payload FROM xiaozhi_chat_archive_outbox WHERE conversation_id=? ORDER BY event_version
                    """, byte[].class, conversation);
            assertEquals(3, bodies.size());
            JsonNode accepted = json.readTree(bodies.get(0));
            JsonNode failed = json.readTree(bodies.get(1));
            JsonNode completed = json.readTree(bodies.get(2));
            assertFalse(accepted.has("tenant_type"));
            assertEquals("42", accepted.path("user_id").asText());
            assertEquals("17", accepted.path("tenant_id").asText());
            assertEquals(input.id(), accepted.at("/message/id").asText());
            assertEquals("accepted", accepted.at("/message/status").asText());
            assertEquals("failed", failed.at("/message/status").asText());
            assertEquals(run.outputMessageId(), completed.at("/message/id").asText());
            assertEquals(failed.at("/message/id"), completed.at("/message/id"));
            assertEquals(failed.at("/message/seq"), completed.at("/message/seq"));
            assertEquals(failed.at("/message/created_at"), completed.at("/message/created_at"));
            assertTrue(Long.parseLong(completed.path("version").asText()) > Long.parseLong(failed.path("version").asText()));
            assertEquals("你好", completed.at("/message/parts/0/text").asText());

            var delivery = new ChatArchiveDelivery(archive, settings, json);
            delivery.deliverNext();
            assertEquals("HTTP_503", jdbc.queryForObject(
                    "SELECT last_error FROM xiaozhi_chat_archive_outbox WHERE message_id=?", String.class, input.id()));
            jdbc.update("""
                    UPDATE xiaozhi_chat_archive_outbox SET available_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE message_id=?
                    """, input.id());
            code.set(200);
            delivery.deliverNext();
            assertEquals(2, received.size());
            assertTrue(received.stream().allMatch(Capture::validSignature));
            assertArrayEquals(received.get(0).body(), received.get(1).body());
            assertNotEquals(received.get(0).nonce(), received.get(1).nonce());
            assertEquals("delivered", jdbc.queryForObject(
                    "SELECT status FROM xiaozhi_chat_archive_outbox WHERE message_id=?", String.class, input.id()));

            code.set(409);
            delivery.deliverNext();
            assertEquals(1, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM xiaozhi_chat_archive_outbox WHERE conversation_id=? AND status='failed' AND last_error='HTTP_409'
                    """, Integer.class, conversation));
            String accountConversation = conversations.create(99L, new CreateConversation("Account AI", null)).id();
            request.setAttribute(ExternalIdentityService.ATTRIBUTE, new Principal(2, "account-fixture", 1, 99,
                    "xiaozhi-test", "42", new Tenant("account", "42"), new Scope(List.of(), List.of()), Instant.now().plusSeconds(300)));
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            try {
                conversations.send(99L, accountConversation, text("account-input"));
            } finally {
                RequestContextHolder.resetRequestAttributes();
            }
            var accountRun = ai.claim(new Claim("archive-worker"));
            ai.fail(accountRun.runId(), new Fail(accountRun.leaseToken(), "PROVIDER_TIMEOUT"));
            var accountBodies = jdbc.queryForList("SELECT payload FROM xiaozhi_chat_archive_outbox WHERE conversation_id=? ORDER BY event_version", byte[].class, accountConversation);
            assertEquals(2, accountBodies.size());
            for (byte[] body : accountBodies) {
                var typed = json.readTree(body);
                assertEquals("account", typed.path("tenant_type").asText());
                assertEquals("42", typed.path("tenant_id").asText());
                assertEquals("42", typed.path("user_id").asText());
            }
            conversations.send(99L, conversation, text("non-external"));
            assertEquals(3, count(jdbc, conversation));
        } finally {
            receiver.stop(0);
        }
    }

    private static int count(JdbcTemplate jdbc, String conversation) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM xiaozhi_chat_archive_outbox WHERE conversation_id=?",
                Integer.class, conversation);
    }

    private static SendMessage text(String id) {
        return new SendMessage(id, List.of(new MessagePart("text", "冷库巡检", null, null, null, null, null, null)));
    }
}
