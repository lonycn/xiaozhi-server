package xiaozhi.modules.supportmedia;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import xiaozhi.modules.conversation.ConversationDao;
import xiaozhi.modules.supportmedia.livekit.LiveKitAdapter;

class MediaStoreProxyTest {
    @Test void accountHookWorksThroughTheActualRepositoryProxy() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(7L))).thenReturn(true);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(PersistenceExceptionTranslationPostProcessor.class);
            context.registerBean(MediaStore.class,
                    () -> new MediaStore(jdbc, mock(PlatformTransactionManager.class)));
            context.refresh();
            MediaStore store = context.getBean(MediaStore.class);
            assertTrue(AopUtils.isCglibProxy(store));
            assertSame(jdbc, store.jdbc());
            MediaService service = new MediaService(store, mock(MediaPolicy.class),
                    mock(LiveKitAdapter.class), mock(ConversationDao.class),
                    new ObjectMapper(), new MockEnvironment());
            assertDoesNotThrow(() -> service.accountChanged(7L));
            verify(jdbc).queryForObject(anyString(), eq(Boolean.class), eq(7L));
        }
    }
}
