package xiaozhi.modules.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import xiaozhi.modules.attachment.AttachmentService;
import xiaozhi.modules.conversation.ConversationProblem;

class AiMediaLeaseTest {
    static final String RUN="a".repeat(32), CONVERSATION="b".repeat(32), INPUT="c".repeat(32), TOKEN="d".repeat(64);
    static class Fixture {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        PlatformTransactionManager transactions=mock(PlatformTransactionManager.class);
        AiAttachmentContext attachments=mock(AiAttachmentContext.class);
        AtomicBoolean active=new AtomicBoolean();
        Map<String,Object> run=new HashMap<>(Map.of("id",RUN,"conversation_id",CONVERSATION,"input_message_id",INPUT,
                "status","running","lease_token",TOKEN,"control_epoch",1L));
        Map<String,Object> conversation=new HashMap<>(Map.of("state","AI_ACTIVE","control_epoch",1L,"owner_user_id",1L));
        AiExecutionService service=new AiExecutionService(jdbc,new ObjectMapper(),transactions);
        Fixture() {
            service.setEnabled(true);service.setAttachments(attachments);
            when(transactions.getTransaction(any(TransactionDefinition.class))).thenAnswer(i->{active.set(true);return null;});
            doAnswer(i->{active.set(false);return null;}).when(transactions).commit(any());
            doAnswer(i->{active.set(false);return null;}).when(transactions).rollback(any());
            when(jdbc.queryForList("SELECT * FROM ai_run WHERE id=?",RUN)).thenReturn(List.of(run));
            when(jdbc.queryForList("SELECT * FROM ai_run WHERE id=? FOR UPDATE",RUN)).thenReturn(List.of(run));
            when(jdbc.queryForList("SELECT * FROM ai_conversation WHERE id=? FOR UPDATE",CONVERSATION)).thenReturn(List.of(conversation));
            when(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=? AND status=1",Long.class,1L)).thenReturn(1L);
            when(jdbc.queryForObject("SELECT lease_expires_at>CURRENT_TIMESTAMP(3) FROM ai_run WHERE id=?",Boolean.class,RUN)).thenReturn(true);
            when(jdbc.queryForMap("SELECT seq,parts_json FROM ai_message WHERE id=?",INPUT))
                .thenReturn(Map.of("seq",4L,"parts_json","[{\"type\":\"image\",\"attachment_id\":\"123\"}]"));
            when(jdbc.queryForObject("SELECT seq FROM ai_message WHERE id=?",Long.class,INPUT)).thenReturn(4L);
            when(attachments.plan(eq(CONVERSATION),eq(4L),any())).thenReturn(new AiAttachmentContext.Plan(
                List.of(new AiModels.MediaRef("123","display","image/jpeg","image",null)),false,true,null));
        }
    }
    @Test void streamIsOpenedAfterLeaseAuthorizationTransactionHasFinished() throws Exception {
        var f=new Fixture();
        when(f.attachments.content("123",CONVERSATION,4,"display")).thenAnswer(i->{
            assertFalse(f.active.get());return new AttachmentService.Content(new ByteArrayInputStream(new byte[]{1}),1,"image/jpeg","display.jpg",false);});
        try(var content=f.service.media(RUN,"123","display",TOKEN)) {assertEquals(1,content.size());}
        verify(f.attachments).content("123",CONVERSATION,4,"display");
    }
    @Test void revokedModeEpochLeaseAndAccountCannotOpenAnyStream() {
        for(String condition:List.of("mode","epoch","token","expiry","account")) {
            var f=new Fixture();
            switch(condition) {
                case "mode" -> f.conversation.put("state","HUMAN_ACTIVE");
                case "epoch" -> f.conversation.put("control_epoch",2L);
                case "token" -> f.run.put("lease_token","e".repeat(64));
                case "expiry" -> when(f.jdbc.queryForObject("SELECT lease_expires_at>CURRENT_TIMESTAMP(3) FROM ai_run WHERE id=?",Boolean.class,RUN)).thenReturn(false);
                case "account" -> when(f.jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=? AND status=1",Long.class,1L)).thenReturn(0L);
            }
            assertEquals(409,assertThrows(ConversationProblem.class,()->f.service.media(RUN,"123","display",TOKEN)).status());
            verifyNoInteractions(f.attachments);
        }
    }
    @Test void originalVariantAndAnotherAttachmentCannotBeFetched() {
        for(String[] request:List.of(new String[]{"123","original"},new String[]{"456","display"})) {
            var f=new Fixture();
            assertEquals(404,assertThrows(ConversationProblem.class,()->f.service.media(RUN,request[0],request[1],TOKEN)).status());
            verify(f.attachments,never()).content(anyString(),anyString(),anyLong(),anyString());
        }
    }
    @Test void waitingTranscriptionDoesNotConsumeAiClaimAttempt() {
        var f=new Fixture();f.run.put("status","queued");f.run.put("attempt",0L);
        when(f.jdbc.queryForList(AiExecutionService.DISCOVERY_QUERY,0L))
            .thenReturn(List.of(Map.of("id",RUN,"conversation_id",CONVERSATION,"list_sequence",32L)));
        when(f.attachments.plan(eq(CONVERSATION),eq(4L),any())).thenReturn(new AiAttachmentContext.Plan(List.of(),true,false,null));
        assertNull(f.service.claim(new AiModels.Claim("worker")));
        verify(f.jdbc,never()).update(anyString(),any(Object[].class));
        String later="e".repeat(32);
        when(f.jdbc.queryForList(AiExecutionService.DISCOVERY_QUERY,32L))
            .thenReturn(List.of(Map.of("id",later,"conversation_id",CONVERSATION,"list_sequence",33L)));
        when(f.jdbc.queryForList("SELECT * FROM ai_run WHERE id=? FOR UPDATE",later))
            .thenReturn(List.of(Map.of("id",later,"status","completed")));
        assertNull(f.service.claim(new AiModels.Claim("worker")));
        verify(f.jdbc).queryForList(AiExecutionService.DISCOVERY_QUERY,32L);
        verify(f.jdbc).queryForList("SELECT * FROM ai_run WHERE id=? FOR UPDATE",later);
    }
    @Test void discoveryRewindsForOldExpiredRunAndRecoversAfterEmptyQueue() {
        var f=new Fixture();f.run.put("attempt",0L);
        var candidate=Map.<String,Object>of("id",RUN,"conversation_id",CONVERSATION,"list_sequence",32L);
        when(f.jdbc.queryForObject("SELECT lease_expires_at>CURRENT_TIMESTAMP(3) FROM ai_run WHERE id=?",Boolean.class,RUN)).thenReturn(false);
        when(f.jdbc.queryForList(AiExecutionService.DISCOVERY_QUERY,0L)).thenReturn(List.of(candidate));
        when(f.jdbc.queryForList(AiExecutionService.DISCOVERY_QUERY,32L)).thenReturn(List.of());
        when(f.attachments.plan(eq(CONVERSATION),eq(4L),any())).thenReturn(new AiAttachmentContext.Plan(List.of(),true,false,null));
        assertNull(f.service.claim(new AiModels.Claim("worker")));
        assertNull(f.service.claim(new AiModels.Claim("worker")));
        verify(f.jdbc,times(2)).queryForList("SELECT * FROM ai_run WHERE id=? FOR UPDATE",RUN);
        when(f.jdbc.queryForList(AiExecutionService.DISCOVERY_QUERY,0L)).thenReturn(List.of());
        assertNull(f.service.claim(new AiModels.Claim("worker")));
        when(f.jdbc.queryForList(AiExecutionService.DISCOVERY_QUERY,0L)).thenReturn(List.of(candidate));
        assertNull(f.service.claim(new AiModels.Claim("worker")));
        verify(f.jdbc,times(3)).queryForList("SELECT * FROM ai_run WHERE id=? FOR UPDATE",RUN);
        verify(f.jdbc,never()).update(anyString(),any(Object[].class));
    }

}
