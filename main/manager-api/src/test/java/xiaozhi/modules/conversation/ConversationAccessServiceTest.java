package xiaozhi.modules.conversation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static xiaozhi.modules.conversation.ConversationServiceTest.text;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import xiaozhi.modules.conversation.ConversationDao.StoredMessage;
import xiaozhi.modules.conversation.ConversationModels.*;

class ConversationAccessServiceTest {
    private static final String ID = "a".repeat(32);
    private final ConversationDao dao = mock(ConversationDao.class);
    private final ConversationAccessService access = new ConversationAccessService(dao);
    private final ConversationService service;
    private final Conversation conversation = new Conversation(ID, 1, null, "title", "HUMAN_ACTIVE", 4, 8,
            Instant.EPOCH, Instant.EPOCH);

    ConversationAccessServiceTest() {
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new ConversationService(dao, JsonMapper.builder().addModule(new JavaTimeModule()).build(), manager);
        when(dao.owned(eq(ID), eq(71L), anyBoolean())).thenThrow(new ConversationProblem(404, "CONVERSATION_NOT_FOUND"));
        when(dao.byId(eq(ID), anyBoolean())).thenReturn(conversation);
    }

    @Test
    void ownerDoesNotRequireSupportProfile() {
        when(dao.owned(ID, 7, true)).thenReturn(conversation);
        assertTrue(access.read(7, ID, true).owner());
        verify(dao, never()).staffGrant(anyString(), anyLong());
    }

    @Test
    void noAssignmentOrDisabledProfileIsNotAResourceGrant() {
        assertEquals(404, assertThrows(ConversationProblem.class, () -> service.get(71L, ID)).status());
        assertThrows(ConversationProblem.class, () -> service.messages(71L, ID, 0, 10));
        assertThrows(ConversationProblem.class, () -> service.events(71L, ID, 0, 10));
        verify(dao, never()).messages(anyString(), anyLong(), anyInt(), anyLong());
    }

    @Test
    void historicalStaffHasOnlyBoundedLedgerAndNoCurrentMetadata() {
        when(dao.staffGrant(ID, 71)).thenReturn(new ConversationDao.StaffGrant(false, 2, 3));
        when(dao.messages(ID, 0, 11, 2)).thenReturn(List.of());
        when(dao.events(ID, 0, 11, 3)).thenReturn(List.of());
        assertEquals(404, assertThrows(ConversationProblem.class, () -> service.get(71L, ID)).status());
        assertFalse(service.messages(71L, ID, 0, 10).hasMore());
        assertFalse(service.events(71L, ID, 0, 10).hasMore());
        verify(dao).messages(ID, 0, 11, 2);
        verify(dao).events(ID, 0, 11, 3);
        verify(dao, never()).messages(anyString(), anyLong(), anyInt());
    }

    @Test
    void currentStaffReadsFullLedgerAndDetail() {
        when(dao.staffGrant(ID, 71)).thenReturn(new ConversationDao.StaffGrant(true, 0, 0));
        when(dao.messages(ID, 0, 11, Long.MAX_VALUE)).thenReturn(List.of());
        assertEquals(conversation, service.get(71L, ID));
        service.messages(71L, ID, 0, 10);
        verify(dao).messages(ID, 0, 11, Long.MAX_VALUE);
    }

    @Test
    void supportReplyLocksProfileBeforeConversationAndPersistsRealRole() {
        when(dao.lockStaffRole(71)).thenReturn("expert");
        when(dao.currentAssignment(ID, 71)).thenReturn(true);
        when(dao.duplicate(ID, "reply")).thenReturn(null, new StoredMessage("m", ID, 5, "reply", "71",
                "[{\"type\":\"text\",\"text\":\"answer\"}]", "hash", Instant.EPOCH, "expert", "completed"));
        Message message = service.sendSupport(71L, ID, new SendMessage("reply", List.of(text("answer"))), "expert");
        assertEquals("expert", message.senderType());
        assertEquals("completed", message.status());
        InOrder order = inOrder(dao);
        order.verify(dao).lockStaffRole(71);
        order.verify(dao).byId(ID, true);
        order.verify(dao).currentAssignment(ID, 71);
        verify(dao).insertMessage(anyString(), eq(ID), eq(5L), eq("reply"), eq(71L), anyString(),
                anyString(), eq("expert"), eq("completed"));
        verify(dao).insertEvent(anyString(), eq(ID), eq(9L), eq("support.message.created"), anyString());
        verify(dao).advance(ID, 5, 9);
    }

    @Test
    void noLongerAssignedStaffCannotEvenReplayAnOldReply() {
        when(dao.lockStaffRole(71)).thenReturn("expert");
        assertEquals(404, assertThrows(ConversationProblem.class, () -> service.sendSupport(71L, ID,
                new SendMessage("reply", List.of(text("answer"))), "expert")).status());
        verify(dao, never()).duplicate(anyString(), anyString());
    }

    @Test
    void senderRoleCannotBeSelectedByCaller() {
        when(dao.lockStaffRole(71)).thenReturn("support_agent");
        assertEquals(403, assertThrows(ConversationProblem.class, () -> service.sendSupport(71L, ID,
                new SendMessage("reply", List.of(text("answer"))), "expert")).status());
        verify(dao, never()).byId(anyString(), anyBoolean());
    }

    @Test
    void crossActorIdempotencyCollisionDoesNotReturnAnotherSendersMessage() {
        when(dao.lockStaffRole(71)).thenReturn("expert");
        when(dao.currentAssignment(ID, 71)).thenReturn(true);
        // Hash matches the same parts, but the original sender is the owner.
        String parts = "[{\"type\":\"text\",\"text\":\"answer\"}]";
        String hash;
        try {
            hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(parts.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new AssertionError(e); }
        when(dao.duplicate(ID, "reply")).thenReturn(new StoredMessage("m", ID, 1, "reply", "7", parts,
                hash, Instant.EPOCH));
        assertEquals(409, assertThrows(ConversationProblem.class, () -> service.sendSupport(71L, ID,
                new SendMessage("reply", List.of(text("answer"))), "expert")).status());
        verify(dao, never()).advance(anyString(), anyLong(), anyLong());
    }
}
