package xiaozhi.modules.conversation;

import static org.junit.jupiter.api.Assertions.*;
import static xiaozhi.modules.conversation.ConversationServiceTest.text;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.LongStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import xiaozhi.modules.conversation.ConversationModels.*;

/** Runs on an explicitly isolated MySQL 8 database, never on the application's configured database. */
@EnabledIfEnvironmentVariable(named = "CONVERSATION_TEST_JDBC_URL", matches = ".+")
class ConversationPersistenceTest {
    private static JdbcTemplate jdbc;
    private static DriverManagerDataSource source;
    private static ConversationService service;

    @BeforeAll
    static void initialize() throws Exception {
        String url = System.getenv("CONVERSATION_TEST_JDBC_URL");
        if (!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/xiaozhi_conversation_test_[a-z0-9_]+(\\?.*)?")) {
            throw new IllegalArgumentException("Tests require a loopback xiaozhi_conversation_test_* database");
        }
        source = new DriverManagerDataSource(url, "root", System.getenv("CONVERSATION_TEST_DB_PASSWORD"));
        jdbc = new JdbcTemplate(source);
        // A legacy record must survive the additive migration untouched.
        jdbc.execute("CREATE TABLE ai_agent_chat_history (id BIGINT PRIMARY KEY, content VARCHAR(100))");
        jdbc.update("INSERT INTO ai_agent_chat_history VALUES (1, 'legacy-sentinel')");
        jdbc.execute("CREATE TABLE ai_agent (id VARCHAR(32) PRIMARY KEY, user_id BIGINT)");
        jdbc.update("INSERT INTO ai_agent VALUES ('owned-agent', 7), ('other-agent', 8)");
        jdbc.execute("CREATE TABLE sys_user (id BIGINT PRIMARY KEY, status TINYINT NOT NULL)");
        jdbc.update("INSERT INTO sys_user VALUES (7, 1), (8, 1), (71, 1)");
        try (var connection = source.getConnection(); var migration = new Liquibase(
                "db/changelog/conversation.changelog.yaml", new ClassLoaderResourceAccessor(), new JdbcConnection(connection))) {
            migration.update(new Contexts());
            migration.update(new Contexts());
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM DATABASECHANGELOG", Long.class));
        try (var connection = source.getConnection(); var migration = new Liquibase(
                "db/changelog/support.changelog.yaml", new ClassLoaderResourceAccessor(), new JdbcConnection(connection))) {
            migration.update(new Contexts());
            migration.update(new Contexts());
        }
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM DATABASECHANGELOG", Long.class));
        service = service(new ConversationDao(jdbc));
    }

    private static ConversationService service(ConversationDao dao) {
        return new ConversationService(dao, JsonMapper.builder().addModule(new JavaTimeModule()).build(),
                new DataSourceTransactionManager(source));
    }

    @Test
    void persistsAcrossServiceRestartAndRejectsCrossAccountReadsAndWrites() {
        Conversation conversation = service.create(7L, new CreateConversation("设备故障", "owned-agent"));
        var sent = service.send(7L, conversation.id(), new SendMessage("one", List.of(text("压缩机有异响"))));
        var restarted = service(new ConversationDao(jdbc));
        assertEquals(sent, restarted.messages(7L, conversation.id(), 0, 50).items().getFirst());
        assertTrue(restarted.list(8L, 0, 50).items().stream().noneMatch(c -> c.id().equals(conversation.id())));
        assertEquals(404, assertThrows(ConversationProblem.class,
                () -> restarted.get(8L, conversation.id())).status());
        assertThrows(ConversationProblem.class, () -> restarted.messages(8L, conversation.id(), 0, 50));
        assertThrows(ConversationProblem.class, () -> restarted.events(8L, conversation.id(), 0, 50));
        assertThrows(ConversationProblem.class, () -> restarted.send(8L, conversation.id(), new SendMessage("evil", List.of(text("spoof")))));
        assertEquals(403, assertThrows(ConversationProblem.class,
                () -> restarted.create(7L, new CreateConversation("test", "other-agent"))).status());
        assertEquals("legacy-sentinel", jdbc.queryForObject("SELECT content FROM ai_agent_chat_history WHERE id=1", String.class));
    }

    @Test
    void concurrentRetriesCreateOneMessageAndOneAcceptedEvent() throws Exception {
        var conversation = service.create(7L, new CreateConversation("retry", null));
        var request = new SendMessage("same", List.of(text("一次发送")));
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<String>> calls = new ArrayList<>();
            for (int i = 0; i < 24; i++) calls.add(() -> service.send(7L, conversation.id(), request).id());
            var ids = executor.invokeAll(calls).stream().map(future -> {
                try { return future.get(); } catch (Exception e) { throw new AssertionError(e); }
            }).distinct().toList();
            assertEquals(1, ids.size());
        }
        assertEquals(1, service.messages(7L, conversation.id(), 0, 50).items().size());
        assertEquals(2, service.events(7L, conversation.id(), 0, 50).items().size());
        assertEquals(409, assertThrows(ConversationProblem.class, () -> service.send(7L, conversation.id(),
                new SendMessage("same", List.of(text("不同内容"))))).status());
        // ASCII binary comparison: differently cased client IDs must remain independent.
        assertEquals(2, service.send(7L, conversation.id(), new SendMessage("SAME", List.of(text("另一次发送")))).seq());
    }

    @Test
    void concurrentMessagesHaveContiguousSequencesAndReplayWithoutLoss() throws Exception {
        var conversation = service.create(7L, new CreateConversation("concurrent", null));
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<Long>> calls = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                String clientId = "client-" + i;
                calls.add(() -> service.send(7L, conversation.id(), new SendMessage(clientId, List.of(text(clientId)))).seq());
            }
            for (var future : executor.invokeAll(calls)) assertNotNull(future.get());
        }
        var first = service.messages(7L, conversation.id(), 0, 11);
        assertTrue(first.hasMore());
        var second = service.messages(7L, conversation.id(), first.nextCursor(), 100);
        assertFalse(second.hasMore());
        var combined = new ArrayList<>(first.items());
        combined.addAll(second.items());
        assertEquals(LongStream.rangeClosed(1, 30).boxed().toList(), combined.stream().map(Message::seq).toList());
        assertEquals(30, second.nextCursor());
        var events = service.events(7L, conversation.id(), 1, 100);
        assertEquals(30, events.items().size());
        assertEquals(31, events.nextCursor());
        assertTrue(service.events(7L, conversation.id(), 31, 100).items().isEmpty());
        assertEquals(31, service.events(7L, conversation.id(), 31, 100).nextCursor());
    }

    @Test
    void mixedPartsKeepTheirOrderAndConversationListUsesExclusiveCursor() throws Exception {
        var first = service.create(71L, new CreateConversation("first", null));
        var second = service.create(71L, new CreateConversation("second", null));
        var page = service.list(71L, 0, 1);
        assertTrue(page.hasMore());
        assertEquals(second.id(), page.items().getFirst().id());
        assertEquals(first.id(), service.list(71L, page.nextCursor(), 1).items().getFirst().id());
        var json = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        var request = service.request(json.readTree("""
                {"client_message_id":"mixed","parts":[
                  {"type":"text","text":"现场位置"},
                  {"type":"location","latitude":31.1,"longitude":121.1,"coordinate_system":"GCJ02",
                   "accuracy_m":15,"source":"user_shared","authorized_at":"2026-01-01T00:00:00Z"},
                  {"type":"text","text":"压缩机有异响"}]}
                """), SendMessage.class);
        var message = service.send(71L, first.id(), request);
        assertEquals(request.parts(), message.parts());
        assertEquals(1, service.messages(71L, first.id(), 0, 50).items().size());
        assertEquals(message, service.send(71L, first.id(), request));
    }

    @Test
    void eventFailureRollsBackMessageAndCounters() {
        var conversation = service.create(7L, new CreateConversation("rollback", null));
        ConversationDao failingDao = new ConversationDao(jdbc) {
            @Override public void insertEvent(String id, String conversationId, long seq, String type, String data) {
                throw new IllegalStateException("simulated event write failure");
            }
        };
        assertThrows(IllegalStateException.class, () -> service(failingDao).send(7L, conversation.id(),
                new SendMessage("rollback", List.of(text("must roll back")))));
        assertEquals(0, service.get(7L, conversation.id()).lastSeq());
        assertEquals(1, service.get(7L, conversation.id()).lastEventSeq());
        assertTrue(service.messages(7L, conversation.id(), 0, 50).items().isEmpty());
        assertEquals(1, service.send(7L, conversation.id(), new SendMessage("rollback", List.of(text("retry")))).seq());
    }
}
