package xiaozhi.modules.support;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

/** Retained fresh schema proves upgrade behavior on actual legacy assignment rows. */
@EnabledIfEnvironmentVariable(named="SUPPORT_MIGRATION_TEST_JDBC_URL",matches=".+")
class SupportServiceTypesMigrationTest {
    @Test void migrationPreservesAssignmentsAndDerivesOnlyLegacyGrantedRoles() throws Exception {
        String url=System.getenv("SUPPORT_MIGRATION_TEST_JDBC_URL");
        if(!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/xiaozhi_support_migration_test_[a-z0-9_]+(\\?.*)?"))
            throw new IllegalArgumentException("A fresh isolated migration schema is required");
        var source=new DriverManagerDataSource(url,"root",System.getenv("SUPPORT_MIGRATION_TEST_DB_PASSWORD"));
        var jdbc=new JdbcTemplate(source);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Long.class));
        jdbc.execute("CREATE TABLE ai_agent(id VARCHAR(32) PRIMARY KEY,user_id BIGINT)");
        jdbc.execute("CREATE TABLE sys_user(id BIGINT PRIMARY KEY,status TINYINT NOT NULL)");
        try(var connection=source.getConnection();var migration=new Liquibase("db/changelog/conversation.changelog.yaml",new ClassLoaderResourceAccessor(),new JdbcConnection(connection))) {
            migration.update(new Contexts());
        }
        try(var connection=source.getConnection()) { ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/changelog/202610301200.sql")); }
        jdbc.update("INSERT INTO support_agent_profile(user_id,role) VALUES(3,'support_agent'),(4,'expert')");
        String conv="1".repeat(32),queued="2".repeat(32),assigned="3".repeat(32),waiting="4".repeat(32);
        for(String id:List.of(conv,queued,waiting))jdbc.update("INSERT INTO ai_conversation(id,owner_user_id,title) VALUES(?,2,'legacy')",id);
        jdbc.update("INSERT INTO support_request(id,conversation_id,client_request_id,request_hash,status,assigned_agent_user_id) VALUES(?,?,'assigned',?,'active',4)",assigned,conv,"a".repeat(64));
        jdbc.update("INSERT INTO support_assignment(id,request_id,conversation_id,agent_user_id) VALUES(?,?,?,4)","5".repeat(32),assigned,conv);
        jdbc.update("INSERT INTO support_request(id,conversation_id,client_request_id,request_hash,target_agent_user_id) VALUES(?,?,'directed',?,4)",queued,queued,"b".repeat(64));
        jdbc.update("INSERT INTO support_request(id,conversation_id,client_request_id,request_hash) VALUES(?,?,'undirected',?)",waiting,waiting,"c".repeat(64));
        for(int repeat=0;repeat<2;repeat++)try(var connection=source.getConnection();var migration=new Liquibase("db/changelog/support-service-types.changelog.yaml",new ClassLoaderResourceAccessor(),new JdbcConnection(connection))) {
            migration.update(new Contexts());
        }
        assertEquals("expert",jdbc.queryForObject("SELECT service_type FROM support_request WHERE id=?",String.class,assigned));
        assertEquals("expert",jdbc.queryForObject("SELECT service_type FROM support_request WHERE id=?",String.class,queued));
        assertEquals("support_agent",jdbc.queryForObject("SELECT service_type FROM support_request WHERE id=?",String.class,waiting));
        assertEquals("expert",jdbc.queryForObject("SELECT service_type FROM support_assignment",String.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM support_assignment WHERE agent_user_id=4 AND released_at IS NULL",Long.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM support_agent_profile WHERE JSON_LENGTH(roles_json)<>1",Long.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM support_agent_profile WHERE user_id=4 AND JSON_CONTAINS(roles_json,JSON_QUOTE('expert'))",Long.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM support_agent_profile WHERE user_id=4 AND JSON_CONTAINS(roles_json,JSON_QUOTE('support_agent'))",Long.class));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM support_request",Long.class));
        jdbc.update("UPDATE support_agent_profile SET accepting=1,presence_expires_at=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 45 SECOND),roles_json=JSON_ARRAY('support_agent','expert')");
        for(int repeat=0;repeat<2;repeat++)try(var connection=source.getConnection();var migration=new Liquibase("db/changelog/support-service-presence.changelog.yaml",new ClassLoaderResourceAccessor(),new JdbcConnection(connection))) {
            migration.update(new Contexts());
        }
        assertEquals(1,jdbc.queryForObject("SELECT accepting FROM support_agent_profile WHERE user_id=3",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT expert_accepting FROM support_agent_profile WHERE user_id=3",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT accepting FROM support_agent_profile WHERE user_id=4",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT expert_accepting FROM support_agent_profile WHERE user_id=4",Integer.class));
        assertNotNull(jdbc.queryForObject("SELECT expert_presence_expires_at FROM support_agent_profile WHERE user_id=4",java.sql.Timestamp.class));
        assertNull(jdbc.queryForObject("SELECT presence_expires_at FROM support_agent_profile WHERE user_id=4",java.sql.Timestamp.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM support_assignment WHERE agent_user_id=4 AND released_at IS NULL",Long.class));
    }
}
