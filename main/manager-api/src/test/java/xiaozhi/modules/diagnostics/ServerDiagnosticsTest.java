package xiaozhi.modules.diagnostics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import xiaozhi.modules.security.user.SecurityUser;

class ServerDiagnosticsTest {
    @Test void flagsDisabledDoNotQueryOptionalTables() {
        var db=mock(JdbcTemplate.class);when(db.queryForObject("SELECT 1",Integer.class)).thenReturn(1);
        var result=new ServerDiagnosticsService(db,new MockEnvironment()).snapshot();
        assertEquals("limited",result.get("state"));assertEquals("database_and_job_ledgers",result.get("readiness_scope"));
        verify(db,times(1)).queryForObject(anyString(),eq(Integer.class));verifyNoMoreInteractions(db);
    }
    @Test void missingEnabledTableIsUnknownAndNeverReportedZero() {
        var db=mock(JdbcTemplate.class);when(db.queryForObject("SELECT 1",Integer.class)).thenReturn(1);
        when(db.queryForMap(anyString())).thenThrow(new org.springframework.jdbc.BadSqlGrammarException("fixture","query",new java.sql.SQLException()));
        var env=new MockEnvironment().withProperty("features.conversation-enabled","true").withProperty("features.ai-text-enabled","true");
        var result=new ServerDiagnosticsService(db,env).snapshot();assertEquals("degraded",result.get("state"));
        var checks=(Map<?,?>)result.get("checks");assertEquals(Map.of("state","unknown"),checks.get("ai_runs"));
        assertEquals(Map.of("state","disabled"),checks.get("attachment_jobs"));
    }
    @Test void expiredLeasesAndGroupedToolFailuresAreVisible() {
        var db=mock(JdbcTemplate.class);when(db.queryForObject("SELECT 1",Integer.class)).thenReturn(1);
        when(db.queryForMap(anyString())).thenReturn(Map.of("active",3L,"expired",1L));
        when(db.queryForList(contains("agent_tool_audit"))).thenReturn(List.of(
            Map.of("state","failed","safe_code","TOOL_EXECUTION_FAILED","total",2L),
            Map.of("state","discarded","safe_code","OTHER","total",1L)));
        var env=new MockEnvironment().withProperty("features.conversation-enabled","true").withProperty("features.ai-text-enabled","true").withProperty("features.agent-tools-enabled","true");
        var result=new ServerDiagnosticsService(db,env).snapshot();assertEquals("degraded",result.get("state"));
        var tools=(Map<?,?>)((Map<?,?>)result.get("checks")).get("tool_failures_60m");
        assertEquals(2L,tools.get("failed"));assertEquals(1L,tools.get("discarded"));
    }
    @Test void adminOnlyAndDatabaseFailureIsUnknownNoStore() {
        var service=mock(ServerDiagnosticsService.class);var controller=new ServerDiagnosticsController(service);var req=new MockHttpServletRequest();
        try(var security=mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUserId).thenReturn(3L);
            when(service.administrator(3)).thenReturn(false);
            assertEquals(403,controller.readiness(req).getStatusCode().value());verify(service,never()).snapshot();
            when(service.administrator(3)).thenReturn(true);when(service.snapshot()).thenThrow(new RuntimeException("private secret"));
            var response=controller.readiness(req);assertEquals(503,response.getStatusCode().value());assertEquals("no-store",response.getHeaders().getCacheControl());
            assertFalse(response.toString().contains("private secret"));assertEquals("degraded",((Map<?,?>)response.getBody().getData()).get("state"));
            req.addHeader("Authorization","Bearer xext_abcdef");assertEquals(403,controller.readiness(req).getStatusCode().value());
        }
    }
}
