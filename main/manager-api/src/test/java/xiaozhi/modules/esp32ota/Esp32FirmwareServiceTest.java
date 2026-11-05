package xiaozhi.modules.esp32ota;

import com.fasterxml.jackson.databind.ObjectMapper;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import xiaozhi.modules.device.dto.DeviceReportReqDTO;
import xiaozhi.modules.sys.service.SysParamsService;
import xiaozhi.modules.esp32gateway.Esp32GatewayService;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Synthetic image headers and real isolated MySQL; not firmware/device validation. */
@EnabledIfEnvironmentVariable(named = "ESP32_FIRMWARE_TEST_JDBC_URL", matches = ".+")
class Esp32FirmwareServiceTest {
    static final String SECRET = "isolated-device-hmac-secret-32bytes";
    static final String BOARD = "bread-compact-wifi", VARIANT = "bread-compact-wifi-128x32-ai-release";
    static JdbcTemplate db;
    static TransactionTemplate tx;
    static Esp32FirmwareService service;
    static SysParamsService params;
    @TempDir static Path artifacts;

    @BeforeAll static void setup() throws Exception {
        String url = System.getenv("ESP32_FIRMWARE_TEST_JDBC_URL");
        if (!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/xiaozhi_esp32_firmware_test_[a-z0-9_]+(\\?.*)?"))
            throw new IllegalArgumentException("Fresh isolated loopback firmware schema required");
        var source = new DriverManagerDataSource(url, "root", System.getenv("ESP32_FIRMWARE_TEST_DB_PASSWORD"));
        db = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()", Integer.class), "Never reuse a schema");
        db.execute("CREATE TABLE sys_user(id BIGINT PRIMARY KEY,status INT,update_date DATETIME(6)) ENGINE=InnoDB");
        db.execute("CREATE TABLE ai_agent(id VARCHAR(32) PRIMARY KEY,user_id BIGINT,updated_at DATETIME(6)) ENGINE=InnoDB");
        db.execute("CREATE TABLE ai_device(id VARCHAR(32) PRIMARY KEY,user_id BIGINT,agent_id VARCHAR(32),mac_address VARCHAR(50),app_version VARCHAR(32),update_date DATETIME(6)) ENGINE=InnoDB");
        for (String path : List.of("esp32-gateway", "esp32-firmware")) {
            try (var connection = source.getConnection(); var migration = new Liquibase("db/changelog/" + path + ".changelog.yaml", new ClassLoaderResourceAccessor(), new JdbcConnection(connection))) {
                migration.update(new Contexts()); migration.update(new Contexts());
            }
        }
        params = mock(SysParamsService.class); when(params.getValue("server.secret", false)).thenReturn(SECRET);
        var env = new MockEnvironment().withProperty("esp32.ota.directory", artifacts.toString())
                .withProperty("esp32.ota.public-base-url", "https://firmware.example.invalid")
                .withProperty("esp32.ota.download-key", "isolated-download-key-at-least-32-bytes");
        service = new Esp32FirmwareService(db, params, new ObjectMapper(), env);
    }

    static <T> T run(Supplier<T> action) { return tx.execute(status -> action.get()); }
    static String next() { return UUID.randomUUID().toString().replace("-", ""); }
    static String device() {
        String id = next(); long owner = Math.abs(UUID.randomUUID().getMostSignificantBits() >>> 2) + 1;
        db.update("INSERT INTO sys_user(id,status) VALUES(?,1)", owner);
        db.update("INSERT INTO ai_agent(id,user_id) VALUES(?,?)", id, owner);
        db.update("INSERT INTO ai_device(id,user_id,agent_id,mac_address) VALUES(?,?,?,?)", id, owner, id, "mac-" + id);
        db.update("INSERT INTO esp32_authorization(device_id,fingerprint,revision) VALUES(?,'fixture',1)", id);
        return id;
    }
    static byte[] app(String version) {
        byte[] image = new byte[512]; image[0] = (byte)0xe9; image[12] = 9;
        image[32] = 0x32; image[33] = 0x54; image[34] = (byte)0xcd; image[35] = (byte)0xab;
        System.arraycopy(version.getBytes(StandardCharsets.UTF_8), 0, image, 48, version.length());
        System.arraycopy("xiaozhi".getBytes(StandardCharsets.UTF_8), 0, image, 80, 7);
        return image;
    }
    static Esp32FirmwareService.Artifact upload(String type, byte[] bytes) throws Exception {
        return service.upload(type, new ByteArrayInputStream(bytes));
    }
    static Esp32FirmwareService.ReleaseRequest request(String key, String version, Esp32FirmwareService.Artifact app, Esp32FirmwareService.Artifact assets) {
        return new Esp32FirmwareService.ReleaseRequest(key,version,BOARD,VARIANT,"esp32s3",16*1024*1024,app,assets,"1.0.0","a".repeat(40),"v6.0.1");
    }
    static Map<String,Object> create(Esp32FirmwareService.ReleaseRequest request) {
        return run(() -> { try { return service.create(1, request); } catch (java.io.IOException e) { throw new RuntimeException(e); } });
    }
    static String release(String version) throws Exception {
        var r=request(next(),version,upload("app", app(version)),upload("assets",new byte[]{1,2,3}));
        String id=(String)create(r).get("id"); run(() -> { try { return service.state(id,"published"); } catch (java.io.IOException e) { throw new RuntimeException(e); } }); return id;
    }
    static String assign(String release, String device) {
        return (String)run(() -> service.assign(1,release,new Esp32FirmwareService.Assignment(device,"automatic",false,600))).get("id");
    }
    static DeviceReportReqDTO report(String name, String current, int flash) {
        var r=new DeviceReportReqDTO(); var board=new DeviceReportReqDTO.BoardInfo(); board.setType(BOARD);board.setName(name);r.setBoard(board);
        var app=new DeviceReportReqDTO.Application();app.setVersion(current);r.setApplication(app);r.setFlashSize(flash);return r;
    }
    static Esp32FirmwareService.Manifest offer(String device, String name, String current, int flash) throws Exception {
        return (Esp32FirmwareService.Manifest)service.manifest("mac-"+device,"client",report(name,current,flash));
    }
    static String ticket(Esp32FirmwareService.Manifest manifest) { return manifest.getUrl().split("\\?ticket=",2)[1]; }
    static String token(String device) throws Exception {
        long timestamp=System.currentTimeMillis()/1000; String content="client|mac-"+device+"|"+timestamp;
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return "Bearer "+Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)))+"."+timestamp;
    }
    static void receipt(String device,String release,String event,String state,String version,String authorization) {
        run(() -> {service.receipt("mac-"+device,"client",authorization,new Esp32FirmwareService.Receipt(release,event,state,version));return null;});
    }

    @Test void usbBootstrapKeepsOrderedDeviceHealthAndImmutableOperatorEvidence() throws Exception {
        var env=new MockEnvironment().withProperty("esp32.ota.directory",artifacts.toString())
                .withProperty("esp32.ota.public-base-url","http://192.168.3.152:19183/xiaozhi")
                .withProperty("esp32.ota.allow-lan-http","true").withProperty("esp32.ota.usb-bootstrap-enabled","true")
                .withProperty("esp32.ota.download-key","isolated-download-key-at-least-32-bytes");
        var maintenance=new Esp32FirmwareService(db,params,new ObjectMapper(),env);
        var app=upload("app",app("9.31.1"));var assets=upload("assets",new byte[]{9,31,1});
        var request=new Esp32FirmwareService.ReleaseRequest(next(),"9.31.1",BOARD,"bread-compact-wifi-128x32-ai-lan","esp32s3",16777216,app,assets,"9.31.1","a".repeat(40),"v6.0.1");
        String release=(String)run(()->{try{return maintenance.create(1,request);}catch(Exception e){throw new RuntimeException(e);}}).get("id");
        run(()->{try{return maintenance.state(release,"published");}catch(Exception e){throw new RuntimeException(e);}});
        String device=device(),assignment=assign(release,device);
        var proof=new Esp32FirmwareService.UsbBootstrap("2.5.2","74:4d:bd:a2:c4:2c","mac-"+device,1,"1".repeat(64),
                "4811619cacae08ef2e0e71b7220c6033a346ca5da7ca179082408c963ef530b5","2".repeat(64),"3".repeat(64),"4".repeat(64),true,true,app,assets);
        assertThrows(ResponseStatusException.class,()->service.usbBootstrap(1,assignment,proof));
        var result=run(()->{try{return maintenance.usbBootstrap(1,assignment,proof);}catch(java.io.IOException e){throw new RuntimeException(e);}});
        assertEquals("verified",result.get("state"));
        assertEquals(result,run(()->{try{return maintenance.usbBootstrap(1,assignment,proof);}catch(java.io.IOException e){throw new RuntimeException(e);}}));
        assertEquals(2,maintenance.events(assignment).size());
        offer(device,"bread-compact-wifi-128x32-ai-lan","9.31.1",16777216);
        assertEquals("verified",db.queryForObject("SELECT last_state FROM esp32_firmware_assignment WHERE id=?",String.class,assignment));
        assertThrows(ResponseStatusException.class,()->receipt(device,release,next(),"healthy","9.31.1",token(device)));
        receipt(device,release,next(),"booted","9.31.1",token(device));
        receipt(device,release,next(),"healthy","9.31.1",token(device));
        assertEquals("healthy",maintenance.usbBootstrap(1,assignment,proof).get("state"));
        var different=new Esp32FirmwareService.UsbBootstrap("2.5.2",proof.factoryMac(),proof.softwareMac(),1,"5".repeat(64),proof.partitionSha256(),proof.originalAppSha256(),proof.nvsBeforeSha256(),proof.nvsAfterSha256(),true,true,app,assets);
        assertThrows(ResponseStatusException.class,()->maintenance.usbBootstrap(1,assignment,different));
    }

    @Test void usbBootstrapRejectsUnpreservedOrWrongReadbackBeforeRecording() throws Exception {
        var env=new MockEnvironment().withProperty("esp32.ota.directory",artifacts.toString())
                .withProperty("esp32.ota.public-base-url","http://127.0.0.1:19183/xiaozhi")
                .withProperty("esp32.ota.allow-loopback-http","true").withProperty("esp32.ota.usb-bootstrap-enabled","true")
                .withProperty("esp32.ota.download-key","isolated-download-key-at-least-32-bytes");
        var maintenance=new Esp32FirmwareService(db,params,new ObjectMapper(),env);
        String device=device(),release=release("9.32.1"),assignment=assign(release,device);
        var proof=new Esp32FirmwareService.UsbBootstrap("2.5.2","74:4d:bd:a2:c4:2c","mac-"+device,1,"1".repeat(64),"2".repeat(64),"3".repeat(64),"4".repeat(64),"5".repeat(64),false,true,new Esp32FirmwareService.Artifact("6".repeat(64),512),new Esp32FirmwareService.Artifact("7".repeat(64),3));
        assertThrows(ResponseStatusException.class,()->maintenance.usbBootstrap(1,assignment,proof));
        assertTrue(maintenance.events(assignment).isEmpty());
    }

    @Test void existingMacAddressPrimaryKeyCanReceiveAssignmentWithoutRebinding() throws Exception {
        String original=device(),legacy="02:21:64:63:44:5a";
        db.update("UPDATE ai_device SET id=? WHERE id=?",legacy,original);
        db.update("UPDATE esp32_authorization SET device_id=? WHERE device_id=?",legacy,original);
        String assignment=assign(release("9.33.1"),legacy);
        assertEquals(assignment,service.assignments(legacy).getFirst().get("id"));
        assertThrows(ResponseStatusException.class,()->service.assignments("../untrusted"));
    }

    @Test void firstGatewayRegistrationCannotRevokeEarlierFirmwareAssignment() throws Exception {
        var gateway=new Esp32GatewayService(db,params,new ObjectMapper(),true);
        service.setGatewayAuthorization(gateway);
        try {
            String device=device(),release=release("9.34.1"),assignment=assign(release,device);
            String stamp=db.queryForObject("SELECT binding_stamp FROM esp32_firmware_assignment WHERE id=?",String.class,assignment);
            // The fixture fingerprint is intentionally stale, exactly as a never-connected legacy device can be.
            run(()->gateway.register(new Esp32GatewayService.Register(null,"mac-"+device,"client",uncheckedToken(device),"first-gateway",List.of("self.get_device_status"))));
            assertEquals(stamp,service.assignments(device).getFirst().get("binding_stamp"));
            assertNotNull(offer(device,VARIANT,"9.34.1",16777216));
            receipt(device,release,next(),"downloading","9.34.1",token(device));
            receipt(device,release,next(),"verified","9.34.1",token(device));
            receipt(device,release,next(),"booted","9.34.1",token(device));
            receipt(device,release,next(),"healthy","9.34.1",token(device));
        } finally {service.setGatewayAuthorization(null);}
    }
    static String uncheckedToken(String device){try{return token(device).substring(7);}catch(Exception e){throw new RuntimeException(e);}}

    @Test void immutableMultipleVersionsAndIdempotency() throws Exception {
        var a=upload("app",app("2.5.3"));var assets=upload("assets",new byte[]{7,8});var req=request(next(),"2.5.3",a,assets);
        var one=create(req); assertEquals(one.get("id"),create(req).get("id"));
        assertThrows(ResponseStatusException.class,()->create(request(req.idempotencyKey(),"2.5.4",a,assets)));
        assertThrows(ResponseStatusException.class,()->create(request(next(),"2.5.3",a,assets)));
        var newer=create(request(next(),"2.5.4",upload("app",app("2.5.4")),assets));
        assertNotEquals(one.get("id"),newer.get("id"));
        assertEquals("2.5.3",db.queryForObject("SELECT version FROM esp32_firmware_release WHERE id=?",String.class,one.get("id")));
    }

    @Test void validatesArtifactSizeDigestAndImageDescriptorVersion() throws Exception {
        var a=upload("app",app("2.5.3"));var assets=upload("assets",new byte[]{1});
        assertThrows(ResponseStatusException.class,()->create(request(next(),"2.5.4",a,assets)));
        assertThrows(ResponseStatusException.class,()->create(request(next(),"2.5.3",new Esp32FirmwareService.Artifact(a.sha256(),a.size()+1),assets)));
        assertThrows(ResponseStatusException.class,()->create(request(next(),"2.5.3",new Esp32FirmwareService.Artifact("0".repeat(64),a.size()),assets)));
        assertThrows(ResponseStatusException.class,()->upload("app",new byte[0x3f0001]));
        var wrong=app("2.5.3");wrong[12]=0;var bad=upload("app",wrong);
        assertThrows(ResponseStatusException.class,()->create(request(next(),"2.5.3",bad,assets)));
    }

    @Test void exactVariantAndFlashSizeAndSameVersionHealthManifest() throws Exception {
        String device=device(), release=release("3.1.3");assign(release,device);
        assertNull(offer(device,"other-variant","3.1.2",16*1024*1024));
        assertNull(offer(device,VARIANT,"3.1.2",8*1024*1024));
        assertNotNull(offer(device,VARIANT,"3.1.2",16*1024*1024));
        // A just-booted image must receive its own manifest to prove health without reinstalling.
        var same=offer(device,VARIANT,"3.1.3",16*1024*1024);
        assertNotNull(same,"same-version boot needs the controlled manifest for health confirmation");
        assertEquals("3.1.3",same.getVersion());
    }

    @Test void ticketsRevokedOnPauseReassignAndUnbind() throws Exception {
        String device=device(), first=release("4.1.3"), second=release("4.1.4");String assignment=assign(first,device);
        String t1=ticket(offer(device,VARIANT,"4.1.2",16*1024*1024));assertNotNull(service.download(first,"app",t1));
        long revision=db.queryForObject("SELECT revision FROM esp32_authorization WHERE device_id=?",Long.class,device);
        db.update("UPDATE ai_device SET app_version='4.1.2',update_date=CURRENT_TIMESTAMP(6) WHERE id=?",device);
        assertEquals(revision,db.queryForObject("SELECT revision FROM esp32_authorization WHERE device_id=?",Long.class,device));
        assertNotNull(service.download(first,"app",t1),"ordinary metadata updates cannot revoke a device ticket");
        run(()->{service.pauseAssignment(assignment);return null;});assertThrows(ResponseStatusException.class,()->service.download(first,"app",t1));
        assign(first,device);String t2=ticket(offer(device,VARIANT,"4.1.2",16*1024*1024));assign(second,device);
        assertThrows(ResponseStatusException.class,()->service.download(first,"app",t2));
        String t3=ticket(offer(device,VARIANT,"4.1.2",16*1024*1024));run(()->{try{return service.state(second,"paused");}catch(java.io.IOException e){throw new RuntimeException(e);}});
        assertThrows(ResponseStatusException.class,()->service.download(second,"app",t3));
        run(()->{try{return service.state(second,"published");}catch(java.io.IOException e){throw new RuntimeException(e);}});
        String t4=ticket(offer(device,VARIANT,"4.1.2",16*1024*1024));
        db.update("UPDATE esp32_authorization SET revision=revision+1 WHERE device_id=?",device);
        assertThrows(ResponseStatusException.class,()->service.download(second,"app",t4));
        assign(second,device);String t5=ticket(offer(device,VARIANT,"4.1.2",16*1024*1024));
        db.update("UPDATE ai_device SET user_id=NULL WHERE id=?",device);
        assertThrows(ResponseStatusException.class,()->service.download(second,"app",t5));
    }

    @Test void lanDeploymentCannotPublishAsFormalVariant() throws Exception {
        var env=new MockEnvironment().withProperty("esp32.ota.directory",artifacts.toString())
                .withProperty("esp32.ota.public-base-url","http://127.0.0.1:18893")
                .withProperty("esp32.ota.allow-loopback-http","true")
                .withProperty("esp32.ota.download-key","isolated-download-key-at-least-32-bytes");
        var lan=new Esp32FirmwareService(db,params,new ObjectMapper(),env);
        var image=upload("app",app("6.1.3"));var assets=upload("assets",new byte[]{1,2,3});
        var formal=request(next(),"6.1.3",image,assets);
        assertThrows(ResponseStatusException.class,()->run(()->{try{return lan.create(1,formal);}catch(java.io.IOException e){throw new RuntimeException(e);}}));
        var dev=new Esp32FirmwareService.ReleaseRequest(next(),"6.1.3",BOARD,"bread-compact-wifi-128x32-ai-lan","esp32s3",16*1024*1024,image,assets,"1.0.0","a".repeat(40),"v6.0.1");
        var record=run(()->{try{return lan.create(1,dev);}catch(java.io.IOException e){throw new RuntimeException(e);}});
        assertEquals("development",record.get("environment"));String id=(String)record.get("id");
        run(()->{try{return lan.state(id,"published");}catch(java.io.IOException e){throw new RuntimeException(e);}});
        String device=device();run(()->lan.assign(1,id,new Esp32FirmwareService.Assignment(device,"automatic",false,600)));
        assertNull(lan.manifest("mac-"+device,"client",report(VARIANT,"6.1.2",16*1024*1024)));
        var manifest=(Esp32FirmwareService.Manifest)lan.manifest("mac-"+device,"client",report("bread-compact-wifi-128x32-ai-lan","6.1.2",16*1024*1024));
        assertNotNull(manifest);assertEquals(Boolean.TRUE,manifest.attributes().get("development_only"));
    }

    @Test void receiptRequiresAuthenticationOrderingIdempotencyAndTerminalProtection() throws Exception {
        String device=device(), release=release("5.1.3"), assignment=assign(release,device), auth=token(device);
        assertThrows(ResponseStatusException.class,()->receipt(device,release,"bad-auth","downloading","5.1.3","Bearer invalid"));
        assertThrows(ResponseStatusException.class,()->receipt(device,release,"without-offer","downloading","5.1.3",auth),"downloading requires an offer");
        assertNotNull(offer(device,VARIANT,"5.1.2",16*1024*1024));
        assertThrows(ResponseStatusException.class,()->receipt(device,release,"jump","healthy","5.1.3",auth),"healthy cannot skip downloading/verified/booted");
        assertThrows(ResponseStatusException.class,()->receipt(device,release,"jump-verified","verified","5.1.3",auth),"verified cannot skip downloading");
        receipt(device,release,"event-downloading","downloading","5.1.3",auth);
        assertThrows(ResponseStatusException.class,()->receipt(device,release,"jump-booted","booted","5.1.3",auth),"booted cannot skip verified");
        for(String state:List.of("verified","booted","healthy")) receipt(device,release,"event-"+state,state,"5.1.3",auth);
        receipt(device,release,"event-healthy","healthy","5.1.3",auth);
        assertEquals(5,db.queryForObject("SELECT COUNT(*) FROM esp32_firmware_event WHERE assignment_id=?",Integer.class,assignment));
        assertThrows(ResponseStatusException.class,()->receipt(device,release,"event-healthy","failed","5.1.3",auth));
        assertThrows(ResponseStatusException.class,()->receipt(device,release,"late","failed","5.1.3",auth));
        db.update("UPDATE ai_device SET user_id=NULL WHERE id=?",device);
        assertThrows(ResponseStatusException.class,()->receipt(device,release,"after-unbind","healthy","5.1.3",auth));
    }
}
