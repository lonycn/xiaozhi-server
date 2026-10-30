package xiaozhi.modules.externalidentity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import xiaozhi.modules.externalidentity.ExternalBusinessAdapter.*;
import xiaozhi.modules.externalidentity.ExternalModels.*;

/** Synthetic source contract tests; not a Laravel/IoT/knowledge integration acceptance. */
class ExternalBusinessHttpAdapterTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final Instant NOW=Instant.parse("2026-10-31T00:00:00Z");
    static final String ISSUER="xiaozhi-source-test",PATH="/integrations/xiaozhi/read";
    static final byte[] KEY="business-test-key-32-byte-independent".getBytes(StandardCharsets.UTF_8);
    static final Principal ACTOR=new Principal(1,"digest",2,3,ISSUER,"real-user-7",new Tenant("customer","8"),
        new Scope(List.of("project-9"),List.of("device-10")),NOW.plusSeconds(300));
    static final Read DEVICE=new Read("device","device-10",null);
    final ExternalBusinessSettings settings=settings("https://source.invalid"+PATH);
    final ExternalSettings identities=identitySettings();
    final ExternalIdentityService identity=mock(ExternalIdentityService.class);
    final JdbcTemplate db=mock(JdbcTemplate.class);

    ExternalBusinessHttpAdapterTest() {
        doReturn(ACTOR).when(identity).authenticateHash("digest");
        when(db.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(Map.of("source_session_id","actual-source-session")));
    }
    static ExternalBusinessSettings settings(String endpoint) {
        var result=new ExternalBusinessSettings();result.setEnabled(true);
        result.setSourcesJson(sourceJson(endpoint,KEY));return result;
    }
    static String sourceJson(String endpoint,byte[] key) {
        try {return JSON.writeValueAsString(Map.of(ISSUER,Map.of("endpoint",endpoint,"key_id","xiaozhi-business-test",
            "secret_b64",Base64.getEncoder().encodeToString(key))));}
        catch(Exception error){throw new AssertionError(error);}
    }
    static ExternalSettings identitySettings() {
        var result=new ExternalSettings();result.setEnabled(true);
        result.setKeysJson("{\"xiaozhi-identity-key\":{\"issuer\":\""+ISSUER+"\",\"secret_b64\":\""
            +Base64.getEncoder().encodeToString("distinct-identity-key-at-least-32-bytes".getBytes(StandardCharsets.UTF_8))+"\"}}");return result;
    }
    ExternalBusinessHttpAdapter adapter(ExternalBusinessHttpAdapter.Transport transport) {
        return new ExternalBusinessHttpAdapter(settings,identities,identity,db,Clock.fixed(NOW,ZoneOffset.UTC),transport);
    }
    static ObjectNode evidence(String kind,String record) {
        ObjectNode root=JSON.createObjectNode();root.put("version",1);root.put("issuer",ISSUER);
        root.put("external_subject",ACTOR.subject());root.put("source_session_id","actual-source-session");
        root.set("tenant",JSON.valueToTree(ACTOR.tenant()));root.putArray("project_ids").add("project-9");
        root.put("resource_type",kind);root.put("source_record_id",record);root.put("observed_at",NOW.toString());
        root.putObject("data").put("name","真实来源协议测试，合成响应");root.putArray("references");return root;
    }
    @SuppressWarnings("unchecked")
    static HttpResponse<byte[]> response(HttpRequest request,byte[] body,int status) {
        var response=(HttpResponse<byte[]>)mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);when(response.body()).thenReturn(body);
        String timestamp=Long.toString(NOW.getEpochSecond());
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type",List.of("application/json"),
            ExternalBusinessHttpAdapter.HEADER+"Timestamp",List.of(timestamp),
            ExternalBusinessHttpAdapter.HEADER+"Signature",List.of(ExternalBusinessHttpAdapter.responseSignature(KEY,
                request.headers().firstValue(ExternalBusinessHttpAdapter.HEADER+"Signature").orElseThrow(),timestamp,body))),
            (name,value)->true));return response;
    }
    static HttpResponse<byte[]> response(HttpRequest request,ObjectNode body)throws Exception {
        return response(request,JSON.writeValueAsBytes(body),200);
    }
    static void code(String expected,org.junit.jupiter.api.function.Executable work) {
        assertEquals(expected,assertThrows(ExternalProblem.class,work).getMessage());
    }
    @Test void independentPublicVectorFixesCanonicalBytesAndSignatureDomains() {
        byte[] key="B6-outbound-known-public-test-vector".getBytes(StandardCharsets.UTF_8);
        String request=ExternalBusinessHttpAdapter.requestSignature(key,"/source/read","1793404800",
            "0123456789abcdef0123456789abcdef","{\"q\":\"冷库<>&\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals("0a4740e63a20c32b83b51e34cfad775fcfd15a5e88f4a0bf9eb2ca7112e0ef5b",request);
        assertEquals("862653949a800757f4fbe515b42c5fd8ff7117d5c3001ef04bbe452589144f8a",
            ExternalBusinessHttpAdapter.responseSignature(key,request,"1793404801","{\"ok\":true}".getBytes(StandardCharsets.UTF_8)));
        settings.setSourcesJson(sourceJson("https://source.invalid"+PATH,key));
        code("BUSINESS_CONFIGURATION_INVALID",()->settings.source(ISSUER));
    }
    @Test void disabledAndAbsentIssuerStayUnconfiguredWithoutOutboundOrDatabase() {
        settings.setEnabled(false);
        code("BUSINESS_NOT_CONFIGURED",()->adapter(request->{fail("unexpected outbound");return null;}).read(ACTOR,DEVICE));
        settings.setEnabled(true);
        settings.setSourcesJson(sourceJson("https://source.invalid"+PATH,KEY).replace(ISSUER,"xiaozhi-other"));
        code("BUSINESS_NOT_CONFIGURED",()->adapter(request->{fail("unexpected outbound");return null;}).read(ACTOR,DEVICE));
        verifyNoInteractions(db,identity);
    }
    @Test void productionConfigurationRequiresExactHttpsAndIndependentKey() {
        for(String endpoint:List.of("http://127.0.0.1/read","https://u:p@source.invalid/read",
                "https://source.invalid/read?q=1","https://source.invalid/read#frag","https://source.invalid/a/%2fread",
                "https://source.invalid/a/../read","https://source.invalid:0/read")) {
            settings.setSourcesJson(sourceJson(endpoint,KEY));code("BUSINESS_CONFIGURATION_INVALID",()->settings.source(ISSUER));
        }
        byte[] identityKey=identities.keys().values().iterator().next().secret();
        settings.setSourcesJson(sourceJson("https://source.invalid"+PATH,identityKey));
        code("BUSINESS_CONFIGURATION_INVALID",()->adapter(request->{fail("unexpected outbound");return null;}).read(ACTOR,DEVICE));
    }
    @Test void strictConfigurationRejectsDuplicateAndUnknownFieldsAndPublicKey() {
        String valid=sourceJson("https://source.invalid"+PATH,KEY);
        for(String invalid:List.of(valid.replace("\"endpoint\":","\"extra\":1,\"endpoint\":"),
                valid+" {}",valid.substring(0,valid.length()-1)+",\""+ISSUER+"\":{}}",
                sourceJson("https://source.invalid"+PATH,Base64.getDecoder().decode("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=")))) {
            settings.setSourcesJson(invalid);code("BUSINESS_CONFIGURATION_INVALID",()->settings.source(ISSUER));
        }
    }
    @Test void signedSourceResponseRetainsRealRecordTimeAndProjectAndStaleMarker() {
        Evidence actual=adapter(request->{
            assertEquals("POST",request.method());assertEquals(PATH,request.uri().getRawPath());
            assertEquals(Duration.ofSeconds(5),request.timeout().orElseThrow());
            var body=evidence("device","device-10");body.put("observed_at",NOW.minusSeconds(301).toString());
            return response(request,body);
        }).read(ACTOR,DEVICE);
        assertEquals("device-10",actual.sourceRecordId());assertEquals("8",actual.tenantId());
        assertEquals(NOW.minusSeconds(301),actual.observedAt());assertTrue(actual.data().path("stale").asBoolean());
        assertEquals("project-9",actual.data().path("project_ids").get(0).asText());
        verify(identity,times(2)).authenticateHash("digest");
    }
    @Test void invalidScopeAndUnsupportedOperationsNeverReachSource() {
        var adapter=adapter(request->{fail("unexpected outbound");return null;});
        code("RESOURCE_NOT_FOUND",()->adapter.read(ACTOR,new Read("device","other",null)));
        code("BUSINESS_OPERATION_INVALID",()->adapter.read(ACTOR,new Read("device_control","device-10",null)));
        code("INVALID_RESOURCE_ID",()->adapter.read(ACTOR,new Read("work_order","../1",null)));
        code("INVALID_KNOWLEDGE_QUERY",()->adapter.read(ACTOR,new Read("knowledge",null," ")));
    }
    @Test void wrongTenantSubjectSessionRecordTypeProjectAndFutureObservationAreRejected()throws Exception {
        List<ObjectNode> bad=new ArrayList<>();
        bad.add(evidence("device","wrong-record"));bad.add(evidence("work_order","device-10"));
        var tenant=evidence("device","device-10");((ObjectNode)tenant.get("tenant")).put("type","company");bad.add(tenant);
        var subject=evidence("device","device-10");subject.put("external_subject","another-user");bad.add(subject);
        var session=evidence("device","device-10");session.put("source_session_id","another-session");bad.add(session);
        var project=evidence("device","device-10");project.putArray("project_ids").add("other-project");bad.add(project);
        var future=evidence("device","device-10");future.put("observed_at",NOW.plusSeconds(6).toString());bad.add(future);
        var noProject=evidence("device","device-10");noProject.putArray("project_ids");bad.add(noProject);
        for(ObjectNode body:bad)code("BUSINESS_PROVENANCE_INVALID",()->adapter(request->response(request,body)).read(ACTOR,DEVICE));
    }
    @Test void malformedDuplicateTrailingOrUnknownResponseFieldsFailClosed()throws Exception {
        String valid=JSON.writeValueAsString(evidence("device","device-10"));
        for(String bad:List.of(valid+" {}",valid.replace("\"version\":1","\"version\":1,\"version\":1"),
                valid.replace("\"version\":1","\"version\":1,\"url\":\"https://evil.invalid\""),"{broken"))
            code("BUSINESS_PROVENANCE_INVALID",()->adapter(request->response(request,bad.getBytes(StandardCharsets.UTF_8),200)).read(ACTOR,DEVICE));
    }
    @Test void knowledgeRequiresPerRecordReferencesWithinSignedProjects() {
        var body=evidence("knowledge","document:11");
        var adapter=adapter(request->response(request,body));var query=new Read("knowledge",null,"冷库除霜");
        code("BUSINESS_PROVENANCE_INVALID",()->adapter.read(ACTOR,query));
        var ref=((com.fasterxml.jackson.databind.node.ArrayNode)body.get("references")).addObject();
        ref.put("source_record_id","document:11");ref.put("observed_at",NOW.minusSeconds(600).toString());
        ref.putArray("project_ids").add("project-9");ref.putObject("data").put("excerpt","来源原文节选");
        assertTrue(adapter.read(ACTOR,query).data().path("references").get(0).path("stale").asBoolean());
        ref.putArray("project_ids").add("other-project");
        code("BUSINESS_PROVENANCE_INVALID",()->adapter.read(ACTOR,query));
    }
    @Test void workOrderRequiresNonemptyProjectIntersection() {
        var body=evidence("work_order","order:12");
        assertEquals("order:12",adapter(request->response(request,body)).read(ACTOR,new Read("work_order","order:12",null)).sourceRecordId());
        body.putArray("project_ids");
        code("BUSINESS_PROVENANCE_INVALID",()->adapter(request->response(request,body)).read(ACTOR,new Read("work_order","order:12",null)));
    }
    @Test void unsignedTamperedReplayedOversizedAndRedirectedResponseAreRejected()throws Exception {
        byte[] raw=JSON.writeValueAsBytes(evidence("device","device-10"));
        code("BUSINESS_SOURCE_SIGNATURE_INVALID",()->adapter(request->{
            var response=response(request,raw,200);when(response.body()).thenReturn("{}".getBytes(StandardCharsets.UTF_8));return response;
        }).read(ACTOR,DEVICE));
        code("BUSINESS_SOURCE_RESPONSE_INVALID",()->adapter(request->{
            var response=response(request,raw,200);when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type",List.of("application/json")),(n,v)->true));return response;
        }).read(ACTOR,DEVICE));
        AtomicReference<HttpResponse<byte[]>> captured=new AtomicReference<>();
        var adapter=adapter(request->{if(captured.get()==null)captured.set(response(request,raw,200));return captured.get();});
        adapter.read(ACTOR,DEVICE);code("BUSINESS_SOURCE_SIGNATURE_INVALID",()->adapter.read(ACTOR,DEVICE));
        code("BUSINESS_SOURCE_RESPONSE_INVALID",()->adapter(request->response(request,new byte[262145],200)).read(ACTOR,DEVICE));
        code("BUSINESS_SOURCE_UNAVAILABLE",()->adapter(request->response(request,raw,302)).read(ACTOR,DEVICE));
    }
    @Test void deniedMissingAndFailedSourceNeverReturnFallbackData() {
        for(int status:List.of(401,403,404,429,500,204)) {
            String expected=switch(status){case 401,403->"BUSINESS_SOURCE_ACCESS_DENIED";case 404->"RESOURCE_NOT_FOUND";
                case 429->"BUSINESS_SOURCE_RATE_LIMITED";default->"BUSINESS_SOURCE_UNAVAILABLE";};
            code(expected,()->adapter(request->response(request,new byte[0],status)).read(ACTOR,DEVICE));
        }
        code("BUSINESS_SOURCE_UNAVAILABLE",()->adapter(request->{throw new java.io.IOException("must not leak secret");}).read(ACTOR,DEVICE));
    }
    @Test void sourceRevokedDuringNetworkAndRotatedConfigurationDiscardLateEvidence() {
        when(identity.authenticateHash("digest")).thenReturn(ACTOR).thenThrow(new ExternalProblem(401,"SOURCE_SESSION_REVOKED"));
        code("SOURCE_SESSION_REVOKED",()->adapter(request->response(request,evidence("device","device-10"))).read(ACTOR,DEVICE));
        doReturn(ACTOR).when(identity).authenticateHash("digest");
        code("BUSINESS_CONFIGURATION_CHANGED",()->adapter(request->{settings.setSourcesJson(sourceJson("https://changed.invalid"+PATH,KEY));
            return response(request,evidence("device","device-10"));}).read(ACTOR,DEVICE));
    }
    @Test void sourceSessionLookupMustMatchPersistedIdentityAndDependenciesFailClosed() {
        when(db.queryForList(anyString(),any(Object[].class))).thenReturn(List.of());
        code("BUSINESS_CONTEXT_INVALID",()->adapter(request->{fail("unexpected outbound");return null;}).read(ACTOR,DEVICE));
        when(db.queryForList(anyString(),any(Object[].class))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private database details"));
        code("BUSINESS_SOURCE_UNAVAILABLE",()->adapter(request->{fail("unexpected outbound");return null;}).read(ACTOR,DEVICE));
    }
    @Test void limitedSubscriberCancelsOversizedResponseBeforeAllocation() {
        var subscriber=new ExternalBusinessHttpAdapter.LimitedBodySubscriber();Flow.Subscription subscription=mock(Flow.Subscription.class);
        subscriber.onSubscribe(subscription);subscriber.onNext(List.of(ByteBuffer.allocate(262144)));subscriber.onNext(List.of(ByteBuffer.allocate(1)));
        verify(subscription).cancel();assertTrue(subscriber.getBody().toCompletableFuture().isCompletedExceptionally());
    }
    @Test void defaultSpringConfigurationInstallsNoBusinessAdapter() {
        try(var context=new AnnotationConfigApplicationContext()) {
            context.register(ExternalBusinessSettings.class,ExternalBusinessConfiguration.class);context.refresh();
            assertTrue(context.getBeansOfType(ExternalBusinessAdapter.class).isEmpty());
            code("BUSINESS_NOT_CONFIGURED",()->context.getBean(ExternalBusinessSettings.class).source(ISSUER));
        }
    }
    @Test void enabledSpringConfigurationInstallsRealAdapterWithoutContactingSource() {
        try(var context=new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("business-test",Map.of("external-business.enabled","true","features.external-identity-enabled","true")));
            context.registerBean(ExternalSettings.class,()->identities);context.registerBean(ExternalIdentityService.class,()->identity);
            context.registerBean(JdbcTemplate.class,()->db);context.register(ExternalBusinessSettings.class,ExternalBusinessConfiguration.class);context.refresh();
            assertInstanceOf(ExternalBusinessHttpAdapter.class,context.getBean(ExternalBusinessAdapter.class));
            clearInvocations(db,identity); // Spring invokes JdbcTemplate.afterPropertiesSet during bean creation.
            code("BUSINESS_NOT_CONFIGURED",()->context.getBean(ExternalBusinessAdapter.class).read(ACTOR,DEVICE));
            verifyNoInteractions(db,identity);
        }
    }
    @Test void businessFlagCannotEnableAdapterWhileExternalIdentityRemainsDisabled() {
        try(var context=new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("business-only",Map.of("external-business.enabled","true")));
            context.register(ExternalBusinessSettings.class,ExternalBusinessConfiguration.class);context.refresh();
            assertTrue(context.getBeansOfType(ExternalBusinessAdapter.class).isEmpty());
        }
    }
    @Test void expiredResponseTimestampDuplicateHeadersAndCompressedContentFailClosed()throws Exception {
        byte[] raw=JSON.writeValueAsBytes(evidence("device","device-10"));
        for(String mode:List.of("old","duplicate","compressed","wrong-type")) {
            code(mode.equals("old")?"BUSINESS_SOURCE_SIGNATURE_INVALID":"BUSINESS_SOURCE_RESPONSE_INVALID",()->adapter(request->{
                var response=response(request,raw,200);var headers=new HashMap<String,List<String>>(response.headers().map());
                if(mode.equals("old")) {
                    String timestamp=Long.toString(NOW.minusSeconds(61).getEpochSecond());
                    headers.put(ExternalBusinessHttpAdapter.HEADER+"Timestamp",List.of(timestamp));
                    headers.put(ExternalBusinessHttpAdapter.HEADER+"Signature",List.of(ExternalBusinessHttpAdapter.responseSignature(KEY,
                        request.headers().firstValue(ExternalBusinessHttpAdapter.HEADER+"Signature").orElseThrow(),timestamp,raw)));
                    // Replace case-insensitively; HttpHeaders.map canonical casing is implementation-specific.
                    headers.entrySet().removeIf(entry->entry.getKey().equalsIgnoreCase(ExternalBusinessHttpAdapter.HEADER+"Timestamp")
                        && !entry.getKey().equals(ExternalBusinessHttpAdapter.HEADER+"Timestamp"));
                    headers.entrySet().removeIf(entry->entry.getKey().equalsIgnoreCase(ExternalBusinessHttpAdapter.HEADER+"Signature")
                        && !entry.getKey().equals(ExternalBusinessHttpAdapter.HEADER+"Signature"));
                } else if(mode.equals("duplicate")) {
                    headers.entrySet().removeIf(entry->entry.getKey().equalsIgnoreCase("Content-Type"));
                    headers.put("Content-Type",List.of("application/json","application/json"));
                } else if(mode.equals("compressed"))headers.put("Content-Encoding",List.of("gzip"));
                else {headers.entrySet().removeIf(entry->entry.getKey().equalsIgnoreCase("Content-Type"));headers.put("Content-Type",List.of("text/plain"));}
                when(response.headers()).thenReturn(HttpHeaders.of(headers,(name,value)->true));return response;
            }).read(ACTOR,DEVICE));
        }
    }
    @Test void changedTrustedContextAndEmptyWorkOrderScopeAreRejectedBeforeNetwork() {
        Principal changed=new Principal(ACTOR.tokenId(),ACTOR.tokenHash(),ACTOR.sourceId(),ACTOR.userId(),ACTOR.issuer(),ACTOR.subject(),
            ACTOR.tenant(),new Scope(List.of(),ACTOR.scope().deviceIds()),ACTOR.expiresAt());
        doReturn(changed).when(identity).authenticateHash("digest");
        code("BUSINESS_CONTEXT_INVALID",()->adapter(request->{fail("unexpected outbound");return null;}).read(ACTOR,DEVICE));
        code("RESOURCE_NOT_FOUND",()->adapter(request->{fail("unexpected outbound");return null;}).read(changed,new Read("work_order","12",null)));
    }
    @Test void actualLoopbackHttpExchangesExactSignedBytesAndBoundedDelegation()throws Exception {
        // Only this injected test transport maps the administrator's HTTPS origin to a synthetic
        // loopback HTTP server. Production settings and production TLS verification stay strict.
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicReference<Throwable> serverFailure=new AtomicReference<>();
        server.createContext(PATH,exchange->{
            try {
                assertEquals("POST",exchange.getRequestMethod());byte[] raw=exchange.getRequestBody().readAllBytes();
                String timestamp=exchange.getRequestHeaders().getFirst(ExternalBusinessHttpAdapter.HEADER+"Timestamp");
                String nonce=exchange.getRequestHeaders().getFirst(ExternalBusinessHttpAdapter.HEADER+"Nonce");
                String signature=exchange.getRequestHeaders().getFirst(ExternalBusinessHttpAdapter.HEADER+"Signature");
                assertEquals(ExternalBusinessHttpAdapter.requestSignature(KEY,PATH,timestamp,nonce,raw),signature);
                var input=JSON.readTree(raw);assertEquals("actual-source-session",input.path("source_session_id").asText());
                assertEquals("real-user-7",input.path("external_subject").asText());
                assertEquals("device-10",input.path("scope").path("device_ids").get(0).asText());
                assertEquals(NOW.plusSeconds(30).getEpochSecond(),input.path("expires_at").asLong());
                assertFalse(new String(raw,StandardCharsets.UTF_8).contains("digest"));
                byte[] output=JSON.writeValueAsBytes(evidence("device","device-10"));
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.getResponseHeaders().set(ExternalBusinessHttpAdapter.HEADER+"Timestamp",timestamp);
                exchange.getResponseHeaders().set(ExternalBusinessHttpAdapter.HEADER+"Signature",ExternalBusinessHttpAdapter.responseSignature(KEY,signature,timestamp,output));
                exchange.sendResponseHeaders(200,output.length);exchange.getResponseBody().write(output);
            } catch(Throwable error){serverFailure.set(error);exchange.sendResponseHeaders(500,-1);}
            finally {exchange.close();}
        });server.start();
        try {
            var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            var adapter=adapter(request->{
                var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+PATH))
                    .timeout(request.timeout().orElseThrow()).POST(request.bodyPublisher().orElseThrow());
                request.headers().map().forEach((name,values)->values.forEach(value->builder.header(name,value)));
                return client.send(builder.build(),ignored->new ExternalBusinessHttpAdapter.LimitedBodySubscriber());
            });
            assertEquals("device-10",adapter.read(ACTOR,DEVICE).sourceRecordId());
            if(serverFailure.get()!=null)throw new AssertionError(serverFailure.get());
        } finally {server.stop(0);}
    }
    @Test void productionTransportBoundsIncompleteBodyAfterFastHeaders()throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/slow",exchange->{
            exchange.sendResponseHeaders(200,100);exchange.getResponseBody().write('{');exchange.getResponseBody().flush();
            // Intentionally leave a partial body open; server.stop closes it after the test.
        });server.start();
        try {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/slow"))
                .timeout(Duration.ofSeconds(2)).GET().build();
            long started=System.nanoTime();
            assertThrows(java.util.concurrent.TimeoutException.class,()->ExternalBusinessHttpAdapter.productionTransport().send(request));
            assertTrue(Duration.ofNanos(System.nanoTime()-started).toSeconds()<7);
        } finally {server.stop(0);}
    }
}
