package xiaozhi.modules.conversation;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import xiaozhi.modules.esp32integration.Esp32Policies;
import xiaozhi.modules.security.user.SecurityUser;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
class ConversationIndexControllerTest {
    @Test void invalidAndDuplicateQueriesKeepActualHttp400InsteadOfLegacyHttp200() throws Exception {
        var controller=new ConversationIndexController(new ConversationIndexService(mock(JdbcTemplate.class),mock(Esp32Policies.class)));
        var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ConversationExceptionHandler()).build();
        try(var user=mockStatic(SecurityUser.class);var security=mockStatic(SecurityUtils.class)) {
            user.when(SecurityUser::getUserId).thenReturn(1L);Subject subject=mock(Subject.class);security.when(SecurityUtils::getSubject).thenReturn(subject);when(subject.isPermitted("sys:role:normal")).thenReturn(true);
            mvc.perform(get("/api/v1/ai/conversations/index").param("owner_user_id","2")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
            mvc.perform(get("/api/v1/ai/conversations/index").param("source","app","hardware")).andExpect(status().isBadRequest());
            mvc.perform(get("/api/v1/ai/conversations/index").param("source","invalid")).andExpect(status().isBadRequest());
            mvc.perform(get("/api/v1/ai/conversations/index/options").param("owner","2")).andExpect(status().isBadRequest());
        }
    }
}
