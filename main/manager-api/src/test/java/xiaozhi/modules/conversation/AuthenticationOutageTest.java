package xiaozhi.modules.conversation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.apache.shiro.authc.AuthenticationException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import xiaozhi.common.utils.HttpContextUtils;
import xiaozhi.common.utils.MessageUtils;
import xiaozhi.modules.security.oauth2.*;

class AuthenticationOutageTest {
    static class Legacy extends Oauth2Filter {
        boolean fail(Throwable error, MockHttpServletResponse response) {
            return onLoginFailure(null, new AuthenticationException(error), new MockHttpServletRequest(), response);
        }
    }
    static class Conversation extends ConversationOauth2Filter {
        @Override protected boolean executeLogin(ServletRequest request, ServletResponse response) {
            return onLoginFailure(null, new AuthenticationException(new CannotGetJdbcConnectionException("private diagnostic")), request, response);
        }
        boolean authenticate(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
            return onAccessDenied(request,response);
        }
    }
    @Test void wrappedJdbcOutageIs503AndDoesNotLeakDependencyDiagnostic() throws Exception {
        var response=new MockHttpServletResponse();
        assertFalse(new Legacy().fail(new RuntimeException(new CannotGetJdbcConnectionException("private diagnostic")),response));
        assertEquals(503,response.getStatus());assertEquals("2",response.getHeader("Retry-After"));
        assertTrue(response.getContentAsString().contains("AUTHENTICATION_UNAVAILABLE"));
        assertFalse(response.getContentAsString().contains("private diagnostic"));
    }
    @Test void conversationDeniedWrapperPreservesDependencyStatus() throws Exception {
        var request=new MockHttpServletRequest();request.addHeader("Authorization","Bearer synthetic-session");
        var response=new MockHttpServletResponse();assertFalse(new Conversation().authenticate(request,response));
        assertEquals(503,response.getStatus());
    }
    @Test void realInvalidCredentialsKeepLegacyResponseContract() throws Exception {
        try(var messages=mockStatic(MessageUtils.class);var context=mockStatic(HttpContextUtils.class)) {
            context.when(HttpContextUtils::getOrigin).thenReturn("http://localhost");
            var response=new MockHttpServletResponse();assertFalse(new Legacy().fail(new AuthenticationException(),response));
            assertEquals(200,response.getStatus());assertFalse(response.getContentAsString().contains("AUTHENTICATION_UNAVAILABLE"));
        }
    }
    @Test void redisAndSqlTransientConnectionFailuresAreRetryable() {
        for(Throwable failure:java.util.List.of(new org.springframework.data.redis.RedisConnectionFailureException("test"),
                new java.sql.SQLTransientConnectionException("test"),new java.sql.SQLRecoverableException("test"))) {
            var response=new MockHttpServletResponse();new Legacy().fail(failure,response);assertEquals(503,response.getStatus());
        }
    }
}
