package xiaozhi.modules.support;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.conversation.ConversationModels.CursorPage;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.support.SupportModels.Expert;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/support")
@ConditionalOnProperty(prefix="features", name={"conversation-enabled","support-handoff-enabled"}, havingValue="true")
public class SupportDirectoryController {
    private final SupportProfileService profiles;
    @GetMapping("/experts")
    public Result<CursorPage<Expert>> experts(@RequestParam(defaultValue="0") long before,@RequestParam(defaultValue="50") int limit) {
        return new Result<CursorPage<Expert>>().ok(profiles.experts(SecurityUser.getUserId(),before,limit));
    }
    @GetMapping("/avatars/{user}/{version}")
    public ResponseEntity<byte[]> avatar(@PathVariable long user,@PathVariable String version) {
        var avatar=profiles.avatar(user,version);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(avatar.mime()))
            .header("X-Content-Type-Options","nosniff").header("Cache-Control","public, max-age=300").body(avatar.bytes());
    }
}
