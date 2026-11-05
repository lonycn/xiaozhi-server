package xiaozhi.modules.conversation;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import xiaozhi.modules.esp32integration.Esp32Policies;

/** One unique retained schema only; never touches or initializes the Dev database. */
@EnabledIfEnvironmentVariable(named="CONVERSATION_INDEX_TEST_JDBC_URL",matches=".+")
class ConversationIndexPersistenceTest {
    @Test void sourcesOwnershipDeviceFiltersNamespaceAndKeysetPaginationUseRealSql() {
        String url=System.getenv("CONVERSATION_INDEX_TEST_JDBC_URL");
        if(!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/xiaozhi_conversation_index_test_[a-z0-9_]+(\\?.*)?"))throw new IllegalArgumentException("Only a new retained index test schema is allowed");
        var db=new JdbcTemplate(new DriverManagerDataSource(url,"root",System.getenv("CONVERSATION_INDEX_TEST_DB_PASSWORD")));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class));
        db.execute("CREATE TABLE ai_agent(id VARCHAR(32),user_id BIGINT,agent_name VARCHAR(80)) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        db.execute("CREATE TABLE ai_device(id VARCHAR(32),user_id BIGINT,agent_id VARCHAR(32),mac_address VARCHAR(64)) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        db.execute("CREATE TABLE ai_conversation(id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin,owner_user_id BIGINT,agent_id VARCHAR(32),title VARCHAR(200),state VARCHAR(32),updated_at DATETIME(3),last_seq BIGINT) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        db.execute("CREATE TABLE esp32_device_session(id VARCHAR(32),device_id VARCHAR(32),conversation_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin)");
        db.execute("CREATE TABLE ai_agent_chat_history(session_id VARCHAR(64),agent_id VARCHAR(32),mac_address VARCHAR(64),created_at DATETIME(3)) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        db.execute("CREATE TABLE external_tenant_principal(user_id BIGINT)");
        db.execute("CREATE TABLE ai_conversation_event(conversation_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin,seq BIGINT,event_type VARCHAR(64),data_json LONGTEXT)");
        db.execute("CREATE TABLE ai_agent_chat_title(session_id VARCHAR(64),title VARCHAR(200)) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        db.update("INSERT INTO ai_agent VALUES('agent-a',1,'Mine'),('agent-b',2,'Foreign')");
        db.update("INSERT INTO ai_device VALUES('device-a',1,'agent-a','mac-a')");
        String app="1".repeat(32),hardware="2".repeat(32),foreign="3".repeat(32),denied="4".repeat(32);
        for(var row:List.of(new Object[]{app,1,"app"},new Object[]{hardware,1,"hardware"},new Object[]{foreign,2,"foreign"},new Object[]{denied,1,"denied"}))db.update("INSERT INTO ai_conversation VALUES(?,?,NULL,?,'AI_ACTIVE','2026-11-02 12:00:00.123',1)",row);
        db.update("UPDATE ai_conversation SET agent_id='agent-a' WHERE id IN (?,?)",hardware,denied);
        db.update("INSERT INTO esp32_device_session VALUES('session-h','device-a',?),('session-denied','device-a',?)",hardware,denied);
        db.update("INSERT INTO ai_agent_chat_history VALUES(?,'agent-a','mac-a','2026-11-02 12:00:00.123'),(?,'agent-a','mac-a','2026-11-02 12:00:00.123'),(?,'agent-a','mac-b','2026-11-02 12:00:00.123'),('foreign','agent-b','secret-mac','2026-11-02 12:00:00.123')",app,app,app);
        db.update("INSERT INTO ai_agent_chat_title VALUES(?,'First'),(?,'Second')",app,app);
        db.update("INSERT INTO ai_conversation VALUES(?,1,NULL,'web','AI_ACTIVE','2026-11-02 12:00:00.123',1),(?,2,NULL,'external-web','AI_ACTIVE','2026-11-02 12:00:00.123',1)","5".repeat(32),"6".repeat(32));
        db.update("INSERT INTO external_tenant_principal VALUES(2)");
        db.update("INSERT INTO ai_conversation_event VALUES(?,1,'conversation.created',?), (?,1,'conversation.created',?), (?,1,'conversation.created',?)",app,new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(Map.of("source","app")).toString(),"6".repeat(32),new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(Map.of("source","web")).toString(),hardware,new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(Map.of("source","app")).toString());
        var policies=mock(Esp32Policies.class);when(policies.mayRead(hardware,1)).thenReturn(true);when(policies.mayRead(denied,1)).thenReturn(false);
        var service=new ConversationIndexService(db,policies);
        var all=service.list(1,Map.of(),true);assertEquals(4,all.items().size());assertTrue(all.items().stream().noneMatch(i->i.title()!=null&&i.title().equals("foreign")));
        assertEquals(4,all.items().stream().map(ConversationIndexService.Item::rowKey).distinct().count());
        assertEquals(2,all.items().stream().filter(i->i.id().equals(app)).count(),"Same native ID in separate ledgers retains separate row keys");
        assertEquals(1,service.list(1,Map.of("source","app"),true).items().size());
        assertEquals(1,service.list(1,Map.of("source","web"),true).items().size());
        assertEquals(1,service.list(2,Map.of("source","app"),false).items().size(),"Older external principals default to App");
        assertEquals(1,service.list(2,Map.of("source","web"),false).items().size(),"Explicit web metadata overrides an external identity default");
        var hw=service.list(1,Map.of("source","hardware"),true);assertEquals(2,hw.items().size());
        var filtered=service.list(1,Map.of("source","hardware","agent_id","agent-a","mac_address","mac-a"),true);
        assertEquals(2,filtered.items().size());assertEquals(2,filtered.items().stream().filter(i->i.kind().equals("device_history")).findFirst().orElseThrow().messageCount());
        assertEquals(3,all.items().stream().filter(i->i.kind().equals("device_history")).findFirst().orElseThrow().messageCount(),"Duplicate title rows cannot multiply message counts");
        assertTrue(service.list(1,Map.of("mac_address","secret-mac"),true).items().isEmpty());
        assertEquals(3,service.list(1,Map.of(),false).items().size(),"No legacy read permission means no legacy metadata");
        Set<String> keys=new HashSet<>();String before="";int pages=0;boolean more;
        do{var page=service.list(1,Map.of("limit","1","before",before),true);page.items().forEach(i->assertTrue(keys.add(i.rowKey())));assertNotEquals(before,page.nextCursor());before=page.nextCursor();more=page.hasMore();assertTrue(++pages<8);}while(more);
        assertEquals(4,keys.size());
        var options=service.options(1,true);assertFalse(options.toString().contains("Foreign"));assertFalse(options.toString().contains("secret-mac"));assertTrue(options.toString().contains("mac-b"));
        assertEquals(List.of(),service.options(1,false).get("agents"));
        for(var bad:List.of(Map.of("source","spoof"),Map.of("owner_user_id","2"),Map.of("before","broken"),Map.of("limit","0")))assertThrows(ConversationProblem.class,()->service.list(1,bad,true));
    }
}
