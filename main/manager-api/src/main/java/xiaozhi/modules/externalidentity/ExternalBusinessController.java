package xiaozhi.modules.externalidentity;

import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.*;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.externalidentity.ExternalBusinessAdapter.*;

@RestController
@RequestMapping("/api/v1/external/business")
@ConditionalOnProperty(prefix="features",name="external-identity-enabled",havingValue="true")
public class ExternalBusinessController {
    private final ExternalIdentityService identity;private final ObjectProvider<ExternalBusinessAdapter> adapters;
    public ExternalBusinessController(ExternalIdentityService identity,ObjectProvider<ExternalBusinessAdapter> adapters){this.identity=identity;this.adapters=adapters;}
    @GetMapping("/devices/{id}") public Result<Evidence> device(@PathVariable String id,HttpServletRequest request,HttpServletResponse response){return read(new Read("device",id,null),request,response);}
    @GetMapping("/work-orders/{id}") public Result<Evidence> workorder(@PathVariable String id,HttpServletRequest request,HttpServletResponse response){return read(new Read("work_order",id,null),request,response);}
    @GetMapping("/knowledge") public Result<Evidence> knowledge(@RequestParam String q,HttpServletRequest request,HttpServletResponse response){if(q.isBlank()||q.length()>1000)throw new ExternalProblem(400,"INVALID_KNOWLEDGE_QUERY");return read(new Read("knowledge",null,q),request,response);}
    private Result<Evidence> read(Read query,HttpServletRequest request,HttpServletResponse response) {
        ExternalIdentityController.privateResponse(response);var actor=identity.required(request);
        if(query.resourceId()!=null && !query.resourceId().matches("[A-Za-z0-9_.:-]{1,100}"))throw new ExternalProblem(400,"INVALID_RESOURCE_ID");
        if(query.kind().equals("device") && !actor.scope().deviceIds().contains(query.resourceId()))throw new ExternalProblem(404,"RESOURCE_NOT_FOUND");
        ExternalBusinessAdapter adapter=adapters.getIfAvailable();if(adapter==null)throw new ExternalProblem(503,"BUSINESS_NOT_CONFIGURED");
        Evidence evidence=adapter.read(actor,query);
        if(evidence==null || !actor.issuer().equals(evidence.issuer()) || !actor.tenant().type().equals(evidence.tenantType())
            || !actor.tenant().id().equals(evidence.tenantId()) || !query.kind().equals(evidence.resourceType())
            || evidence.sourceRecordId()==null || evidence.sourceRecordId().isBlank()
            || query.resourceId()!=null&&!query.resourceId().equals(evidence.sourceRecordId())
            || evidence.observedAt()==null || evidence.data()==null)
            throw new ExternalProblem(503,"BUSINESS_PROVENANCE_INVALID");
        return new Result<Evidence>().ok(evidence);
    }
}
