package xiaozhi.modules.security.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.apache.shiro.session.mgt.SessionManager;
import org.apache.shiro.spring.web.ShiroFilterFactoryBean;
import org.apache.shiro.web.mgt.WebSecurityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Role;

import xiaozhi.modules.security.oauth2.Oauth2Realm;
import xiaozhi.modules.sys.service.SysParamsService;

class ShiroConfigTest {

    @Test
    void beanPostProcessorFactoriesDoNotInstantiateShiroConfigOrBusinessDependenciesEarly() throws Exception {
        Method lifecycleFactory = ShiroConfig.class.getDeclaredMethod("lifecycleBeanPostProcessor");
        Method filterFactory = ShiroConfig.class.getDeclaredMethod(
                "shiroFilterWithWorker", WebSecurityManager.class, SysParamsService.class, org.springframework.core.env.Environment.class);

        assertTrue(Modifier.isStatic(lifecycleFactory.getModifiers()));
        assertTrue(BeanPostProcessor.class.isAssignableFrom(ShiroFilterFactoryBean.class));
        assertTrue(Modifier.isStatic(filterFactory.getModifiers()));
        assertNotNull(filterFactory.getAnnotation(Bean.class));
        assertEquals("shiroFilter", filterFactory.getAnnotation(Bean.class).value()[0]);
        Lazy securityManagerLazy = filterFactory.getParameters()[0].getAnnotation(Lazy.class);
        Lazy sysParamsServiceLazy = filterFactory.getParameters()[1].getAnnotation(Lazy.class);
        assertNotNull(securityManagerLazy);
        assertNotNull(sysParamsServiceLazy);
        assertTrue(securityManagerLazy.value());
        assertTrue(sysParamsServiceLazy.value());
    }

    @Test
    void earlyShiroFactoryReadsWorkerCredentialFromEnvironmentAndKeepsItScoped() throws Exception {
        // Refresh the real configuration: a direct factory call cannot reproduce early BPP creation.
        String synthetic = "b2-isolated-worker-key-not-for-production";
        for (String configured : java.util.List.of(synthetic, "")) {
            try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
                context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                        "worker-test", java.util.Map.of("ai.worker.key", configured)));
                // Registered singletons avoid injecting mocked realms' own business dependencies.
                context.getBeanFactory().registerSingleton("oauth2Realm", org.mockito.Mockito.mock(Oauth2Realm.class));
                context.getBeanFactory().registerSingleton("sysParamsService", org.mockito.Mockito.mock(SysParamsService.class));
                context.register(ShiroConfig.class);
                context.refresh();
                var factory = (ShiroFilterFactoryBean) context.getBean("&shiroFilter");
                var worker = (org.apache.shiro.web.filter.AccessControlFilter) factory.getFilters().get("aiWorker");
                var request = new org.springframework.mock.web.MockHttpServletRequest();
                var response = new org.springframework.mock.web.MockHttpServletResponse();
                request.addHeader("X-AI-Worker-Key", synthetic);
                assertEquals(!configured.isEmpty(), worker.onPreHandle(request, response, null));
                request.removeHeader("X-AI-Worker-Key");
                request.addHeader("Authorization", "Bearer " + synthetic);
                var bearerResponse = new org.springframework.mock.web.MockHttpServletResponse();
                org.junit.jupiter.api.Assertions.assertFalse(worker.onPreHandle(request, bearerResponse, null));
                assertEquals(401, bearerResponse.getStatus());
                request.addHeader("X-AI-Worker-Key", "wrong-key");
                org.junit.jupiter.api.Assertions.assertFalse(worker.onPreHandle(request,
                        new org.springframework.mock.web.MockHttpServletResponse(), null));
                assertEquals("aiWorker", factory.getFilterChainDefinitionMap().get("/internal/ai-worker/**"));
                assertEquals("conversation", factory.getFilterChainDefinitionMap().get("/api/v1/ai/**"));
                assertEquals("oauth2", factory.getFilterChainDefinitionMap().get("/**"));
            }
        }
    }

    @Test
    void authorizationAdvisorIsInfrastructureAndDefersItsSecurityManager() throws Exception {
        Method advisorFactory = ShiroConfig.class.getDeclaredMethod(
                "authorizationAttributeSourceAdvisor", WebSecurityManager.class);

        assertTrue(Modifier.isStatic(advisorFactory.getModifiers()));
        Lazy securityManagerLazy = advisorFactory.getParameters()[0].getAnnotation(Lazy.class);
        assertNotNull(securityManagerLazy);
        assertTrue(securityManagerLazy.value());
        assertEquals(BeanDefinition.ROLE_INFRASTRUCTURE, advisorFactory.getAnnotation(Role.class).value());
    }

    @Test
    void securityManagerRetainsTheWebSecurityContractRequiredByShiroFilter() throws Exception {
        Method securityManagerFactory = ShiroConfig.class.getDeclaredMethod(
                "securityManager", Oauth2Realm.class, SessionManager.class);

        assertEquals(WebSecurityManager.class, securityManagerFactory.getReturnType());
    }
}
