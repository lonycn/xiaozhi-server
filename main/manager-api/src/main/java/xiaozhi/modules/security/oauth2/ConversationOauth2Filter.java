package xiaozhi.modules.security.oauth2;

import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationToken;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;

/** Reuse token authentication while giving the new API real HTTP 401 responses. */
public class ConversationOauth2Filter extends Oauth2Filter {
    @Override
    protected boolean onAccessDenied(ServletRequest request, ServletResponse response) throws Exception {
        boolean allowed = super.onAccessDenied(request, response);
        if (!allowed && ((HttpServletResponse) response).getStatus() < 400) {
            ((HttpServletResponse) response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        }
        return allowed;
    }

    @Override
    protected boolean onLoginFailure(AuthenticationToken token, AuthenticationException error,
            ServletRequest request, ServletResponse response) {
        ((HttpServletResponse) response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        return super.onLoginFailure(token, error, request, response);
    }
}
