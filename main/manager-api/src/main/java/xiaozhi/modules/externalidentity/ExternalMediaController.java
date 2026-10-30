package xiaozhi.modules.externalidentity;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ContentDisposition;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.*;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.attachment.*;
import xiaozhi.common.jdbc.TimestampColumnMapRowMapper;
import xiaozhi.modules.externalidentity.ExternalModels.*;

/** Source-bound capabilities; never exposes the legacy actor-only ticket or object-store URL. */
@RestController
@RequestMapping("/api/v1/external/attachments")
@ConditionalOnProperty(prefix="features",name={"external-identity-enabled","conversation-enabled","attachments-enabled"},havingValue="true")
public class ExternalMediaController {
    private final ExternalIdentityService identity;private final AttachmentService attachments;private final JdbcTemplate db;
    public ExternalMediaController(ExternalIdentityService identity,AttachmentService attachments,JdbcTemplate db){this.identity=identity;this.attachments=attachments;this.db=db;}
    @GetMapping("/{id}/access") public Result<MediaAccess> access(@PathVariable String id,@RequestParam String variant,HttpServletRequest request,HttpServletResponse response) {
        ExternalIdentityController.privateResponse(response);positiveId(id);Principal actor=identity.required(request);
        var resource=attachments.access(actor.userId(),id,variant,"");
        String token=ExternalIdentityService.random("xzi_media_");Instant expiry=ExternalIdentityService.min(Instant.now().plusSeconds(60),actor.expiresAt());
        db.update("INSERT INTO external_media_ticket(token_hash,bearer_id,attachment_id,variant,expires_at) VALUES(?,?,?,?,?)",
            ExternalIdentityService.hash(token),actor.tokenId(),Long.parseLong(id),variant,ExternalIdentityService.ts(expiry));
        String url=request.getContextPath()+"/api/v1/external/attachments/"+id+"/content?ticket="+token;
        return new Result<MediaAccess>().ok(new MediaAccess(url,expiry,resource.mimeType(),resource.filename()));
    }
    @GetMapping("/{id}/content") public void content(@PathVariable String id,@RequestParam String ticket,HttpServletRequest request,HttpServletResponse response)throws IOException {
        positiveId(id);if(!request.getParameterMap().keySet().equals(Set.of("ticket")) || ticket==null || !ticket.matches("xzi_media_[a-f0-9]{64}"))throw new ExternalProblem(401,"MEDIA_TICKET_INVALID");
        var rows=db.query("SELECT m.*,t.token_hash AS bearer_hash FROM external_media_ticket m JOIN external_access_token t ON t.id=m.bearer_id WHERE m.token_hash=? AND m.attachment_id=?",new TimestampColumnMapRowMapper(),ExternalIdentityService.hash(ticket),Long.parseLong(id));
        if(rows.isEmpty())throw new ExternalProblem(401,"MEDIA_TICKET_INVALID");var capability=rows.getFirst();
        if(!ExternalIdentityService.instant(capability.get("expires_at")).isAfter(Instant.now()))throw new ExternalProblem(401,"MEDIA_TICKET_EXPIRED");
        String bearer=(String)capability.get("bearer_hash");Principal actor=identity.authenticateHash(bearer);
        // Reuse dynamic owner/assignment checks, then open the stream outside the authorization transaction.
        var current=attachments.access(actor.userId(),id,(String)capability.get("variant"),"");
        String rawQuery=URI.create(current.url()).getRawQuery();String legacyTicket=null;
        for(String pair:rawQuery.split("&")){String[] parts=pair.split("=",2);if(parts.length==2 && parts[0].equals("ticket"))legacyTicket=URLDecoder.decode(parts[1],StandardCharsets.UTF_8);}
        if(legacyTicket==null)throw new ExternalProblem(503,"MEDIA_UNAVAILABLE");
        try(var content=attachments.ticketContent(id,legacyTicket)) {
            ExternalIdentityController.privateResponse(response);response.setHeader("X-Content-Type-Options","nosniff");response.setHeader("Content-Security-Policy","sandbox; default-src 'none'");
            response.setContentType(content.mimeType());response.setHeader("Content-Disposition",(content.inline()?ContentDisposition.inline():ContentDisposition.attachment()).filename(content.filename(),StandardCharsets.UTF_8).build().toString());
            boolean rangeMedia=content.mimeType().startsWith("audio/")||content.mimeType().startsWith("video/");String range=rangeMedia?request.getHeader("Range"):null;
            AttachmentRules.ByteRange selected;
            try {selected=AttachmentRules.range(range,content.size());}catch(xiaozhi.modules.conversation.ConversationProblem invalid){response.setStatus(416);response.setHeader("Content-Range","bytes */"+content.size());return;}
            if(rangeMedia)response.setHeader("Accept-Ranges","bytes");
            if(range!=null){response.setStatus(206);response.setHeader("Content-Range","bytes "+selected.start()+"-"+selected.end()+"/"+content.size());}
            response.setContentLengthLong(selected.length());content.input().skipNBytes(selected.start());long remaining=selected.length(),lastCheck=0;byte[] buffer=new byte[65536];
            while(remaining>0) {
                if(System.nanoTime()-lastCheck>=1_000_000_000L){
                    try {identity.authenticateHash(bearer);}catch(RuntimeException revoked){return;}lastCheck=System.nanoTime();
                }
                int count=content.input().read(buffer,0,(int)Math.min(buffer.length,remaining));if(count<0)throw new EOFException("Private media truncated");
                // A slow source read must not emit bytes after authority was revoked while it blocked.
                if(System.nanoTime()-lastCheck>=1_000_000_000L){try{identity.authenticateHash(bearer);}catch(RuntimeException revoked){return;}lastCheck=System.nanoTime();}
                response.getOutputStream().write(buffer,0,count);remaining-=count;
            }
        }
    }
    static void positiveId(String id){try{if(id==null || !id.matches("[1-9][0-9]{0,18}") || Long.parseLong(id)<1)throw new NumberFormatException();}catch(NumberFormatException invalid){throw new ExternalProblem(404,"ATTACHMENT_NOT_FOUND");}}
}
