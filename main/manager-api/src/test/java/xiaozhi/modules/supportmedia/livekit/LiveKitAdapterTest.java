package xiaozhi.modules.supportmedia.livekit;

import static org.junit.jupiter.api.Assertions.*;
import static xiaozhi.modules.supportmedia.livekit.LiveKitFailure.Code.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.junit.jupiter.api.Test;
import xiaozhi.modules.supportmedia.livekit.LiveKitAdapter.*;

/** All HTTP is an injected fake; this suite opens no sockets and reads no runtime credentials. */
class LiveKitAdapterTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final String KEY="test-livekit-key",SECRET="test-livekit-secret-not-a-runtime-credential";
    static final String ROOM="xiaozhi-test-123-g7-"+"a".repeat(32),IDENTITY="customer-123-g7";
    static final OwnedRoom OWNED=new OwnedRoom("123",7,ROOM);
    static final Instant NOW=Instant.parse("2026-10-30T12:00:00Z");
    static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
    static Config config() { return new Config("http://livekit.invalid:7880","wss://rtc.invalid",KEY,SECRET,"xiaozhi-test-"); }
    static final class Request {
        URI uri;String authorization;byte[] body;Duration timeout;
        Request(URI uri,String authorization,byte[] body,Duration timeout) {this.uri=uri;this.authorization=authorization;this.body=body;this.timeout=timeout;}
    }
    static final class Wire implements Transport {
        final List<Request> requests=new ArrayList<>();int status=200;String response="{}";IOException failure;
        @Override public RpcResponse send(URI uri,String authorization,byte[] body,Duration timeout) throws IOException {
            requests.add(new Request(uri,authorization,body,timeout));
            if(failure!=null)throw failure;
            return new RpcResponse(status,response.getBytes(StandardCharsets.UTF_8));
        }
    }
    LiveKitAdapter adapter(Wire wire) { return new LiveKitAdapter(config(),JSON,(r,op,id)->r.equals(OWNED),CLOCK,wire); }
    static JsonNode payload(String token) throws Exception {return JSON.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));}
    static byte[] hmac(String message) throws Exception {
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return mac.doFinal(message.getBytes(StandardCharsets.US_ASCII));
    }
    static String jwt(JsonNode claims) throws Exception {return jwt(claims,"{\"alg\":\"HS256\",\"typ\":\"JWT\"}");}
    static String jwt(JsonNode claims,String header) throws Exception {
        Base64.Encoder encoder=Base64.getUrlEncoder().withoutPadding();
        String message=encoder.encodeToString(header.getBytes(StandardCharsets.UTF_8))+"."+encoder.encodeToString(JSON.writeValueAsBytes(claims));
        return message+"."+encoder.encodeToString(hmac(message));
    }
    static ObjectNode webhookClaims(byte[] raw) throws Exception {
        return JSON.createObjectNode().put("iss",KEY).put("exp",NOW.getEpochSecond()+60).put("nbf",NOW.getEpochSecond())
            .put("sha256",Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(raw)));
    }
    static byte[] body() {return ("{ \"event\":\"participant_joined\",\"id\":\"evt-1\",\"room\":{\"name\":\""+ROOM+"\"},\"note\":\"冷库\" }\n").getBytes(StandardCharsets.UTF_8);}
    void fails(LiveKitFailure.Code expected,Runnable operation) {
        LiveKitFailure failure=assertThrows(LiveKitFailure.class,operation::run);
        assertEquals(expected,failure.code());assertEquals("LIVEKIT_"+expected,failure.getMessage());assertNull(failure.getCause());
    }
    @Test void missingAndInvalidConfigurationFailClosedWithoutAnyHttp() {
        Wire wire=new Wire();
        var disabled=new LiveKitAdapter(Config.disabled(),JSON,(r,o,i)->true,CLOCK,wire);
        assertFalse(disabled.configured());fails(NOT_CONFIGURED,()->disabled.createRoom(OWNED));
        for(Config invalid:List.of(new Config("http://user:secret@host","wss://rtc.invalid",KEY,SECRET,"test-"),
                new Config("https://host?key=private","wss://rtc.invalid",KEY,SECRET,"test-"),
                new Config("https://host","https://rtc.invalid",KEY,SECRET,"test-"),
                new Config("http://host","wss://rtc.invalid",KEY,SECRET,"x-"),
                new Config("http://host","wss://rtc.invalid",KEY,SECRET,"test-",10001,60),
                new Config("http://host","wss://rtc.invalid",KEY,SECRET,"test-",5000,121))) {
            var adapter=new LiveKitAdapter(invalid,JSON,(r,o,i)->true,CLOCK,wire);
            assertFalse(adapter.configured());fails(NOT_CONFIGURED,()->adapter.issueToken(OWNED,IDENTITY,true,true));
        }
        assertTrue(wire.requests.isEmpty());
    }
    @Test void prefixAloneOrMissingDbOwnershipNeverAuthorizesAnyRoomOperation() {
        Wire wire=new Wire();var denied=new LiveKitAdapter(config(),JSON,(r,o,i)->false,CLOCK,wire);
        fails(OWNERSHIP_DENIED,()->denied.createRoom(OWNED));
        fails(OWNERSHIP_DENIED,()->denied.listParticipants(OWNED));
        fails(OWNERSHIP_DENIED,()->denied.getParticipant(OWNED,IDENTITY));
        fails(OWNERSHIP_DENIED,()->denied.removeParticipant(OWNED,IDENTITY));
        fails(OWNERSHIP_DENIED,()->denied.deleteRoom(OWNED));
        fails(OWNERSHIP_DENIED,()->denied.issueToken(OWNED,IDENTITY,true,true));
        fails(OWNERSHIP_DENIED,()->new LiveKitAdapter(config(),JSON,null,CLOCK,wire).createRoom(OWNED));
        assertTrue(wire.requests.isEmpty());
    }
    @Test void malformedOrDifferentGenerationNamesFailBeforeOwnershipOrNetwork() {
        Wire wire=new Wire();var adapter=new LiveKitAdapter(config(),JSON,(r,o,i)->{fail("malformed name must not reach DB callback");return true;},CLOCK,wire);
        for(OwnedRoom room:List.of(new OwnedRoom("123",8,ROOM),new OwnedRoom("124",7,ROOM),
                new OwnedRoom("123",0,ROOM),new OwnedRoom("123",7,"other-"+ROOM),
                new OwnedRoom("9223372036854775808",7,"xiaozhi-test-9223372036854775808-g7-"+"a".repeat(32))))
            fails(OWNERSHIP_DENIED,()->adapter.deleteRoom(room));
        assertTrue(wire.requests.isEmpty());
    }
    @Test void createExactRoomUsesOnlyServerSideShortCreateGrantAndNoRecording() throws Exception {
        Wire wire=new Wire();wire.response="{\"sid\":\"RM_1\",\"name\":\""+ROOM+"\"}";
        var created=adapter(wire).createRoom(OWNED);assertEquals(new RoomInfo("RM_1",ROOM),created);
        Request request=wire.requests.getFirst();assertEquals(1,wire.requests.size());
        assertEquals("/twirp/livekit.RoomService/CreateRoom",request.uri.getPath());assertNull(request.uri.getQuery());
        JsonNode sent=JSON.readTree(request.body);assertEquals(ROOM,sent.path("name").asText());
        assertEquals(2,sent.path("max_participants").asInt());assertEquals(120,sent.path("empty_timeout").asInt());
        assertFalse(sent.has("egress"));assertFalse(sent.has("room_preset"));assertEquals(Duration.ofSeconds(5),request.timeout);
        String token=request.authorization.substring(7);String[] segments=token.split("\\.");
        assertArrayEquals(hmac(segments[0]+"."+segments[1]),Base64.getUrlDecoder().decode(segments[2]));
        JsonNode claims=payload(token);assertEquals(KEY,claims.path("iss").asText());
        assertEquals(60,claims.path("exp").asLong()-NOW.getEpochSecond());assertTrue(claims.path("video").path("roomCreate").asBoolean());
        assertEquals(ROOM,claims.path("video").path("room").asText());assertFalse(claims.path("video").has("roomJoin"));
    }
    @Test void samePrefixWrongCreateResponseNameIsProtocolFailure() {
        Wire wire=new Wire();wire.response="{\"sid\":\"RM_1\",\"name\":\"not-the-owned-room\"}";
        fails(PROTOCOL,()->adapter(wire).createRoom(OWNED));
    }
    @Test void participantsExposeRealTrackSourcesAndScopedAdminGrant() throws Exception {
        Wire wire=new Wire();wire.response="{\"participants\":[{\"sid\":\"PA_1\",\"identity\":\""+IDENTITY+"\",\"state\":\"ACTIVE\",\"tracks\":[{\"sid\":\"TR_1\",\"source\":\"MICROPHONE\",\"muted\":false},{\"sid\":\"TR_2\",\"source\":1,\"muted\":true}]}]}";
        var participants=adapter(wire).listParticipants(OWNED);
        assertEquals("ACTIVE",participants.getFirst().state());assertEquals("MICROPHONE",participants.getFirst().tracks().getFirst().source());
        assertEquals("CAMERA",participants.getFirst().tracks().get(1).source());assertTrue(participants.getFirst().tracks().get(1).muted());
        Request request=wire.requests.getFirst();assertEquals(ROOM,JSON.readTree(request.body).path("room").asText());
        JsonNode video=payload(request.authorization.substring(7)).path("video");
        assertTrue(video.path("roomAdmin").asBoolean());assertFalse(video.has("roomCreate"));assertEquals(ROOM,video.path("room").asText());
        wire.response="{\"sid\":\"PA_1\",\"identity\":\""+IDENTITY+"\",\"state\":2}";
        assertEquals("ACTIVE",adapter(wire).getParticipant(OWNED,IDENTITY).orElseThrow().state());
    }
    @Test void getRequiresExactParticipantIdentityAndValidEnumTypes() {
        Wire wire=new Wire();wire.response="{\"sid\":\"PA_1\",\"identity\":\"different\",\"state\":\"ACTIVE\"}";
        fails(PROTOCOL,()->adapter(wire).getParticipant(OWNED,IDENTITY));
        wire.response="{\"participants\":[{\"sid\":\"PA_1\",\"identity\":\"a\",\"state\":\"FAKE_READY\"}]}";
        fails(PROTOCOL,()->adapter(wire).listParticipants(OWNED));
    }
    @Test void onlyExactTwirpNotFoundIsIdempotentCleanupAndGetMissing() {
        Wire wire=new Wire();wire.status=404;wire.response="{\"code\":\"not_found\",\"msg\":\"private upstream body\"}";
        var adapter=adapter(wire);adapter.removeParticipant(OWNED,IDENTITY);adapter.deleteRoom(OWNED);
        assertTrue(adapter.getParticipant(OWNED,IDENTITY).isEmpty());
        fails(NOT_FOUND,()->adapter.listParticipants(OWNED));
        wire.response="{\"code\":\"permission_denied\",\"msg\":\"private upstream body\"}";
        fails(PROTOCOL,()->adapter.deleteRoom(OWNED));
        wire.status=500;wire.response="{\"code\":\"not_found\"}";fails(UNAVAILABLE,()->adapter.deleteRoom(OWNED));
        wire.status=403;fails(AUTH,()->adapter.removeParticipant(OWNED,IDENTITY));
    }
    @Test void providerFailuresAreSanitizedBoundedAndNeverRetriedOrRedirected() {
        for(int status:List.of(401,403,429,503,400,302)) {
            Wire wire=new Wire();wire.status=status;wire.response="{\"code\":\"upstream\",\"msg\":\"private-api-secret\"}";
            var expected=status==401||status==403?AUTH:status==429?RATE_LIMIT:status>=500?UNAVAILABLE:PROTOCOL;
            fails(expected,()->adapter(wire).deleteRoom(OWNED));assertEquals(1,wire.requests.size());
        }
        Wire network=new Wire();network.failure=new IOException("private transport detail");fails(UNAVAILABLE,()->adapter(network).listParticipants(OWNED));
        Wire timeout=new Wire();timeout.failure=new HttpTimeoutException("private timeout detail");
        fails(TIMEOUT,()->adapter(timeout).listParticipants(OWNED));assertEquals(1,timeout.requests.size());
        Wire oversized=new Wire();oversized.response="x".repeat(LiveKitAdapter.MAX_BODY+1);fails(PROTOCOL,()->adapter(oversized).listParticipants(OWNED));
        Wire invalid=new Wire();invalid.response="not json";fails(PROTOCOL,()->adapter(invalid).listParticipants(OWNED));
    }
    @Test void interruptedHttpRestoresInterruptionWithoutExposingCause() {
        Transport transport=(uri,auth,body,timeout)->{throw new InterruptedException("private detail");};
        try {
            var adapter=new LiveKitAdapter(config(),JSON,(r,o,i)->true,CLOCK,transport);
            fails(UNAVAILABLE,()->adapter.deleteRoom(OWNED));assertTrue(Thread.currentThread().isInterrupted());
        } finally {Thread.interrupted();}
    }
    @Test void joinJwtOnlyPermitsConsentedMicrophoneCameraAndNeverDataAdminRecordingOrSip() throws Exception {
        for(boolean microphone:List.of(false,true))for(boolean camera:List.of(false,true)) {
            Wire wire=new Wire();JoinToken token=adapter(wire).issueToken(OWNED,IDENTITY,microphone,camera);
            JsonNode claims=payload(token.token()),video=claims.path("video");
            assertEquals(IDENTITY,claims.path("sub").asText());assertEquals(ROOM,video.path("room").asText());
            assertTrue(video.path("roomJoin").asBoolean());assertTrue(video.path("canSubscribe").asBoolean());
            assertEquals(microphone||camera,video.path("canPublish").asBoolean());
            Set<String> sources=new HashSet<>();video.path("canPublishSources").forEach(node->sources.add(node.asText()));
            assertEquals(microphone,sources.contains("microphone"));assertEquals(camera,sources.contains("camera"));
            assertFalse(sources.contains("screen_share"));
            for(String permission:List.of("canPublishData","roomCreate","roomList","roomAdmin","roomRecord","ingressAdmin","agent","recorder","canUpdateOwnMetadata"))
                assertFalse(video.path(permission).asBoolean(),permission);
            assertFalse(claims.has("sip"));assertFalse(claims.has("roomConfig"));assertEquals(NOW.plusSeconds(60),token.expiresAt());
            assertEquals("wss://rtc.invalid",token.serverUrl());assertTrue(wire.requests.isEmpty());
            assertFalse(token.toString().contains(token.token()));assertFalse(config().toString().contains(SECRET));
        }
    }
    @Test void dbIdentityAndGenerationAuthorityIsRequiredForEachTokenIssuance() {
        Wire wire=new Wire();List<String> checks=new ArrayList<>();
        var adapter=new LiveKitAdapter(config(),JSON,(r,o,i)->{checks.add(o+":"+i);return r.equals(OWNED)&&IDENTITY.equals(i);},CLOCK,wire);
        adapter.issueToken(OWNED,IDENTITY,true,false);
        fails(OWNERSHIP_DENIED,()->adapter.issueToken(OWNED,"other-identity",true,true));
        assertEquals(List.of("TOKEN:"+IDENTITY,"TOKEN:other-identity"),checks);
        fails(INVALID_REQUEST,()->adapter.issueToken(OWNED,"../user",true,true));
    }
    @Test void jwtNumericDatesStayNumericWithApplicationLongToStringSerializer() throws Exception {
        ObjectMapper configured=JSON.copy();SimpleModule module=new SimpleModule();module.addSerializer(Long.class,ToStringSerializer.instance);
        module.addSerializer(Long.TYPE,ToStringSerializer.instance);configured.registerModule(module);
        assertEquals("\"123\"",configured.writeValueAsString(123L));
        var adapter=new LiveKitAdapter(config(),configured,(r,o,i)->true,CLOCK,new Wire());
        JsonNode claims=payload(adapter.issueToken(OWNED,IDENTITY,true,true).token());
        assertTrue(claims.path("exp").isIntegralNumber());assertTrue(claims.path("nbf").isIntegralNumber());
    }
    @Test void officialRawJwtOrCanonicalBearerWebhookVerifiesExactRawBytesBeforeParsing() throws Exception {
        byte[] raw=body();ObjectNode claims=webhookClaims(raw);String token=jwt(claims);
        var adapter=adapter(new Wire());
        assertEquals("participant_joined",adapter.verifyWebhook(raw,token).path("event").asText());
        assertEquals("evt-1",adapter.verifyWebhook(raw,"Bearer "+token).path("id").asText());
        byte[] normalized=JSON.writeValueAsBytes(JSON.readTree(raw));
        fails(INVALID_WEBHOOK,()->adapter.verifyWebhook(normalized,token));
        String noPadding=claims.path("sha256").asText().replace("=","");claims.put("sha256",noPadding);
        String invalid=jwt(claims);fails(INVALID_WEBHOOK,()->adapter.verifyWebhook(raw,invalid));
    }
    @Test void webhookRejectsSignatureAlgorithmIssuerExpiryAndNotBefore() throws Exception {
        byte[] raw=body();var adapter=adapter(new Wire());
        List<String> invalid=new ArrayList<>();
        String valid=jwt(webhookClaims(raw));invalid.add(valid.substring(0,valid.lastIndexOf('.')+1)+"A".repeat(43));
        invalid.add(jwt(webhookClaims(raw),"{\"alg\":\"none\"}"));invalid.add(jwt(webhookClaims(raw),"{\"alg\":\"HS512\"}"));
        invalid.add(jwt(webhookClaims(raw).put("iss","different-project")));
        invalid.add(jwt(webhookClaims(raw).put("exp",NOW.getEpochSecond())));
        invalid.add(jwt(webhookClaims(raw).put("exp",Long.toString(NOW.getEpochSecond()+60))));
        invalid.add(jwt(webhookClaims(raw).put("nbf",NOW.getEpochSecond()+1)));
        ObjectNode noExpiry=webhookClaims(raw);noExpiry.remove("exp");invalid.add(jwt(noExpiry));
        invalid.add(jwt(webhookClaims(raw),"{\"alg\":\"HS256\",\"jku\":\"https://untrusted.invalid/key\"}"));
        invalid.add("Bearer  "+valid);invalid.add(valid+"=");
        for(String token:invalid)fails(INVALID_WEBHOOK,()->adapter.verifyWebhook(raw,token));
    }
    @Test void webhookRejectsWrongStandardBase64DigestDuplicateFieldsAndInvalidBodies() throws Exception {
        byte[] raw=body();var adapter=adapter(new Wire());
        String wrong=jwt(webhookClaims(raw).put("sha256",Base64.getEncoder().encodeToString(new byte[32])));
        fails(INVALID_WEBHOOK,()->adapter.verifyWebhook(raw,wrong));
        String missing=jwt(webhookClaims(raw).putNull("sha256"));fails(INVALID_WEBHOOK,()->adapter.verifyWebhook(raw,missing));
        String duplicateHeader=jwt(webhookClaims(raw),"{\"alg\":\"none\",\"alg\":\"HS256\"}");
        fails(INVALID_WEBHOOK,()->adapter.verifyWebhook(raw,duplicateHeader));
        for(byte[] invalidBody:List.of("not-json".getBytes(StandardCharsets.UTF_8),"[]".getBytes(StandardCharsets.UTF_8),
                "{\"event\":\"a\",\"event\":\"b\"}".getBytes(StandardCharsets.UTF_8))) {
            String signed=jwt(webhookClaims(invalidBody));fails(INVALID_WEBHOOK,()->adapter.verifyWebhook(invalidBody,signed));
        }
        String token=jwt(webhookClaims(raw));fails(INVALID_WEBHOOK,()->adapter.verifyWebhook(new byte[LiveKitAdapter.MAX_BODY+1],token));
    }
    @Test void webhookSha256RequiresStandardBase64NotUrlSafeEncoding() throws Exception {
        byte[] raw="{\"event\":\"participant_joined\"}".getBytes(StandardCharsets.UTF_8);
        ObjectNode claims=webhookClaims(raw);String standard=claims.path("sha256").asText();
        assertTrue(standard.contains("/") && standard.contains("+"));
        var adapter=adapter(new Wire());assertEquals("participant_joined",adapter.verifyWebhook(raw,jwt(claims)).path("event").asText());
        claims.put("sha256",standard.replace('+','-').replace('/','_'));
        String urlSafe=jwt(claims);fails(INVALID_WEBHOOK,()->adapter.verifyWebhook(raw,urlSafe));
    }
    @Test void boundedJdkBodySubscriberCancelsBeforeRetainingOversizedResponse() {
        LiveKitHttpTransport.BoundedBody body=new LiveKitHttpTransport.BoundedBody();boolean[] canceled={false};
        body.onSubscribe(new Flow.Subscription(){public void request(long n){}public void cancel(){canceled[0]=true;}});
        body.onNext(List.of(ByteBuffer.wrap(new byte[LiveKitAdapter.MAX_BODY])));
        body.onNext(List.of(ByteBuffer.wrap(new byte[]{1})));
        assertTrue(canceled[0]);CompletionException failure=assertThrows(CompletionException.class,()->body.getBody().toCompletableFuture().join());
        assertEquals(PROTOCOL,((LiveKitFailure)failure.getCause()).code());
        LiveKitHttpTransport.BoundedBody small=new LiveKitHttpTransport.BoundedBody();
        small.onSubscribe(new Flow.Subscription(){public void request(long n){}public void cancel(){}});
        small.onNext(List.of(ByteBuffer.wrap(new byte[]{1,2}),ByteBuffer.wrap(new byte[]{3})));small.onComplete();
        assertArrayEquals(new byte[]{1,2,3},small.getBody().toCompletableFuture().join());
    }
}
