package xiaozhi.modules.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.apache.shiro.web.filter.AccessControlFilter;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Dedicated capability; does not authenticate a Shiro subject or grant any user API access. */
public class AiWorkerKeyFilter extends AccessControlFilter {
    private final byte[] key;
    public AiWorkerKeyFilter(String key) { this.key = key.getBytes(StandardCharsets.UTF_8); }
    @Override protected boolean isAccessAllowed(ServletRequest request, ServletResponse response, Object mappedValue) {
        String supplied = ((HttpServletRequest)request).getHeader("X-AI-Worker-Key");
        return key.length >= 32 && supplied != null && supplied.length() <= 512
                && MessageDigest.isEqual(key, supplied.getBytes(StandardCharsets.UTF_8));
    }
    @Override protected boolean onAccessDenied(ServletRequest request, ServletResponse response) throws Exception {
        HttpServletResponse http=(HttpServletResponse)response;
        http.setStatus(401); http.setContentType("application/json;charset=UTF-8");
        http.getWriter().write("{\"code\":401,\"msg\":\"WORKER_AUTHENTICATION_REQUIRED\",\"data\":null}"); return false;
    }
}
