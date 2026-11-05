package xiaozhi.modules.supportmedia;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import com.fasterxml.jackson.databind.json.JsonMapper;
import liquibase.*;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import xiaozhi.modules.conversation.*;
import xiaozhi.modules.support.*;
import xiaozhi.modules.supportmedia.MediaModels.*;
import xiaozhi.modules.supportmedia.livekit.LiveKitAdapter;

/** Only a newly created isolated MySQL schema; no existing business rows are cleared. */
@EnabledIfEnvironmentVariable(named="APP_CALL_TEST_JDBC_URL",matches=".+")
class AppOutgoingCallPersistenceTest {
    static JdbcTemplate db; static MediaService media; static SupportService support; static ConversationService conversations;
    static final Consent VIDEO=new Consent(true,true,false);
    @BeforeAll static void setup() throws Exception {
        var source=new DriverManagerDataSource(System.getenv("APP_CALL_TEST_JDBC_URL"),"root",System.getenv("APP_CALL_TEST_DB_PASSWORD"));
        db=new JdbcTemplate(source);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Long.class));
        db.execute("CREATE TABLE sys_user(id BIGINT PRIMARY KEY,status TINYINT NOT NULL,super_admin TINYINT NOT NULL DEFAULT 0,username VARCHAR(80))");
        db.execute("CREATE TABLE ai_agent(id VARCHAR(32) PRIMARY KEY,user_id BIGINT)");
        for(long id:new long[]{7,8,9,10,71,72,73,74,75,76})db.update("INSERT INTO sys_user(id,status,username) VALUES(?,1,?)",id,"call-fixture-"+id);
        for(String name:List.of("conversation","support","support-service-types","support-service-presence","attachments","annotations","support-media","support-media-source-binding"))
            try(var connection=source.getConnection();var migration=new Liquibase("db/changelog/"+name+".changelog.yaml",new ClassLoaderResourceAccessor(),new JdbcConnection(connection))){migration.update(new Contexts());}
        for(long id:new long[]{71,72,73,74,75,76})db.update("INSERT INTO support_agent_profile(user_id,role,roles_json,capacity_text,expert_accepting,expert_presence_expires_at) VALUES(?,'expert',JSON_ARRAY('expert'),2,1,DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 1 HOUR))",id);
        var manager=new DataSourceTransactionManager(source);var dao=new ConversationDao(db);var json=JsonMapper.builder().build();
        conversations=new ConversationService(dao,json,manager);support=new SupportService(db,json,manager,conversations);
        var livekit=mock(LiveKitAdapter.class);when(livekit.configured()).thenReturn(true);
        var store=new MediaStore(db,manager);media=new MediaService(store,new MediaPolicy(store,dao),livekit,dao,json,new MockEnvironment().withProperty("features.support-media-enabled","true"));
    }
    String queue(long owner,long agent){var c=conversations.create(owner,new ConversationModels.CreateConversation("outgoing-call fixture",null));return support.handoff(owner,c.id(),new SupportModels.Handoff(UUID.randomUUID().toString(),"text",agent,"expert")).requestId();}
    Create body(String request,String client){return new Create(request,client,"video",VIDEO);}
    String client(){return UUID.randomUUID().toString();}
    @Test void onlineQueuedExpertReceivesOriginalConversationCallAndCancelKeepsText(){
        String request=queue(7,71);var call=media.dial(7L,body(request,client()));assertEquals("invited",call.status());var session=call.session();
        assertEquals("active",support.get(7L,request).status());assertEquals("HUMAN_ACTIVE",conversations.get(7L,session.conversationId()).state());
        var incoming=media.invitations(71L,0,50).items().stream().filter(i->i.id().equals(session.id())).findFirst().orElseThrow();
        assertEquals("7",incoming.fromUserId());assertEquals("71",incoming.toUserId());assertEquals("video",incoming.mode());assertEquals(request,incoming.requestId());
        assertThrows(ConversationProblem.class,()->media.token(7L,session.id(),new TokenRequest(session.controlEpoch(),1)));
        assertEquals("canceled",media.cancel(7L,session.id(),new Epoch(session.controlEpoch())).status());
        assertEquals("active",support.get(7L,request).status());assertTrue(media.invitations(71L,0,50).items().stream().noneMatch(i->i.id().equals(session.id())));
    }
    @Test void offlineAndBusyReturnNormalDialResultsWithoutPromotingQueue(){
        db.update("UPDATE support_agent_profile SET expert_accepting=0 WHERE user_id=72");String request=queue(8,72);
        assertEquals("offline",media.dial(8L,body(request,client())).status());assertEquals("queued",support.get(8L,request).status());
        db.update("UPDATE support_agent_profile SET expert_accepting=1 WHERE user_id=72");var first=media.dial(8L,body(request,client())).session();
        String other=queue(9,72);assertEquals("busy",media.dial(9L,body(other,client())).status());assertEquals("queued",support.get(9L,other).status());
        media.reject(72L,first.id(),new Epoch(first.controlEpoch()));assertEquals("active",support.get(8L,request).status());
    }
    @Test void repeatedAndConcurrentDialCreatesOneAssignmentAndOneInvitation() throws Exception {
        String request=queue(9,73),client=client();Set<String> ids=new HashSet<>();
        try(var executor=Executors.newFixedThreadPool(4)){
            var tasks=new ArrayList<Callable<Session>>();for(int i=0;i<8;i++)tasks.add(()->media.create(9L,body(request,client)));
            for(var result:executor.invokeAll(tasks))ids.add(result.get().id());
        }
        assertEquals(1,ids.size());assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM support_assignment WHERE request_id=?",Integer.class,request));
        db.update("UPDATE support_agent_profile SET expert_accepting=0 WHERE user_id=73");assertEquals(ids.iterator().next(),media.dial(9L,body(request,client)).session().id());
        var session=media.get(9L,ids.iterator().next());media.cancel(9L,session.id(),new Epoch(session.controlEpoch()));
    }
    @Test void capacityAndRoleSpecificPresenceAreAuthoritativeAndForeignOwnerCannotDial(){
        db.update("UPDATE support_agent_profile SET roles_json=JSON_ARRAY('expert','support_agent'),accepting=1,presence_expires_at=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 1 HOUR),expert_accepting=0 WHERE user_id=74");
        String request=queue(10,74);assertEquals("offline",media.dial(10L,body(request,client())).status());
        assertThrows(ConversationProblem.class,()->media.dial(7L,body(request,client())));
        db.update("UPDATE support_agent_profile SET expert_accepting=1,capacity_text=1 WHERE user_id=74");support.claim(74L,queue(7,74));
        assertEquals("busy",media.dial(10L,body(request,client())).status());assertEquals("queued",support.get(10L,request).status());
    }
    @Test void acceptedCallAndRacingCancelUseTheSameEpochAndNeverAutoAccept(){
        String request=queue(10,75);var invited=media.dial(10L,body(request,client())).session();
        assertEquals("invited",media.get(10L,invited.id()).status());var accepted=media.accept(75L,invited.id(),new Accept(invited.controlEpoch(),VIDEO));
        assertEquals("connecting",accepted.status());assertThrows(ConversationProblem.class,()->media.cancel(10L,invited.id(),new Epoch(invited.controlEpoch())));
        media.end(10L,invited.id(),new End(accepted.controlEpoch(),"fixture_end"));assertEquals("active",support.get(10L,request).status());
    }
}
