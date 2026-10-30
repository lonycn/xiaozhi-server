package xiaozhi.modules.externalidentity;

import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;

/** Independent outbound read credentials; never reuse identity, Laravel PAT or RTC secrets. */
@Component
public class ExternalBusinessSettings {
    private boolean enabled;
    private String sourcesJson="";
    private int staleSeconds=300;

    public record Source(URI endpoint,String keyId,byte[] secret) {
        public Source { secret=secret.clone(); }
        @Override public byte[] secret(){return secret.clone();}
        @Override public String toString(){return "ExternalBusinessSource[redacted]";}
    }
    @Value("${external-business.enabled:false}") public void setEnabled(boolean value){enabled=value;}
    @Value("${external-business.sources-json:}") public void setSourcesJson(String value){sourcesJson=value;}
    @Value("${external-business.stale-seconds:300}") public void setStaleSeconds(int value){staleSeconds=value;}
    public int staleSeconds(){return staleSeconds;}
    public Source source(String issuer) {
        if(!enabled || sourcesJson==null || sourcesJson.isBlank())throw new ExternalProblem(503,"BUSINESS_NOT_CONFIGURED");
        try {
            if(staleSeconds<1 || staleSeconds>86400)throw new IllegalArgumentException();
            ObjectMapper mapper=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            JsonNode root=mapper.readTree(sourcesJson);
            if(root==null || !root.isObject() || root.isEmpty() || root.size()>20)throw new IllegalArgumentException();
            Map<String,Source> sources=new HashMap<>();Set<String> secrets=new HashSet<>();
            var fields=root.fields();
            while(fields.hasNext()) {
                var field=fields.next();JsonNode value=field.getValue();
                if(!field.getKey().matches("xiaozhi-[A-Za-z0-9_.-]{1,64}") || !value.isObject() || value.size()!=3
                    || !value.path("endpoint").isTextual() || !value.path("key_id").isTextual()
                    || !value.path("secret_b64").isTextual())throw new IllegalArgumentException();
                String keyId=value.path("key_id").asText(),encoded=value.path("secret_b64").asText();
                URI uri=URI.create(value.path("endpoint").asText());
                // Exact administrator-provided endpoint. Never accept a client URL, redirects or encoded aliases.
                if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null
                    || uri.getRawQuery()!=null || uri.getRawFragment()!=null || uri.getPort()==0 || uri.getPort()>65535
                    || uri.getRawPath()==null || !uri.getRawPath().matches("(?:/[A-Za-z0-9_-]+)+")
                    || !keyId.matches("xiaozhi-business-[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException();
                byte[] secret=Base64.getDecoder().decode(encoded);
                if(secret.length<32 || secret.length>128 || !Base64.getEncoder().encodeToString(secret).equals(encoded)
                    || encoded.equals("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=")
                    || encoded.equals("QjYtb3V0Ym91bmQta25vd24tcHVibGljLXRlc3QtdmVjdG9y") || !secrets.add(encoded))throw new IllegalArgumentException();
                sources.put(field.getKey(),new Source(uri,keyId,secret));
            }
            Source source=sources.get(issuer);
            if(source==null)throw new ExternalProblem(503,"BUSINESS_NOT_CONFIGURED");
            return source;
        } catch(ExternalProblem problem){throw problem;}
        catch(Exception ignored){throw new ExternalProblem(503,"BUSINESS_CONFIGURATION_INVALID");}
    }
    @Override public String toString(){return "ExternalBusinessSettings[redacted]";}
}
