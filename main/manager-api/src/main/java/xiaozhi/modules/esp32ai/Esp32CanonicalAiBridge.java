package xiaozhi.modules.esp32ai;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import xiaozhi.modules.ai.AiExecutionService;
import xiaozhi.modules.esp32conversation.*;
import java.util.*;

/** Only enqueues the canonical worker. Does not create messages or run a model. */
@Service
public class Esp32CanonicalAiBridge implements Esp32AiExecutionBridge {
    private final ObjectProvider<AiExecutionService> ai;
    private final JdbcTemplate db;
    private final Esp32AiRunGuard guard;
    public Esp32CanonicalAiBridge(ObjectProvider<AiExecutionService> ai,JdbcTemplate db,Esp32AiRunGuard guard){this.ai=ai;this.db=db;this.guard=guard;}
    @Override public void submit(Esp32ConversationService.TrustedContext ctx,String conversationId,String messageId,String turnId){ai.getObject().onDeviceUserMessage(conversationId,messageId,ctx,turnId);}
    @Override public void submit(Esp32ConversationService.TrustedContext ctx,String conversationId,String messageId,String turnId,String rolePrompt){
        ai.getObject().onDeviceUserMessage(conversationId,messageId,ctx,turnId,rolePrompt);
    }
    @Override @Transactional public Map<String,Object> reply(String conversationId,String messageId){
        var rows=db.queryForList("SELECT r.id,r.status,r.output_text,r.output_message_id,m.status AS message_status FROM ai_run r JOIN ai_message m ON m.id=r.output_message_id WHERE r.conversation_id=? AND r.input_message_id=?",conversationId,messageId);
        if(rows.size()!=1)throw Esp32AiRunGuard.fail("ESP32_RUN_UNAVAILABLE");var r=rows.getFirst();String id=(String)r.get("id");
        if(!guard.bound(id))throw Esp32AiRunGuard.fail("ESP32_RUN_SOURCE_MISMATCH");guard.validate(id);
        var conversation=db.queryForMap("SELECT state,control_epoch FROM ai_conversation WHERE id=? FOR UPDATE",conversationId);
        r=db.queryForMap("SELECT r.id,r.status,r.control_epoch,r.output_text,r.output_message_id,m.status AS message_status FROM ai_run r JOIN ai_message m ON m.id=r.output_message_id WHERE r.id=? FOR UPDATE",id);
        String state=(String)r.get("status");
        if(!"AI_ACTIVE".equals(conversation.get("state"))||((Number)conversation.get("control_epoch")).longValue()!=((Number)r.get("control_epoch")).longValue())return Map.of("status","canceled","runId",id);
        if("completed".equals(state)&&"completed".equals(r.get("message_status"))){String text=Objects.toString(r.get("output_text"),"");if(text.isBlank()||text.length()>16000)return Map.of("status","failed","runId",id,"errorCode","ESP32_REPLY_TEXT_INVALID");return Map.of("status","completed","runId",id,"text",text);}
        if(Set.of("failed","canceled").contains(state))return Map.of("status",state,"runId",id);
        if(!Set.of("queued","running","streaming").contains(state))throw Esp32AiRunGuard.fail("ESP32_RUN_STATE_INVALID");
        return Map.of("status","pending","runId",id);
    }
}
