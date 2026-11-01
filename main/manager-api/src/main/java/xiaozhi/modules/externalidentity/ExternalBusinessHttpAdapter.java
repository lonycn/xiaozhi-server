package xiaozhi.modules.externalidentity;

import java.io.ByteArrayOutputStream;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.jdbc.core.JdbcTemplate;
import xiaozhi.modules.externalidentity.ExternalBusinessAdapter.*;
import xiaozhi.modules.externalidentity.ExternalBusinessSettings.Source;
import xiaozhi.modules.externalidentity.ExternalModels.Principal;

/**
 * Read-only delegation protocol v1. This is an integration seam, not an implementation of a
 * Laravel endpoint. The configured source must recheck its live user/session, membership and
 * each record; the signed scope is only an upper bound and never substitutes for that check.
 *
 * POST exact configured HTTPS path; X-Xiaozhi-Business-{Key-Id,Timestamp,Nonce,Signature}.
 * Request canonical LF join, no trailing LF:
 * XIAOZHI-BUSINESS-READ-V1, POST, raw path, timestamp, nonce, SHA256(raw request).
 * Successful response has exactly one X-Xiaozhi-Business-{Timestamp,Signature}; canonical:
 * XIAOZHI-BUSINESS-RESPONSE-V1, request signature, response timestamp, 200, SHA256(raw response).
 * Both signatures are lowercase hex HMAC-SHA256 with the independent decoded source key.
 * Sources must enforce a 60-second signature window and atomically reject reused key+nonce.
 * This client performs no retries, redirects, external writes or authentication fallback.
 */
public final class ExternalBusinessHttpAdapter implements ExternalBusinessAdapter {
    private static final Logger AUDIT=LoggerFactory.getLogger(ExternalBusinessHttpAdapter.class);
    static final int MAX_RESPONSE_BYTES=262144;
    static final String HEADER="X-Xiaozhi-Business-";
    private final ExternalBusinessSettings settings;
    private final ExternalSettings identities;
    private final ExternalIdentityService identity;
    private final JdbcTemplate db;
    private final Clock clock;
    private final Transport transport;
    private final ObjectMapper json=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    @FunctionalInterface interface Transport { HttpResponse<byte[]> send(HttpRequest request)throws Exception; }
    public ExternalBusinessHttpAdapter(ExternalBusinessSettings settings,ExternalSettings identities,
            ExternalIdentityService identity,JdbcTemplate db) {
        this(settings,identities,identity,db,Clock.systemUTC(),productionTransport());
    }
    ExternalBusinessHttpAdapter(ExternalBusinessSettings settings,ExternalSettings identities,
            ExternalIdentityService identity,JdbcTemplate db,Clock clock,Transport transport) {
        this.settings=settings;this.identities=identities;this.identity=identity;this.db=db;
        this.clock=clock;this.transport=transport;
    }
    static Transport productionTransport() {
        HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
        return request->{
            // HttpRequest.timeout stops at headers on this JDK. Bound the complete body too.
            var pending=client.sendAsync(request,ignored->new LimitedBodySubscriber());
            try {return pending.get(5,TimeUnit.SECONDS);}
            catch(TimeoutException|InterruptedException failure){pending.cancel(true);throw failure;}
        };
    }
    @Override public Evidence read(Principal original,Read read) {
        String auditId=UUID.randomUUID().toString();
        try {
            Evidence evidence=perform(original,read);
            AUDIT.info("external_business_read audit_id={} outcome=success actor_id={} issuer={} tenant_type={} tenant_id={} project_ids={} kind={} record_id={} observed_at={} stale={}",
                auditId,original.userId(),evidence.issuer(),evidence.tenantType(),evidence.tenantId(),evidence.data().path("project_ids"),
                evidence.resourceType(),evidence.sourceRecordId(),evidence.observedAt(),evidence.data().path("stale").asBoolean());
            return evidence;
        }
        catch(ExternalProblem problem){AUDIT.info("external_business_read audit_id={} outcome=denied code={}",auditId,problem.getMessage());throw problem;}
        catch(InterruptedException ignored){Thread.currentThread().interrupt();throw unavailable(auditId);}
        catch(Exception ignored){throw unavailable(auditId);}
    }
    private static ExternalProblem unavailable(String auditId) {
        AUDIT.info("external_business_read audit_id={} outcome=failed code=BUSINESS_SOURCE_UNAVAILABLE",auditId);
        return failure("BUSINESS_SOURCE_UNAVAILABLE");
    }
    private Evidence perform(Principal original,Read read)throws Exception {
        Source source=settings.source(original.issuer());
        // Outbound credentials cannot share any active identity key, even under another issuer.
        if(identities.keys().values().stream().anyMatch(key->MessageDigest.isEqual(key.secret(),source.secret())))
            throw failure("BUSINESS_CONFIGURATION_INVALID");
        Principal actor=identity.authenticateHash(original.tokenHash());
        if(!ExternalIdentityService.sameBinding(original,actor))
            throw failure("BUSINESS_CONTEXT_INVALID");
        validateRead(actor,read);
        String session=sourceSession(actor);
        Instant requestedAt=clock.instant();
        ObjectNode body=json.createObjectNode();body.put("version",1);body.put("issuer",actor.issuer());
        body.put("external_subject",actor.subject());body.put("source_session_id",session);
        body.set("tenant",json.valueToTree(actor.tenant()));body.set("scope",json.valueToTree(actor.scope()));
        ObjectNode operation=body.putObject("operation");operation.put("kind",read.kind());
        if(read.resourceId()==null)operation.putNull("resource_id");else operation.put("resource_id",read.resourceId());
        if(read.query()==null)operation.putNull("query");else operation.put("query",read.query());
        body.put("requested_at",requestedAt.getEpochSecond());
        body.put("expires_at",Math.min(requestedAt.plusSeconds(30).getEpochSecond(),actor.expiresAt().getEpochSecond()));
        byte[] raw=json.writeValueAsBytes(body);
        if(raw.length>65536)throw failure("BUSINESS_CONTEXT_INVALID");
        String nonce=UUID.randomUUID().toString().replace("-",""),timestamp=Long.toString(requestedAt.getEpochSecond());
        String signed=requestSignature(source.secret(),source.endpoint().getRawPath(),timestamp,nonce,raw);
        HttpRequest request=HttpRequest.newBuilder(source.endpoint()).timeout(Duration.ofSeconds(5))
            .header("Content-Type","application/json").header("Accept","application/json")
            .header("Accept-Encoding","identity").header(HEADER+"Key-Id",source.keyId())
            .header(HEADER+"Timestamp",timestamp).header(HEADER+"Nonce",nonce).header(HEADER+"Signature",signed)
            .POST(HttpRequest.BodyPublishers.ofByteArray(raw)).build();
        HttpResponse<byte[]> response=transport.send(request);
        // A source/Bearer revoked while awaiting the upstream cannot release a late response.
        if(!ExternalIdentityService.sameBinding(actor,identity.authenticateHash(actor.tokenHash())))throw failure("BUSINESS_CONTEXT_INVALID");
        Source current=settings.source(actor.issuer());
        if(!current.endpoint().equals(source.endpoint()) || !current.keyId().equals(source.keyId())
            || !MessageDigest.isEqual(current.secret(),source.secret()))throw failure("BUSINESS_CONFIGURATION_CHANGED");
        int status=response.statusCode();
        if(status==404)throw new ExternalProblem(404,"RESOURCE_NOT_FOUND");
        if(status==401 || status==403)throw new ExternalProblem(403,"BUSINESS_SOURCE_ACCESS_DENIED");
        if(status==429)throw failure("BUSINESS_SOURCE_RATE_LIMITED");
        if(status!=200)throw failure("BUSINESS_SOURCE_UNAVAILABLE");
        byte[] content=response.body();
        if(content==null || content.length>MAX_RESPONSE_BYTES)throw failure("BUSINESS_SOURCE_RESPONSE_INVALID");
        String contentType=single(response, "Content-Type");
        if(!contentType.matches("(?i)application/json(?:\\s*;\\s*charset=utf-8)?")
            || response.headers().allValues("Content-Encoding").stream().anyMatch(value->!value.equalsIgnoreCase("identity")))
            throw failure("BUSINESS_SOURCE_RESPONSE_INVALID");
        String responseTime=single(response,HEADER+"Timestamp"),signature=single(response,HEADER+"Signature");
        if(!responseTime.matches("[0-9]{1,12}") || !signature.matches("[a-f0-9]{64}")
            || Math.abs(clock.instant().getEpochSecond()-Long.parseLong(responseTime))>60
            || !MessageDigest.isEqual(signature.getBytes(StandardCharsets.US_ASCII),
                responseSignature(source.secret(),signed,responseTime,content).getBytes(StandardCharsets.US_ASCII)))
            throw failure("BUSINESS_SOURCE_SIGNATURE_INVALID");
        return evidence(actor,read,session,content);
    }
    private String sourceSession(Principal actor) {
        var rows=db.queryForList("SELECT source_session_id FROM external_source_session WHERE id=? AND issuer=? AND external_subject=?",
            actor.sourceId(),actor.issuer(),actor.subject());
        if(rows.size()!=1 || !(rows.getFirst().get("source_session_id") instanceof String value)
            || value.isBlank() || value.length()>100)throw failure("BUSINESS_CONTEXT_INVALID");
        return value;
    }
    private static void validateRead(Principal actor,Read read) {
        if(read==null || !Set.of("device","work_order","knowledge").contains(read.kind()))
            throw new ExternalProblem(400,"BUSINESS_OPERATION_INVALID");
        if(read.kind().equals("knowledge")) {
            if(read.resourceId()!=null || read.query()==null || read.query().isBlank() || read.query().length()>1000)
                throw new ExternalProblem(400,"INVALID_KNOWLEDGE_QUERY");
        } else {
            if(read.query()!=null || read.resourceId()==null || !read.resourceId().matches("[A-Za-z0-9_.:-]{1,100}"))
                throw new ExternalProblem(400,"INVALID_RESOURCE_ID");
            if(read.kind().equals("device") && !actor.scope().deviceIds().contains(read.resourceId()))
                throw new ExternalProblem(404,"RESOURCE_NOT_FOUND");
            if(read.kind().equals("work_order") && actor.scope().projectIds().isEmpty())
                throw new ExternalProblem(404,"RESOURCE_NOT_FOUND");
        }
    }
    private Evidence evidence(Principal actor,Read read,String session,byte[] content) {
        try {
            JsonNode node=json.readTree(content);
            exact(node,Set.of("version","issuer","external_subject","source_session_id","tenant","project_ids",
                "resource_type","source_record_id","observed_at","data","references"));
            if(!node.path("version").isInt() || node.path("version").intValue()!=1
                || !text(node,"issuer").equals(actor.issuer()) || !text(node,"external_subject").equals(actor.subject())
                || !text(node,"source_session_id").equals(session) || !text(node,"resource_type").equals(read.kind()))throw invalid();
            JsonNode tenant=node.get("tenant");exact(tenant,Set.of("type","id"));
            if(!text(tenant,"type").equals(actor.tenant().type()) || !text(tenant,"id").equals(actor.tenant().id()))throw invalid();
            Set<String> projects=projectIds(node.get("project_ids"),new HashSet<>(actor.scope().projectIds()));
            if(read.kind().equals("work_order") && projects.isEmpty())throw invalid();
            if(read.kind().equals("device") && !actor.scope().projectIds().isEmpty() && projects.isEmpty())throw invalid();
            String record=text(node,"source_record_id");
            if(!record.matches("[A-Za-z0-9_.:-]{1,100}") || read.resourceId()!=null && !read.resourceId().equals(record))throw invalid();
            Instant observed=observed(node);JsonNode data=node.get("data"),refs=node.get("references");
            if(data==null || data.isNull() || !data.isContainerNode() || !refs.isArray() || refs.size()>20)throw invalid();
            if(read.kind().equals("knowledge") && (refs.isEmpty() || !record.equals(text(refs.get(0),"source_record_id"))))throw invalid();
            ArrayNode references=json.createArrayNode();
            for(JsonNode ref:refs) {
                exact(ref,Set.of("source_record_id","observed_at","project_ids","data"));
                if(!text(ref,"source_record_id").matches("[A-Za-z0-9_.:-]{1,100}") || ref.path("data").isNull()
                    || !ref.path("data").isObject())throw invalid();
                projectIds(ref.get("project_ids"),projects);Instant at=observed(ref);
                ObjectNode item=ref.deepCopy();item.put("stale",stale(at));references.add(item);
            }
            ObjectNode payload=json.createObjectNode();payload.set("record",data);payload.set("project_ids",node.get("project_ids"));
            payload.set("references",references);payload.put("stale",stale(observed));payload.put("received_at",clock.instant().toString());
            return new Evidence(actor.issuer(),actor.tenant().type(),actor.tenant().id(),read.kind(),record,observed,payload);
        } catch(ExternalProblem problem){throw problem;}
        catch(Exception ignored){throw invalid();}
    }
    private boolean stale(Instant value){return value.isBefore(clock.instant().minusSeconds(settings.staleSeconds()));}
    private Instant observed(JsonNode node) {
        Instant at=Instant.parse(text(node,"observed_at"));
        if(at.isAfter(clock.instant().plusSeconds(5)) || at.isBefore(Instant.EPOCH))throw invalid();
        return at;
    }
    private static Set<String> projectIds(JsonNode node,Set<String> upper) {
        if(node==null || !node.isArray() || node.size()>128)throw invalid();
        Set<String> result=new HashSet<>();
        for(JsonNode id:node)if(!id.isTextual() || !upper.contains(id.asText()) || !result.add(id.asText()))throw invalid();
        return result;
    }
    private static void exact(JsonNode node,Set<String> fields) {
        if(node==null || !node.isObject() || node.size()!=fields.size())throw invalid();
        var names=node.fieldNames();while(names.hasNext())if(!fields.contains(names.next()))throw invalid();
    }
    private static String text(JsonNode node,String field) {
        JsonNode value=node==null?null:node.get(field);
        if(value==null || !value.isTextual() || value.asText().isBlank())throw invalid();
        return value.asText();
    }
    private static String single(HttpResponse<?> response,String name) {
        var values=response.headers().allValues(name);
        if(values.size()!=1)throw failure("BUSINESS_SOURCE_RESPONSE_INVALID");
        return values.getFirst();
    }
    static String requestSignature(byte[] key,String path,String timestamp,String nonce,byte[] body) {
        return sign(key,String.join("\n","XIAOZHI-BUSINESS-READ-V1","POST",path,timestamp,nonce,ExternalIdentityService.hash(body)));
    }
    static String responseSignature(byte[] key,String requestSignature,String timestamp,byte[] body) {
        return sign(key,String.join("\n","XIAOZHI-BUSINESS-RESPONSE-V1",requestSignature,timestamp,"200",ExternalIdentityService.hash(body)));
    }
    private static String sign(byte[] key,String canonical) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch(Exception ignored){throw failure("BUSINESS_SOURCE_UNAVAILABLE");}
    }
    private static ExternalProblem invalid(){return failure("BUSINESS_PROVENANCE_INVALID");}
    private static ExternalProblem failure(String code){return new ExternalProblem(503,code);}

    /** Bound allocation while receiving, instead of checking size after an unbounded download. */
    static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream output=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody(){return result;}
        @Override public void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
        @Override public void onNext(List<ByteBuffer> buffers) {
            for(ByteBuffer buffer:buffers) {
                if(buffer.remaining()>MAX_RESPONSE_BYTES-output.size()) {
                    subscription.cancel();result.completeExceptionally(failure("BUSINESS_SOURCE_RESPONSE_INVALID"));return;
                }
                byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);output.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error){result.completeExceptionally(error);}
        @Override public void onComplete(){result.complete(output.toByteArray());}
    }
}
