package xiaozhi.modules.externalidentity;

import java.io.IOException;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.*;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.externalidentity.ExternalModels.*;

@RestController
@ConditionalOnProperty(prefix="features",name="external-identity-enabled",havingValue="true")
@RequestMapping("/api/v1/external/identity")
public class ExternalIdentityController {
    private final ExternalIdentityService service;private final ObjectMapper json;
    public ExternalIdentityController(ExternalIdentityService service,ObjectMapper json){this.service=service;this.json=json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);}
    @PostMapping("/issue") public Result<Issued> issue(HttpServletRequest request,HttpServletResponse response)throws IOException {
        privateResponse(response);byte[] body=signedBody(request,ExternalIdentityService.ISSUE);return new Result<Issued>().ok(service.issue(service.verify(request.getRequestURI(),body,headers(request)),body));
    }
    @PostMapping("/revoke") public void revoke(HttpServletRequest request,HttpServletResponse response)throws IOException {
        privateResponse(response);byte[] body=signedBody(request,ExternalIdentityService.REVOKE);service.revoke(service.verify(request.getRequestURI(),body,headers(request)),body);response.setStatus(204);
    }
    @PostMapping("/renew") public Result<Map<String,Object>> renew(HttpServletRequest request,HttpServletResponse response)throws IOException {
        privateResponse(response);byte[] body=signedBody(request,ExternalIdentityService.RENEW);
        return new Result<Map<String,Object>>().ok(service.renew(request.getRequestURI(),body,headers(request)));
    }
    @PostMapping("/exchange") public Result<Exchanged> exchange(HttpServletRequest request,HttpServletResponse response)throws IOException {
        privateResponse(response);if(request.getQueryString()!=null)throw new ExternalProblem(400,"INVALID_EXTERNAL_REQUEST");
        byte[] raw=body(request);JsonNode input;
        try {input=json.readTree(raw);}catch(Exception ignored){throw new ExternalProblem(400,"INVALID_EXTERNAL_REQUEST");}
        ExternalIdentityService.exact(input,Set.of("ticket"));
        return new Result<Exchanged>().ok(service.exchange(ExternalIdentityService.text(input,"ticket",128)));
    }
    @GetMapping("/me") public Result<Map<String,Object>> me(HttpServletRequest request,HttpServletResponse response){privateResponse(response);return new Result<Map<String,Object>>().ok(service.view(service.required(request)));}
    @PostMapping("/logout") public Result<Void> logout(HttpServletRequest request,HttpServletResponse response)throws IOException {
        privateResponse(response);byte[] raw=body(request);
        try{ExternalIdentityService.exact(json.readTree(raw),Set.of());}catch(ExternalProblem problem){throw problem;}catch(Exception ignored){throw new ExternalProblem(400,"INVALID_EXTERNAL_REQUEST");}
        service.logout(service.required(request));return new Result<Void>().ok(null);
    }
    private static byte[] signedBody(HttpServletRequest request,String expected)throws IOException {
        if(!"POST".equals(request.getMethod()) || request.getQueryString()!=null || !request.getRequestURI().equals(request.getContextPath()+expected))throw new ExternalProblem(401,"SIGNATURE_INVALID");
        return body(request);
    }
    static byte[] body(HttpServletRequest request)throws IOException {
        if(request.getContentLengthLong()>32768)throw new ExternalProblem(413,"EXTERNAL_BODY_LIMIT");
        byte[] body=request.getInputStream().readNBytes(32769);if(body.length>32768)throw new ExternalProblem(413,"EXTERNAL_BODY_LIMIT");return body;
    }
    static void privateResponse(HttpServletResponse response){response.setHeader("Cache-Control","no-store");response.setHeader("Referrer-Policy","no-referrer");}
    private static Map<String,List<String>> headers(HttpServletRequest request) {
        Map<String,List<String>> result=new HashMap<>();
        for(String name:List.of("x-external-key-id","x-external-timestamp","x-external-nonce","x-external-signature"))result.put(name,Collections.list(request.getHeaders(name)));
        return result;
    }
}
