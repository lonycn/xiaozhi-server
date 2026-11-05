package xiaozhi.modules.supportmedia;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import com.fasterxml.jackson.databind.json.JsonMapper;
import xiaozhi.modules.conversation.ConversationProblem;
import xiaozhi.modules.externalidentity.ExternalPathPolicy;
import xiaozhi.modules.supportmedia.MediaModels.*;

class AppCallDialResultTest {
    @Test void offlineBusyAndSelfBusyAreExpectedDialOutcomesButAuthorizationStillFails(){
        var service=spy(new MediaService(null,null,null,null,JsonMapper.builder().build(),new MockEnvironment()));
        var body=new Create("a".repeat(32),"fixture","video",new Consent(true,true,false));
        for(var entry:Map.of("MEDIA_AGENT_OFFLINE","offline","MEDIA_AGENT_BUSY","busy","MEDIA_CUSTOMER_BUSY","self_busy","AGENT_AT_CAPACITY","busy").entrySet()){
            doThrow(new ConversationProblem(409,entry.getKey())).when(service).create(7L,body);
            var result=service.dial(7L,body);assertEquals(entry.getValue(),result.status());assertNull(result.session());
        }
        for(int status:List.of(401,403,404)){
            doThrow(new ConversationProblem(status,"MEDIA_NOT_FOUND")).when(service).create(7L,body);
            assertEquals(status,assertThrows(ConversationProblem.class,()->service.dial(7L,body)).status());
        }
    }
    @Test void externalDialIsExplicitPostOnlyAndRequiresRealtimeGate(){
        String path="/api/v1/support/media-sessions/dial";
        assertTrue(ExternalPathPolicy.allowed("POST",path));assertTrue(ExternalPathPolicy.rtc(path));
        assertFalse(ExternalPathPolicy.allowed("GET",path));assertFalse(ExternalPathPolicy.allowed("POST",path+"/other"));
    }
}
