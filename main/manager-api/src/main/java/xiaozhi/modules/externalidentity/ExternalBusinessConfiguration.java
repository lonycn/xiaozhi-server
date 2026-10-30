package xiaozhi.modules.externalidentity;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@ConditionalOnProperty(name={"external-business.enabled","features.external-identity-enabled"},havingValue="true")
public class ExternalBusinessConfiguration {
    @Bean
    @ConditionalOnMissingBean(ExternalBusinessAdapter.class)
    ExternalBusinessAdapter externalBusinessHttpAdapter(ExternalBusinessSettings settings,
            ExternalSettings identities,ExternalIdentityService identity,JdbcTemplate db) {
        return new ExternalBusinessHttpAdapter(settings,identities,identity,db);
    }
}
