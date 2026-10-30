package xiaozhi.modules.annotation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import com.fasterxml.jackson.databind.json.JsonMapper;
import xiaozhi.modules.annotation.AnnotationModels.*;
import xiaozhi.modules.attachment.*;
import xiaozhi.modules.conversation.*;
import xiaozhi.modules.security.user.SecurityUser;

class AnnotationControllerTest {
    @Test void annotationRoutesRequireAllThreeExplicitFlags(){
        var runner=new ApplicationContextRunner().withBean(AnnotationService.class,()->mock(AnnotationService.class)).withBean(ConversationService.class,()->mock(ConversationService.class)).withUserConfiguration(AnnotationController.class);
        runner.run(c->assertTrue(c.getBeansOfType(AnnotationController.class).isEmpty()));
        runner.withPropertyValues("features.annotations-enabled=true","features.conversation-enabled=true").run(c->assertTrue(c.getBeansOfType(AnnotationController.class).isEmpty()));
        runner.withPropertyValues("features.annotations-enabled=true","features.conversation-enabled=true","features.attachments-enabled=true").run(c->assertEquals(1,c.getBeansOfType(AnnotationController.class).size()));
    }
    @Test void actualHttpErrorsAndStrictRequestsReuseConversationEnvelope()throws Exception {
        var json=JsonMapper.builder().build();var requests=new ConversationService(mock(ConversationDao.class),json,mock(org.springframework.transaction.PlatformTransactionManager.class));var service=mock(AnnotationService.class);
        when(service.getFrame(any(),any())).thenThrow(new ConversationProblem(404,"FRAME_NOT_FOUND"));
        var mvc=MockMvcBuilders.standaloneSetup(new AnnotationController(service,requests)).setControllerAdvice(new AnnotationExceptionHandler()).build();
        mvc.perform(get("/api/v1/support/frames/123")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
        mvc.perform(post("/api/v1/support/frames").contentType(MediaType.APPLICATION_JSON).content("{\"conversation_id\":\"x\",\"source_attachment_id\":\"123\",\"owner\":8}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/support/frames").contentType(MediaType.APPLICATION_JSON).content("{\"time_ms\":18.5}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/support/annotations")).andExpect(status().isBadRequest());
        verify(service,never()).createFrame(any(),any());
    }
    @Test void creatorComesFromSessionAndRawBodyCannotChooseIt()throws Exception {
        var requests=mock(ConversationService.class);var service=mock(AnnotationService.class);CreateFrame body=new CreateFrame("conversation","key","123",18000L);when(requests.request(any(),eq(CreateFrame.class))).thenReturn(body);
        var mvc=MockMvcBuilders.standaloneSetup(new AnnotationController(service,requests)).build();
        try(var security=mockStatic(SecurityUser.class)){security.when(SecurityUser::getUserId).thenReturn(71L);mvc.perform(post("/api/v1/support/frames").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());verify(service).createFrame(71L,body);}
    }
    @Test void generatedProviderDoesNotCreateSpringConstructorCycle(){
        var db=mock(org.springframework.jdbc.core.JdbcTemplate.class);var dao=mock(ConversationDao.class);var manager=mock(org.springframework.transaction.PlatformTransactionManager.class);var json=JsonMapper.builder().build();
        new ApplicationContextRunner().withBean(org.springframework.jdbc.core.JdbcTemplate.class,()->db).withBean(ConversationDao.class,()->dao).withBean(ConversationAccessService.class,()->new ConversationAccessService(dao)).withBean(com.fasterxml.jackson.databind.ObjectMapper.class,()->json).withBean(org.springframework.transaction.PlatformTransactionManager.class,()->manager)
            .withBean(AttachmentService.class,()->new AttachmentService(db,dao,json,manager,new org.springframework.mock.env.MockEnvironment(),mock(PrivateObjectStore.class)))
            .withUserConfiguration(AnnotationService.class).withPropertyValues("features.annotations-enabled=true","features.conversation-enabled=true","features.attachments-enabled=true")
            .run(context->{assertNull(context.getStartupFailure());assertNotNull(context.getBean(GeneratedMediaSupport.class));});
    }
}
