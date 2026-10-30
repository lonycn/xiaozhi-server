package xiaozhi.modules.support;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import xiaozhi.modules.conversation.ConversationDao;
import xiaozhi.modules.conversation.ConversationModels.*;
import xiaozhi.modules.conversation.ConversationProblem;
import xiaozhi.modules.conversation.ConversationService;
import xiaozhi.modules.support.SupportModels.*;

/** Real MySQL concurrency and permissions; refuses the configured application database. */
@EnabledIfEnvironmentVariable(named = "SUPPORT_TEST_JDBC_URL", matches = ".+")
class SupportPersistenceTest {
    static JdbcTemplate jdbc;
    static DriverManagerDataSource source;
    static ConversationService conversations;
    static SupportService support;

    @BeforeAll
    static void initialize() throws Exception {
        String url = System.getenv("SUPPORT_TEST_JDBC_URL");
        if (!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/xiaozhi_support_test_[a-z0-9_]+(\\?.*)?")) {
            throw new IllegalArgumentException("Tests require an isolated loopback xiaozhi_support_test_* database");
        }
        source = new DriverManagerDataSource(url, "root", System.getenv("SUPPORT_TEST_DB_PASSWORD"));
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE sys_user (id BIGINT PRIMARY KEY, status TINYINT NOT NULL, super_admin TINYINT NOT NULL DEFAULT 0, username VARCHAR(80))");
        jdbc.execute("CREATE TABLE ai_agent (id VARCHAR(32) PRIMARY KEY, user_id BIGINT)");
        jdbc.execute("CREATE TABLE ai_agent_chat_history (id BIGINT PRIMARY KEY, content VARCHAR(100))");
        jdbc.update("INSERT INTO ai_agent_chat_history VALUES (1, 'legacy-sentinel')");
        for (long actor = 1; actor <= 6; actor++) {
            jdbc.update("INSERT INTO sys_user (id,status,super_admin,username) VALUES (?,1,?,?)", actor, actor == 1 ? 1 : 0, "support-test-" + actor);
        }
        for (String path : List.of("conversation", "support")) {
            try (var connection = source.getConnection(); var migration = new Liquibase(
                    "db/changelog/" + path + ".changelog.yaml", new ClassLoaderResourceAccessor(), new JdbcConnection(connection))) {
                migration.update(new Contexts());
                migration.update(new Contexts());
            }
        }
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM DATABASECHANGELOG", Long.class));
        var json = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        var tx = new DataSourceTransactionManager(source);
        conversations = new ConversationService(new ConversationDao(jdbc), json, tx);
        support = new SupportService(jdbc, json, tx, conversations);
    }

    @BeforeEach
    void resetIsolatedTables() {
        for (String table : List.of("support_request_key", "support_assignment", "support_request", "ai_message", "ai_conversation_event", "ai_conversation", "support_agent_profile")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("UPDATE sys_user SET status=1");
        support.upsertAgent(1L, 3L, new AgentUpdate("support_agent", true, 1));
        support.upsertAgent(1L, 4L, new AgentUpdate("expert", true, 1));
        support.presence(3L, new PresenceUpdate(true));
        support.presence(4L, new PresenceUpdate(true));
    }

    private String conversation() {
        return conversations.create(2L, new CreateConversation("隔离后端测试", null)).id();
    }

    private String handoff(String id) {
        return support.handoff(2L, id, new Handoff("request-1", "text", null)).requestId();
    }

    private SendMessage message(String key, String text) {
        return new SendMessage(key, List.of(new MessagePart("text", text, null, null, null, null, null, null)));
    }

    private long number(String sql, Object... values) {
        return jdbc.queryForObject(sql, Long.class, values);
    }

    private int problem(Runnable action) {
        return assertThrows(ConversationProblem.class, action::run).status();
    }

    @Test
    void humanTextLifecycleUsesOneHistoryAndExplicitAiResume() {
        String id = conversation();
        conversations.send(2L, id, message("owner-1", "开发测试：设备异常"));
        String request = handoff(id);
        long epoch = number("SELECT control_epoch FROM ai_conversation WHERE id=?", id);
        assertTrue(epoch > 0);
        assertEquals("HUMAN_QUEUED", conversations.get(2L, id).state());
        assertEquals(1, support.queue(3L, 0, 50).items().size());
        support.claim(3L, request);
        assertEquals("HUMAN_ACTIVE", conversations.get(2L, id).state());
        var reply = support.send(3L, request, message("staff-1", "开发测试：请检查运行状态"));
        assertEquals("support_agent", reply.senderType());
        assertEquals("3", reply.senderId());
        assertEquals("completed", reply.status());
        assertEquals(2, conversations.messages(2L, id, 0, 100).items().size());
        assertEquals(reply, support.send(3L, request, message("staff-1", "开发测试：请检查运行状态")));
        assertEquals(1, number("SELECT COUNT(*) FROM support_request WHERE id=? AND first_response_at IS NOT NULL", request));
        assertEquals(404, problem(() -> conversations.messages(5L, id, 0, 100)));
        assertEquals(404, problem(() -> conversations.events(5L, id, 0, 100)));
        support.resolve(3L, request, new EndRequest("测试完成"));
        support.resolve(3L, request, new EndRequest("测试完成"));
        assertEquals("RESOLVED", conversations.get(2L, id).state());
        var before = conversations.events(2L, id, 0, 100).items().size();
        support.resumeAi(2L, id);
        assertEquals("AI_ACTIVE", conversations.get(2L, id).state());
        assertTrue(number("SELECT control_epoch FROM ai_conversation WHERE id=?", id) > epoch);
        support.resumeAi(2L, id);
        assertEquals(before + 1, conversations.events(2L, id, 0, 100).items().size());
        conversations.send(2L, id, message("owner-2", "这条是恢复后的私有消息"));
        assertEquals(2, conversations.messages(3L, id, 0, 100).items().size());
        assertEquals(404, problem(() -> conversations.get(3L, id)));
        assertTrue(conversations.events(3L, id, 0, 100).items().stream().noneMatch(e -> "ai.resumed".equals(e.type())));
        assertThrows(ConversationProblem.class, () -> support.send(3L, request, message("late", "离任回复")));
        assertEquals("legacy-sentinel", jdbc.queryForObject("SELECT content FROM ai_agent_chat_history WHERE id=1", String.class));
    }

    @Test
    void concurrentRequestsAndClaimsAreAtomic() throws Exception {
        String id = conversation();
        try (var executor = Executors.newFixedThreadPool(10)) {
            var start = new CountDownLatch(1);
            List<Callable<String>> requests = new ArrayList<>();
            for (int i = 0; i < 20; i++) requests.add(() -> {
                start.await();
                return support.handoff(2L, id, new Handoff("concurrent-1", "text", null)).requestId();
            });
            var futures = requests.stream().map(executor::submit).toList();
            start.countDown();
            var results = new ArrayList<String>();
            for (var future : futures) results.add(future.get());
            assertEquals(1, results.stream().distinct().count());
        }
        assertEquals(1, number("SELECT COUNT(*) FROM support_request WHERE conversation_id=?", id));
        assertEquals(1, number("SELECT control_epoch FROM ai_conversation WHERE id=?", id));
        String request = jdbc.queryForObject("SELECT id FROM support_request WHERE conversation_id=?", String.class, id);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            List<Callable<Long>> claims = List.of(() -> claimResult(start, 3L, request), () -> claimResult(start, 4L, request));
            var futures = claims.stream().map(executor::submit).toList();
            start.countDown();
            var results = new ArrayList<Long>();
            for (var future : futures) results.add(future.get());
            assertEquals(1, results.stream().filter(v -> v > 0).count());
        }
        assertEquals(1, number("SELECT COUNT(*) FROM support_assignment WHERE request_id=? AND released_at IS NULL", request));
    }

    private long claimResult(CountDownLatch start, long actor, String request) throws Exception {
        start.await();
        try { support.claim(actor, request); return actor; }
        catch (ConversationProblem error) { assertEquals(409, error.status()); return -1; }
    }

    @Test
    void profileLockPreventsOversubscriptionAcrossDifferentConversations() throws Exception {
        String first = handoff(conversation());
        String second = handoff(conversation());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a = executor.submit(() -> claimResult(start, 3L, first));
            var b = executor.submit(() -> claimResult(start, 3L, second));
            start.countDown();
            assertEquals(1, List.of(a.get(), b.get()).stream().filter(v -> v > 0).count());
        }
        assertEquals(1, number("SELECT COUNT(*) FROM support_assignment WHERE agent_user_id=3 AND released_at IS NULL"));
    }

    @Test
    void rejectsSpoofingTargetsConflictsAndExpiredOrDisabledProfiles() {
        String id = conversation();
        String request = handoff(id);
        assertEquals(409, problem(() -> support.handoff(2L, id, new Handoff("request-1", "text", 4L))));
        assertThrows(ConversationProblem.class, () -> support.handoff(2L, conversation(), new Handoff("bad-mode", "video", null)));
        jdbc.update("UPDATE support_agent_profile SET presence_expires_at=DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE) WHERE user_id=3");
        assertThrows(ConversationProblem.class, () -> support.claim(3L, request));
        support.presence(3L, new PresenceUpdate(true));
        support.claim(3L, request);
        assertEquals(409, problem(() -> support.cancel(2L, request, new EndRequest(null))));
        assertThrows(ConversationProblem.class, () -> support.resumeAi(2L, id));
        conversations.send(2L, id, message("collision", "用户原文"));
        assertEquals(409, problem(() -> support.send(3L, request, message("collision", "用户原文"))));
        assertThrows(ConversationProblem.class, () -> conversations.sendSupport(4L, id, message("spoof", "冒充客服"), "support_agent"));
        jdbc.update("UPDATE sys_user SET status=0 WHERE id=3");
        assertThrows(ConversationProblem.class, () -> conversations.messages(3L, id, 0, 100));
        assertThrows(ConversationProblem.class, () -> support.send(3L, request, message("disabled", "禁用账号")));
        assertThrows(ConversationProblem.class, () -> support.upsertAgent(2L, 5L, new AgentUpdate("expert", true, 2)));
    }

    @Test
    void cancellationAndRequeuePreserveHistoryAndAllowAnotherAssignee() {
        String first = handoff(conversation());
        support.cancel(2L, first, new EndRequest("取消等待"));
        support.cancel(2L, first, new EndRequest("取消等待"));
        assertEquals(0, number("SELECT COUNT(*) FROM support_request WHERE status='queued'"));
        String id = conversation();
        String request = handoff(id);
        support.claim(3L, request);
        support.send(3L, request, message("first-staff", "交接前记录"));
        support.requeue(3L, request, new EndRequest("暂停接待"), false);
        support.claim(4L, request);
        var reply = support.send(4L, request, message("second-staff", "交接后记录"));
        assertEquals("expert", reply.senderType());
        assertEquals(1, conversations.messages(3L, id, 0, 100).items().size());
        assertEquals(2, conversations.messages(2L, id, 0, 100).items().size());
        assertThrows(ConversationProblem.class, () -> support.send(3L, request, message("retired", "离任写入")));
    }

    @Test
    void eventInsertFailureRollsBackAnEntireClaim() {
        String request = handoff(conversation());
        jdbc.execute("CREATE TRIGGER b1_test_event_failure BEFORE INSERT ON ai_conversation_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated test event failure'");
        try { assertThrows(RuntimeException.class, () -> support.claim(3L, request)); }
        finally { jdbc.execute("DROP TRIGGER b1_test_event_failure"); }
        assertEquals("queued", jdbc.queryForObject("SELECT status FROM support_request WHERE id=?", String.class, request));
        assertEquals(0, number("SELECT COUNT(*) FROM support_assignment WHERE request_id=?", request));
        support.claim(3L, request);
        assertEquals(1, number("SELECT COUNT(*) FROM support_assignment WHERE request_id=? AND released_at IS NULL", request));
    }
}
