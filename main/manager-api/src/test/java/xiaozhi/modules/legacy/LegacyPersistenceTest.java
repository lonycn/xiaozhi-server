package xiaozhi.modules.legacy;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.integration.spring.SpringResourceAccessor;
import org.springframework.core.io.DefaultResourceLoader;

/** Synthetic inserts only; database must be a separately created empty test schema. */
@EnabledIfEnvironmentVariable(named = "LEGACY_TEST_JDBC_URL", matches = ".+")
class LegacyPersistenceTest {
    private static JdbcTemplate jdbc;
    private static LegacyService service;
    private static long owner, other, disabled;
    private static String agent, otherAgent, disabledAgent, session1, session2, foreignSession;

    @BeforeAll
    static void prepare() throws Exception {
        String url = System.getenv("LEGACY_TEST_JDBC_URL");
        if (!url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/xiaozhi_legacy_test_[a-z0-9_]+(?:\\?[^#]*)?"))
            throw new IllegalArgumentException("Requires a loopback xiaozhi_legacy_test_* database");
        var source = new DriverManagerDataSource(url, System.getenv().getOrDefault("LEGACY_TEST_DB_USER", "root"),
                System.getenv("LEGACY_TEST_DB_PASSWORD"));
        jdbc = new JdbcTemplate(source);
        String schema = jdbc.queryForObject("SELECT DATABASE()", String.class);
        String expected = url.substring(url.indexOf('/', "jdbc:mysql://".length()) + 1).split("\\?", 2)[0];
        if (!expected.equals(schema) || !schema.matches("xiaozhi_legacy_test_[a-z0-9_]+"))
            throw new IllegalArgumentException("Unexpected database schema");
        // The master changelog includes historical destructive changes: only an empty,
        // explicitly named isolated schema may ever reach migration.
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()", Integer.class);
        if (existing == null || existing != 0) throw new IllegalStateException("Legacy test requires a new empty database");
        try (var connection = source.getConnection(); var migration = new Liquibase(
                "db/changelog/db.changelog-master.yaml", new SpringResourceAccessor(new DefaultResourceLoader()), new JdbcConnection(connection))) {
            migration.update(new Contexts());
            Long firstCount = jdbc.queryForObject("SELECT COUNT(*) FROM DATABASECHANGELOG", Long.class);
            assertNotNull(firstCount);
            assertTrue(firstCount > 0);
            migration.update(new Contexts());
            assertEquals(firstCount, jdbc.queryForObject("SELECT COUNT(*) FROM DATABASECHANGELOG", Long.class));
        }
        // Confirm the complete schema before writing any fixtures.
        Integer tables = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
                AND LOWER(table_name) IN ('databasechangelog', 'sys_user', 'ai_agent', 'ai_agent_chat_history')
                """, Integer.class);
        if (tables == null || tables != 4) throw new IllegalStateException("Full Liquibase migration required");
        String contentType = jdbc.queryForObject("""
                SELECT DATA_TYPE FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'ai_agent_chat_history' AND column_name = 'content'
                """, String.class);
        if (!"text".equalsIgnoreCase(contentType)) throw new IllegalStateException("History content migration required");
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        owner = positiveId(); other = positiveId(); disabled = positiveId();
        agent = "la" + suffix; otherAgent = "lb" + suffix; disabledAgent = "lc" + suffix;
        session1 = "s1" + suffix; session2 = "s2" + suffix; foreignSession = "s3" + suffix;
        jdbc.update("INSERT INTO sys_user (id, username, status, super_admin) VALUES (?, ?, 1, 0), (?, ?, 1, 1), (?, ?, 0, 0)",
                owner, "lo" + suffix, other, "lx" + suffix, disabled, "ld" + suffix);
        jdbc.update("INSERT INTO ai_agent (id, user_id) VALUES (?, ?), (?, ?), (?, ?)", agent, owner, otherAgent, other, disabledAgent, disabled);
        jdbc.update("INSERT INTO ai_agent_chat_history (agent_id, session_id, chat_type, content, audio_id) VALUES (?, ?, 1, ?, ?)", agent, session1, "压缩机有异响", "audio-test");
        jdbc.update("INSERT INTO ai_agent_chat_history (agent_id, session_id, chat_type, content) VALUES (?, ?, 2, ?)", agent, session1, "请检查设备");
        jdbc.update("INSERT INTO ai_agent_chat_history (agent_id, session_id, chat_type, content) VALUES (?, ?, 7, ?)", agent, session1, "保留未知角色");
        jdbc.update("INSERT INTO ai_agent_chat_history (agent_id, session_id, chat_type, content) VALUES (?, ?, 1, ?)", agent, session2, "第二会话");
        jdbc.update("INSERT INTO ai_agent_chat_history (agent_id, session_id, chat_type, content) VALUES (?, ?, 1, ?)", otherAgent, foreignSession, "其他用户");
        service = new LegacyService(jdbc);
    }
    private static long positiveId() { return (UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE) | 1; }

    @Test
    void stablePagesPreserveSourceTextRolesAndDoNotMutateHistory() {
        var first = service.sessions(owner, agent, "0", 1);
        var second = service.sessions(owner, agent, first.nextCursor(), 1);
        assertTrue(first.hasMore()); assertFalse(second.hasMore());
        assertEquals(List.of(session2, session1), List.of(first.items().getFirst().sessionId(), second.items().getFirst().sessionId()));
        var messages = new ArrayList<LegacyModels.Message>();
        String cursor = "0"; boolean more;
        do {
            var page = service.messages(owner, agent, session1, cursor, 1);
            messages.addAll(page.items()); cursor = page.nextCursor(); more = page.hasMore();
        } while (more);
        assertEquals(3, messages.size());
        assertEquals(3, messages.stream().map(LegacyModels.Message::sourceId).distinct().count());
        assertEquals(List.of("user", "assistant", "unknown"), messages.stream().map(LegacyModels.Message::senderType).toList());
        assertEquals(List.of("压缩机有异响", "请检查设备", "保留未知角色"), messages.stream().map(m -> m.parts().getFirst().text()).toList());
        assertTrue(messages.stream().allMatch(LegacyModels.Message::readOnly));
        assertTrue(messages.getFirst().hasAudio());
        assertEquals("压缩机有异响", jdbc.queryForObject("SELECT content FROM ai_agent_chat_history WHERE id = ?", String.class, Long.parseLong(messages.getFirst().sourceId())));
        assertEquals(3L, jdbc.queryForObject("SELECT COUNT(*) FROM ai_agent_chat_history WHERE agent_id = ? AND session_id = ?", Long.class, agent, session1));
        assertTrue(service.messages(owner, agent, session1, cursor, 1).items().isEmpty());
    }

    @Test
    void ownershipAndSessionPairAreEnforcedEvenForSuperAdmin() {
        assertEquals(404, assertThrows(LegacyProblem.class, () -> service.sessions(other, agent, "0", 10)).status());
        assertEquals(404, assertThrows(LegacyProblem.class, () -> service.sessions(disabled, disabledAgent, "0", 10)).status());
        assertEquals(404, assertThrows(LegacyProblem.class, () -> service.messages(owner, agent, foreignSession, "0", 10)).status());
        assertEquals(404, assertThrows(LegacyProblem.class, () -> service.messages(owner, "missing", session1, "0", 10)).status());
    }
}
