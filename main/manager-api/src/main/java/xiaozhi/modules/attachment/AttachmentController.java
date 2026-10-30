package xiaozhi.modules.attachment;

import java.io.*;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ContentDisposition;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.attachment.AttachmentModels.*;
import xiaozhi.modules.conversation.*;
import xiaozhi.modules.security.user.SecurityUser;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(prefix="features",name={"conversation-enabled","attachments-enabled"},havingValue="true")
public class AttachmentController {
    private final AttachmentService service;
    private final ConversationService requests;
    @PostMapping("/api/v1/ai/uploads/init")
    public Result<InitView> init(@RequestBody JsonNode body,HttpServletRequest request){return new Result<InitView>().ok(service.init(SecurityUser.getUserId(),requests.request(body,Init.class),request.getContextPath()));}
    @PutMapping("/api/v1/ai/uploads/{id}/content")
    public Result<View> upload(@PathVariable String id,HttpServletRequest request)throws IOException{return new Result<View>().ok(service.upload(SecurityUser.getUserId(),id,request.getContentType(),request.getContentLengthLong(),request.getInputStream()));}
    @PostMapping("/api/v1/ai/uploads/{id}/complete")
    public Result<View> complete(@PathVariable String id,@RequestBody JsonNode body){requests.request(body,Empty.class);return new Result<View>().ok(service.completeUpload(SecurityUser.getUserId(),id));}
    @GetMapping("/api/v1/ai/attachments/{id}")
    public Result<View> get(@PathVariable String id){return new Result<View>().ok(service.get(SecurityUser.getUserId(),id));}
    @PostMapping("/api/v1/ai/attachments/{id}/retry")
    public Result<View> retry(@PathVariable String id,@RequestBody JsonNode body){requests.request(body,Empty.class);return new Result<View>().ok(service.retry(SecurityUser.getUserId(),id));}
    @GetMapping("/api/v1/ai/attachments/{id}/access")
    public Result<Access> access(@PathVariable String id,@RequestParam(defaultValue="display") String variant,HttpServletRequest request){return new Result<Access>().ok(service.access(SecurityUser.getUserId(),id,variant,request.getContextPath()));}
    @GetMapping("/api/v1/ai/attachments/{id}/content")
    public void content(@PathVariable String id,@RequestParam String ticket,HttpServletRequest request,HttpServletResponse response)throws IOException{
        try(var selected=service.ticketContent(id,ticket)){write(selected,request,response,true);}
    }
    static void write(AttachmentService.Content content,HttpServletRequest request,HttpServletResponse response,boolean ranges)throws IOException {
        response.setContentType(content.mimeType());response.setHeader("X-Content-Type-Options","nosniff");response.setHeader("Cache-Control","private, no-store");response.setHeader("Pragma","no-cache");response.setHeader("Referrer-Policy","no-referrer");response.setHeader("Content-Security-Policy","sandbox; default-src 'none'");
        response.setHeader("Content-Disposition",(content.inline()?ContentDisposition.inline():ContentDisposition.attachment()).filename(content.filename(),StandardCharsets.UTF_8).build().toString());
        boolean media=ranges && (content.mimeType().startsWith("audio/") || content.mimeType().startsWith("video/"));String range=media?request.getHeader("Range"):null;
        AttachmentRules.ByteRange selected;
        if(media)response.setHeader("Accept-Ranges","bytes");
        try {selected=AttachmentRules.range(range,content.size());}
        catch(ConversationProblem p){response.setStatus(416);response.setHeader("Content-Range","bytes */"+content.size());return;}
        if(range!=null){response.setStatus(206);response.setHeader("Content-Range","bytes "+selected.start()+"-"+selected.end()+"/"+content.size());}
        response.setContentLengthLong(selected.length());content.input().skipNBytes(selected.start());long remaining=selected.length();byte[] buffer=new byte[65536];
        while(remaining>0){int n=content.input().read(buffer,0,(int)Math.min(buffer.length,remaining));if(n<0)throw new EOFException("Private media truncated");response.getOutputStream().write(buffer,0,n);remaining-=n;}
    }
}
