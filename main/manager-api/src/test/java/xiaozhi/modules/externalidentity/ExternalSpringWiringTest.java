package xiaozhi.modules.externalidentity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.transaction.PlatformTransactionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import xiaozhi.modules.security.dao.SysUserTokenDao;
import xiaozhi.modules.sys.dao.SysUserDao;
import xiaozhi.modules.security.service.impl.ShiroServiceImpl;

/** Real Spring DI plus an actual CGLIB delegate proxy; never starts HTTP/DB/services. */
class ExternalSpringWiringTest {
    static final String TOKEN="xext_"+"a".repeat(64);
    @Configuration static class Wiring {
        @Bean ObjectMapper mapper(){return new ObjectMapper();}
        @Bean PlatformTransactionManager manager(){return mock(PlatformTransactionManager.class);}
        @Bean SysUserDao users(){return mock(SysUserDao.class);}
        @Bean SysUserTokenDao tokens(){return mock(SysUserTokenDao.class);}
        @Bean JdbcTemplate db() {
            return mock(JdbcTemplate.class,invocation->{
                if(invocation.getMethod().getName().equals("query")) {
                    String sql=invocation.getArgument(0);Timestamp expires=Timestamp.from(Instant.now().plusSeconds(300));
                    if(sql.startsWith("SELECT * FROM external_access_token"))return List.of(Map.of("id",1L,"token_hash",ExternalIdentityService.hash(TOKEN),"source_id",2L,"principal_id",3L,"scope_json","{\"project_ids\":[],\"device_ids\":[]}","expires_at",expires));
                    if(sql.startsWith("SELECT * FROM external_source_session"))return List.of(Map.of("id",2L,"issuer",ExternalProtocolTest.ISSUER,"external_subject","subject","expires_at",expires));
                    if(sql.startsWith("SELECT * FROM external_tenant_principal"))return List.of(Map.of("id",3L,"user_id",7L,"tenant_type","company","tenant_id","1"));
                    if(sql.startsWith("SELECT CAST(status"))return List.of(Map.of("status",1,"super_admin",0));
                    throw new AssertionError("Unexpected identity query");
                }
                return RETURNS_DEFAULTS.answer(invocation);
            });
        }
        @Bean static BeanPostProcessor actualCglibProxy() {
            return new BeanPostProcessor(){
                @Override public Object postProcessAfterInitialization(Object bean,String name) {
                    if(bean instanceof ExternalIdentityService) {
                        ProxyFactory factory=new ProxyFactory(bean);factory.setProxyTargetClass(true);
                        factory.addAdvice((MethodInterceptor)invocation->invocation.proceed());return factory.getProxy();
                    }
                    return bean;
                }
            };
        }
    }
    @Test void realSpringInjectedCglibServiceWorksThroughControllerFilterAndShiro()throws Exception {
        try(var context=new AnnotationConfigApplicationContext()) {
            String keys="{\""+ExternalProtocolTest.KEY_ID+"\":{\"issuer\":\""+ExternalProtocolTest.ISSUER+"\",\"secret_b64\":\""+Base64.getEncoder().encodeToString(ExternalProtocolTest.KEY)+"\"}}";
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated-b6",Map.of("features.external-identity-enabled","true","external-identity.keys-json",keys)));
            context.register(Wiring.class,ExternalSettings.class,ExternalIdentityService.class,ExternalIdentityController.class,ExternalBoundaryConfiguration.class,ShiroServiceImpl.class);context.refresh();
            var source=context.getBean(ExternalIdentityService.class);assertTrue(AopUtils.isCglibProxy(source));
            for(var field:ExternalIdentityService.class.getFields())assertTrue(java.lang.reflect.Modifier.isStatic(field.getModifiers()),"No peer reads a public instance field from a proxy");
            String timestamp=Long.toString(Instant.now().getEpochSecond()),nonce="b".repeat(32);byte[] raw="{\"wire\":\"<>&\"}".getBytes(StandardCharsets.UTF_8);
            assertEquals(ExternalProtocolTest.ISSUER,source.verify(ExternalIdentityService.ISSUE,raw,Map.of("x-external-key-id",List.of(ExternalProtocolTest.KEY_ID),"x-external-timestamp",List.of(timestamp),"x-external-nonce",List.of(nonce),"x-external-signature",List.of(ExternalIdentityService.signature(ExternalProtocolTest.KEY,ExternalIdentityService.ISSUE,timestamp,nonce,raw)))).issuer());
            assertEquals(7L,context.getBean(ShiroServiceImpl.class).getByToken(TOKEN).getUserId());
            @SuppressWarnings("unchecked") var registration=(FilterRegistrationBean<ExternalBoundaryFilter>)context.getBean("externalIdentityBoundary");
            var request=new MockHttpServletRequest("GET","/api/v1/external/identity/me");request.addHeader("Authorization","Bearer "+TOKEN);var response=new MockHttpServletResponse();
            registration.getFilter().doFilter(request,response,(actual,output)->{
                var result=context.getBean(ExternalIdentityController.class).me((jakarta.servlet.http.HttpServletRequest)actual,(jakarta.servlet.http.HttpServletResponse)output);
                assertEquals("7",result.getData().get("user_id"));assertEquals(false,result.getData().get("rtc_enabled"));
            });
            verifyNoInteractions(context.getBean(SysUserTokenDao.class));assertEquals("no-store",response.getHeader("Cache-Control"));
        }
    }
}
