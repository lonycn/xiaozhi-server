package xiaozhi.modules.realtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import xiaozhi.modules.security.config.WebMvcConfig;
import xiaozhi.modules.conversation.*;
import xiaozhi.modules.realtime.RealtimeModels.*;

class RealtimeControllerTest {
    static ConversationService parser(ObjectMapper json){return new ConversationService(mock(ConversationDao.class),json,mock(org.springframework.transaction.PlatformTransactionManager.class));}
    @Test void defaultOffAndNoWorkerConfigurationStartsWithoutPublishingOrDatabaseCalls(){
        var db=mock(JdbcTemplate.class);var runner=new ApplicationContextRunner().withBean(JdbcTemplate.class,()->db).withBean(org.springframework.transaction.PlatformTransactionManager.class,()->mock(org.springframework.transaction.PlatformTransactionManager.class)).withBean(ObjectMapper.class,JsonMapper::new).withBean(ConversationService.class,()->mock(ConversationService.class)).withUserConfiguration(RealtimeStore.class,RealtimeService.class,RealtimeMaintenance.class,RealtimeController.class,RealtimeWorkerController.class);
        runner.run(context->{assertNull(context.getStartupFailure());assertTrue(context.getBeansOfType(RealtimeController.class).isEmpty());assertTrue(context.getBeansOfType(RealtimeService.class).isEmpty());});
        runner.withPropertyValues("features.conversation-enabled=true").run(context->{assertNull(context.getStartupFailure());assertTrue(context.getBeansOfType(RealtimeController.class).isEmpty());var service=context.getBean(RealtimeService.class);assertFalse(service.enabled());assertThrows(ConversationProblem.class,()->service.create(7L,new Create("a".repeat(32),"test",new Consent(true,false))));});
        runner.withPropertyValues("features.conversation-enabled=true","features.realtime-ai-enabled=true").run(context->{assertNull(context.getStartupFailure());assertEquals(1,context.getBeansOfType(RealtimeController.class).size());assertEquals(503,assertThrows(ConversationProblem.class,()->context.getBean(RealtimeService.class).create(7L,new Create("a".repeat(32),"test",new Consent(true,false)))).status());});assertTrue(mockingDetails(db).getInvocations().stream().allMatch(invocation->invocation.getMethod().getName().equals("afterPropertiesSet")));
    }
    @Test void strictBodiesRejectActorProviderAndToolSpoofingAndTruthyOrStringNumericValues()throws Exception{
        var json=JsonMapper.builder().build();var requests=parser(json);
        for(String raw:List.of("{\"conversation_id\":\"x\",\"owner_user_id\":7}","{\"consent\":{\"microphone\":\"true\",\"camera\":false}}","{\"consent\":{\"microphone\":true,\"camera\":1}}","{\"provider_url\":\"https://foreign.invalid\"}","{\"tool_result\":\"invented\"}"))assertThrows(ConversationProblem.class,()->requests.request(json.readTree(raw),Create.class));
        for(String raw:List.of("{\"expected_epoch\":\"1\"}","{\"expected_epoch\":1.5}"))assertThrows(ConversationProblem.class,()->requests.request(json.readTree(raw),Epoch.class));
        assertThrows(ConversationProblem.class,()->requests.request(json.readTree("{\"lease_token\":\"x\",\"input_busy\":\"false\"}"),Heartbeat.class));
        assertThrows(ConversationProblem.class,()->requests.request(json.readTree("{\"sequence\":\"1\",\"assistant\":true}"),ProviderEvent.class));
    }
    @Test void actualMvcMapperUsesNumbersAndIsoTimesEvenWithGlobalLongStringSerializer(){
        var json=new WebMvcConfig().jackson2HttpMessageConverter().getObjectMapper();Instant now=Instant.parse("2026-10-30T00:00:00Z");
        var session=new Session("a".repeat(32),"b".repeat(32),"active",4,"qwen3.8-omni-flash-realtime",new Consent(true,false),"provider",2,"response","message",null,now,now,null);
        var wire=json.valueToTree(new Receipt(2,session,"message","streaming"));assertTrue(wire.get("sequence").isIntegralNumber());assertTrue(wire.path("session").get("control_epoch").isIntegralNumber());assertTrue(wire.path("session").get("last_event_sequence").isIntegralNumber());assertEquals(now.toString(),wire.path("session").get("created_at").asText());assertTrue(wire.path("session").get("id").isTextual());
        var usage=json.valueToTree(new Usage(452L,95L,547L));assertTrue(usage.get("total_tokens").isIntegralNumber());assertTrue(json.valueToTree(new Page<>(List.of(),2,false)).get("next_cursor").isIntegralNumber());
    }
    @Test void dedicatedWorkerKeyIsFailClosedAndNeverUsesBearerOrOtherWorkerKeys()throws Exception{
        String key="isolated-realtime-synthetic-worker-key";for(String configured:List.of("",key)){
            var filter=new RealtimeWorkerKeyFilter(configured);var request=new MockHttpServletRequest();request.addHeader("Authorization","Bearer "+key);request.addHeader("X-AI-Worker-Key",key);request.addHeader("X-Media-Worker-Key",key);var response=new MockHttpServletResponse();assertFalse(filter.onPreHandle(request,response,null));assertEquals(401,response.getStatus());request.addHeader("X-Realtime-Worker-Key",key);assertEquals(!configured.isEmpty(),filter.onPreHandle(request,new MockHttpServletResponse(),null));
        }
    }
    @Test void exactXssExclusionPreservesProviderTextAndBoundsBytesBeforeParsing()throws Exception{
        var json=JsonMapper.builder().build();var service=mock(RealtimeService.class);when(service.providerEvent(anyString(),any())).thenAnswer(call->{var body=call.getArgument(1,ProviderEvent.class);assertEquals("真实 <>& 中文\n transcript",body.text());return new Receipt(1,null,"m","streaming");});
        var properties=new xiaozhi.common.xss.XssProperties();properties.setEnabled(true);var xss=new xiaozhi.common.xss.XssFilter(properties,new org.springframework.util.AntPathMatcher());var controller=new RealtimeWorkerController(service,parser(json),json);var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new RealtimeExceptionHandler()).addFilters(xss).build();
        String path="/internal/realtime-worker/sessions/"+"a".repeat(32)+"/events";String body=json.writeValueAsString(Map.of("lease_token","b".repeat(64),"sequence",1,"provider_event_id","test","type","response.delta","response_id","response","text","真实 <>& 中文\n transcript"));
        mvc.perform(post(path).servletPath(path).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.data.message_id").value("m"));verify(service,times(1)).providerEvent(anyString(),any());clearInvocations(service);
        mvc.perform(post(path).servletPath(path).contentType("application/json").content(new byte[(1<<20)+1])).andExpect(status().isPayloadTooLarge());verifyNoInteractions(service);
        var unknownLength=new MockHttpServletRequest("POST",path){@Override public long getContentLengthLong(){return -1;}};unknownLength.setContent(new byte[(1<<20)+1]);assertEquals(413,assertThrows(ConversationProblem.class,()->controller.event("a".repeat(32),unknownLength)).status());verifyNoInteractions(service);
        for(String other:List.of(path+"/child",path+"-fake","/internal/realtime-worker/sessions/not-an-id/events","/api/v1/ai/realtime/sessions")){var request=new MockHttpServletRequest("POST",other);request.setServletPath(other);xss.doFilter(request,new MockHttpServletResponse(),(filtered,response)->assertInstanceOf(xiaozhi.common.xss.XssHttpServletRequestWrapper.class,filtered));}
    }
    @Test void publicEndpointsCannotSubmitProviderEventsOrAssistantContent()throws Exception{
        var json=JsonMapper.builder().build();var service=mock(RealtimeService.class);var mvc=MockMvcBuilders.standaloneSetup(new RealtimeController(service,parser(json))).setControllerAdvice(new RealtimeExceptionHandler()).build();
        mvc.perform(post("/api/v1/ai/realtime/sessions").contentType("application/json").content("{\"conversation_id\":\"x\",\"assistant\":\"forged\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/ai/realtime/sessions/"+"a".repeat(32)+"/events").contentType("application/json").content("{}")).andExpect(status().isNotFound());verifyNoInteractions(service);
    }
    @Test void repositoryProxyAccessesTargetJdbcThroughMethods(){
        var jdbc=mock(JdbcTemplate.class);var manager=mock(org.springframework.transaction.PlatformTransactionManager.class);var runner=new ApplicationContextRunner().withBean(JdbcTemplate.class,()->jdbc).withBean(org.springframework.transaction.PlatformTransactionManager.class,()->manager).withBean(org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor.class,org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor::new).withUserConfiguration(RealtimeStore.class);
        runner.run(context->{var store=context.getBean(RealtimeStore.class);assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(store));assertSame(jdbc,store.jdbc());assertNotNull(store.rows("SELECT fixture"));});
    }
    @Test void capabilitiesAreRedactedInRecordStringRepresentations(){String secret="c".repeat(64);assertFalse(new Ticket("session",1,"ws://fixture.invalid",secret,Instant.now()).toString().contains(secret));assertFalse(new Consume(secret,"worker","server").toString().contains(secret));assertFalse(new Heartbeat(secret,false).toString().contains(secret));assertFalse(new ProviderEvent(secret,1L,"event","session.ready","provider",null,null,null,null,null,null,null,null).toString().contains(secret));}
    @Test void actualEarlyShiroFactoryLoadsDedicatedRealtimeKeyFromEnvironment()throws Exception{
        String key="p3-early-environment-worker-secret-synthetic";
        try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()){
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("realtime-key",Map.of("realtime.worker.key",key)));
            context.getBeanFactory().registerSingleton("realm",mock(xiaozhi.modules.security.oauth2.Oauth2Realm.class));context.getBeanFactory().registerSingleton("params",mock(xiaozhi.modules.sys.service.SysParamsService.class));context.register(xiaozhi.modules.security.config.ShiroConfig.class);context.refresh();
            var factory=(org.apache.shiro.spring.web.ShiroFilterFactoryBean)context.getBean("&shiroFilter");assertEquals("realtimeWorker",factory.getFilterChainDefinitionMap().get("/internal/realtime-worker/**"));assertEquals("conversation",factory.getFilterChainDefinitionMap().get("/api/v1/ai/**"));var filter=(RealtimeWorkerKeyFilter)factory.getFilters().get("realtimeWorker");var request=new MockHttpServletRequest();request.addHeader("X-Realtime-Worker-Key",key);assertTrue(filter.onPreHandle(request,new MockHttpServletResponse(),null));
        }
    }

}
