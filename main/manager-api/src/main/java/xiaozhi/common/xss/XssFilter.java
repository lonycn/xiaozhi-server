package xiaozhi.common.xss;

import java.io.IOException;

import org.springframework.util.PathMatcher;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.AllArgsConstructor;

/**
 * XSS过滤
 * Copyright (c) 人人开源 All rights reserved.
 * Website: https://www.renren.io
 */
@AllArgsConstructor
public class XssFilter implements Filter {
    private final XssProperties properties;
    private final PathMatcher pathMatcher;

    @Override
    public void init(FilterConfig config) throws ServletException {
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpServletRequest = (HttpServletRequest) request;

        // These exact JSON controllers bound and strictly parse the original body. Reject
        // queries before the legacy wrapper can buffer a body that will never be accepted.
        if (strictInteractionBody(httpServletRequest)) {
            if (httpServletRequest.getQueryString() != null) {
                var httpResponse = (jakarta.servlet.http.HttpServletResponse) response;
                httpResponse.setStatus(400);
                httpResponse.setContentType("application/json;charset=UTF-8");
                httpResponse.getWriter().write("{\"code\":400,\"msg\":\"INVALID_REQUEST\",\"data\":null}");
                return;
            }
            chain.doFilter(request, response);
            return;
        }

        // 放行
        if (shouldNotFilter(httpServletRequest)) {
            chain.doFilter(request, response);

            return;
        }

        chain.doFilter(new XssHttpServletRequestWrapper(httpServletRequest), response);
    }

    private boolean shouldNotFilter(HttpServletRequest request) {
        // This exact endpoint verifies the signed RAW body before parsing and enforces its own
        // bounded read. Rewriting/buffering JSON here would invalidate the signature and bypass
        // that bound. Keep every other URL on the existing XSS path.
        if ("/internal/livekit/webhook".equals(request.getServletPath())) return true;
        // B6 signatures authenticate exact wire bytes; never transform these two POST bodies.
        // Their earlier boundary filter limits input before wrapping, then dedicated code verifies
        // HMAC before strict JSON parsing. Do not widen this to an integration prefix.
        String path=request.getRequestURI().substring(request.getContextPath().length());
        if("POST".equals(request.getMethod()) && request.getQueryString()==null
                && xiaozhi.modules.esp32integration.Esp32BoundaryConfiguration.exact(path))return true;
        if("POST".equals(request.getMethod()) && request.getQueryString()==null
                && java.util.Set.of("/api/v1/external/identity/issue","/api/v1/external/identity/revoke","/api/v1/external/identity/renew").contains(path))return true;
        // Dedicated-key worker callbacks preserve literal provider transcripts; controller reads
        // at most 1 MiB before strict parsing. This excludes no sibling or child URL.
        if (request.getServletPath().matches("/internal/realtime-worker/sessions/[a-f0-9]{32}/events")) return true;
        // The AI delta controller bounds and strictly parses the original provider text.
        if ("POST".equals(request.getMethod())
                && request.getServletPath().matches("/internal/ai-worker/runs/[a-f0-9]{32}/delta")) return true;
        // The dedicated-key tool controller bounds and strictly validates raw structured arguments.
        if ("POST".equals(request.getMethod()) && request.getQueryString()==null
                && request.getServletPath().matches("/internal/ai-worker/runs/[a-f0-9]{32}/tools")) return true;
        if("POST".equals(request.getMethod()) && request.getQueryString()==null
                && request.getServletPath().matches("/internal/realtime-worker/sessions/[a-f0-9]{32}/tool-proposals")) return true;
        // 放行不过滤的URL
        return properties.getExcludeUrls().stream()
                .anyMatch(excludeUrl -> pathMatcher.match(excludeUrl, request.getServletPath()));
    }

    private static boolean strictInteractionBody(HttpServletRequest request) {
        String id = "[a-f0-9]{32}";
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if ("PUT".equals(request.getMethod()))
            return path.matches("/internal/answer-speech-worker/jobs/"+id+"/audio");
        if (!"POST".equals(request.getMethod())) return false;
        return path.matches("/api/v1/ai/realtime/sessions/"+id+"/(?:input-pauses|materials)")
                || path.matches("/api/v1/ai/realtime/sessions/"+id+"/input-pauses/"+id+"/(?:resume|cancel)")
                || path.matches("/internal/realtime-worker/sessions/"+id+"/input-pauses/"+id+"/(?:cleared|resumed)")
                || path.matches("/internal/realtime-worker/sessions/"+id+"/materials/"+id+"/(?:prepared|perception-usage|context-ack|fail)")
                || path.matches("/api/v1/ai/conversations/"+id+"/messages/"+id+"/speech")
                || path.matches("/api/v1/ai/conversations/"+id+"/speech/"+id+"/cancel")
                || path.matches("/api/v1/ai/conversations/"+id+"/skill")
                || path.equals("/internal/answer-speech-worker/claim")
                || path.matches("/internal/ai-worker/runs/"+id+"/usage")
                || path.matches("/internal/answer-speech-worker/jobs/"+id+"/(?:heartbeat|complete|fail|usage)");
    }

    @Override
    public void destroy() {
    }

}
