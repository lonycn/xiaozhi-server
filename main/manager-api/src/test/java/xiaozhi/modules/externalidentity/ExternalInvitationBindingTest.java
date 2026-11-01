package xiaozhi.modules.externalidentity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import xiaozhi.modules.externalidentity.ExternalModels.*;

class ExternalInvitationBindingTest {
    @Test void aDifferentSourceOrTokenCannotUseBoundSessionEvenForSameUser() {
        var authority=new ExternalMediaAuthority(mock(JdbcTemplate.class),mock(ExternalSettings.class),mock(Environment.class),mock(PlatformTransactionManager.class));
        var request=new MockHttpServletRequest();
        var session=Map.<String,Object>of("customer_user_id",42L,"external_source_id",23L,"external_token_id",17L,"status","connecting");
        try {
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            request.setAttribute(ExternalIdentityService.ATTRIBUTE,principal(24,17));
            assertThrows(ExternalProblem.class,()->authority.assertCustomer(session,42,"customer_user_id"));
            request.setAttribute(ExternalIdentityService.ATTRIBUTE,principal(23,18));
            assertThrows(ExternalProblem.class,()->authority.assertCustomer(session,42,"customer_user_id"));
        } finally { RequestContextHolder.resetRequestAttributes(); }
    }
    private static Principal principal(long source,long token) {
        return new Principal(token,"hash",source,42,"issuer","subject",new Tenant("org","x"),new Scope(List.of(),List.of()),Instant.now().plusSeconds(60));
    }
}
