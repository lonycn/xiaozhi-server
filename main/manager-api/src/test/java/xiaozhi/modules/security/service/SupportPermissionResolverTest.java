package xiaozhi.modules.security.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.ResultSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

class SupportPermissionResolverTest {
    @Test
    void disabledFeatureNeverQueries() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        assertTrue(new SupportPermissionResolver(jdbc, false).resolve(1L).isEmpty());
        verifyNoInteractions(jdbc);
    }

    @Test
    void grantsOnlyCurrentEnabledProfileAndAdminPermissions() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet row = mock(ResultSet.class);
        doAnswer(call -> {
            ((RowCallbackHandler) call.getArgument(1)).processRow(row);
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
        SupportPermissionResolver resolver = new SupportPermissionResolver(jdbc, true);
        when(row.getInt("super_admin")).thenReturn(1);
        assertEquals(Set.of("support:agents:manage"), resolver.resolve(1L));
        when(row.getInt("super_admin")).thenReturn(0);
        when(row.getInt("enabled")).thenReturn(1);
        when(row.getString("role")).thenReturn("expert");
        assertTrue(resolver.resolve(1L).contains("support:message:send"));
        when(row.getInt("enabled")).thenReturn(0);
        assertTrue(resolver.resolve(1L).isEmpty());
        verify(jdbc, times(3)).query(contains("u.status = 1"), any(RowCallbackHandler.class), any(Object[].class));
    }
}
