package xiaozhi.modules.conversation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.apache.shiro.authc.AuthenticationException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import xiaozhi.common.utils.HttpContextUtils;
import xiaozhi.common.utils.MessageUtils;
import xiaozhi.modules.security.config.ShiroConfig;
import xiaozhi.modules.security.oauth2.ConversationOauth2Filter;

class ConversationAuthenticationTest {
    static class Filter extends ConversationOauth2Filter {
        boolean missing(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
            return onAccessDenied(request, response);
        }
        boolean invalid(MockHttpServletRequest request, MockHttpServletResponse response) {
            return onLoginFailure(null, new AuthenticationException(), request, response);
        }
    }

    @Test
    void missingAndInvalidTokensReturnHttp401() throws Exception {
        try (var messages = mockStatic(MessageUtils.class); var context = mockStatic(HttpContextUtils.class)) {
            context.when(HttpContextUtils::getOrigin).thenReturn("http://localhost");
            var request = new MockHttpServletRequest();
            request.setRequestURI("/api/v1/ai/conversations");
            var missing = new MockHttpServletResponse();
            assertFalse(new Filter().missing(request, missing));
            assertEquals(401, missing.getStatus());
            var invalid = new MockHttpServletResponse();
            assertFalse(new Filter().invalid(request, invalid));
            assertEquals(401, invalid.getStatus());
        }
    }

    @Test
    void onlyNewAiRoutesUseNewAuthenticationResponseSemantics() {
        var filters = ShiroConfig.shirFilter(mock(org.apache.shiro.web.mgt.WebSecurityManager.class),
                mock(xiaozhi.modules.sys.service.SysParamsService.class));
        var chain = filters.getFilterChainDefinitionMap();
        assertEquals("conversation", chain.get("/api/v1/ai/**"));
        assertEquals("server", chain.get("/agent/chat-history/report"));
        assertEquals("oauth2", chain.get("/**"));
        assertTrue(new java.util.ArrayList<>(chain.keySet()).indexOf("/api/v1/ai/**")
                < new java.util.ArrayList<>(chain.keySet()).indexOf("/**"));
    }
}
