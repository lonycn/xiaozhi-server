package xiaozhi.modules.esp32integration;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.web.filter.OncePerRequestFilter;
import org.apache.shiro.web.filter.AccessControlFilter;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.JsonParser;

/** Bounded literal gateway requests, before the legacy XSS wrapper and Shiro. */
@Configuration
public class Esp32BoundaryConfiguration {
    public static final String HEADER="X-ESP32-Gateway-Key";
    public static boolean authenticated(HttpServletRequest request,String configured) {
        var headers=Collections.list(request.getHeaders(HEADER));
        return configured!=null && configured.length()>=32 && configured.length()<=512 && headers.size()==1
                && headers.getFirst().length()<=512 && MessageDigest.isEqual(configured.getBytes(StandardCharsets.UTF_8),headers.getFirst().getBytes(StandardCharsets.UTF_8));
    }
    public static class KeyFilter extends AccessControlFilter {
        private final String key;
        public KeyFilter(String key){this.key=key;}
        @Override protected boolean isAccessAllowed(ServletRequest request,ServletResponse response,Object value){return authenticated((HttpServletRequest)request,key);}
        @Override protected boolean onAccessDenied(ServletRequest request,ServletResponse response)throws Exception{error((HttpServletResponse)response,401,"ESP32_GATEWAY_AUTHENTICATION_REQUIRED");return false;}
    }
    static final Set<String> ROUTES=Set.of("register","heartbeat","poll","result","disconnect","sessions/connect","sessions/input","sessions/output","sessions/event","sessions/abort","sessions/abort-turn","sessions/reply");
    public static boolean exact(String path){return path.startsWith("/internal/esp32/")&&ROUTES.contains(path.substring("/internal/esp32/".length()));}
    @Bean public FilterRegistrationBean<Boundary> esp32Boundary(Environment env) {
        var registration=new FilterRegistrationBean<>(new Boundary(env));registration.setOrder(Ordered.HIGHEST_PRECEDENCE+9);registration.addUrlPatterns("/*");return registration;
    }
    public static class Boundary extends OncePerRequestFilter {
        private final Environment env;
        private final ObjectMapper json=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        public Boundary(Environment env){this.env=env;}
        @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws IOException,ServletException {
            String path=request.getRequestURI().substring(request.getContextPath().length());
            if(!path.startsWith("/internal/esp32/")){chain.doFilter(request,response);return;}
            if(!authenticated(request,env.getProperty("esp32.gateway.key",""))){error(response,401,"ESP32_GATEWAY_AUTHENTICATION_REQUIRED");return;}
            if(!env.getProperty("features.esp32-enabled",Boolean.class,false)){error(response,503,"ESP32_DISABLED");return;}
            if(!exact(path)||request.getQueryString()!=null){error(response,404,"ESP32_ROUTE_NOT_FOUND");return;}
            if(!"POST".equals(request.getMethod())){error(response,405,"METHOD_NOT_ALLOWED");return;}
            if(request.getContentType()==null||!request.getContentType().toLowerCase(Locale.ROOT).startsWith("application/json")){error(response,415,"UNSUPPORTED_MEDIA_TYPE");return;}
            if(request.getContentLengthLong()>32768){error(response,413,"ESP32_BODY_LIMIT");return;}
            byte[] raw=request.getInputStream().readNBytes(32769);
            if(raw.length>32768){error(response,413,"ESP32_BODY_LIMIT");return;}
            try {
                JsonNode node=json.readTree(raw);if(node==null||!node.isObject())throw new IllegalArgumentException();
                if(!path.contains("/sessions/"))validateGateway(path.substring(path.lastIndexOf('/')+1),node);
            }catch(Exception invalid){error(response,400,"INVALID_ESP32_REQUEST");return;}
            chain.doFilter(new Raw(request,raw),response);
        }
        private void validateGateway(String action,JsonNode n) {
            Set<String> fence=Set.of("deviceId","connectionId","generation","gatewayId","bindingVersion");
            Set<String> fields=switch(action){case "register"->Set.of("deviceId","deviceMac","clientId","token","gatewayId","capabilities");case "result"->Set.of("fence","commandId","success","result","status");default->fence;};
            n.fieldNames().forEachRemaining(k->{if(!fields.contains(k))throw new IllegalArgumentException();});
            if("register".equals(action)) {
                for(String name:List.of("deviceMac","clientId","token","gatewayId"))if(!n.path(name).isTextual())throw new IllegalArgumentException();
                if(!n.path("capabilities").isArray())throw new IllegalArgumentException();
            } else if("result".equals(action)) {
                if(!n.path("commandId").isTextual()||!n.path("result").isObject()||!n.has("fence"))throw new IllegalArgumentException();
                if(n.has("success")&&!n.path("success").isBoolean())throw new IllegalArgumentException();
                if(!n.has("status")&&!n.has("success"))throw new IllegalArgumentException();
                if(n.has("status")) {
                    if(!n.path("status").isTextual()||!Set.of("succeeded","failed","unknown").contains(n.path("status").textValue()))throw new IllegalArgumentException();
                    if(n.has("success")&&n.path("success").booleanValue()!=n.path("status").textValue().equals("succeeded"))throw new IllegalArgumentException();
                }
                validateFence(n.path("fence"),fence);
            } else validateFence(n,fence);
        }
        private void validateFence(JsonNode n,Set<String> fields) {
            if(!n.isObject()||n.size()!=fields.size()||!n.path("generation").isIntegralNumber()||!n.path("generation").canConvertToLong())throw new IllegalArgumentException();
            n.fieldNames().forEachRemaining(k->{if(!fields.contains(k))throw new IllegalArgumentException();});
            for(String k:fields)if(!k.equals("generation")&&!n.path(k).isTextual())throw new IllegalArgumentException();
        }
    }
    static class Raw extends HttpServletRequestWrapper {
        private final byte[] body;Raw(HttpServletRequest r,byte[] body){super(r);this.body=body;}
        @Override public int getContentLength(){return body.length;}@Override public long getContentLengthLong(){return body.length;}
        @Override public ServletInputStream getInputStream(){var in=new ByteArrayInputStream(body);return new ServletInputStream(){public int read(){return in.read();}public int read(byte[] b,int o,int l){return in.read(b,o,l);}public boolean isFinished(){return in.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener r){throw new UnsupportedOperationException();}};}
    }
    static void error(HttpServletResponse r,int status,String code)throws IOException{r.setStatus(status);r.setContentType("application/json;charset=UTF-8");r.setHeader("Cache-Control","no-store");r.getWriter().write("{\"code\":"+status+",\"msg\":\""+code+"\",\"data\":null}");}
}
