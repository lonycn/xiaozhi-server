package xiaozhi.modules.externalidentity;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.*;

@Component
public class ExternalSettings {
    private boolean enabled;
    private boolean mediaEnabled;
    @Value("${features.external-realtime-media-enabled:false}") public void setMediaEnabled(boolean value){mediaEnabled=value;}
    public boolean mediaEnabled(){return enabled && mediaEnabled;}
    private String keysJson="";
    private int tokenSeconds=300;
    public record Key(String issuer,byte[] secret) { @Override public String toString(){return "ExternalKey[redacted]";} }
    @Value("${features.external-identity-enabled:false}") public void setEnabled(boolean value){enabled=value;}
    @Value("${external-identity.keys-json:}") public void setKeysJson(String value){keysJson=value;}
    @Value("${external-identity.token-seconds:300}") public void setTokenSeconds(int value){tokenSeconds=value;}
    public int tokenSeconds(){return tokenSeconds;}
    public boolean enabled(){return enabled;}
    public Map<String,Key> keys() {
        if(!enabled || tokenSeconds<30 || tokenSeconds>900)throw new ExternalProblem(503,"IDENTITY_NOT_CONFIGURED");
        try {
            ObjectMapper mapper=new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            JsonNode node=mapper.readTree(keysJson);
            if(node==null || !node.isObject() || node.isEmpty() || node.size()>20)throw new IllegalArgumentException();
            Map<String,Key> result=new HashMap<>();var fields=node.fields();
            while(fields.hasNext()) {
                var entry=fields.next();String id=entry.getKey();JsonNode value=entry.getValue();
                if(!id.matches("xiaozhi-[A-Za-z0-9_-]{1,64}") || !value.isObject() || value.size()!=2
                        || !value.path("issuer").isTextual() || !value.path("secret_b64").isTextual())throw new IllegalArgumentException();
                String issuer=value.path("issuer").asText(),encoded=value.path("secret_b64").asText();
                if(!issuer.matches("xiaozhi-[A-Za-z0-9_.-]{1,64}"))throw new IllegalArgumentException();
                byte[] secret=Base64.getDecoder().decode(encoded);
                if(secret.length<32 || secret.length>128 || !Base64.getEncoder().encodeToString(secret).equals(encoded)
                        || encoded.equals("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="))throw new IllegalArgumentException();
                if(result.values().stream().anyMatch(existing->Arrays.equals(existing.secret(),secret)))throw new IllegalArgumentException();
                result.put(id,new Key(issuer,secret));
            }
            return Map.copyOf(result);
        } catch(Exception ignored){throw new ExternalProblem(503,"IDENTITY_NOT_CONFIGURED");}
    }
    public boolean issuerActive(String issuer){return keys().values().stream().anyMatch(key->key.issuer().equals(issuer));}
    @Override public String toString(){return "ExternalSettings[redacted]";}
}
