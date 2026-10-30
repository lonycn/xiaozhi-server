package xiaozhi.modules.supportsummary;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import xiaozhi.modules.conversation.*;
import xiaozhi.modules.support.SupportService;

/** Actual service constructors and optional injection; no mocked controller or foundation service. */
class SupportSummaryWiringTest {
    private final ApplicationContextRunner runner=new ApplicationContextRunner()
            .withUserConfiguration(SupportSummaryService.class,Foundation.class);
    @Test void defaultOffStartsWithoutAnyConversationFoundationDependency() {
        runner.run(context->{assertNull(context.getStartupFailure());assertTrue(context.getBeansOfType(SupportSummaryService.class).isEmpty());assertTrue(context.getBeansOfType(ConversationAccessService.class).isEmpty());});
    }
    @Test void eitherMissingFoundationFlagStartsWithoutSummaryService() {
        for(String[] flags:new String[][]{{"false","false"},{"true","false"},{"false","true"}})
            runner.withPropertyValues("features.conversation-enabled="+flags[0],"features.support-handoff-enabled="+flags[1]).run(context->{
                assertNull(context.getStartupFailure());assertTrue(context.getBeansOfType(SupportSummaryService.class).isEmpty());
                if(flags[0].equals("true"))assertNull(ReflectionTestUtils.getField(context.getBean(SupportService.class),"summaries"));
            });
    }
    @Test void bothFoundationFlagsWireRealSummaryAndHandoffService() {
        runner.withPropertyValues("features.conversation-enabled=true","features.support-handoff-enabled=true").run(context->{
            assertNull(context.getStartupFailure());var service=context.getBean(SupportSummaryService.class);
            assertSame(context.getBean(ConversationAccessService.class),ReflectionTestUtils.getField(service,"access"));
            assertSame(service,ReflectionTestUtils.getField(context.getBean(SupportService.class),"summaries"));
        });
    }
    @Configuration(proxyBeanMethods=false)
    @ConditionalOnProperty(prefix="features",name="conversation-enabled",havingValue="true")
    static class Foundation {
        @Bean JdbcTemplate jdbc(){return new JdbcTemplate(new DriverManagerDataSource("jdbc:mysql://127.0.0.1:1/unused_wiring_test","unused",""));}
        @Bean ObjectMapper mapper(){return new ObjectMapper();}
        @Bean PlatformTransactionManager transactions(JdbcTemplate jdbc){return new DataSourceTransactionManager(jdbc.getDataSource());}
        @Bean ConversationDao dao(JdbcTemplate jdbc){return new ConversationDao(jdbc);}
        @Bean ConversationAccessService access(ConversationDao dao){return new ConversationAccessService(dao);}
        @Bean ConversationService conversations(ConversationDao dao,ObjectMapper json,PlatformTransactionManager tx){return new ConversationService(dao,json,tx);}
        @Bean SupportService support(JdbcTemplate jdbc,ObjectMapper json,PlatformTransactionManager tx,ConversationService conversations){return new SupportService(jdbc,json,tx,conversations);}
    }
}
