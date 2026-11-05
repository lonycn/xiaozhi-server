package xiaozhi.modules.esp32gateway;

import liquibase.*;
import liquibase.change.core.RawSQLChange;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Simulates the observed deployed schema/history mismatch without touching any existing schema. */
@EnabledIfEnvironmentVariable(named="ESP32_GATEWAY_UPGRADE_TEST_JDBC_URL",matches=".+")
class Esp32GatewayUpgradeTest {
    static final String PATH="db/changelog/esp32-gateway.changelog.yaml";
    static final String ORIGINAL="8:d3e073f499bdd28deb94425a3b9eeb98";
    @Test void threeColumnLegacySchemaUpgradesWithoutChangingExistingRowsOrChecksum()throws Exception {
        String url=System.getenv("ESP32_GATEWAY_UPGRADE_TEST_JDBC_URL");
        if(!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/xiaozhi_esp32_gateway_upgrade_test_[a-z0-9_]+(\\?.*)?"))throw new IllegalArgumentException("Fresh isolated loopback upgrade schema required");
        var source=new DriverManagerDataSource(url,"root",System.getenv("ESP32_GATEWAY_UPGRADE_TEST_DB_PASSWORD"));var db=new JdbcTemplate(source);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class));
        db.execute("CREATE TABLE sys_user(id BIGINT PRIMARY KEY,username VARCHAR(40),status INT,update_date DATETIME(6)) ENGINE=InnoDB");
        db.execute("CREATE TABLE ai_agent(id VARCHAR(32) PRIMARY KEY,user_id BIGINT,updated_at DATETIME(6)) ENGINE=InnoDB");
        db.execute("CREATE TABLE ai_device(id VARCHAR(32) PRIMARY KEY,user_id BIGINT,agent_id VARCHAR(32),mac_address VARCHAR(50),update_date DATETIME(6)) ENGINE=InnoDB");
        db.update("INSERT INTO sys_user VALUES(7,'preserved-owner',1,CURRENT_TIMESTAMP(6))");db.update("INSERT INTO ai_agent VALUES('agent',7,CURRENT_TIMESTAMP(6))");db.update("INSERT INTO ai_device VALUES('device',7,'agent','mac-device',CURRENT_TIMESTAMP(6))");
        try(var c=source.getConnection();var accessor=new ClassLoaderResourceAccessor()) {
            var database=DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
            try(var full=new Liquibase(PATH,accessor,database)) {
                var first=full.getDatabaseChangeLog().getChangeSets().getFirst();assertEquals(ORIGINAL,first.generateCheckSum().toString());
                // Actual deployed tables have only the three legacy authorization columns, while
                // the recorded frozen checksum and six trigger definitions are the later +1 form.
                for(var change:first.getChanges()) {
                    String sql=((RawSQLChange)change).getSql();
                    if(sql.contains("CREATE TABLE")&&sql.contains("esp32_authorization")) {
                        sql=sql.replaceAll("(?m)^\\s*credential_fingerprint VARCHAR\\(64\\) NULL,\\r?\\n","")
                               .replaceAll("(?m)^\\s*token_not_before BIGINT NOT NULL DEFAULT 0,\\r?\\n","");
                        for(String statement:sql.split(";"))if(!statement.isBlank())db.execute(statement);
                    } else db.execute(sql);
                }
                var historical=new DatabaseChangeLog(full.getDatabaseChangeLog().getFilePath());historical.addChangeSet(first);
                // The outer Liquibase owns this shared database connection and closes it once.
                new Liquibase(historical,accessor,database).changeLogSync(new Contexts());
            }
        }
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='esp32_authorization'",Integer.class));
        assertEquals(6,db.queryForObject("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND action_statement LIKE '%UNIX_TIMESTAMP()+1%'",Integer.class));
        db.update("INSERT INTO esp32_authorization(device_id,fingerprint,revision) VALUES('device','legacy-fingerprint',7)");
        db.update("INSERT INTO esp32_connection VALUES('device','legacy-connection',9,'legacy-gateway','legacy-binding',9999999999999,'online','[]')");
        db.update("INSERT INTO esp32_command(id,device_id,owner_id,agent_id,binding_version,connection_id,generation,gateway_id,idempotency_key,fingerprint,tool,arguments,state,result_json,deadline,created_at) VALUES('legacy-command','device',7,'agent','legacy-binding','legacy-connection',9,'legacy-gateway','legacy-key','legacy-hash','self.get_device_status','{}','succeeded','{\"volume\":20}',9999999999999,12345)");
        db.update("INSERT INTO esp32_gateway_audit VALUES('legacy-audit','device','legacy-command','succeeded',12345)");
        List<String> tables=List.of("sys_user","ai_agent","ai_device","esp32_connection","esp32_command","esp32_gateway_audit");
        Map<String,List<Map<String,Object>>> before=new LinkedHashMap<>();for(String table:tables)before.put(table,db.queryForList("SELECT * FROM "+table));
        var originalHistory=db.queryForMap("SELECT * FROM DATABASECHANGELOG WHERE ID='202611020700-esp32-gateway'");
        long clock=db.queryForObject("SELECT UNIX_TIMESTAMP()",Long.class);
        try(var c=source.getConnection();var migration=new Liquibase(PATH,new ClassLoaderResourceAccessor(),new JdbcConnection(c))) {
            var pending=migration.listUnrunChangeSets(new Contexts(),new LabelExpression());
            assertEquals(List.of("202611021100-esp32-gateway-token-cutoff-column","202611021101-esp32-gateway-credential-fingerprint-column","202611021102-esp32-gateway-strict-revocation"),pending.stream().map(change->change.getId()).toList());
            migration.update(new Contexts());migration.update(new Contexts());assertTrue(migration.listUnrunChangeSets(new Contexts(),new LabelExpression()).isEmpty());
        }
        for(String table:tables)assertEquals(before.get(table),db.queryForList("SELECT * FROM "+table),table+" rows must remain identical");
        assertEquals(originalHistory,db.queryForMap("SELECT * FROM DATABASECHANGELOG WHERE ID='202611020700-esp32-gateway'"));
        assertEquals(4,db.queryForObject("SELECT COUNT(*) FROM DATABASECHANGELOG",Integer.class));
        var auth=db.queryForMap("SELECT * FROM esp32_authorization WHERE device_id='device'");assertEquals("legacy-fingerprint",auth.get("fingerprint"));assertEquals(7L,((Number)auth.get("revision")).longValue());assertNull(auth.get("credential_fingerprint"));assertTrue(((Number)auth.get("token_not_before")).longValue()>=clock+31);
        assertEquals(6,db.queryForObject("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name LIKE '%_v2' AND action_statement LIKE '%UNIX_TIMESTAMP()+31%'",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name NOT LIKE '%_v2'",Integer.class));
        db.update("UPDATE sys_user SET status=0 WHERE id=7");assertEquals(8L,db.queryForObject("SELECT revision FROM esp32_authorization WHERE device_id='device'",Long.class));
        db.update("INSERT INTO esp32_authorization(device_id,fingerprint,revision) VALUES('fresh','initial',1)");assertEquals(0L,db.queryForObject("SELECT token_not_before FROM esp32_authorization WHERE device_id='fresh'",Long.class));
    }
}
