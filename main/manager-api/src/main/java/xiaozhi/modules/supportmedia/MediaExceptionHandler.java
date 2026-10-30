package xiaozhi.modules.supportmedia;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.supportmedia.livekit.LiveKitFailure;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes={MediaController.class,MediaWebhookController.class,MediaDrainController.class})
public class MediaExceptionHandler extends xiaozhi.modules.conversation.ConversationExceptionHandler {
    @ExceptionHandler(LiveKitFailure.class) public ResponseEntity<Result<Void>> livekit(LiveKitFailure error){int status=switch(error.code()){case INVALID_WEBHOOK->401;case AUTH,OWNERSHIP_DENIED->403;case INVALID_REQUEST->400;case RATE_LIMIT->429;case NOT_FOUND->409;default->503;};return ResponseEntity.status(status).contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(new Result<Void>().error(status,"LIVEKIT_"+error.code().name()));}
}
