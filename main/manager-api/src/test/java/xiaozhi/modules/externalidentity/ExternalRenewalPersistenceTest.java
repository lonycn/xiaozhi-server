package xiaozhi.modules.externalidentity;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import liquibase.*;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import xiaozhi.modules.externalidentity.ExternalModels.*;

/** Only creates a new isolated schema. Never drops, truncates, or clears any schema. */
@EnabledIfEnvironmentVariable(named="RENEWAL_TEST_JDBC_URL",matches=".+")
class ExternalRenewalPersistenceTest {
    static final ObjectMapper JSON=JsonMapper.builder().addModule(new JavaTimeModule()).build();
    static JdbcTemplate db;static DriverManagerDataSource data;static DataSourceTransactionManager transactions;
    static final Instant BASE=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    static class MutableClock extends Clock {
        final AtomicReference<Instant> now=new AtomicReference<>(BASE);
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now.get();}
    }
    record Fixture(ExternalSettings settings,MutableClock clock,ExternalIdentityService identity,String subject,String source,Exchanged exchange,Principal principal,CountDownLatch tokenQuery){}
    @BeforeAll static void initialize()throws Exception {
        String url=System.getenv("RENEWAL_TEST_JDBC_URL");
        var match=java.util.regex.Pattern.compile("jdbc:mysql://127\\.0\\.0\\.1:34776/(xiaozhi_renewal_test_[a-z0-9_]+)(\\?.*)?").matcher(url);
        if(!match.matches())throw new IllegalArgumentException("Requires a new loopback xiaozhi_renewal_test_* schema on34776");
        String schema=match.group(1),password=System.getenv("EXTERNAL_TEST_DB_PASSWORD");
        var admin=new JdbcTemplate(new DriverManagerDataSource("jdbc:mysql://127.0.0.1:34776/?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true","root",password));
        if(admin.queryForObject("SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=?",Long.class,schema)!=0)throw new IllegalArgumentException("Refusing an existing renewal schema");
        admin.execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        data=new DriverManagerDataSource(url,"root",password);db=new JdbcTemplate(data);transactions=new DataSourceTransactionManager(data);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Long.class));
        db.execute("CREATE TABLE sys_user(id BIGINT PRIMARY KEY,username VARCHAR(50) UNIQUE,password VARCHAR(100),super_admin TINYINT,status TINYINT,create_date DATETIME(3))");
        db.execute("CREATE TABLE renewal_sentinel(id BIGINT PRIMARY KEY,value VARCHAR(80))");db.update("INSERT INTO renewal_sentinel VALUES(1,'retain-isolated-evidence')");
        for(int i=0;i<2;i++)try(var connection=data.getConnection();var migration=new Liquibase("db/changelog/external-identity.changelog.yaml",new ClassLoaderResourceAccessor(),new JdbcConnection(connection))){migration.update(new Contexts());}
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM DATABASECHANGELOG",Long.class));
    }
    static String random(){return UUID.randomUUID().toString();}
    static Fixture fixture(int tokenSeconds,Instant now)throws Exception {
        var settings=ExternalRenewalProtocolTest.settings();settings.setTokenSeconds(tokenSeconds);settings.setRenewalEnabled(true);
        var clock=new MutableClock();clock.now.set(now);
        var tokenQuery=new CountDownLatch(1);
        var observed=new JdbcTemplate(data){
            @Override public <T> List<T> query(String sql,org.springframework.jdbc.core.RowMapper<T> mapper,Object... args){
                if(sql.startsWith("SELECT * FROM external_access_token WHERE id=") && sql.endsWith("FOR UPDATE"))tokenQuery.countDown();
                return super.query(sql,mapper,args);
            }
        };
        var identity=new ExternalIdentityService(observed,JSON,transactions,settings,clock);String subject=random(),source=random();
        var raw=JSON.createObjectNode().put("external_subject",subject).put("source_session_id",source).put("source_expires_at",now.plusSeconds(3600).getEpochSecond()).put("display_name","renewal isolated fixture");
        raw.putObject("tenant").put("type","company").put("id","1");raw.set("scope",JSON.valueToTree(Map.of("project_ids",List.of("p"),"device_ids",List.of("d"))));
        byte[] bytes=JSON.writeValueAsBytes(raw);var signed=identity.verify(ExternalIdentityService.ISSUE,bytes,ExternalRenewalProtocolTest.headers(ExternalIdentityService.ISSUE,bytes,now));
        var exchanged=identity.exchange(identity.issue(signed,bytes).ticket());return new Fixture(settings,clock,identity,subject,source,exchanged,identity.authenticate(exchanged.token()),tokenQuery);
    }
    static byte[] renewal(Fixture f,int seconds,Map<String,Object> changes)throws Exception {
        Map<String,Object> value=new LinkedHashMap<>();value.put("external_subject",f.subject);value.put("source_session_id",f.source);
        value.put("token_id",Long.toString(f.principal.tokenId()));value.put("tenant",f.principal.tenant());value.put("scope",f.principal.scope());
        value.put("requested_lifetime_seconds",seconds);value.putAll(changes);return JSON.writeValueAsBytes(value);
    }
    static Map<String,Object> renew(Fixture f,int seconds,Map<String,Object> changes)throws Exception {
        byte[] raw=renewal(f,seconds,changes);return f.identity.renew(ExternalIdentityService.RENEW,raw,ExternalRenewalProtocolTest.headers(ExternalIdentityService.RENEW,raw,f.clock.instant()));
    }
    static void revoke(Fixture f)throws Exception {
        byte[] raw=JSON.writeValueAsBytes(Map.of("external_subject",f.subject,"source_session_id",f.source));
        f.identity.revoke(f.identity.verify(ExternalIdentityService.REVOKE,raw,ExternalRenewalProtocolTest.headers(ExternalIdentityService.REVOKE,raw,f.clock.instant())),raw);
    }
    static Instant tokenExpiry(Fixture f){return db.queryForObject("SELECT expires_at FROM external_access_token WHERE id=?",Timestamp.class,f.principal.tokenId()).toInstant();}
    static Instant sourceExpiry(Fixture f){return db.queryForObject("SELECT expires_at FROM external_source_session WHERE id=?",Timestamp.class,f.principal.sourceId()).toInstant();}
    @Test void renewalExtendsSameBearerWithinSourceAndNeverReplacesOrWidensIt()throws Exception {
        var f=fixture(300,BASE);Instant before=tokenExpiry(f),sourceBefore=sourceExpiry(f);long tokens=db.queryForObject("SELECT COUNT(*) FROM external_access_token",Long.class);
        f.clock.now.set(BASE.plusSeconds(240));var renewed=renew(f,300,Map.of());var current=f.identity.authenticate(f.exchange.token());
        assertEquals(f.principal.tokenId(),current.tokenId());assertEquals(f.principal.tokenHash(),current.tokenHash());
        assertEquals(f.principal.sourceId(),current.sourceId());assertEquals(f.principal.scope(),current.scope());assertEquals(f.principal.tenant(),current.tenant());
        assertTrue(current.expiresAt().isAfter(before));assertEquals(sourceBefore,sourceExpiry(f));assertEquals(tokens,db.queryForObject("SELECT COUNT(*) FROM external_access_token",Long.class));
        assertEquals(Long.toString(current.tokenId()),renewed.get("token_id"));assertFalse(JSON.writeValueAsString(renewed).contains(f.principal.tokenHash()));
        f.clock.now.set(BASE.plusSeconds(301));assertNotNull(f.identity.authenticate(f.exchange.token()),"Original bearer is usable after its old expiry");
        f.clock.now.set(sourceBefore.minusSeconds(10));db.update("UPDATE external_access_token SET expires_at=? WHERE id=?",Timestamp.from(sourceBefore.minusSeconds(1)),current.tokenId());
        renew(f,300,Map.of());assertEquals(sourceBefore,tokenExpiry(f));assertEquals(sourceBefore,sourceExpiry(f));
    }
    @Test void signedSourceMustReassertExactSubjectSessionTenantAndScope()throws Exception {
        var f=fixture(300,BASE);Instant before=tokenExpiry(f);
        for(var changes:List.of(Map.<String,Object>of("external_subject","other"),Map.<String,Object>of("source_session_id","other"),
                Map.<String,Object>of("token_id","999999999"),Map.<String,Object>of("tenant",Map.of("type","company","id","2")),
                Map.<String,Object>of("scope",Map.of("project_ids",List.of("p","forged"),"device_ids",List.of("d"))))) {
            assertThrows(ExternalProblem.class,()->renew(f,300,changes));assertEquals(before,tokenExpiry(f));
        }
        f.clock.now.set(BASE.plusSeconds(1));byte[] raw=renewal(f,300,Map.of());var proof=ExternalRenewalProtocolTest.headers(ExternalIdentityService.RENEW,raw,f.clock.instant());
        f.identity.renew(ExternalIdentityService.RENEW,raw,proof);assertEquals("NONCE_REPLAY",assertThrows(ExternalProblem.class,()->f.identity.renew(ExternalIdentityService.RENEW,raw,proof)).getMessage());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM renewal_sentinel WHERE value='retain-isolated-evidence'",Long.class));
    }
    @Test void expiredRevokedDisabledOrRemovedIssuerCannotRenew()throws Exception {
        var expired=fixture(30,BASE);expired.clock.now.set(tokenExpiry(expired));assertEquals("EXTERNAL_TOKEN_REVOKED_OR_EXPIRED",assertThrows(ExternalProblem.class,()->renew(expired,30,Map.of())).getMessage());
        var logout=fixture(300,BASE);logout.identity.logout(logout.principal);assertThrows(ExternalProblem.class,()->renew(logout,300,Map.of()));
        var revoked=fixture(300,BASE);revoke(revoked);assertEquals("SOURCE_SESSION_REVOKED",assertThrows(ExternalProblem.class,()->renew(revoked,300,Map.of())).getMessage());
        var disabled=fixture(300,BASE);db.update("UPDATE sys_user SET status=0 WHERE id=?",disabled.principal.userId());assertEquals("ACCOUNT_DISABLED",assertThrows(ExternalProblem.class,()->renew(disabled,300,Map.of())).getMessage());
        var privileged=fixture(300,BASE);db.update("UPDATE sys_user SET super_admin=1 WHERE id=?",privileged.principal.userId());assertThrows(ExternalProblem.class,()->renew(privileged,300,Map.of()));
        var source=fixture(300,BASE);source.clock.now.set(sourceExpiry(source));assertEquals("SOURCE_SESSION_EXPIRED",assertThrows(ExternalProblem.class,()->renew(source,300,Map.of())).getMessage());
        var removed=fixture(300,BASE);removed.settings.setKeysJson("{}");assertThrows(ExternalProblem.class,()->renew(removed,300,Map.of()));
    }
    @Test void renewalAndSourceRevocationRaceAlwaysEndsRevoked()throws Exception {
        for(int i=0;i<6;i++) {
            var f=fixture(300,BASE);f.clock.now.set(BASE.plusSeconds(200));var start=new CountDownLatch(1);
            try(var pool=Executors.newFixedThreadPool(2)) {
                var renewing=pool.submit(()->{start.await();try{return renew(f,300,Map.of());}catch(ExternalProblem denied){return denied;}});
                var revoking=pool.submit(()->{start.await();revoke(f);return true;});start.countDown();renewing.get(10,TimeUnit.SECONDS);assertTrue(revoking.get(10,TimeUnit.SECONDS));
            }
            assertThrows(ExternalProblem.class,()->f.identity.authenticate(f.exchange.token()));assertThrows(ExternalProblem.class,()->renew(f,300,Map.of()));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM external_access_token WHERE id=? AND revoked_at IS NOT NULL",Long.class,f.principal.tokenId()));
        }
    }
    static void awaitLockWait(Fixture fixture,Future<?> pending)throws Exception {
        assertTrue(fixture.tokenQuery.await(4,TimeUnit.SECONDS),"Renewal must reach the token FOR UPDATE query");
        assertThrows(TimeoutException.class,()->pending.get(150,TimeUnit.MILLISECONDS),"The real MySQL token row lock must prevent completion");
    }
    static void lockToken(Connection connection,Fixture f)throws Exception {
        connection.setAutoCommit(false);try(var statement=connection.prepareStatement("SELECT id FROM external_access_token WHERE id=? FOR UPDATE")){statement.setLong(1,f.principal.tokenId());try(var result=statement.executeQuery()){assertTrue(result.next());}}
    }
    @Test void expiryWhileWaitingForMysqlLockCannotBeRevived()throws Exception {
        var f=fixture(30,BASE);Instant original=tokenExpiry(f);f.clock.now.set(original.minusSeconds(1));byte[] raw=renewal(f,30,Map.of());var proof=ExternalRenewalProtocolTest.headers(ExternalIdentityService.RENEW,raw,f.clock.instant());
        try(var blocker=data.getConnection();var pool=Executors.newSingleThreadExecutor()) {
            lockToken(blocker,f);var renewing=pool.submit(()->{try{return f.identity.renew(ExternalIdentityService.RENEW,raw,proof);}catch(ExternalProblem denied){return denied;}});
            try{awaitLockWait(f,renewing);f.clock.now.set(original);}finally{blocker.commit();}Object result=renewing.get(10,TimeUnit.SECONDS);
            assertInstanceOf(ExternalProblem.class,result);assertEquals("EXTERNAL_TOKEN_REVOKED_OR_EXPIRED",((ExternalProblem)result).getMessage());
        }
        assertEquals(original,tokenExpiry(f));
    }
    @Test void rotatingSigningKeyWhileRenewalWaitsInvalidatesOldProof()throws Exception {
        var f=fixture(300,BASE);Instant original=tokenExpiry(f);byte[] raw=renewal(f,300,Map.of());var proof=ExternalRenewalProtocolTest.headers(ExternalIdentityService.RENEW,raw,f.clock.instant());
        try(var blocker=data.getConnection();var pool=Executors.newSingleThreadExecutor()) {
            lockToken(blocker,f);var renewing=pool.submit(()->{try{return f.identity.renew(ExternalIdentityService.RENEW,raw,proof);}catch(ExternalProblem denied){return denied;}});
            try{awaitLockWait(f,renewing);byte[] replacement=new byte[32];Arrays.fill(replacement,(byte)73);f.settings.setKeysJson(ExternalRenewalProtocolTest.keys(replacement));}finally{blocker.commit();}
            Object result=renewing.get(10,TimeUnit.SECONDS);assertInstanceOf(ExternalProblem.class,result);assertEquals("SIGNATURE_INVALID",((ExternalProblem)result).getMessage());
        }
        assertEquals(original,tokenExpiry(f));
    }
    @Test void dynamicMediaAuthorityUsesRenewedSameIdAndRejectsDifferentBearerTakeover()throws Exception {
        var f=fixture(300,BASE.minusSeconds(500));
        var authority=new ExternalMediaAuthority(db,f.settings,new MockEnvironment().withProperty("features.external-realtime-media-enabled","true"),transactions);
        assertThrows(ExternalProblem.class,()->authority.validate(f.principal.sourceId(),f.principal.tokenId(),f.principal.userId()),"Fixture's original token expiry is past real wall time");
        f.clock.now.set(BASE.minusSeconds(201));renew(f,300,Map.of());
        assertTrue(authority.validate(f.principal.sourceId(),f.principal.tokenId(),f.principal.userId()).isAfter(Instant.now()));
        var session=new HashMap<String,Object>();session.put("external_source_id",f.principal.sourceId());session.put("external_token_id",f.principal.tokenId());session.put("customer_user_id",f.principal.userId());
        var request=new MockHttpServletRequest();request.setAttribute(ExternalIdentityService.ATTRIBUTE,f.principal);RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            authority.assertCustomer(session,f.principal.userId(),"customer_user_id");
            // A different access token for this same account and source cannot replace the bound token.
            var other=new Principal(f.principal.tokenId()+100000,f.principal.tokenHash(),f.principal.sourceId(),f.principal.userId(),f.principal.issuer(),f.principal.subject(),f.principal.tenant(),f.principal.scope(),BASE.plusSeconds(300));
            request.setAttribute(ExternalIdentityService.ATTRIBUTE,other);assertThrows(ExternalProblem.class,()->authority.assertCustomer(session,f.principal.userId(),"customer_user_id"));
        }finally{RequestContextHolder.resetRequestAttributes();}
    }
}
