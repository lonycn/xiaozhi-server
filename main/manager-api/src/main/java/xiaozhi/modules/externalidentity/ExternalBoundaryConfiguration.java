package xiaozhi.modules.externalidentity;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;

@Configuration
public class ExternalBoundaryConfiguration {
    @Bean public FilterRegistrationBean<ExternalBoundaryFilter> externalIdentityBoundary(ObjectProvider<ExternalIdentityService> services) {
        var registration=new FilterRegistrationBean<>(new ExternalBoundaryFilter(services::getIfAvailable));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE+10);registration.addUrlPatterns("/*");return registration;
    }
}
