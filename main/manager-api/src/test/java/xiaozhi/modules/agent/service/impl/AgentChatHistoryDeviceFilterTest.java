package xiaozhi.modules.agent.service.impl;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import xiaozhi.modules.agent.dao.AiAgentChatHistoryDao;
import xiaozhi.modules.agent.entity.AgentChatHistoryEntity;
import xiaozhi.modules.agent.service.AgentChatTitleService;
@SuppressWarnings({"unchecked", "rawtypes"})
class AgentChatHistoryDeviceFilterTest {
    private AiAgentChatHistoryDao dao;
    private AgentChatHistoryServiceImpl service;
    @BeforeEach void setup() {
        dao = mock(AiAgentChatHistoryDao.class);
        service = new AgentChatHistoryServiceImpl(mock(AgentChatTitleService.class));
        ReflectionTestUtils.setField(service, "baseMapper", dao);
    }
    @Test void filtersDeviceBeforeGroupingAndPaginationWhileKeepingAgentScope() {
        doReturn(new Page<Map<String, Object>>(1, 20)).when(dao).selectMapsPage(any(), any());
        service.getSessionListByAgentId(Map.of("agentId", "agent-a", "page", 1, "limit", 20, "macAddress", "02:AA:BB:CC:DD:EE"));
        ArgumentCaptor<QueryWrapper<AgentChatHistoryEntity>> cap = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(dao).selectMapsPage(any(), cap.capture());
        String sql = cap.getValue().getCustomSqlSegment();
        assertTrue(sql.indexOf("mac_address =") < sql.indexOf("GROUP BY"));
        assertTrue(sql.contains("agent_id ="));
        assertTrue(cap.getValue().getParamNameValuePairs().values().containsAll(List.of("agent-a", "02:AA:BB:CC:DD:EE")));
    }
    @Test void allDevicesDoesNotAddAnEmptyMacPredicate() {
        doReturn(new Page<Map<String, Object>>(1, 20)).when(dao).selectMapsPage(any(), any());
        service.getSessionListByAgentId(Map.of("agentId", "agent-a", "page", 1, "limit", 20, "macAddress", ""));
        ArgumentCaptor<QueryWrapper<AgentChatHistoryEntity>> cap = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(dao).selectMapsPage(any(), cap.capture());
        assertFalse(cap.getValue().getCustomSqlSegment().contains("mac_address ="));
    }
    @Test void messageReadsKeepAgentSessionAndDeviceScope() {
        when(dao.selectList(any())).thenReturn(List.of());
        assertTrue(service.getChatHistoryBySessionId("agent-a", "session-a", "device-a").isEmpty());
        ArgumentCaptor<QueryWrapper<AgentChatHistoryEntity>> cap = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(dao).selectList(cap.capture());
        String sql = cap.getValue().getCustomSqlSegment();
        assertTrue(sql.contains("agent_id =")); assertTrue(sql.contains("session_id =")); assertTrue(sql.contains("mac_address ="));
        assertTrue(cap.getValue().getParamNameValuePairs().values().containsAll(List.of("agent-a", "session-a", "device-a")));
    }
    @Test void deviceChoicesComeFromAllAgentHistoryIncludingUnboundDevices() {
        doReturn(List.of("device-a", "device-b")).when(dao).selectObjs(any());
        assertEquals(List.of("device-a", "device-b"), service.getHistoryDevicesByAgentId("agent-a"));
        ArgumentCaptor<QueryWrapper<AgentChatHistoryEntity>> cap = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(dao).selectObjs(cap.capture());
        assertEquals("DISTINCT mac_address", cap.getValue().getSqlSelect());
        assertTrue(cap.getValue().getCustomSqlSegment().contains("agent_id ="));
        assertTrue(cap.getValue().getCustomSqlSegment().contains("mac_address IS NOT NULL"));
    }
}
