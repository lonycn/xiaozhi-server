package xiaozhi.modules.annotation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import xiaozhi.modules.annotation.AnnotationModels.Frame;
import xiaozhi.modules.attachment.AttachmentModels.View;
import xiaozhi.modules.attachment.AttachmentService;
import xiaozhi.modules.conversation.ConversationAccessService;
import xiaozhi.modules.security.config.WebMvcConfig;

/** Exercise the actual MVC mapper, whose global Long serializer intentionally emits ID strings. */
class AnnotationWireTest {
    final ObjectMapper wire=new WebMvcConfig().jackson2HttpMessageConverter().getObjectMapper();
    @Test void frameMeasurementsRemainNumbersAndIdsRemainStrings() {
        var frame=new Frame("101","conversation","102","103",18000L,18033L,"first_at_or_after",1920,1080,"ready",null,Instant.now());
        var value=wire.valueToTree(frame);
        assertTrue(value.get("requested_time_ms").isIntegralNumber());assertEquals(18000,value.get("requested_time_ms").intValue());
        assertTrue(value.get("actual_time_ms").isIntegralNumber());assertEquals(18033,value.get("actual_time_ms").intValue());
        assertTrue(value.get("id").isTextual());assertTrue(value.get("source_attachment_id").isTextual());assertTrue(value.get("width").isIntegralNumber());
    }
    @SuppressWarnings("unchecked")
    @Test void captureClaimAndTrustedMetadataKeepBoundedMeasurementNumbersOnActualWire() {
        var db=mock(JdbcTemplate.class);var attachments=mock(AttachmentService.class);
        var frame=new AnnotationService.F(101,"conversation",7,"client","hash",201,202,18000L,null,Instant.now());
        doReturn(List.of(frame)).when(db).query(eq("SELECT * FROM support_frame WHERE frame_attachment_id=?"),any(RowMapper.class),eq(202L));
        var source=new View("201","conversation","source.mp4","video/mp4","video",123,"hash","ready",null,Map.of("width",1920,"height",1080,"duration_ms",20000),List.of(),Instant.now());
        when(attachments.serverSource(7L,"201","conversation",true)).thenReturn(source);
        var service=new AnnotationService(db,attachments,mock(ConversationAccessService.class),new ObjectMapper(),mock(PlatformTransactionManager.class));
        var input=wire.valueToTree(service.input(202,"capture_frame").metadata());
        assertTrue(input.get("requested_time_ms").isIntegralNumber());assertEquals(18000,input.get("requested_time_ms").intValue());assertTrue(input.get("expected_width").isIntegralNumber());assertTrue(input.get("frame_id").isTextual());
        var complete=wire.valueToTree(service.complete(202,"capture_frame",Map.of("mime_type","image/jpeg","width",1920L,"height",1080L,"actual_time_ms",18033L)));
        for(String key:List.of("requested_time_ms","actual_time_ms","width","height"))assertTrue(complete.get(key).isIntegralNumber(),key);
        assertEquals(18033,complete.get("actual_time_ms").intValue());assertTrue(complete.get("source_attachment_id").isTextual());
    }
}
