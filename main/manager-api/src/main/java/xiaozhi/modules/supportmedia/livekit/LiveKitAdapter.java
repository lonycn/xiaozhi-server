package xiaozhi.modules.supportmedia.livekit;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import static xiaozhi.modules.supportmedia.livekit.LiveKitFailure.Code.*;

/**
 * Explicit RoomService adapter, with no discovery, SDK, retries, redirect, recording or SIP API.
 * Protocol references: https://docs.livekit.io/reference/other/roomservice-api/ and
 * https://docs.livekit.io/python/livekit/api/webhook.html . Independently implemented;
 * the local aimeet adapter was a design reference only, with no copied implementation.
 *
 * RemoveParticipant is NOT assumed to revoke old JWTs on self-hosted LiveKit. The caller must
 * isolate replacement participants in a new, never-reused generation/room. An unexpired old
 * token can recreate its empty OLD room even after DeleteRoom; only the exact DB-owned old
 * room may be cleaned again. This adapter never sweeps by prefix or claims token revocation.
 */
public final class LiveKitAdapter implements AutoCloseable {
    static final int MAX_BODY=1 << 20;
    private static final Base64.Encoder URL_ENCODER=Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER=Base64.getUrlDecoder();
    private static final Pattern IDENTITY=Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    public enum Operation { CREATE, LIST, GET, REMOVE, DELETE, TOKEN }
    public record OwnedRoom(String sessionId,long generation,String roomName) { }
    public record RoomInfo(String sid,String name) { }
    public record TrackInfo(String sid,String source,boolean muted) { }
    public record Participant(String sid,String identity,String state,List<TrackInfo> tracks) { }
    public record JoinToken(String serverUrl,String token,Instant expiresAt) {
        @Override public String toString() { return "JoinToken[redacted]"; }
    }
    public record Config(String internalUrl,String publicUrl,String apiKey,String apiSecret,
                         String roomPrefix,int requestTimeoutMillis,int tokenTtlSeconds) {
        public Config(String internalUrl,String publicUrl,String apiKey,String apiSecret,String roomPrefix) {
            this(internalUrl,publicUrl,apiKey,apiSecret,roomPrefix,5000,60);
        }
        public static Config disabled() { return new Config("","","","","",5000,60); }
        @Override public String toString() { return "LiveKitConfig[redacted]"; }
    }
    /** Injectable no-network test seam. Production uses bounded JDK HttpClient transport. */
    @FunctionalInterface public interface Transport {
        RpcResponse send(URI uri,String authorization,byte[] body,Duration timeout) throws IOException,InterruptedException;
    }
    public record RpcResponse(int status,byte[] body) {
        @Override public String toString() { return "RpcResponse[status="+status+", body=redacted]"; }
    }
    private final Config config;
    private final ObjectMapper json;
    private final RoomOwnership ownership;
    private final Transport transport;
    private final Clock clock;

    public LiveKitAdapter(Config config,ObjectMapper json,RoomOwnership ownership) {
        this(config,json,ownership,Clock.systemUTC(),null);
    }
    public LiveKitAdapter(Config config,ObjectMapper json,RoomOwnership ownership,Clock clock,Transport transport) {
        this.config=config==null?Config.disabled():config;
        // JsonNodes preserve numeric JWT NumericDates even when the application's long serializer uses strings.
        this.json=json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.ownership=ownership;this.clock=Objects.requireNonNull(clock);
        this.transport=transport!=null?transport:configured()
                ?new LiveKitHttpTransport(Duration.ofMillis(Math.min(this.config.requestTimeoutMillis(),3000))):null;
    }
    public boolean configured() {
        try {
            return endpoint(config.internalUrl(),Set.of("http","https"))
                && endpoint(config.publicUrl(),Set.of("ws","wss"))
                && safe(config.apiKey(),256) && safe(config.apiSecret(),2048)
                && config.roomPrefix()!=null && config.roomPrefix().matches("[A-Za-z0-9_-]{2,47}-")
                && config.requestTimeoutMillis()>=100 && config.requestTimeoutMillis()<=10000
                && config.tokenTtlSeconds()>=15 && config.tokenTtlSeconds()<=120;
        } catch(RuntimeException ignored) { return false; }
    }
    private static boolean endpoint(String value,Set<String> schemes) {
        if(value==null || value.isBlank())return false;
        URI uri=URI.create(value);
        return schemes.contains(uri.getScheme()) && uri.getHost()!=null && uri.getRawUserInfo()==null
                && uri.getRawQuery()==null && uri.getRawFragment()==null
                && !uri.getPath().contains("..") && !value.contains("\\");
    }
    private static boolean safe(String value,int max) {
        return value!=null && !value.isBlank() && value.length()<=max
            && value.chars().noneMatch(c->c<32 || c==127);
    }
    private void requireConfigured() { if(!configured())throw new LiveKitFailure(NOT_CONFIGURED); }
    private void authorize(OwnedRoom room,Operation operation,String identity) {
        requireConfigured();
        if(room==null || room.sessionId()==null || !room.sessionId().matches("[1-9][0-9]{0,18}")
                || room.generation()<1 || room.roomName()==null || room.roomName().length()>160
                || !room.roomName().matches(Pattern.quote(config.roomPrefix()+room.sessionId()+"-g"+room.generation()+"-")+"[a-f0-9]{32}"))
            throw new LiveKitFailure(OWNERSHIP_DENIED);
        try { if(Long.parseLong(room.sessionId())<1)throw new NumberFormatException(); }
        catch(NumberFormatException ignored) { throw new LiveKitFailure(OWNERSHIP_DENIED); }
        if(identity!=null && !IDENTITY.matcher(identity).matches())throw new LiveKitFailure(INVALID_REQUEST);
        boolean allowed=false;
        try { allowed=ownership!=null && ownership.allowed(room,operation,identity); }
        catch(RuntimeException ignored) { /* Do not expose DB/provider exception messages. */ }
        if(!allowed)throw new LiveKitFailure(OWNERSHIP_DENIED);
    }
    public RoomInfo createRoom(OwnedRoom room) {
        authorize(room,Operation.CREATE,null);
        ObjectNode body=json.createObjectNode().put("name",room.roomName())
            .put("empty_timeout",120).put("departure_timeout",30).put("max_participants",2);
        JsonNode result=rpc("CreateRoom",room,body,true);
        String name=requiredText(result,"name",160),sid=requiredText(result,"sid",128);
        if(!room.roomName().equals(name))throw new LiveKitFailure(PROTOCOL);
        return new RoomInfo(sid,name);
    }
    public List<Participant> listParticipants(OwnedRoom room) {
        authorize(room,Operation.LIST,null);
        JsonNode result=rpc("ListParticipants",room,json.createObjectNode().put("room",room.roomName()),false);
        JsonNode items=result.path("participants");
        if(items.isMissingNode())return List.of();
        if(!items.isArray() || items.size()>100)throw new LiveKitFailure(PROTOCOL);
        List<Participant> participants=new ArrayList<>();
        for(JsonNode item:items)participants.add(participant(item));
        return List.copyOf(participants);
    }
    public Optional<Participant> getParticipant(OwnedRoom room,String identity) {
        authorizeIdentity(room,Operation.GET,identity);
        try {
            Participant value=participant(rpc("GetParticipant",room,participantBody(room,identity),false));
            if(!identity.equals(value.identity()))throw new LiveKitFailure(PROTOCOL);
            return Optional.of(value);
        } catch(LiveKitFailure failure) { if(failure.code()==NOT_FOUND)return Optional.empty();throw failure; }
    }
    public void removeParticipant(OwnedRoom room,String identity) {
        authorizeIdentity(room,Operation.REMOVE,identity);
        cleanup("RemoveParticipant",room,participantBody(room,identity),false);
    }
    public void deleteRoom(OwnedRoom room) {
        authorize(room,Operation.DELETE,null);
        cleanup("DeleteRoom",room,json.createObjectNode().put("room",room.roomName()),true);
    }
    private void cleanup(String method,OwnedRoom room,ObjectNode body,boolean createGrant) {
        try { rpc(method,room,body,createGrant); }
        catch(LiveKitFailure failure) { if(failure.code()!=NOT_FOUND)throw failure; }
    }
    private void authorizeIdentity(OwnedRoom room,Operation operation,String identity) {
        if(identity==null)throw new LiveKitFailure(INVALID_REQUEST);
        authorize(room,operation,identity);
    }
    private ObjectNode participantBody(OwnedRoom room,String identity) {
        return json.createObjectNode().put("room",room.roomName()).put("identity",identity);
    }
    /**
     * Consent booleans must come from the caller's authenticated, persisted consent snapshot,
     * never unvalidated client grants. Consent changes require the caller's generation fence.
     */
    public JoinToken issueToken(OwnedRoom room,String identity,boolean microphone,boolean camera) {
        return issueToken(room,identity,microphone,camera,null);
    }
    public JoinToken issueToken(OwnedRoom room,String identity,boolean microphone,boolean camera,Instant authorityExpiresAt) {
        authorizeIdentity(room,Operation.TOKEN,identity);
        ObjectNode grants=json.createObjectNode().put("room",room.roomName()).put("roomJoin",true)
            .put("canSubscribe",true).put("canPublish",microphone||camera).put("canPublishData",false)
            .put("canUpdateOwnMetadata",false).put("roomCreate",false).put("roomList",false)
            .put("roomAdmin",false).put("roomRecord",false).put("ingressAdmin",false)
            .put("hidden",false).put("recorder",false).put("agent",false);
        ArrayNode sources=grants.putArray("canPublishSources");
        if(microphone)sources.add("microphone");if(camera)sources.add("camera");
        long expiry=clock.instant().getEpochSecond()+config.tokenTtlSeconds();
        if(authorityExpiresAt!=null){expiry=Math.min(expiry,authorityExpiresAt.getEpochSecond());if(expiry<=clock.instant().getEpochSecond())throw new LiveKitFailure(LiveKitFailure.Code.OWNERSHIP_DENIED);}
        return new JoinToken(config.publicUrl(),sign(claims(identity,expiry).set("video",grants)),Instant.ofEpochSecond(expiry));
    }
    private ObjectNode claims(String identity,long expiry) {
        long now=clock.instant().getEpochSecond();
        return json.createObjectNode().put("iss",config.apiKey()).put("sub",identity)
            .put("nbf",now).put("iat",now).put("exp",expiry);
    }
    private String adminToken(OwnedRoom room,boolean createGrant) {
        ObjectNode grant=json.createObjectNode().put("room",room.roomName())
            .put("canPublish",false).put("canSubscribe",false).put("canPublishData",false);
        grant.put(createGrant?"roomCreate":"roomAdmin",true);
        return sign(claims("",clock.instant().getEpochSecond()+60).set("video",grant));
    }
    private String sign(JsonNode claims) {
        try {
            String header=URL_ENCODER.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.US_ASCII));
            String payload=URL_ENCODER.encodeToString(json.writeValueAsBytes(claims));
            String message=header+"."+payload;
            return message+"."+URL_ENCODER.encodeToString(hmac(message.getBytes(StandardCharsets.US_ASCII)));
        } catch(Exception ignored) { throw new LiveKitFailure(PROTOCOL); }
    }
    private byte[] hmac(byte[] message) throws Exception {
        Mac mac=Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(config.apiSecret().getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return mac.doFinal(message);
    }
    private JsonNode rpc(String method,OwnedRoom room,ObjectNode body,boolean createGrant) {
        try {
            URI uri=URI.create(config.internalUrl().replaceAll("/+$","")+"/twirp/livekit.RoomService/"+method);
            RpcResponse response=transport.send(uri,"Bearer "+adminToken(room,createGrant),json.writeValueAsBytes(body),
                    Duration.ofMillis(config.requestTimeoutMillis()));
            byte[] raw=response.body();
            if(raw==null || raw.length>MAX_BODY)throw new LiveKitFailure(PROTOCOL);
            if(response.status()!=200) {
                String code="";
                try { JsonNode error=json.readTree(raw);if(error!=null && error.path("code").isTextual())code=error.path("code").asText(); }
                catch(IOException ignored) { }
                // Only an exact Twirp 404/not_found for this already-authorized resource is idempotent.
                if(response.status()==404 && code.equals("not_found"))throw new LiveKitFailure(NOT_FOUND);
                if(response.status()==401 || response.status()==403)throw new LiveKitFailure(AUTH);
                if(response.status()==429)throw new LiveKitFailure(RATE_LIMIT);
                if(response.status()>=500)throw new LiveKitFailure(UNAVAILABLE);
                throw new LiveKitFailure(PROTOCOL);
            }
            JsonNode result=json.readTree(raw);
            if(result==null || !result.isObject())throw new LiveKitFailure(PROTOCOL);
            return result;
        } catch(HttpTimeoutException ignored) { throw new LiveKitFailure(TIMEOUT); }
        catch(InterruptedException ignored) { Thread.currentThread().interrupt();throw new LiveKitFailure(UNAVAILABLE); }
        catch(com.fasterxml.jackson.core.JsonProcessingException ignored) { throw new LiveKitFailure(PROTOCOL); }
        catch(IOException ignored) { throw new LiveKitFailure(UNAVAILABLE); }
        catch(LiveKitFailure failure) { throw failure; }
        catch(Exception ignored) { throw new LiveKitFailure(PROTOCOL); }
    }
    private Participant participant(JsonNode value) {
        String sid=requiredText(value,"sid",128),identity=requiredText(value,"identity",128);
        String state=enumValue(value.get("state"),List.of("JOINING","JOINED","ACTIVE","DISCONNECTED"));
        JsonNode tracks=value.path("tracks");List<TrackInfo> result=new ArrayList<>();
        if(!tracks.isMissingNode() && (!tracks.isArray() || tracks.size()>32))throw new LiveKitFailure(PROTOCOL);
        if(tracks.isArray())for(JsonNode track:tracks) {
            String source=enumValue(track.get("source"),List.of("UNKNOWN","CAMERA","MICROPHONE","SCREEN_SHARE","SCREEN_SHARE_AUDIO"));
            JsonNode muted=track.get("muted");
            if(muted!=null && !muted.isBoolean())throw new LiveKitFailure(PROTOCOL);
            result.add(new TrackInfo(requiredText(track,"sid",128),source,muted!=null&&muted.asBoolean()));
        }
        return new Participant(sid,identity,state,List.copyOf(result));
    }
    private static String enumValue(JsonNode value,List<String> known) {
        if(value==null)return known.getFirst();
        if(value.isIntegralNumber() && value.canConvertToInt() && value.intValue()>=0 && value.intValue()<known.size())return known.get(value.intValue());
        if(value.isTextual() && known.contains(value.asText()))return value.asText();
        throw new LiveKitFailure(PROTOCOL);
    }
    private static String requiredText(JsonNode node,String field,int maximum) {
        JsonNode value=node.get(field);
        if(value==null || !value.isTextual() || !safe(value.textValue(),maximum))throw new LiveKitFailure(PROTOCOL);
        return value.textValue();
    }
    /** Crypto validation only. Caller must map event room to its DB-owned generation and deduplicate event IDs. */
    public JsonNode verifyWebhook(byte[] rawBody,String authorization) {
        requireConfigured();
        try {
            if(rawBody==null || rawBody.length==0 || rawBody.length>MAX_BODY || authorization==null || authorization.length()>16384)
                throw new IllegalArgumentException();
            String token=authorization.startsWith("Bearer ")?authorization.substring(7):authorization;
            String[] parts=token.split("\\.",-1);
            if(parts.length!=3)throw new IllegalArgumentException();
            for(String part:parts)if(part.isEmpty() || part.length()>8192 || !part.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException();
            byte[] headerBytes=decodePart(parts[0]);
            JsonNode header=json.readTree(headerBytes);
            if(header==null || !header.isObject() || !"HS256".equals(header.path("alg").asText())
                    || header.has("typ")&&!"JWT".equals(header.path("typ").asText()))throw new IllegalArgumentException();
            var fields=header.fieldNames();while(fields.hasNext())if(!Set.of("alg","typ").contains(fields.next()))throw new IllegalArgumentException();
            byte[] signature=decodePart(parts[2]);
            if(signature.length!=32 || !MessageDigest.isEqual(hmac((parts[0]+"."+parts[1]).getBytes(StandardCharsets.US_ASCII)),signature))
                throw new IllegalArgumentException();
            JsonNode claims=json.readTree(decodePart(parts[1]));
            if(claims==null || !claims.isObject() || !claims.path("iss").isTextual() || !config.apiKey().equals(claims.path("iss").asText()))
                throw new IllegalArgumentException();
            long now=clock.instant().getEpochSecond();JsonNode expiry=claims.get("exp"),notBefore=claims.get("nbf");
            if(expiry==null || !expiry.isIntegralNumber() || !expiry.canConvertToLong() || expiry.longValue()<=now
                    || notBefore!=null&&(!notBefore.isIntegralNumber() || !notBefore.canConvertToLong() || notBefore.longValue()>now))
                throw new IllegalArgumentException();
            JsonNode hash=claims.get("sha256");
            if(hash==null || !hash.isTextual() || hash.textValue().length()!=44)throw new IllegalArgumentException();
            byte[] digest=Base64.getDecoder().decode(hash.textValue());
            if(digest.length!=32 || !Base64.getEncoder().encodeToString(digest).equals(hash.textValue())
                    || !MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(rawBody),digest))throw new IllegalArgumentException();
            // No JSON parsing or normalization of the event body happens before all checks above.
            JsonNode event=json.readTree(rawBody);
            if(event==null || !event.isObject())throw new IllegalArgumentException();
            return event;
        } catch(Exception ignored) { throw new LiveKitFailure(INVALID_WEBHOOK); }
    }
    @Override public void close() {
        if(transport instanceof LiveKitHttpTransport local)local.close();
    }
    private static byte[] decodePart(String value) {
        byte[] decoded=URL_DECODER.decode(value);
        if(!URL_ENCODER.encodeToString(decoded).equals(value))throw new IllegalArgumentException();
        return decoded;
    }
}
