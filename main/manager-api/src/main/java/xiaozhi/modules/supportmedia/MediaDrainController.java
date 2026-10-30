package xiaozhi.modules.supportmedia;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.security.user.SecurityUser;

/** Resource-scoped cleanup visibility remains available while publishing is switched off. */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(prefix="features",name={"conversation-enabled","support-handoff-enabled"},havingValue="true")
public class MediaDrainController {
    private final MediaService service;
    @GetMapping("/api/v1/support/media-drain") public Result<?> status(@RequestParam(value="conversation_id",required=false) String conversation){return new Result<>().ok(service.drainStatus(SecurityUser.getUserId(),conversation));}
}
