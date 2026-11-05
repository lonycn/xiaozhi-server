package xiaozhi.modules.esp32ai;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import liquibase.*;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import xiaozhi.modules.esp32conversation.Esp32ConversationService.TrustedContext;
import xiaozhi.modules.esp32gateway.Esp32GatewayService;
import xiaozhi.modules.agenttool.AgentToolModels.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class Esp32AiPersistenceTest {
 static JdbcTemplate db;static TransactionTemplate tx;static Esp32GatewayService gateway;static Esp32AiRunGuard guard;static Esp32DeviceToolService tools;static final ObjectMapper json=new ObjectMapper();static final String lease="a".repeat(64);
 record Fixture(String run,String session,String turn,TrustedContext ctx){}
 @BeforeAll static void setup()throws Exception{
  String url=System.getenv("ESP32_AI_TEST_JDBC_URL");Assumptions.assumeTrue(url!=null&&url.matches(".*[/]xiaozhi_esp32_ai_test_[a-zA-Z0-9_]+[?].*"));var ds=new DriverManagerDataSource(url,"root",System.getenv("ESP32_AI_TEST_DB_PASSWORD"));db=new JdbcTemplate(ds);var manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class));
  db.execute("CREATE TABLE esp32_device_session(id VARCHAR(32) PRIMARY KEY,device_id VARCHAR(32),binding_version VARCHAR(64),owner_user_id BIGINT,agent_id VARCHAR(32),conversation_id VARCHAR(32),execution_mode VARCHAR(30),interaction_mode VARCHAR(20),connection_id VARCHAR(36),generation BIGINT,gateway_id VARCHAR(64),control_version BIGINT,last_turn_seq BIGINT) ENGINE=InnoDB");
  db.execute("CREATE TABLE esp32_voice_turn(id VARCHAR(32) PRIMARY KEY,session_id VARCHAR(32),control_version BIGINT,turn_seq BIGINT,connection_id VARCHAR(36),generation BIGINT,execution_mode VARCHAR(30),input_message_id VARCHAR(32),status VARCHAR(20)) ENGINE=InnoDB");
  db.execute("CREATE TABLE ai_conversation(id VARCHAR(32) PRIMARY KEY,state VARCHAR(20),owner_user_id BIGINT,control_epoch BIGINT) ENGINE=InnoDB");
  db.execute("CREATE TABLE ai_run(id VARCHAR(32) PRIMARY KEY,conversation_id VARCHAR(32),input_message_id VARCHAR(32),output_message_id VARCHAR(32),attempt INT,control_epoch BIGINT,status VARCHAR(20),lease_token VARCHAR(64),lease_expires_at DATETIME(3),output_text TEXT) ENGINE=InnoDB");
  db.execute("CREATE TABLE ai_message(id VARCHAR(32) PRIMARY KEY,parts_json TEXT,status VARCHAR(20)) ENGINE=InnoDB");
  db.execute("CREATE TABLE ai_agent(id VARCHAR(32) PRIMARY KEY,esp32_reply_length VARCHAR(8)) ENGINE=InnoDB");
  db.update("INSERT INTO ai_agent VALUES('agent','standard')");
  try(var c=ds.getConnection();var l=new Liquibase("db/changelog/esp32-ai.changelog.yaml",new ClassLoaderResourceAccessor(),new JdbcConnection(c))){l.update(new Contexts());l.update(new Contexts());}
  gateway=mock(Esp32GatewayService.class);guard=new Esp32AiRunGuard(db,gateway);tools=new Esp32DeviceToolService(db,json,guard,gateway,manager);
 }
 @BeforeEach void resetGateway(){reset(gateway);when(gateway.assertCurrent(any())).thenAnswer(i->i.getArgument(0));when(gateway.status(any(),any())).thenReturn(Map.of("capabilities","[\"self.get_device_status\",\"self.audio_speaker.set_volume\"]"));when(gateway.submit(any(),any(),any(),any(),anyInt())).thenAnswer(i->Map.of("id",UUID.randomUUID().toString()));when(gateway.trustedCommand(any(),any())).thenReturn(Map.of("state","succeeded","result",Map.of("volume",50)));}
 static String id(){return UUID.randomUUID().toString().replace("-","");}static <T>T run(Supplier<T> fn){return tx.execute(t->fn.get());}
 static Fixture fixture(String text)throws Exception{return fixture(text,null);}
 static Fixture fixture(String text,String rolePrompt)throws Exception{
  String r=id(),s=id(),t=id(),conversation=id(),input=id();var ctx=new TrustedContext("device", "b".repeat(64),7,"agent","connection",1,"gw");
  db.update("INSERT INTO esp32_device_session VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",s,ctx.deviceId(),ctx.bindingVersion(),7,"agent",conversation,"ai_worker","ai_only","connection",1,"gw",0,1);
  db.update("INSERT INTO esp32_voice_turn VALUES(?,?,0,1,'connection',1,'ai_worker',?,'accepted')",t,s,input);
  db.update("INSERT INTO ai_conversation VALUES(?,'AI_ACTIVE',7,0)",conversation);
  db.update("INSERT INTO ai_message VALUES(?,?,'accepted')",input,json.writeValueAsString(List.of(Map.of("type","text","text",text))));
  db.update("INSERT INTO ai_run(id,conversation_id,input_message_id,attempt,control_epoch,status,lease_token,lease_expires_at) VALUES(?,?,?,1,0,'running',?,DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 90 SECOND))",r,conversation,input,lease);
  run(()->{guard.prepare(r,ctx,t,rolePrompt);return null;});return new Fixture(r,s,t,ctx);
 }
 static Proposal p(int round,Call...calls){return new Proposal(lease,1,0,round,List.of(calls));}
 static Call call(String id,String tool,int volume){return new Call(id,tool,tool.equals("esp32_set_volume")?json.createObjectNode().put("volume",volume):json.createObjectNode());}
 @Test void rolePromptIsScopedToTheAcceptedDeviceRun()throws Exception{
  String prompt="你是大冷总管。";
  var device=fixture("你是谁",prompt);
  assertEquals(prompt,guard.rolePrompt(device.run));
  assertNull(guard.rolePrompt(fixture("旧连接输入").run));
  assertNull(guard.rolePrompt(id()));
 }
 @Test void localDeviceSettingAppliesWhenTheGatewayHasNoRoleProfile() throws Exception {
  db.update("UPDATE ai_agent SET esp32_reply_length='short' WHERE id='agent'");
  try {
   var device = fixture("你是谁");
   assertTrue(guard.rolePrompt(device.run).contains("四十个汉字"));
   assertEquals("网关提供的角色", guard.rolePrompt(fixture("你是谁", "网关提供的角色").run));
  } finally {
   db.update("UPDATE ai_agent SET esp32_reply_length='standard' WHERE id='agent'");
  }
 }
 @Test void bindingAndLeaseFencesBeforeCatalogAndReplay()throws Exception{
  var f=fixture("请把音量调到50");assertTrue(guard.bound(f.run));assertEquals(3,tools.taskTools(f.run,1,lease,0).tools().size());
  var proposal=p(1,call("v1","esp32_set_volume",50));assertEquals("succeeded",tools.submitCalls(f.run,proposal).results().getFirst().status());
  assertEquals("succeeded",tools.submitCalls(f.run,proposal).results().getFirst().status());verify(gateway,times(1)).submit(any(),any(),any(),any(),anyInt());
  db.update("UPDATE ai_run SET lease_token=? WHERE id=?","c".repeat(64),f.run);assertThrows(Exception.class,()->tools.submitCalls(f.run,proposal));
  assertEquals(1,tools.countCalls(f.run,1));assertTrue(db.queryForObject("SELECT COUNT(*) FROM esp32_ai_tool_audit",Integer.class)>=3);
 }
 @Test void explicitInputGateAndSupersededTurns()throws Exception{
  var f=fixture("设备状态怎么样，音量现在50");assertThrows(Exception.class,()->tools.submitCalls(f.run,p(1,call("v1","esp32_set_volume",50))));verify(gateway,never()).submit(any(),any(),any(),any(),anyInt());
  db.update("UPDATE esp32_device_session SET control_version=1 WHERE id=?",f.session);assertThrows(Exception.class,()->tools.taskTools(f.run,1,lease,0));
 }
 @Test void callAndRoundBudgetsStrict()throws Exception{
  var f=fixture("状态");assertThrows(Exception.class,()->tools.submitCalls(f.run,p(2,call("c0","esp32_get_capabilities",0))));
  Call[] calls=new Call[6];for(int i=0;i<6;i++)calls[i]=call("c"+i,"esp32_get_capabilities",0);assertEquals(0,tools.submitCalls(f.run,p(1,calls)).remainingCalls());
  assertThrows(Exception.class,()->tools.submitCalls(f.run,p(2,call("c7","esp32_get_capabilities",0))));
  assertThrows(Exception.class,()->tools.submitCalls(f.run,p(1,call("c0","esp32_get_status",0))));
 }
 @Test void canonicalBridgeEnqueuesAcceptedInputAndReadsActualOutput()throws Exception{
  var f=fixture("状态");var ai=mock(xiaozhi.modules.ai.AiExecutionService.class);
  @SuppressWarnings("unchecked") org.springframework.beans.factory.ObjectProvider<xiaozhi.modules.ai.AiExecutionService> provider=mock(org.springframework.beans.factory.ObjectProvider.class);
  when(provider.getObject()).thenReturn(ai);var bridge=new Esp32CanonicalAiBridge(provider,db,guard);
  var run=db.queryForMap("SELECT * FROM ai_run WHERE id=?",f.run);String conversation=(String)run.get("conversation_id"),input=(String)run.get("input_message_id");int before=db.queryForObject("SELECT COUNT(*) FROM ai_message",Integer.class);
  bridge.submit(f.ctx,conversation,input,f.turn);verify(ai).onDeviceUserMessage(conversation,input,f.ctx,f.turn);assertEquals(before,db.queryForObject("SELECT COUNT(*) FROM ai_message",Integer.class));
  String output=id();db.update("INSERT INTO ai_message VALUES(?,'[]','completed')",output);db.update("UPDATE ai_run SET output_message_id=?,output_text='actual canonical output',status='completed' WHERE id=?",output,f.run);
  var reply=run(()->bridge.reply(conversation,input));assertEquals("completed",reply.get("status"));assertEquals("actual canonical output",reply.get("text"));assertEquals(f.run,reply.get("runId"));
  db.update("UPDATE ai_run SET output_text=? WHERE id=?","x".repeat(16001),f.run);assertEquals("failed",run(()->bridge.reply(conversation,input)).get("status"));
  db.update("UPDATE ai_run SET output_text=' ' WHERE id=?",f.run);assertEquals("failed",run(()->bridge.reply(conversation,input)).get("status"));
  db.update("UPDATE ai_conversation SET control_epoch=1 WHERE id=?",conversation);assertEquals("canceled",run(()->bridge.reply(conversation,input)).get("status"));
 }
 @Test void pendingDeviceWaitReleasesRunLockAndRevokeBlocksPublication()throws Exception{
  var f=fixture("状态");CountDownLatch polled=new CountDownLatch(1);when(gateway.trustedCommand(any(),any())).thenAnswer(i->{polled.countDown();return Map.of("state","pending");});
  var pool=Executors.newSingleThreadExecutor();try{Future<?> pending=pool.submit(()->tools.submitCalls(f.run,p(1,call("read","esp32_get_status",0))));assertTrue(polled.await(3,TimeUnit.SECONDS));
   run(()->{db.update("UPDATE ai_run SET lease_expires_at=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 90 SECOND) WHERE id=?",f.run);return null;});
   db.update("UPDATE esp32_voice_turn SET status='canceled' WHERE id=?",f.turn);assertThrows(ExecutionException.class,()->pending.get(3,TimeUnit.SECONDS));
   tools.cancelPending(f.run);assertEquals("canceled",db.queryForObject("SELECT state FROM esp32_ai_tool_call WHERE run_id=?",String.class,f.run));
  }finally{pool.shutdownNow();}
 }
}
