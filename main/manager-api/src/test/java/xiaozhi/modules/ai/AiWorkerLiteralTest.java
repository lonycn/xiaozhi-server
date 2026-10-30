package xiaozhi.modules.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import xiaozhi.common.xss.*;
import xiaozhi.modules.conversation.*;
import xiaozhi.modules.security.config.WebMvcConfig;

class AiWorkerLiteralTest {
    private static final String KEY="ai-worker-key-for-test-only-123456789";
    private static final String ID="a".repeat(32);
    private static final String PATH="/internal/ai-worker/runs/"+ID+"/delta";
    @Test void actualFilterAndMvcPreserveLiteralTextAndEnforceBoundaries() throws Exception {
        var json=new WebMvcConfig().jackson2HttpMessageConverter().getObjectMapper();
        var requests=new ConversationService(mock(ConversationDao.class),json,mock(org.springframework.transaction.PlatformTransactionManager.class));
        var service=mock(AiExecutionService.class);
        String text="真实 <>& 中文\nemoji 😀 \"quoted\"";
        when(service.delta(eq(ID),any())).thenAnswer(call->{assertEquals(text,call.getArgument(1,AiModels.Delta.class).text());return Map.of("sequence","1");});
        var properties=new XssProperties();properties.setEnabled(true);
        var xss=new XssFilter(properties,new org.springframework.util.AntPathMatcher());
        var keyFilter=new AiWorkerKeyFilter(KEY);
        jakarta.servlet.Filter auth=(request,response,chain)->{try {if(keyFilter.onPreHandle(request,response,null))chain.doFilter(request,response);} catch (jakarta.servlet.ServletException | java.io.IOException ex) {throw ex;} catch (Exception ex) {throw new jakarta.servlet.ServletException(ex);}};
        var controller=new AiWorkerController(service,requests,json);
        var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ConversationExceptionHandler()).addFilters(auth,xss).build();
        String body=json.writeValueAsString(Map.of("lease_token","b".repeat(64),"sequence",1,"text",text));
        mvc.perform(post(PATH).servletPath(PATH).header("X-AI-Worker-Key",KEY).contentType("application/json").content(body)).andExpect(status().isOk());
        verify(service).delta(eq(ID),any());clearInvocations(service);
        mvc.perform(post(PATH).servletPath(PATH).contentType("application/json").content(new byte[(1<<20)+1])).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).servletPath(PATH).header("X-AI-Worker-Key","wrong").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).servletPath(PATH).header("X-AI-Worker-Key",KEY).contentType("application/json").content(new byte[(1<<20)+1])).andExpect(status().isPayloadTooLarge());
        mvc.perform(post(PATH).servletPath(PATH).header("X-AI-Worker-Key",KEY).contentType("application/json").content("{\"lease_token\":\"x\",\"sequence\":1,\"text\":\"x\",\"extra\":true}")).andExpect(status().isBadRequest());
        mvc.perform(post(PATH).servletPath(PATH).header("X-AI-Worker-Key",KEY).contentType("application/json").content("{\"lease_token\":\"x\",\"sequence\":1.5,\"text\":\"x\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(PATH).servletPath(PATH).header("X-AI-Worker-Key",KEY).contentType("application/json").content("{\"lease_token\":\"x\",\"sequence\":1,\"text\":42}")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
        var unknownLength=new MockHttpServletRequest("POST",PATH){@Override public long getContentLengthLong(){return -1;}};
        unknownLength.setContent(new byte[(1<<20)+1]);assertEquals(413,assertThrows(ConversationProblem.class,()->controller.delta(ID,unknownLength)).status());
        for(String other:new String[]{PATH+"/child",PATH+"-fake","/internal/ai-worker/runs/not-an-id/delta","/internal/ai-worker/runs/"+ID+"/complete"}) {
            var request=new MockHttpServletRequest("POST",other);request.setServletPath(other);
            xss.doFilter(request,new MockHttpServletResponse(),(filtered,response)->assertInstanceOf(XssHttpServletRequestWrapper.class,filtered));
        }
        var get=new MockHttpServletRequest("GET",PATH);get.setServletPath(PATH);
        xss.doFilter(get,new MockHttpServletResponse(),(filtered,response)->assertInstanceOf(XssHttpServletRequestWrapper.class,filtered));
        reset(service);when(service.delta(eq(ID),any())).thenThrow(new ConversationProblem(403,"LEASE_DENIED"));
        mvc.perform(post(PATH).servletPath(PATH).header("X-AI-Worker-Key",KEY).contentType("application/json").content(body)).andExpect(status().isForbidden());
    }
}
