package xiaozhi.modules.attachment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.apache.shiro.web.filter.AccessControlFilter;
import jakarta.servlet.*;
import jakarta.servlet.http.*;

/** Separate worker credential; user and AI-worker capabilities grant no media-worker rights. */
public final class MediaWorkerKeyFilter extends AccessControlFilter {
    private final byte[] key;
    public MediaWorkerKeyFilter(String key){this.key=key.getBytes(StandardCharsets.UTF_8);}
    @Override protected boolean isAccessAllowed(ServletRequest request,ServletResponse response,Object mappedValue) {
        String supplied=((HttpServletRequest)request).getHeader("X-Media-Worker-Key");
        return key.length>=32 && supplied!=null && supplied.length()<=512 && MessageDigest.isEqual(key,supplied.getBytes(StandardCharsets.UTF_8));
    }
    @Override protected boolean onAccessDenied(ServletRequest request,ServletResponse response)throws Exception {
        HttpServletResponse http=(HttpServletResponse)response;http.setStatus(401);http.setContentType("application/json;charset=UTF-8");
        http.getWriter().write("{\"code\":401,\"msg\":\"MEDIA_WORKER_AUTHENTICATION_REQUIRED\",\"data\":null}");return false;
    }
}
