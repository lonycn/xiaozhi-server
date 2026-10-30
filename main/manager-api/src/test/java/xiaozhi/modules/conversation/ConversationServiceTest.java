package xiaozhi.modules.conversation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import xiaozhi.modules.conversation.ConversationModels.*;

class ConversationServiceTest {
    private final ConversationDao dao = mock(ConversationDao.class);
    private final ObjectMapper json = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private final ConversationService service = new ConversationService(dao, json, transactionManager());
    private static final String ID = "a".repeat(32);

    private static PlatformTransactionManager transactionManager() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return manager;
    }

    static MessagePart text(String value) {
        return new MessagePart("text", value, null, null, null, null, null, null);
    }

    @Test
    void rejectsMissingIdentityBeforeAccessingStorage() {
        assertEquals(401, assertThrows(ConversationProblem.class,
                () -> service.create(null, new CreateConversation("test", null))).status());
        verifyNoInteractions(dao);
    }

    @Test
    void checksAgentOwnershipInsteadOfAcceptingClientAgent() {
        when(dao.ownsAgent("another-agent", 7)).thenReturn(false);
        assertEquals(403, assertThrows(ConversationProblem.class,
                () -> service.create(7L, new CreateConversation("test", "another-agent"))).status());
        verify(dao, never()).insertConversation(any(), anyLong(), any(), any());
    }

    @Test
    void rejectsSpoofedIdentitySenderAndUnverifiedProjectContext() throws Exception {
        for (String payload : List.of(
                "{\"title\":\"test\",\"tenant_id\":\"other\"}",
                "{\"title\":\"test\",\"owner_user_id\":8}",
                "{\"title\":\"test\",\"project_id\":\"other\"}")) {
            assertThrows(ConversationProblem.class, () -> service.request(json.readTree(payload), CreateConversation.class));
        }
        assertThrows(ConversationProblem.class, () -> service.request(json.readTree("""
                {"client_message_id":"a","sender_type":"ai","parts":[{"type":"text","text":"test"}]}
                """), SendMessage.class));
        SendMessage invalidTextAttachment = service.request(json.readTree("""
                {"client_message_id":"a","parts":[{"type":"text","text":"test","attachment_id":"stolen"}]}
                """), SendMessage.class);
        assertThrows(ConversationProblem.class, () -> service.send(7L, ID, invalidTextAttachment));
        verifyNoInteractions(dao);
    }

    @Test
    void rejectsUnverifiedMediaAndInvalidLocationBeforePersistence() {
        assertEquals(415, assertThrows(ConversationProblem.class, () -> service.send(7L, ID,
                new SendMessage("a", List.of(new MessagePart("image", null, null, null, null, null, null, null))))).status());
        for (Double latitude : List.of(91.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(ConversationProblem.class, () -> service.send(7L, ID, new SendMessage("a", List.of(
                    new MessagePart("location", null, latitude, 121.0, "GCJ02", 15.0, "user_shared", Instant.now())))));
        }
        assertThrows(ConversationProblem.class, () -> service.send(7L, ID, new SendMessage("a", List.of(
                new MessagePart("location", null, 31.0, 121.0, "GCJ02", 15.0, "user_shared", null)))));
        assertThrows(ConversationProblem.class, () -> service.send(7L, ID,
                new SendMessage("a", List.of(text("x".repeat(8001))))));
        verifyNoInteractions(dao);
    }

    @Test
    void limitsCursorPages() {
        assertThrows(ConversationProblem.class, () -> service.list(7L, -1, 50));
        assertThrows(ConversationProblem.class, () -> service.list(7L, 0, 101));
        assertThrows(ConversationProblem.class, () -> service.list(7L, 0, 0));
        verifyNoInteractions(dao);
    }
}
