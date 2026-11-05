package xiaozhi.modules.agent.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import xiaozhi.common.page.PageData;
import xiaozhi.common.utils.MessageUtils;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.common.redis.RedisUtils;
import xiaozhi.modules.agent.dto.AgentChatHistoryDTO;
import xiaozhi.modules.agent.dto.AgentChatSessionDTO;
import xiaozhi.modules.agent.service.AgentChatHistoryService;
import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.agent.service.biz.AgentChatHistoryBizService;

class AgentHistoryDownloadScopeTest {
    private final RedisUtils redis = mock(RedisUtils.class);
    private final AgentChatHistoryService history = mock(AgentChatHistoryService.class);
    private final AgentChatHistoryController controller = new AgentChatHistoryController(mock(AgentChatHistoryBizService.class), history, mock(AgentService.class), redis);
    @Test void deviceBoundGrantFiltersCurrentExport() throws Exception {
        when(redis.get(anyString())).thenReturn("agent:session:" + Base64.getUrlEncoder().withoutPadding().encodeToString("02:AA:BB:CC:DD:EE".getBytes(StandardCharsets.UTF_8)));
        when(history.getChatHistoryBySessionId("agent", "session", "02:AA:BB:CC:DD:EE")).thenReturn(List.of(message()));
        try (var messages = mockStatic(MessageUtils.class)) {
        messages.when(() -> MessageUtils.getMessage(anyInt())).thenReturn("User");
        var response = new MockHttpServletResponse(); controller.downloadCurrentSession("grant", response);
        assertTrue(response.getContentAsString().contains("device-scoped text"));
        verify(history).getChatHistoryBySessionId("agent", "session", "02:AA:BB:CC:DD:EE");
        verify(redis).delete(anyString());
        }
    }
    @Test void existingTwoPartGrantsStillExportAllDevices() {
        when(redis.get(anyString())).thenReturn("agent:session");
        when(history.getChatHistoryBySessionId("agent", "session", null)).thenReturn(List.of());
        controller.downloadCurrentSession("grant", new MockHttpServletResponse());
        verify(history).getChatHistoryBySessionId("agent", "session", null);
    }
    @Test void previousSessionsUseTheSameDeviceScopeForListAndMessages() {
        when(redis.get(anyString())).thenReturn("agent:session:" + Base64.getUrlEncoder().withoutPadding().encodeToString("device-a".getBytes(StandardCharsets.UTF_8)));
        AgentChatSessionDTO current = new AgentChatSessionDTO(); current.setSessionId("session");
        when(history.getSessionListByAgentId(anyMap())).thenReturn(new PageData<>(List.of(current), 1));
        when(history.getChatHistoryBySessionId("agent", "session", "device-a")).thenReturn(List.of());
        controller.downloadCurrentSessionWithPrevious("grant", new MockHttpServletResponse());
        verify(history).getSessionListByAgentId(argThat(params -> "device-a".equals(params.get("macAddress")) && "agent".equals(params.get("agentId"))));
        verify(history).getChatHistoryBySessionId("agent", "session", "device-a");
    }
    @Test void deviceMetadataChecksAgentPermissionBeforeReadingHistory() {
        AgentService agents = mock(AgentService.class);
        AgentController reader = new AgentController(agents, history, null, null, redis, null);
        try (var security = mockStatic(SecurityUser.class); var messages = mockStatic(MessageUtils.class)) {
            messages.when(() -> MessageUtils.getMessage(anyInt())).thenReturn("Permission denied");
            security.when(SecurityUser::getUserId).thenReturn(1L);
            assertThrows(xiaozhi.common.exception.RenException.class, () -> reader.getHistoryDevices("agent"));
            verify(history, never()).getHistoryDevicesByAgentId(anyString());
            when(agents.checkAgentPermission("agent", 1L)).thenReturn(true);
            when(history.getHistoryDevicesByAgentId("agent")).thenReturn(List.of("device-a"));
            assertEquals(List.of("device-a"), reader.getHistoryDevices("agent").getData());
        }
    }
    private AgentChatHistoryDTO message() {
        AgentChatHistoryDTO dto = new AgentChatHistoryDTO(); dto.setCreatedAt(new Date()); dto.setChatType((byte)1); dto.setContent("device-scoped text"); return dto;
    }
}
