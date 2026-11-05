package xiaozhi.modules.app;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import java.nio.charset.StandardCharsets;
import xiaozhi.modules.externalidentity.ExternalPathPolicy;

class AiMaterialRoutePolicyTest {
    @Test void strictMaterialControllersReceiveLiteralBytesWhileSiblingsKeepLegacyFiltering() throws Exception {
        var properties=new xiaozhi.common.xss.XssProperties();properties.setEnabled(true);
        var filter=new xiaozhi.common.xss.XssFilter(properties,new org.springframework.util.AntPathMatcher());
        String session="a".repeat(32),material="b".repeat(32);
        byte[] bytes="{\"text\":\"A < B & C > D\"}".getBytes(StandardCharsets.UTF_8);
        for(String path:List.of("/api/v1/ai/realtime/sessions/"+session+"/materials",
                "/internal/realtime-worker/sessions/"+session+"/materials/"+material+"/prepared")) {
            var request=new MockHttpServletRequest("POST",path);request.setContentType("application/json");request.setContent(bytes);
            filter.doFilter(request,new MockHttpServletResponse(),(actual,response)->{
                assertSame(request,actual);assertArrayEquals(bytes,actual.getInputStream().readAllBytes());
            });
        }
        var sibling=new MockHttpServletRequest("POST","/api/v1/ai/realtime/sessions/"+session+"/materials-extra");
        filter.doFilter(sibling,new MockHttpServletResponse(),(actual,response)->assertInstanceOf(xiaozhi.common.xss.XssHttpServletRequestWrapper.class,actual));
    }
    @Test void rejectedMaterialQueryNeverReadsItsBodyBeforeAuthenticationOrTheController() throws Exception {
        var properties=new xiaozhi.common.xss.XssProperties();properties.setEnabled(true);
        var filter=new xiaozhi.common.xss.XssFilter(properties,new org.springframework.util.AntPathMatcher());
        var request=new MockHttpServletRequest("POST","/api/v1/ai/realtime/sessions/"+"a".repeat(32)+"/materials") {
            @Override public jakarta.servlet.ServletInputStream getInputStream(){throw new AssertionError("Rejected query body was read");}
        };
        request.setQueryString("unexpected=1");
        var response=new MockHttpServletResponse();
        filter.doFilter(request,response,(actual,output)->fail("Rejected query reached the controller"));
        assertEquals(400,response.getStatus());
    }
    @Test void materialsAreExactExternalMediaRoutesWithoutExtraQueriesOrMethods() {
        String session="/api/v1/ai/realtime/sessions/"+"a".repeat(32),id="b".repeat(32);
        for(String route:List.of(session+"/input-state",session+"/materials/"+id)) {
            assertTrue(ExternalPathPolicy.allowed("GET",route));
            assertTrue(ExternalPathPolicy.mediaAllowed("GET",route));
            assertEquals(Set.of(),ExternalPathPolicy.queryKeys("GET",route));
            assertFalse(ExternalPathPolicy.allowed("POST",route));
        }
        for(String route:List.of(session+"/input-pauses",session+"/materials",session+"/input-pauses/"+id+"/resume",session+"/input-pauses/"+id+"/cancel")) {
            assertTrue(ExternalPathPolicy.allowed("POST",route));
            assertTrue(ExternalPathPolicy.mediaAllowed("POST",route));
            assertFalse(ExternalPathPolicy.allowed("GET",route));
            assertFalse(ExternalPathPolicy.allowed("PUT",route));
            assertFalse(ExternalPathPolicy.allowed("DELETE",route));
            assertFalse(ExternalPathPolicy.allowed("POST",route+"/child"));
            assertFalse(ExternalPathPolicy.allowed("POST",route+";x"));
        }
        assertFalse(ExternalPathPolicy.allowed("GET",session.replace("aaaa","%61aaa")+"/input-state"));
        assertFalse(ExternalPathPolicy.allowed("POST",session+"/materials/"+id+"/prepared"));
    }
    @Test void configuredMaterialsStillRequireTheWholeAuthorityChainAndDoNotClaimReadiness() {
        var env=new MockEnvironment();
        var properties=List.of("conversation-enabled","realtime-ai-enabled","attachments-enabled","agent-tools-enabled","realtime-tools-enabled","realtime-materials-enabled");
        for(String property:properties)env.withProperty("features."+property,"true");
        for(String property:properties) {
            env.withProperty("features."+property,"false");
            assertEquals("disabled",status(env),property);
            env.withProperty("features."+property,"true");
        }
        assertEquals("verification_required",status(env));
        var value=new AppInteractionCapabilities(env).value();
        assertEquals("configuration_only",value.get("availability_basis"));
        assertEquals(List.of("text","image","file"),((Map<?,?>)value.get("realtime_materials")).get("allowed_parts"));
    }
    private static Object status(MockEnvironment env) {
        Map<?,?> capabilities=(Map<?,?>)new AppInteractionCapabilities(env).value().get("capabilities");
        return ((Map<?,?>)capabilities.get("realtime_materials")).get("status");
    }
}
