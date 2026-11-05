package xiaozhi.modules.esp32conversation;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import xiaozhi.modules.conversation.ConversationProblem;
import xiaozhi.modules.esp32gateway.Esp32GatewayService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Esp32ConversationWireTest {
    final Esp32ConversationService sessions=mock(Esp32ConversationService.class);
    final Esp32GatewayService gateway=mock(Esp32GatewayService.class);
    final Esp32ConversationController controller=new Esp32ConversationController(sessions,gateway,new ObjectMapper());
    final String fence="{\"deviceId\":\"device\",\"connectionId\":\"connection\",\"generation\":1,\"gatewayId\":\"gateway\",\"bindingVersion\":\"binding\"}";
    @Test void rejectsSpoofingDuplicatesAndCoercionBeforeRegistryAccess(){
        for(String body:new String[]{"null","[]","{}","{\"fence\":"+fence+",\"executionMode\":\"ai_worker\",\"ownerId\":7}","{\"fence\":"+fence+",\"executionMode\":\"ai_worker\",\"executionMode\":\"legacy_record_only\"}","{\"fence\":"+fence.replace("\"generation\":1","\"generation\":\"1\"")+",\"executionMode\":\"ai_worker\"}","{\"fence\":"+fence+",\"executionMode\":\"ai_worker\"} {}"}) {
            assertEquals(400,assertThrows(ConversationProblem.class,()->controller.connect(body)).status());
        }
        verifyNoInteractions(gateway,sessions);
    }
}
