package xiaozhi.modules.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Semaphore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import xiaozhi.modules.conversation.*;
import xiaozhi.modules.conversation.ConversationModels.*;
import xiaozhi.modules.security.user.SecurityUser;

class AiStreamFailureTest {
    private static final String ID = "a".repeat(32);
    private final AiExecutionService execution = mock(AiExecutionService.class);
    private final ConversationService conversations = mock(ConversationService.class);
    private final ObjectMapper json = new ObjectMapper();
    private final AiController controller = new AiController(execution, conversations, json);

    private static MockHttpServletRequest request() {
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer synthetic-session");
        return request;
    }
    private static CursorPage<Event> first() {
        var event = new Event("event",ID,7,"assistant.text.delta",new ObjectMapper().createObjectNode()
                .put("text","partial"),Instant.parse("2026-10-30T00:00:00Z"));
        // More data skips the idle wait, making the failing second read fully deterministic.
        return new CursorPage<>(List.of(event),7,true);
    }
    private int availablePermits() throws Exception {
        var field = AiController.class.getDeclaredField("streams"); field.setAccessible(true);
        return ((Semaphore)field.get(controller)).availablePermits();
    }
    @Test void initialTransientReadProducesJson503BeforeCommittingSse() throws Exception {
        when(conversations.events(7L,ID,4,100)).thenThrow(new TransientDataAccessResourceException("synthetic timeout"));
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ConversationExceptionHandler()).build();
        try(var security=mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUserId).thenReturn(7L);
            mvc.perform(get("/api/v1/ai/conversations/"+ID+"/stream?after=4")
                    .header("Authorization","Bearer synthetic-session").accept(MediaType.TEXT_EVENT_STREAM))
                    .andExpect(status().isServiceUnavailable()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.code").value(503)).andExpect(jsonPath("$.msg").value("STREAM_UNAVAILABLE"));
        }
        assertEquals(16,availablePermits());
    }
    @Test void initialRecoverableAuthorizationReadAlsoMapsTo503() throws Exception {
        doThrow(new RecoverableDataAccessException("synthetic connection failure")).when(execution).authorizeStream(7L);
        var response = new MockHttpServletResponse();
        try(var security=mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUserId).thenReturn(7L);
            var error=assertThrows(ConversationProblem.class,()->controller.stream(ID,4,null,request(),response));
            assertEquals(503,error.status()); assertEquals("STREAM_UNAVAILABLE",error.getMessage());
        }
        assertFalse(response.isCommitted()); assertNull(response.getContentType());
        verifyNoInteractions(conversations); assertEquals(16,availablePermits());
    }
    @Test void failedSecondBatchEndsWithLastFlushedCursorAndReleasesCapacity() throws Exception {
        when(conversations.events(7L,ID,4,100)).thenReturn(first());
        when(conversations.events(7L,ID,7,100)).thenThrow(new TransientDataAccessResourceException("synthetic timeout"));
        var response = new MockHttpServletResponse();
        try(var security=mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUserId).thenReturn(7L);
            controller.stream(ID,4,null,request(),response);
        }
        assertTrue(response.isCommitted()); assertEquals(200,response.getStatus());
        assertTrue(response.getContentAsString().contains("id: 7\n"));
        assertFalse(response.getContentAsString().contains("STREAM_UNAVAILABLE"));
        verify(execution,times(2)).authorizeStream(7L);
        verify(conversations).events(7L,ID,7,100); assertEquals(16,availablePermits());
    }
    @Test void failedBatchAuthorizationEndsCleanlyWithoutReadingMoreEvents() throws Exception {
        when(conversations.events(7L,ID,4,100)).thenReturn(first());
        doNothing().doThrow(new TransientDataAccessResourceException("synthetic timeout")).when(execution).authorizeStream(7L);
        var response = new MockHttpServletResponse();
        try(var security=mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUserId).thenReturn(7L);
            controller.stream(ID,4,null,request(),response);
        }
        assertTrue(response.isCommitted()); verify(conversations,times(1)).events(7L,ID,4,100);
        verifyNoMoreInteractions(conversations); assertEquals(16,availablePermits());
    }
    @Test void programmingErrorsRemainVisibleAndStillReleaseCapacity() throws Exception {
        when(conversations.events(7L,ID,4,100)).thenReturn(first());
        var bug = new IllegalStateException("synthetic programming error");
        when(conversations.events(7L,ID,7,100)).thenThrow(bug);
        try(var security=mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUserId).thenReturn(7L);
            assertSame(bug,assertThrows(IllegalStateException.class,()->controller.stream(ID,4,null,request(),new MockHttpServletResponse())));
        }
        assertEquals(16,availablePermits());
    }
    @Test void revokedBatchGrantStillEndsStreamCleanly() throws Exception {
        when(conversations.events(7L,ID,4,100)).thenReturn(first());
        when(conversations.events(7L,ID,7,100)).thenThrow(new ConversationProblem(404,"CONVERSATION_NOT_FOUND"));
        try(var security=mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUserId).thenReturn(7L);
            controller.stream(ID,4,null,request(),new MockHttpServletResponse());
        }
        verify(execution,times(2)).authorizeStream(7L); assertEquals(16,availablePermits());
    }
}
