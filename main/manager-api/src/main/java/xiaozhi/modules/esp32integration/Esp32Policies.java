package xiaozhi.modules.esp32integration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import xiaozhi.modules.esp32conversation.Esp32ConversationService;
import xiaozhi.modules.conversation.ConversationProblem;

/** Device conversations have one gateway-owned AI input path and retain binding checks offline. */
@Service
public class Esp32Policies {
    private final ObjectProvider<Esp32ConversationService> sessions;
    public Esp32Policies(ObjectProvider<Esp32ConversationService> sessions){this.sessions=sessions;}
    public void requireOrdinaryInput(String id){var s=sessions.getIfAvailable();if(s!=null&&s.isAiOnly(id))throw new ConversationProblem(409,"ESP32_DEVICE_CHANNEL_REQUIRED");}
    public void requireNoHuman(String id){var s=sessions.getIfAvailable();if(s!=null)s.requireNoHuman(id);}
    public void requireRead(String id,long owner){var s=sessions.getIfAvailable();if(s!=null)s.requireCurrentBindingForConversation(id,owner);}
    public boolean mayRead(String id,long owner){try{requireRead(id,owner);return true;}catch(ConversationProblem e){if(e.status()==404||e.status()==403)return false;throw e;}}
}
