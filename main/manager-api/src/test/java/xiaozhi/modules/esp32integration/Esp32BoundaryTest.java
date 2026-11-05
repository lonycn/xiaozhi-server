package xiaozhi.modules.esp32integration;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import jakarta.servlet.http.HttpServletRequest;

class Esp32BoundaryTest {
    final String key="isolated-gateway-key-123456789012345678901234";
    MockHttpServletRequest request(String path,String body){var r=new MockHttpServletRequest("POST",path);r.setContentType("application/json");r.addHeader(Esp32BoundaryConfiguration.HEADER,key);r.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));return r;}
    Esp32BoundaryConfiguration.Boundary filter(){return new Esp32BoundaryConfiguration.Boundary(new MockEnvironment().withProperty("features.esp32-enabled","true").withProperty("esp32.gateway.key",key));}
    @Test void validLiteralBodyPassesWithoutChangingProviderOrDeviceText()throws Exception{
        var r=request("/internal/esp32/sessions/input","{\"text\":\"<temperature> & sample\"}");var response=new MockHttpServletResponse();var passed=new AtomicBoolean();
        filter().doFilter(r,response,(req,res)->{passed.set(true);assertEquals("{\"text\":\"<temperature> & sample\"}",new String(((HttpServletRequest)req).getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));});
        assertTrue(passed.get());
    }
    @Test void duplicateKeyUnknownRouteAndChunkedOversizeFailBeforeDispatch()throws Exception{
        for(var item:new Object[][]{{"/internal/esp32/register","{\"token\":1,\"token\":2}",400},{"/internal/esp32/register/child","{}",404},{"/internal/esp32/sessions/input","{\"text\":\""+"x".repeat(32768)+"\"}",413}}){
            var r=request((String)item[0],(String)item[1]);var response=new MockHttpServletResponse();filter().doFilter(r,response,(req,res)->fail("rejected input reached controller"));assertEquals(item[2],response.getStatus());
        }
    }
    @Test void dedicatedKeyIsRequiredAndDuplicateHeadersAreRejected()throws Exception{
        var r=request("/internal/esp32/register","{}");r.addHeader(Esp32BoundaryConfiguration.HEADER,key);var response=new MockHttpServletResponse();filter().doFilter(r,response,(req,res)->fail());assertEquals(401,response.getStatus());
        r=request("/internal/esp32/register","{}");r.removeHeader(Esp32BoundaryConfiguration.HEADER);r.addHeader("X-AI-Worker-Key",key);response=new MockHttpServletResponse();filter().doFilter(r,response,(req,res)->fail());assertEquals(401,response.getStatus());
    }
    @Test void spoofedIdentityAndStringGenerationNeverReachGatewayController()throws Exception{
        for(String body:new String[]{"{\"ownerId\":123}","{\"deviceId\":\"1\",\"connectionId\":\"1\",\"generation\":\"1\",\"gatewayId\":\"1\",\"bindingVersion\":\"1\"}"}){
            var r=request("/internal/esp32/heartbeat",body);var response=new MockHttpServletResponse();filter().doFilter(r,response,(req,res)->fail());assertEquals(400,response.getStatus());
        }
    }
}
