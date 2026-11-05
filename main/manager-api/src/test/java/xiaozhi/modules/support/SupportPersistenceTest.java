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
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Long.class),"Never clean a preexisting schema");
        jdbc.execute("CREATE TABLE sys_user (id BIGINT PRIMARY KEY, status TINYINT NOT NULL, super_admin TINYINT NOT NULL DEFAULT 0, username VARCHAR(80))");
        jdbc.execute("CREATE TABLE ai_agent (id VARCHAR(32) PRIMARY KEY, user_id BIGINT)");
        jdbc.execute("CREATE TABLE ai_agent_chat_history (id BIGINT PRIMARY KEY, content VARCHAR(100))");
        jdbc.update("INSERT INTO ai_agent_chat_history VALUES (1, 'legacy-sentinel')");
        for (long actor = 1; actor <= 6; actor++) {
            jdbc.update("INSERT INTO sys_user (id,status,super_admin,username) VALUES (?,1,?,?)", actor, actor == 1 ? 1 : 0, "support-test-" + actor);
        }
        for (String path : List.of("conversation", "support", "support-public-profiles")) {
            try (var connection = source.getConnection(); var migration = new Liquibase(
                    "db/changelog/" + path + ".changelog.yaml", new ClassLoaderResourceAccessor(), new JdbcConnection(connection))) {
                migration.update(new Contexts());
                migration.update(new Contexts());
            }
        }
        assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM DATABASECHANGELOG", Long.class));
        var json = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        var tx = new DataSourceTransactionManager(source);
        conversations = new ConversationService(new ConversationDao(jdbc), json, tx);
        support = new SupportService(jdbc, json, tx, conversations);
        support.setPublicProfiles(new SupportProfileService(jdbc,json));
    }

    @BeforeEach
    void resetIsolatedTables() {
        for (String table : List.of("support_public_profile", "support_request_transfer", "support_request_key", "support_assignment", "support_request", "ai_message", "ai_conversation_event", "ai_conversation", "support_agent_profile")) {
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
    void independentBusinessPreferencesGateClaimsDirectoryAndHeartbeats() {
        support.upsertAgent(1L,3L,new AgentUpdate("support_agent",true,20,List.of("support_agent","expert")));
        support.presence(3L,new PresenceUpdate(false,"support_agent"));
        Agent expertOnly=support.presence(3L,new PresenceUpdate(true,"expert"));
        assertFalse(expertOnly.servicePresence().get("support_agent").accepting());
        assertTrue(expertOnly.servicePresence().get("expert").accepting());
        assertFalse(support.me(3L).accepting()); // Legacy field describes only the primary business.
        var directory=new SupportProfileService(jdbc,JsonMapper.builder().build());
        assertTrue(directory.experts(2L,0,50).items().stream().filter(e->e.userId()==3).findFirst().orElseThrow().online());
        String expert=support.handoff(2L,conversation(),new Handoff("only-expert","text",3L,"expert")).requestId();
        String customerService=support.handoff(2L,conversation(),new Handoff("only-support","text",3L,"support_agent")).requestId();
        assertEquals(409,problem(()->support.claim(3L,customerService)));
        support.claim(3L,expert);
        var lease=support.me(3L).servicePresence().get("expert").presenceExpiresAt();
        support.presence(3L,new PresenceUpdate(true,"support_agent"));
        assertEquals(lease,support.me(3L).servicePresence().get("expert").presenceExpiresAt());
        support.presence(3L,new PresenceUpdate(false,"expert"));
        assertTrue(support.me(3L).servicePresence().get("support_agent").accepting());
        assertFalse(directory.experts(2L,0,50).items().stream().filter(e->e.userId()==3).findFirst().orElseThrow().online());
        support.claim(3L,customerService);
        String paused=support.handoff(2L,conversation(),new Handoff("paused-expert","text",3L,"expert")).requestId();
        assertEquals(409,problem(()->support.claim(3L,paused)));
        assertEquals("expert",support.send(3L,expert,message("still-active","已有接待继续回复")).senderType());
        support.presence(3L,new PresenceUpdate(true,"expert"));
        jdbc.update("UPDATE support_agent_profile SET expert_presence_expires_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 MINUTE) WHERE user_id=3");
        support.presence(3L,new PresenceUpdate(true,"support_agent"));
        assertEquals(409,problem(()->support.claim(3L,paused))); // Customer-service heartbeat cannot renew expert presence.
        assertEquals(403,problem(()->support.presence(4L,new PresenceUpdate(true,"support_agent"))));
        assertEquals(400,problem(()->support.presence(3L,new PresenceUpdate(true,"unknown"))));
        support.upsertAgent(1L,3L,new AgentUpdate("support_agent",true,20));
        support.upsertAgent(1L,3L,new AgentUpdate("support_agent",true,20,List.of("support_agent","expert")));
        assertFalse(support.me(3L).servicePresence().get("expert").accepting());
        support.presence(3L,new PresenceUpdate(true,"expert"));
        support.upsertAgent(1L,3L,new AgentUpdate("support_agent",false,20,List.of("support_agent","expert")));
        support.upsertAgent(1L,3L,new AgentUpdate("support_agent",true,20,List.of("support_agent","expert")));
        assertFalse(support.me(3L).servicePresence().get("support_agent").accepting());
        assertFalse(support.me(3L).servicePresence().get("expert").accepting());
    }

    @Test
    void publicDirectoryUsesRolesDisplayProfilesAndLivePresenceWithoutLeakingAccounts() {
        var profiles=new SupportProfileService(jdbc,JsonMapper.builder().build());
        assertTrue(profiles.experts(2L,0,50).items().stream().anyMatch(e -> e.userId()==4L && e.online()));
        assertTrue(profiles.experts(2L,0,50).items().stream().noneMatch(e -> e.userId()==3L));
        var details=new PublicProfile("王工程师","","高级工程师","制冷系统调试",List.of("冷库","压缩机"),true);
        support.upsertAgent(1L,3L,new AgentUpdate("support_agent",true,1,List.of("support_agent","expert"),details));
        var expert=profiles.experts(2L,4L,1).items().getFirst();
        assertEquals(3L,expert.userId()); assertEquals("王工程师",expert.displayName());
        assertEquals(details,support.agents(1L,0,50).items().stream().filter(a -> a.userId()==3).findFirst().orElseThrow().profile());
        support.upsertAgent(1L,3L,new AgentUpdate("expert",true,1));
        assertEquals("王工程师",profiles.experts(2L,4,1).items().getFirst().displayName());
        support.presence(3L,new PresenceUpdate(false));
        assertFalse(profiles.experts(2L,4,1).items().getFirst().online());
        support.upsertAgent(1L,3L,new AgentUpdate("expert",true,1,List.of("expert"),new PublicProfile("隐藏专家","","","",List.of(),false)));
        assertTrue(profiles.experts(2L,0,50).items().stream().noneMatch(e -> e.userId()==3L));
        jdbc.update("UPDATE sys_user SET status=0 WHERE id=4");
        assertTrue(profiles.experts(2L,0,50).items().isEmpty());
        assertEquals(401,problem(() -> profiles.experts(null,0,50)));
    }

    @Test
    void invalidPublicProfileRollsBackRoleChangesAndRasterAvatarIsStoredSeparately() throws Exception {
        var profiles=new SupportProfileService(jdbc,JsonMapper.builder().build());
        assertEquals(400,problem(() -> support.upsertAgent(1L,3L,new AgentUpdate("expert",false,2,List.of("expert"),new PublicProfile("x".repeat(81),"","","",List.of(),true)))));
        assertTrue(support.me(3L).enabled()); assertEquals("support_agent",support.me(3L).role());
        var output=new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",output);
        String data="data:image/png;base64,"+java.util.Base64.getEncoder().encodeToString(output.toByteArray());
        support.upsertAgent(1L,4L,new AgentUpdate("expert",true,1,List.of("expert"),new PublicProfile("专家",data,"","",List.of(),true)));
        String url=profiles.experts(2L,0,50).items().getFirst().avatarUrl();
        assertTrue(url.startsWith("/api/v1/support/avatars/4/"));
        assertArrayEquals(output.toByteArray(),profiles.avatar(4L,url.substring(url.lastIndexOf('/')+1)).bytes());
        assertFalse(url.contains("base64"));
        assertEquals(400,problem(() -> support.upsertAgent(1L,4L,new AgentUpdate("expert",true,1,List.of("expert"),new PublicProfile("专家","javascript:alert(1)","","",List.of(),true)))));
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
        support.upsertAgent(1L,4L,new AgentUpdate("expert",true,1,List.of("support_agent","expert")));
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
        support.transfer(3L, request, new Transfer("handover","expert",4L,"暂停接待"));
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

    @Test
    void deskReadsRestoreOnlyLiveAssignmentsAndPreserveProfilePagination() {
        assertNull(support.me(5L));
        assertEquals("support_agent", support.me(3L).role());
        support.upsertAgent(1L, 3L, new AgentUpdate("support_agent", true, 2));
        String first = handoff(conversation());
        String other = support.handoff(2L,conversation(),new Handoff("other","text",4L,"expert")).requestId();
        String last = handoff(conversation());
        support.claim(3L, first);
        support.claim(4L, other);
        support.claim(3L, last);
        var page = support.assigned(3L, 0, 1);
        assertTrue(page.hasMore());
        assertEquals(last, page.items().getFirst().requestId());
        var next = support.assigned(3L, page.nextCursor(), 1);
        assertFalse(next.hasMore());
        assertEquals(first, next.items().getFirst().requestId());
        assertEquals(List.of(other), support.assigned(4L, 0, 100).items().stream().map(Request::requestId).toList());
        support.transfer(3L,first,new Transfer("desk-transfer","expert",4L,"交接"));
        assertEquals(List.of(last), support.assigned(3L, 0, 100).items().stream().map(Request::requestId).toList());
        support.resolve(4L, other, null);
        support.claim(4L, first);
        assertEquals(List.of(last), support.assigned(3L, 0, 100).items().stream().map(Request::requestId).toList());
        assertEquals(403, problem(() -> support.assigned(5L, 0, 100)));
        assertEquals(403, problem(() -> support.agents(3L, 0, 100)));
        var agents = support.agents(1L, 0, 1);
        assertTrue(agents.hasMore());
        assertEquals(4L, agents.items().getFirst().userId());
        assertEquals(3L, support.agents(1L, agents.nextCursor(), 1).items().getFirst().userId());
        jdbc.update("UPDATE sys_user SET status=0 WHERE id=3");
        assertEquals(403, problem(() -> support.me(3L)));
        assertFalse(support.agents(1L, 4, 1).items().getFirst().accountEnabled());
        support.upsertAgent(1L, 4L, new AgentUpdate("expert", false, 1));
        assertNull(support.me(4L));
        assertEquals(403, problem(() -> support.assigned(4L, 0, 100)));
    }
    @Test void queuesAreSeparatedAndMultiRoleSenderComesFromRequest() {
        String supportId=handoff(conversation());
        String expertId=support.handoff(2L,conversation(),new Handoff("expert","text",null,"expert")).requestId();
        assertEquals(List.of(supportId),support.queue(3L,0,100).items().stream().map(Request::requestId).toList());
        assertEquals(List.of(expertId),support.queue(4L,0,100).items().stream().map(Request::requestId).toList());
        assertEquals(403,problem(()->support.queue(3L,0,100,"expert")));
        assertEquals(400,problem(()->support.queue(3L,0,100,"admin")));
        assertEquals(403,problem(()->support.claim(3L,expertId)));
        assertEquals(403,problem(()->support.claim(4L,supportId)));
        support.upsertAgent(1L,4L,new AgentUpdate("support_agent",true,2,List.of("support_agent","expert")));
        assertEquals(2,support.queue(4L,0,100).items().size());
        assertEquals(1,support.queue(4L,0,100,"expert").items().size());
        support.claim(4L,expertId);
        assertEquals("expert",support.send(4L,expertId,message("expert-reply","专业回复")).senderType());
        assertFalse(support.me(4L).servicePresence().get("support_agent").accepting());
        support.presence(4L,new PresenceUpdate(true,"support_agent"));
        support.claim(4L,supportId);
        assertEquals("support_agent",support.send(4L,supportId,message("support-reply","客服回复")).senderType());
        assertEquals(List.of("support_agent","expert"),support.me(4L).roles());
    }

    @Test void transferRevokesLiveRightsRetainsBoundedHistoryAndIsIdempotentBothWays() {
        String conversation=conversation(),request=handoff(conversation);
        support.claim(3L,request);
        support.send(3L,request,message("before","转接前"));
        var hooks=org.mockito.Mockito.mock(xiaozhi.modules.supportmedia.SupportMediaHooks.class);
        support.setMediaHooksForTests(hooks);
        try {
            Transfer body=new Transfer("to-expert","expert",4L,"需要专家");
            Request transferred=support.transfer(3L,request,body);
            assertEquals("queued",transferred.status());assertEquals("expert",transferred.serviceType());
            assertEquals(conversation,transferred.conversationId());assertNull(transferred.assignedAgentUserId());
            assertEquals(transferred,support.transfer(3L,request,body));
            org.mockito.Mockito.verify(hooks,org.mockito.Mockito.times(1)).responsibilityEnded(request);
            assertEquals(409,problem(()->support.transfer(3L,request,new Transfer("to-expert","expert",null,"changed"))));
            assertEquals(403,problem(()->support.send(3L,request,message("late","不能写入"))));
            assertEquals(403,problem(()->support.get(3L,request)));
            assertEquals(404,problem(()->conversations.get(3L,conversation)));
            support.claim(4L,request);
            support.send(4L,request,message("after","专家回复"));
            assertEquals(1,conversations.messages(3L,conversation,0,100).items().size());
            assertEquals(2,conversations.messages(4L,conversation,0,100).items().size());
            assertEquals(transferred,support.transfer(3L,request,body)); // response remains original snapshot
            assertEquals(1,number("SELECT COUNT(*) FROM support_request_transfer WHERE request_id=?",request));
            assertEquals(1,number("SELECT COUNT(*) FROM support_assignment WHERE request_id=? AND released_at IS NOT NULL",request));
            var back=support.transfer(4L,request,new Transfer("to-support","support_agent",3L,"返回客服"));
            assertEquals("support_agent",back.serviceType());support.claim(3L,request);
            assertEquals("support_agent",support.send(3L,request,message("back","继续处理")).senderType());
            assertEquals(2,number("SELECT COUNT(*) FROM support_request_transfer WHERE request_id=?",request));
        } finally { support.setMediaHooksForTests(null); }
    }

    @Test void wrongTargetAndRoleRevocationCannotBypassAssignmentPolicies() {
        String conversation=conversation(),request=handoff(conversation);support.claim(3L,request);
        assertEquals(403,problem(()->support.transfer(3L,request,new Transfer("wrong-target","expert",3L,null))));
        assertEquals("active",support.get(3L,request).status());
        assertEquals(0,number("SELECT COUNT(*) FROM support_request_transfer"));
        support.send(3L,request,message("earlier","旧记录"));
        support.upsertAgent(1L,3L,new AgentUpdate("expert",true,1));
        assertEquals(403,problem(()->support.get(3L,request)));
        assertEquals(403,problem(()->support.send(3L,request,message("stale-role","失效身份"))));
        assertEquals(404,problem(()->conversations.messages(3L,conversation,0,100)));
        assertEquals(404,problem(()->conversations.get(3L,conversation)));
        assertTrue(support.assigned(3L,0,100).items().isEmpty());
        assertEquals(List.of("expert"),support.me(3L).roles());
    }

    @Test void targetedLegacyHandoffUsesTargetPrimaryRoleButUndirectedDefaultsToSupport() {
        assertEquals("support_agent",support.get(2L,handoff(conversation())).serviceType());
        var directed=support.handoff(2L,conversation(),new Handoff("direct","text",4L));
        assertEquals("expert",directed.serviceType());support.claim(4L,directed.requestId());
        assertEquals("expert",support.send(4L,directed.requestId(),message("direct-reply","专家")).senderType());
        assertEquals(403,problem(()->support.handoff(2L,conversation(),new Handoff("bad-target","text",3L,"expert"))));
    }

}
