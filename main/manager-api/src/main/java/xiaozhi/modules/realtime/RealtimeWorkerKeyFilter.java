package xiaozhi.modules.realtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.apache.shiro.web.filter.AccessControlFilter;

/** This credential grants no Shiro subject and no public API access. */
public class RealtimeWorkerKeyFilter extends AccessControlFilter {
    private final byte[] key;
    public RealtimeWorkerKeyFilter(String key){this.key=(key==null?"":key).getBytes(StandardCharsets.UTF_8);}
    @Override protected boolean isAccessAllowed(ServletRequest request,ServletResponse response,Object mapping){String supplied=((HttpServletRequest)request).getHeader("X-Realtime-Worker-Key");return key.length>=32&&key.length<=512&&supplied!=null&&supplied.length()<=512&&MessageDigest.isEqual(key,supplied.getBytes(StandardCharsets.UTF_8));}
    @Override protected boolean onAccessDenied(ServletRequest request,ServletResponse response)throws Exception{var http=(HttpServletResponse)response;http.setStatus(401);http.setContentType("application/json;charset=UTF-8");http.getWriter().write("{\"code\":401,\"msg\":\"REALTIME_WORKER_AUTHENTICATION_REQUIRED\",\"data\":null}");return false;}
}
