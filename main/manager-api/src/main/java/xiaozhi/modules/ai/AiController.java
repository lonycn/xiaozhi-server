package xiaozhi.modules.ai;

import java.io.IOException;
import java.util.concurrent.Semaphore;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.ai.AiModels.*;
import xiaozhi.modules.conversation.*;
import xiaozhi.modules.conversation.ConversationModels.CursorPage;
import xiaozhi.modules.security.user.SecurityUser;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ai/conversations/{id}")
@ConditionalOnProperty(prefix="features",name={"conversation-enabled","ai-text-enabled"},havingValue="true")
public class AiController {
    private final AiExecutionService service;
    private final ConversationService conversations;
    private final ObjectMapper json;
    private final Semaphore streams = new Semaphore(16);
    private xiaozhi.modules.externalidentity.ExternalIdentityService externalIdentity;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setExternalIdentity(xiaozhi.modules.externalidentity.ExternalIdentityService service){externalIdentity=service;}
    @GetMapping("/runs") public Result<CursorPage<Run>> list(@PathVariable String id,
            @RequestParam(defaultValue="0") long before,@RequestParam(defaultValue="50") int limit) {
        return new Result<CursorPage<Run>>().ok(service.list(SecurityUser.getUserId(),id,before,limit));
    }
    @GetMapping("/runs/{runId}") public Result<Run> get(@PathVariable String id,@PathVariable String runId) {
        return new Result<Run>().ok(service.get(SecurityUser.getUserId(),id,runId));
    }
    @PostMapping("/runs/{runId}/cancel") public Result<Run> cancel(@PathVariable String id,@PathVariable String runId,@RequestBody JsonNode body) {
        conversations.request(body,Empty.class);
        return new Result<Run>().ok(service.cancel(SecurityUser.getUserId(),id,runId));
    }
    @PostMapping("/runs/{runId}/retry") public Result<Run> retry(@PathVariable String id,@PathVariable String runId,@RequestBody JsonNode body) {
        conversations.request(body,Empty.class);
        return new Result<Run>().ok(service.retry(SecurityUser.getUserId(),id,runId));
    }
    /** Bounded synchronous servlet keeps Shiro identity on the request thread. Every batch reauthorizes. */
    @GetMapping(value="/stream",produces="text/event-stream")
    public void stream(@PathVariable String id,@RequestParam(defaultValue="0") long after,
            @RequestHeader(value="Last-Event-ID",required=false) String last,
            HttpServletRequest request,HttpServletResponse response) throws IOException {
        String authorization=request.getHeader("Authorization");
        if(authorization==null || !authorization.startsWith("Bearer ")) throw new ConversationProblem(401,"BEARER_REQUIRED");
        long cursor=after;
        if(last!=null && !last.isBlank()) {try {cursor=Long.parseLong(last);}catch(NumberFormatException e){throw new ConversationProblem(400,"INVALID_CURSOR_OR_LIMIT");}}
        Long actor=SecurityUser.getUserId();
        Runnable sourceGuard=externalIdentity==null?()->{}:externalIdentity.longLivedGuard(request);
        CursorPage<ConversationModels.Event> first;
        try {
            sourceGuard.run();service.authorizeStream(actor);
            first=conversations.events(actor,id,cursor,100);
        } catch (TransientDataAccessException | RecoverableDataAccessException unavailable) {
            // No SSE bytes or content type have been written: the API advice can still emit JSON.
            throw new ConversationProblem(503,"STREAM_UNAVAILABLE");
        }
        if(!streams.tryAcquire())throw new ConversationProblem(429,"STREAM_CAPACITY");
        try {
            response.setStatus(200); response.setContentType("text/event-stream"); response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control","no-cache, no-transform"); response.setHeader("X-Accel-Buffering","no");
            var writer=response.getWriter(); long deadline=System.nanoTime()+25_000_000_000L;
            var batch=first;
            while(System.nanoTime()<deadline) {
                try {sourceGuard.run();}catch(ConversationProblem | DataAccessException revokedOrUnavailable){return;}
                for(var event:batch.items()) {
                    writer.write("id: "+event.sequence()+"\nevent: "+event.type()+"\ndata: "+json.writeValueAsString(java.util.Map.of(
                            "event_id",event.eventId(),"conversation_id",event.conversationId(),
                            "sequence",Long.toString(event.sequence()),"type",event.type(),
                            "data",event.data(),"timestamp",event.timestamp().toString()))+"\n\n");
                    cursor=event.sequence();
                }
                writer.write(": heartbeat\n\n"); writer.flush(); if(writer.checkError())return;
                if(!batch.hasMore()) try {Thread.sleep(1000);}catch(InterruptedException e){Thread.currentThread().interrupt();return;}
                // Includes assignment release cutoffs, profile/account disablement and owner access.
                try {sourceGuard.run();service.authorizeStream(actor); batch=conversations.events(actor,id,cursor,100);} catch (ConversationProblem | DataAccessException unavailable) {
                    // The last flushed event is the resume cursor. Do not invoke a JSON error handler
                    // after the response has become SSE; the client reconnects using that cursor.
                    return;
                }
            }
        } finally {streams.release();}
    }
}
