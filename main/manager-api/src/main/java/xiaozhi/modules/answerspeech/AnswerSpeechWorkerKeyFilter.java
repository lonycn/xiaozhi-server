package xiaozhi.modules.answerspeech;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.apache.shiro.web.filter.AccessControlFilter;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
/** Dedicated worker authority never authenticates a user subject. */
public class AnswerSpeechWorkerKeyFilter extends AccessControlFilter {
    private final byte[] key;
    public AnswerSpeechWorkerKeyFilter(String value){key=value.getBytes(StandardCharsets.UTF_8);}
    @Override protected boolean isAccessAllowed(ServletRequest request,ServletResponse response,Object mapped){String supplied=((HttpServletRequest)request).getHeader("X-Answer-Speech-Worker-Key");return key.length>=32&&supplied!=null&&supplied.length()<=512&&MessageDigest.isEqual(key,supplied.getBytes(StandardCharsets.UTF_8));}
    @Override protected boolean onAccessDenied(ServletRequest request,ServletResponse response)throws Exception{var http=(HttpServletResponse)response;http.setStatus(401);http.setContentType("application/json;charset=UTF-8");http.getWriter().write("{\"code\":401,\"msg\":\"WORKER_AUTHENTICATION_REQUIRED\",\"data\":null}");return false;}
}
