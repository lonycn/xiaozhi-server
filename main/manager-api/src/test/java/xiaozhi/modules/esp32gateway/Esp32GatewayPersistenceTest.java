package xiaozhi.modules.esp32gateway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import liquibase.*;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import xiaozhi.modules.sys.service.SysParamsService;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class Esp32GatewayPersistenceTest {
 static JdbcTemplate db; static TransactionTemplate tx; static Esp32GatewayService s; static SysParamsService params; static org.springframework.transaction.PlatformTransactionManager manager; static final String secret="isolated-test-secret-with-enough-entropy";
 @BeforeAll static void setup() throws Exception {
  String url=System.getenv("ESP32_GATEWAY_TEST_JDBC_URL");Assumptions.assumeTrue(url!=null&&url.matches(".*[/]xiaozhi_esp32_gateway_test_[a-zA-Z0-9_]+[?].*"));
  var ds=new DriverManagerDataSource(url,"root",System.getenv("ESP32_GATEWAY_TEST_DB_PASSWORD"));db=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class));
  db.execute("CREATE TABLE ai_device(id VARCHAR(32) PRIMARY KEY,user_id BIGINT,agent_id VARCHAR(32),mac_address VARCHAR(50),update_date DATETIME(6),alias VARCHAR(64)) ENGINE=InnoDB");
  db.execute("CREATE TABLE ai_agent(id VARCHAR(32) PRIMARY KEY,user_id BIGINT,updated_at DATETIME(6),system_prompt TEXT) ENGINE=InnoDB");
  db.execute("CREATE TABLE sys_user(id BIGINT PRIMARY KEY,status INT,update_date DATETIME(6),username VARCHAR(80)) ENGINE=InnoDB");
  try(var c=ds.getConnection();var l=new Liquibase("db/changelog/esp32-gateway.changelog.yaml",new ClassLoaderResourceAccessor(),new JdbcConnection(c))){l.update(new Contexts());l.update(new Contexts());}
  for(String module:List.of("conversation","esp32-conversation"))try(var c=ds.getConnection();var l=new Liquibase("db/changelog/"+module+".changelog.yaml",new ClassLoaderResourceAccessor(),new JdbcConnection(c))){l.update(new Contexts());}
  params=mock(SysParamsService.class);when(params.getValue("server.secret",false)).thenReturn(secret);s=new Esp32GatewayService(db,params,new ObjectMapper(),true);
 }
 static <T>T run(Supplier<T> fn){return tx.execute(t->fn.get());}
 static Esp32GatewayService.DeviceContext connect(String device,String gateway)throws Exception{long cutoff=db.queryForObject("SELECT COALESCE(MAX(token_not_before),0) FROM esp32_authorization WHERE device_id=?",Long.class,device);return connectAt(device,gateway,Math.max(System.currentTimeMillis()/1000,cutoff));}
 static Esp32GatewayService.DeviceContext connectAt(String device,String gateway,long issuedAt)throws Exception{return run(()->{try{var verifier=new Esp32GatewayService(db,params,new ObjectMapper(),true,java.time.Clock.fixed(java.time.Instant.ofEpochSecond(Math.max(System.currentTimeMillis()/1000,issuedAt)),java.time.ZoneOffset.UTC));return verifier.register(new Esp32GatewayService.Register(null,"mac-"+device,"sim",Esp32GatewayValidationTest.token(secret,"sim","mac-"+device,issuedAt),gateway,List.of("self.get_device_status","self.audio_speaker.set_volume")));}catch(Exception e){throw new RuntimeException(e);}});}
 static void fixture(String id){db.update("INSERT INTO sys_user(id,status) VALUES(?,1)",(Math.abs((long)id.hashCode())+1));db.update("INSERT INTO ai_agent(id,user_id) VALUES(?,?)",id,(Math.abs((long)id.hashCode())+1));db.update("INSERT INTO ai_device(id,user_id,agent_id,mac_address) VALUES(?,?,?,?)",id,(Math.abs((long)id.hashCode())+1),id,"mac-"+id);}
 @Test void reconnectIdempotencyLostAckAndCrossOwner()throws Exception{
  fixture("relay");var a=connect("relay","gw-a");
  var command=run(()->s.submit(a,"self.audio_speaker.set_volume",Map.of("volume",25),"key",10));String id=(String)command.get("id");
  assertEquals(id,run(()->s.submit(a,"self.audio_speaker.set_volume",Map.of("volume",25),"key",10)).get("id"));
  assertThrows(Exception.class,()->run(()->s.submit(a,"self.audio_speaker.set_volume",Map.of("volume",26),"key",10)));
  assertEquals(1,run(()->s.poll(Esp32GatewayService.fence(a))).size());assertTrue(run(()->s.poll(Esp32GatewayService.fence(a))).isEmpty());
  var b=connect("relay","gw-b");assertTrue(b.generation()>a.generation());
  assertThrows(Exception.class,()->run(()->s.heartbeat(Esp32GatewayService.fence(a))));
  assertThrows(Exception.class,()->run(()->s.result(new Esp32GatewayService.Result(Esp32GatewayService.fence(a),id,true,Map.of("ok",true)))));
  assertEquals("unknown",run(()->s.command(a.ownerId(),id)).get("state"));assertThrows(Exception.class,()->run(()->s.command(999L,id)));
  assertThrows(Exception.class,()->run(()->s.result(new Esp32GatewayService.Result(Esp32GatewayService.fence(b),UUID.randomUUID().toString(),true,Map.of()))));
 }
 @Test void revokeAndTimeout()throws Exception{
  fixture("revoke");var a=connect("revoke","gw");var c=run(()->s.submit(a,"self.get_device_status",Map.of(),"read",1));String id=(String)c.get("id");run(()->s.poll(Esp32GatewayService.fence(a)));db.update("UPDATE esp32_command SET deadline=0 WHERE id=?",id);
  assertEquals("unknown",run(()->s.command(a.ownerId(),id)).get("state"));
  db.update("UPDATE sys_user SET status=0 WHERE id=?",a.ownerId());assertThrows(Exception.class,()->run(()->s.assertCurrent(a)));assertThrows(Exception.class,()->run(()->s.poll(Esp32GatewayService.fence(a))));
 }
 @Test void invalidationPermanentlyFencesEvenSameBinding()throws Exception{
  fixture("epoch");var a=connect("epoch","gw");run(()->{s.invalidateDevice("epoch");return null;});
  assertThrows(Exception.class,()->run(()->{s.assertBinding(a);return null;}));
  var b=connect("epoch","gw");assertNotEquals(a.bindingVersion(),b.bindingVersion());assertTrue(b.generation()>a.generation());
  run(()->{s.assertBinding(b);return null;});
 }
 @Test void realBindingTriggersPreventAwayBackResurrection()throws Exception{
  fixture("triggers");var a=connect("triggers","gw");
  db.update("UPDATE ai_device SET mac_address='temporary' WHERE id='triggers'");db.update("UPDATE ai_device SET mac_address='mac-triggers' WHERE id='triggers'");
  assertThrows(Exception.class,()->run(()->s.assertCurrent(a)));assertThrows(Exception.class,()->run(()->{s.assertBinding(a);return null;}));
  var b=connect("triggers","gw");db.update("UPDATE sys_user SET status=0 WHERE id=?",b.ownerId());db.update("UPDATE sys_user SET status=1 WHERE id=?",b.ownerId());
  assertThrows(Exception.class,()->run(()->s.assertCurrent(b)));
  var c=connect("triggers","gw");db.update("UPDATE ai_agent SET user_id=999 WHERE id='triggers'");db.update("UPDATE ai_agent SET user_id=? WHERE id='triggers'",c.ownerId());
  assertThrows(Exception.class,()->run(()->s.assertCurrent(c)));
 }
 @Test void revokedLegacyTokenCannotCreateFreshContextAfterAwayBack()throws Exception{
  fixture("tokenepoch");long issued=System.currentTimeMillis()/1000-10;var original=connectAt("tokenepoch","gw",issued);
  db.update("UPDATE ai_device SET mac_address='temporary' WHERE id='tokenepoch'");db.update("UPDATE ai_device SET mac_address='mac-tokenepoch' WHERE id='tokenepoch'");
  assertThrows(Exception.class,()->connectAt("tokenepoch","gw",issued));long cutoff=db.queryForObject("SELECT token_not_before FROM esp32_authorization WHERE device_id='tokenepoch'",Long.class);assertTrue(cutoff>issued);
  assertThrows(Exception.class,()->connectAt("tokenepoch","gw",cutoff-1));var fresh=connectAt("tokenepoch","gw",cutoff);assertNotEquals(original.bindingVersion(),fresh.bindingVersion());
  db.update("UPDATE sys_user SET status=0 WHERE id=?",fresh.ownerId());db.update("UPDATE sys_user SET status=1 WHERE id=?",fresh.ownerId());
  assertThrows(Exception.class,()->connectAt("tokenepoch","gw",issued));
  long next=db.queryForObject("SELECT token_not_before FROM esp32_authorization WHERE device_id='tokenepoch'",Long.class);assertTrue(next>=cutoff);connectAt("tokenepoch","gw",next);
  run(()->{s.invalidateDevice("tokenepoch");return null;});assertThrows(Exception.class,()->connectAt("tokenepoch","gw",issued));
 }
 @Test void oldFutureThirtySecondTokenIsRevokedAndFreshCutoffProofWorks()throws Exception{
  fixture("futurewindow");long before=db.queryForObject("SELECT UNIX_TIMESTAMP()",Long.class);long oldFuture=before+30;connectAt("futurewindow","gw",oldFuture);
  db.update("UPDATE ai_device SET mac_address='temporary' WHERE id='futurewindow'");db.update("UPDATE ai_device SET mac_address='mac-futurewindow' WHERE id='futurewindow'");
  long cutoff=db.queryForObject("SELECT token_not_before FROM esp32_authorization WHERE device_id='futurewindow'",Long.class);assertTrue(cutoff>=before+31);assertThrows(Exception.class,()->connectAt("futurewindow","gw",oldFuture));
  // Simulate the trusted issuer/verifier clock reaching cutoff without sleeping or changing DB clocks.
  var fresh=connectAt("futurewindow","gw",cutoff);assertEquals("futurewindow",fresh.deviceId());
 }
 @Test void metadataUpdateDoesNotRevokeStockTokenButCutoffNeverMovesBack()throws Exception{
  fixture("metadata");long issued=System.currentTimeMillis()/1000-10;var original=connectAt("metadata","gw",issued);
  db.update("UPDATE ai_device SET update_date=CURRENT_TIMESTAMP(6) WHERE id='metadata'");
  db.update("UPDATE ai_agent SET updated_at=CURRENT_TIMESTAMP(6) WHERE id='metadata'");db.update("UPDATE sys_user SET update_date=CURRENT_TIMESTAMP(6) WHERE id=?",original.ownerId());
  assertEquals(original.bindingVersion(),run(()->s.assertCurrent(original)).bindingVersion());run(()->{s.assertBinding(original);return null;});assertEquals(original.bindingVersion(),run(()->s.current("metadata")).bindingVersion());
  connectAt("metadata","gw",issued);assertEquals(0L,db.queryForObject("SELECT token_not_before FROM esp32_authorization WHERE device_id='metadata'",Long.class));
  long future=System.currentTimeMillis()/1000+100;db.update("UPDATE esp32_authorization SET token_not_before=? WHERE device_id='metadata'",future);db.update("UPDATE ai_device SET mac_address='temporary' WHERE id='metadata'");db.update("UPDATE ai_device SET mac_address='mac-metadata' WHERE id='metadata'");assertEquals(future,db.queryForObject("SELECT token_not_before FROM esp32_authorization WHERE device_id='metadata'",Long.class));connectAt("metadata","gw",future);
 }
 @Test void metadataRefreshPreservesLiveConnectionAndTrustedSessionHistory()throws Exception{
  fixture("historymeta");var context=connect("historymeta","gw");
  var beans=new org.springframework.beans.factory.support.DefaultListableBeanFactory();
  beans.registerSingleton("contextGuard",new xiaozhi.modules.esp32conversation.Esp32ContextGuard(s));
  var sessions=new xiaozhi.modules.esp32conversation.Esp32ConversationService(db,new xiaozhi.modules.conversation.ConversationDao(db),new ObjectMapper(),manager,beans.getBeanProvider(xiaozhi.modules.esp32conversation.Esp32AiExecutionBridge.class),beans.getBeanProvider(xiaozhi.modules.esp32conversation.Esp32ConversationService.ContextGuard.class));
  var trusted=xiaozhi.modules.esp32conversation.Esp32ContextGuard.trusted(context);var session=sessions.connect(trusted,"legacy_record_only");String sessionId=(String)session.get("id");sessions.input(trusted,sessionId,"before-refresh","保留这条设备历史");
  db.update("UPDATE ai_device SET alias='refreshed-alias',update_date=CURRENT_TIMESTAMP(6) WHERE id='historymeta'");db.update("UPDATE ai_agent SET system_prompt='updated-role-config',updated_at=CURRENT_TIMESTAMP(6) WHERE id='historymeta'");db.update("UPDATE sys_user SET username='refreshed-owner-name',update_date=CURRENT_TIMESTAMP(6) WHERE id=?",context.ownerId());
  assertEquals(context.bindingVersion(),run(()->s.assertCurrent(context)).bindingVersion());assertEquals(sessionId,sessions.list(context.ownerId(),context.deviceId(),20).getFirst().get("id"));assertEquals(1,sessions.turns(context.ownerId(),sessionId,0,20).size());
  db.update("UPDATE sys_user SET status=0 WHERE id=?",context.ownerId());db.update("UPDATE sys_user SET status=1 WHERE id=?",context.ownerId());
  assertThrows(Exception.class,()->run(()->s.assertCurrent(context)));assertTrue(sessions.list(context.ownerId(),context.deviceId(),20).isEmpty());assertThrows(Exception.class,()->sessions.turns(context.ownerId(),sessionId,0,20));
 }
 @Test void unknownMcpAcknowledgementIsDurableAndCannotBecomeSuccess()throws Exception{
  fixture("unknownack");var c=connect("unknownack","gw");String id=(String)run(()->s.submit(c,"self.audio_speaker.set_volume",Map.of("volume",50),"unknown",10)).get("id");run(()->s.poll(Esp32GatewayService.fence(c)));
  var unknown=new Esp32GatewayService.Result(Esp32GatewayService.fence(c),id,false,Map.of("error_code","MCP_ACK_TIMEOUT"),"unknown");
  assertEquals("unknown",run(()->s.result(unknown)).get("state"));assertEquals("unknown",run(()->s.result(unknown)).get("state"));assertEquals("unknown",run(()->s.trustedCommand(c,id)).get("state"));
  assertThrows(Exception.class,()->run(()->s.result(new Esp32GatewayService.Result(Esp32GatewayService.fence(c),id,true,Map.of("volume",50)))));
 }
 @Test void successRetainedButPublicPayloadRedacted()throws Exception{
  fixture("success");var a=connect("success","gw");String id=(String)run(()->s.submit(a,"self.get_device_status",Map.of(),"read",10)).get("id");run(()->s.poll(Esp32GatewayService.fence(a)));
  var r=new Esp32GatewayService.Result(Esp32GatewayService.fence(a),id,true,Map.of("volume",70));assertEquals("succeeded",run(()->s.result(r)).get("state"));assertEquals("succeeded",run(()->s.result(r)).get("state"));
  assertFalse(run(()->s.command(a.ownerId(),id)).containsKey("result_json"));assertTrue(db.queryForObject("SELECT COUNT(*) FROM esp32_gateway_audit WHERE command_id=?",Integer.class,id)>=3);
 }
 @Test void debugTextUsesTheOwnedLiveSessionAndDurableIdempotentCommandQueue()throws Exception{
  fixture("debugtext");long issued=System.currentTimeMillis()/1000;
  var context=run(()->{try{return s.register(new Esp32GatewayService.Register(null,"mac-debugtext","sim",Esp32GatewayValidationTest.token(secret,"sim","mac-debugtext",issued),"gw",List.of(Esp32GatewayService.TEXT_INPUT)));}catch(Exception e){throw new RuntimeException(e);}});
  var beans=new org.springframework.beans.factory.support.DefaultListableBeanFactory();beans.registerSingleton("contextGuard",new xiaozhi.modules.esp32conversation.Esp32ContextGuard(s));
  var sessions=new xiaozhi.modules.esp32conversation.Esp32ConversationService(db,new xiaozhi.modules.conversation.ConversationDao(db),new ObjectMapper(),manager,beans.getBeanProvider(xiaozhi.modules.esp32conversation.Esp32AiExecutionBridge.class),beans.getBeanProvider(xiaozhi.modules.esp32conversation.Esp32ConversationService.ContextGuard.class));
  var session=sessions.connect(xiaozhi.modules.esp32conversation.Esp32ContextGuard.trusted(context),"ai_worker");String conversation=(String)session.get("conversation_id");
  assertEquals(true,run(()->s.inputTarget(context.ownerId(),"debugtext","mac-debugtext")).get("available"));
  var receipt=run(()->s.submitTextOwner(context.ownerId(),"debugtext",conversation,"<测试>文字输入","debug-key"));String id=(String)receipt.get("id");
  assertEquals(id,run(()->s.submitTextOwner(context.ownerId(),"debugtext",conversation,"<测试>文字输入","debug-key")).get("id"));
  assertThrows(Exception.class,()->run(()->s.submitTextOwner(999L,"debugtext",conversation,"hello","other-owner")));
  assertThrows(Exception.class,()->run(()->s.submitTextOwner(context.ownerId(),"debugtext","f".repeat(32),"hello","wrong-session")));
  assertThrows(Exception.class,()->run(()->s.submitTextOwner(context.ownerId(),"debugtext",conversation,"different","debug-key")));
  var pending=run(()->s.poll(Esp32GatewayService.fence(context)));assertEquals(1,pending.size());assertEquals(Esp32GatewayService.TEXT_INPUT,pending.getFirst().get("tool"));
  var arguments=new ObjectMapper().readTree((String)pending.getFirst().get("arguments"));assertEquals("<测试>文字输入",arguments.get("text").asText());assertEquals(session.get("id"),arguments.get("session_id").asText());
  assertTrue(run(()->s.poll(Esp32GatewayService.fence(context))).isEmpty());
  assertEquals("succeeded",run(()->s.result(new Esp32GatewayService.Result(Esp32GatewayService.fence(context),id,true,Map.of("accepted",true)))).get("state"));
 }

}
