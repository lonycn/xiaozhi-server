package xiaozhi.modules.supportmedia;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.fasterxml.jackson.databind.json.JsonMapper;
import xiaozhi.modules.conversation.*;
import xiaozhi.modules.security.config.WebMvcConfig;
import xiaozhi.modules.supportmedia.MediaModels.*;
import xiaozhi.modules.supportmedia.livekit.*;

class MediaControllerTest {
    @Test void routesRequireAllThreeFlags(){var runner=new ApplicationContextRunner().withBean(xiaozhi.modules.externalidentity.ExternalMediaAuthority.class,()->mock(xiaozhi.modules.externalidentity.ExternalMediaAuthority.class)).withBean(MediaService.class,()->mock(MediaService.class)).withBean(ConversationService.class,()->mock(ConversationService.class)).withUserConfiguration(MediaController.class);runner.run(c->assertTrue(c.getBeansOfType(MediaController.class).isEmpty()));runner.withPropertyValues("features.conversation-enabled=true","features.support-media-enabled=true").run(c->assertTrue(c.getBeansOfType(MediaController.class).isEmpty()));runner.withPropertyValues("features.conversation-enabled=true","features.support-handoff-enabled=true","features.support-media-enabled=true").run(c->assertEquals(1,c.getBeansOfType(MediaController.class).size()));}
    @Test void strictConsentAndBodiesRejectSpoofingAndTruthyStrings()throws Exception {var json=JsonMapper.builder().build();var parser=new ConversationService(mock(ConversationDao.class),json,mock(org.springframework.transaction.PlatformTransactionManager.class));for(String body:List.of("{\"request_id\":\"r\",\"room_name\":\"foreign\"}","{\"consent\":{\"microphone\":\"true\",\"camera\":false}}","{\"consent\":{\"microphone\":true,\"camera\":0}}"))assertThrows(ConversationProblem.class,()->parser.request(json.readTree(body),Create.class));}
    @Test void realMvcMapperKeepsEpochNumericAndDatesIso(){var json=new WebMvcConfig().jackson2HttpMessageConverter().getObjectMapper();var token=new Token("ws://fixture.invalid","redacted",Instant.parse("2026-10-30T00:00:00Z"),"123",2,1,"opaque",List.of("microphone"));var value=json.valueToTree(token);assertTrue(value.get("control_epoch").isIntegralNumber());assertTrue(value.get("session_id").isTextual());assertEquals("2026-10-30T00:00:00Z",value.get("expires_at").asText());assertFalse(token.toString().contains("ws://"));}
    @Test void unsignedWebhookReturns401AndNeverMutatesDatabase()throws Exception {var adapter=mock(LiveKitAdapter.class);when(adapter.verifyWebhook(any(),isNull())).thenThrow(new LiveKitFailure(LiveKitFailure.Code.INVALID_WEBHOOK));var store=mock(MediaStore.class);var controller=new MediaWebhookController(adapter,store,mock(MediaPolicy.class),mock(MediaService.class));var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new MediaExceptionHandler()).build();mvc.perform(post("/internal/livekit/webhook").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));verifyNoInteractions(store);}
    @Test void rawWebhookHttpBodyIsBoundedBeforeCryptoOrJsonParsing()throws Exception {var adapter=mock(LiveKitAdapter.class);var store=mock(MediaStore.class);var controller=new MediaWebhookController(adapter,store,mock(MediaPolicy.class),mock(MediaService.class));var properties=new xiaozhi.common.xss.XssProperties();properties.setEnabled(true);var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new MediaExceptionHandler()).addFilters(new xiaozhi.common.xss.XssFilter(properties,new org.springframework.util.AntPathMatcher())).build();mvc.perform(post("/internal/livekit/webhook").servletPath("/internal/livekit/webhook").contentType(MediaType.APPLICATION_JSON).content(new byte[(1<<20)+1])).andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value(413));verifyNoInteractions(adapter,store);}

    @Test void defaultOffAndMissingOrPartialCredentialsStartWithoutLiveKitNetworkOrPublishingRoutes(){
        var db=mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var runner=new ApplicationContextRunner().withBean(xiaozhi.modules.externalidentity.ExternalMediaAuthority.class,()->mock(xiaozhi.modules.externalidentity.ExternalMediaAuthority.class)).withBean(org.springframework.jdbc.core.JdbcTemplate.class,()->db).withBean(org.springframework.transaction.PlatformTransactionManager.class,()->mock(org.springframework.transaction.PlatformTransactionManager.class)).withBean(ConversationDao.class,()->mock(ConversationDao.class)).withBean(com.fasterxml.jackson.databind.ObjectMapper.class,()->JsonMapper.builder().build()).withBean(ConversationService.class,()->mock(ConversationService.class)).withUserConfiguration(MediaConfiguration.class,MediaStore.class,MediaPolicy.class,MediaService.class,MediaMaintenance.class,MediaController.class,MediaDrainController.class);
        runner.run(context->{assertNull(context.getStartupFailure());assertTrue(context.getBeansOfType(LiveKitAdapter.class).isEmpty());assertTrue(context.getBeansOfType(MediaController.class).isEmpty());});
        for(String url:List.of("","http://fixture.invalid"))runner.withPropertyValues("features.conversation-enabled=true","features.support-handoff-enabled=true","support-media.livekit.internal-url="+url).run(context->{assertNull(context.getStartupFailure());assertFalse(context.getBean(LiveKitAdapter.class).configured());assertTrue(context.getBeansOfType(MediaController.class).isEmpty());assertEquals(1,context.getBeansOfType(MediaDrainController.class).size());context.getBean(MediaMaintenance.class).tick();});
    }

    @Test void signedJsonWebhookCrossesRealXssFilterWithoutChangingRawAnglesAmpersandOrWhitespace()throws Exception {
        var json=JsonMapper.builder().build();var real=new LiveKitAdapter(new LiveKitAdapter.Config("http://fixture.invalid","ws://fixture.invalid",MediaPersistenceTest.API_KEY,MediaPersistenceTest.API_SECRET,"b5-test-"),json,(room,operation,identity)->false,java.time.Clock.systemUTC(),(uri,auth,body,timeout)->{throw new AssertionError("No network allowed");});var adapter=spy(real);var store=mock(MediaStore.class);var controller=new MediaWebhookController(adapter,store,mock(MediaPolicy.class),mock(MediaService.class));var properties=new xiaozhi.common.xss.XssProperties();properties.setEnabled(true);
        var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new MediaExceptionHandler()).addFilters(new xiaozhi.common.xss.XssFilter(properties,new org.springframework.util.AntPathMatcher())).build();
        byte[] raw=(" \n {\"id\":\"raw-xss-body\",\"event\":\"participant_joined\",\"room\":{\"name\":\"foreign-preserve\"},\"participant\":{\"identity\":\"opaque\",\"metadata\":\"原始 <>& 字符\"}}  \n").getBytes(java.nio.charset.StandardCharsets.UTF_8);String signature=MediaPersistenceTest.sign(raw);
        mvc.perform(post("/xiaozhi/internal/livekit/webhook").contextPath("/xiaozhi").servletPath("/internal/livekit/webhook").header("Authorization",signature).contentType(MediaType.APPLICATION_JSON).content(raw)).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        verify(adapter).verifyWebhook(org.mockito.ArgumentMatchers.argThat(bytes->java.util.Arrays.equals(bytes,raw)),eq(signature));
        mvc.perform(post("/internal/livekit/webhook").servletPath("/internal/livekit/webhook").header("Authorization",signature).contentType(MediaType.APPLICATION_JSON).content((new String(raw,java.nio.charset.StandardCharsets.UTF_8)+" ").getBytes(java.nio.charset.StandardCharsets.UTF_8))).andExpect(status().isUnauthorized());
    }
    @Test void webhookXssExclusionIsExactAndDoesNotDisableSiblingOrChildRoutes()throws Exception {
        var properties=new xiaozhi.common.xss.XssProperties();properties.setEnabled(true);var filter=new xiaozhi.common.xss.XssFilter(properties,new org.springframework.util.AntPathMatcher());
        for(String path:List.of("/internal/livekit/webhook/child","/internal/livekit/other","/api/v1/support/media-sessions")){
            var request=new org.springframework.mock.web.MockHttpServletRequest("POST",path);request.setServletPath(path);request.setContentType("application/json");request.setContent("{\"text\":\"<>&\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            filter.doFilter(request,new org.springframework.mock.web.MockHttpServletResponse(),(filtered,response)->assertInstanceOf(xiaozhi.common.xss.XssHttpServletRequestWrapper.class,filtered));
        }
    }

}
