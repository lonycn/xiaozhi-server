package xiaozhi.modules.esp32gateway;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;

/** Capture bounded literal input before the legacy XSS body wrapper. Authentication still applies. */
@Configuration
@ConditionalOnProperty(name="features.esp32-enabled",havingValue="true")
public class Esp32DebugInputBoundary {
    public static final String BODY = Esp32DebugInputBoundary.class.getName()+".body";
    public static class InputFilter extends OncePerRequestFilter {
        @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
            String path=request.getRequestURI().substring(request.getContextPath().length());
            if(!"POST".equals(request.getMethod())||!path.matches("/api/v1/esp32/devices/(?:[A-Za-z0-9_-]{1,32}|(?i:[0-9a-f]{2}(?:(?::|%3a)[0-9a-f]{2}){5}))/text-input")) { chain.doFilter(request,response);return; }
            byte[] body=request.getInputStream().readNBytes(16385);
            if(request.getQueryString()!=null||body.length>16384) {
                response.setStatus(400);response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":\"invalid_text_input\"}");return;
            }
            request.setAttribute(BODY,body);
            chain.doFilter(request,response);
        }
    }
    @Bean public FilterRegistrationBean<InputFilter> esp32DebugInputFilter() {
        var registration=new FilterRegistrationBean<>(new InputFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE+8);
        return registration;
    }
}
