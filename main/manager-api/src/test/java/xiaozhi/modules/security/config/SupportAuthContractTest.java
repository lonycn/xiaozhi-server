package xiaozhi.modules.security.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.Map;
import org.apache.shiro.spring.web.ShiroFilterFactoryBean;
import org.apache.shiro.web.mgt.WebSecurityManager;
import org.junit.jupiter.api.Test;
import xiaozhi.modules.security.oauth2.ConversationOauth2Filter;
import xiaozhi.modules.sys.service.SysParamsService;

class SupportAuthContractTest {
    @Test
    void supportUsesConversationFilterBeforeCatchAll() {
        ShiroFilterFactoryBean factory = ShiroConfig.shirFilter(
                mock(WebSecurityManager.class), mock(SysParamsService.class));
        Map<String, String> chain = factory.getFilterChainDefinitionMap();
        assertEquals("conversation", chain.get("/api/v1/support/**"));
        assertInstanceOf(ConversationOauth2Filter.class, factory.getFilters().get("conversation"));
        var paths = new ArrayList<>(chain.keySet());
        assertTrue(paths.indexOf("/api/v1/support/**") < paths.indexOf("/**"));
    }
    @Test void signedRenewalAndAppContractsUseTheirExactAuthenticationBoundaries() {
        var factory=ShiroConfig.shirFilter(mock(WebSecurityManager.class),mock(SysParamsService.class));
        var chain=factory.getFilterChainDefinitionMap();
        assertEquals("anon",chain.get("/api/v1/external/identity/renew"));
        assertEquals("conversation",chain.get("/api/v1/app/capabilities"));
        assertEquals("conversation",chain.get("/api/v1/admin/diagnostics/readiness"));
        var paths=new ArrayList<>(chain.keySet());
        assertTrue(paths.indexOf("/api/v1/external/identity/renew")<paths.indexOf("/api/v1/external/**"));
        assertTrue(paths.indexOf("/api/v1/app/capabilities")<paths.indexOf("/**"));
    }
}
