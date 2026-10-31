package xiaozhi.modules.externalidentity;

import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.dao.DataAccessException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Runs before Shiro, including anon legacy routes: external credentials have no management bypass. */
public final class ExternalBoundaryFilter extends OncePerRequestFilter {
    private final Supplier<ExternalIdentityService> services;
    public ExternalBoundaryFilter(Supplier<ExternalIdentityService> services){this.services=services;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        String path=request.getRequestURI().substring(request.getContextPath().length());
        // Entry credentials are verified by their dedicated controller, not a stale caller Bearer.
        if(ExternalPathPolicy.publicEntry(path)) {
            try {
                if("POST".equals(request.getMethod()))chain.doFilter(new ExternalRawRequest(request,ExternalIdentityController.body(request)),response);
                else chain.doFilter(request,response);
            }catch(ExternalProblem failure){error(response,failure.status(),failure.getMessage());}
            return;
        }
        List<String> headers=Collections.list(request.getHeaders("Authorization"));
        String authorization=headers.isEmpty()?null:headers.getFirst();
        boolean external=headers.stream().anyMatch(value->value!=null && value.matches("(?i:Bearer)\\s+xext_.*"));
        if(!external){chain.doFilter(request,response);return;}
        try {
            if(headers.size()!=1 || authorization==null || !authorization.matches("Bearer xext_[a-f0-9]{64}"))throw new ExternalProblem(401,"EXTERNAL_TOKEN_INVALID");
            if(ExternalPathPolicy.rtc(path) && !ExternalPathPolicy.mediaAllowed(request.getMethod(),path))throw new ExternalProblem(403,"EXTERNAL_ROUTE_DENIED");
            if(!ExternalPathPolicy.allowed(request.getMethod(),path))throw new ExternalProblem(403,"EXTERNAL_ROUTE_DENIED");
            if(!ExternalPathPolicy.queryKeys(request.getMethod(),path).containsAll(request.getParameterMap().keySet()))throw new ExternalProblem(400,"UNTRUSTED_CONTEXT_PARAMETER");
            ExternalIdentityService service=services.get();if(service==null)throw new ExternalProblem(503,"IDENTITY_NOT_CONFIGURED");
            if(ExternalPathPolicy.mediaAllowed(request.getMethod(),path) && !path.equals("/api/v1/support/media-drain") && !service.mediaEnabled())throw new ExternalProblem(403,"EXTERNAL_MEDIA_DISABLED");
            request.setAttribute(ExternalIdentityService.ATTRIBUTE,service.authenticate(authorization.substring(7)));
            chain.doFilter(request,response);
        } catch(ExternalProblem failure) {error(response,failure.status(),failure.getMessage());}
        catch(DataAccessException unavailable) {error(response,503,"IDENTITY_UNAVAILABLE");}
    }
    private void error(HttpServletResponse response,int status,String code)throws IOException {
        if(response.isCommitted())return;
        response.setStatus(status);response.setContentType("application/json;charset=UTF-8");response.setHeader("Cache-Control","no-store");
        if(status==503)response.setHeader("Retry-After","2");
        response.getWriter().write("{\"code\":"+status+",\"msg\":\""+code+"\",\"data\":null}");
    }
}
