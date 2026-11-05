package xiaozhi.modules.modeltest;

import java.util.List;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.security.user.SecurityUser;
import static xiaozhi.modules.modeltest.ModelTestModels.*;

@RestController
@RequestMapping("/models/{modelId}/tests")
public class ModelTestController {
    private final ModelTestService service;private final ObjectMapper json;
    public ModelTestController(ModelTestService service,ObjectMapper json){this.service=service;this.json=json;}
    long actor(){Long id=SecurityUser.getUserId();if(id==null||id<=0)throw new Failure(401,"AUTHENTICATION_REQUIRED","请先登录。");return id;}
    @PostMapping @RequiresPermissions("sys:role:superAdmin")
    public Result<Run> start(@PathVariable String modelId,HttpServletRequest request,HttpServletResponse response)throws Exception {
        response.setHeader("Cache-Control","no-store");
        byte[] bytes=request.getInputStream().readNBytes(5*1024*1024+1);
        if(bytes.length>5*1024*1024)throw new Failure(413,"REQUEST_TOO_LARGE","测试文件总量过大。");
        Request body;
        try{body=json.readValue(bytes,Request.class);}catch(Exception error){throw new Failure("INVALID_REQUEST","测试参数格式不正确。");}
        return new Result<Run>().ok(service.start(actor(),modelId,body));
    }
    @GetMapping @RequiresPermissions("sys:role:superAdmin")
    public Result<List<Run>> history(@PathVariable String modelId,HttpServletResponse response){response.setHeader("Cache-Control","no-store");return new Result<List<Run>>().ok(service.history(actor(),modelId));}
    @GetMapping("/{id}") @RequiresPermissions("sys:role:superAdmin")
    public Result<Run> get(@PathVariable String modelId,@PathVariable String id,HttpServletResponse response){response.setHeader("Cache-Control","no-store");return new Result<Run>().ok(service.get(actor(),modelId,id));}
    @GetMapping("/{id}/audio") @RequiresPermissions("sys:role:superAdmin")
    public ResponseEntity<byte[]> audio(@PathVariable String modelId,@PathVariable String id)throws Exception{return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType("audio/wav")).header("Content-Disposition","inline; filename=model-test.wav").body(service.audio(actor(),modelId,id));}
    @ExceptionHandler(Failure.class)
    public ResponseEntity<Result<Void>> failure(Failure error){return ResponseEntity.status(error.status).cacheControl(CacheControl.noStore()).body(new Result<Void>().error(error.status,error.getMessage()));}
}
