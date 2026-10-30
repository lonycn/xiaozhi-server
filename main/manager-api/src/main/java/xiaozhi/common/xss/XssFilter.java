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
                && java.util.Set.of("/api/v1/external/identity/issue","/api/v1/external/identity/revoke").contains(path))return true;
        // Dedicated-key worker callbacks preserve literal provider transcripts; controller reads
        // at most 1 MiB before strict parsing. This excludes no sibling or child URL.
        if (request.getServletPath().matches("/internal/realtime-worker/sessions/[a-f0-9]{32}/events")) return true;
        // The AI delta controller bounds and strictly parses the original provider text.
        if ("POST".equals(request.getMethod())
                && request.getServletPath().matches("/internal/ai-worker/runs/[a-f0-9]{32}/delta")) return true;
        // 放行不过滤的URL
        return properties.getExcludeUrls().stream()
                .anyMatch(excludeUrl -> pathMatcher.match(excludeUrl, request.getServletPath()));
    }

    @Override
    public void destroy() {
    }

}