package xiaozhi.modules.supportmedia;

import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import com.fasterxml.jackson.databind.ObjectMapper;
import xiaozhi.modules.supportmedia.livekit.*;
import xiaozhi.modules.supportmedia.livekit.LiveKitAdapter.Config;

@Configuration
@ConditionalOnProperty(prefix="features",name={"conversation-enabled","support-handoff-enabled"},havingValue="true")
public class MediaConfiguration {
    @Bean(destroyMethod="close") public LiveKitAdapter supportLiveKit(Environment env,ObjectMapper json,MediaPolicy ownership) {
        String p="support-media.livekit.";
        return new LiveKitAdapter(new Config(env.getProperty(p+"internal-url",""),env.getProperty(p+"public-url",""),env.getProperty(p+"api-key",""),env.getProperty(p+"api-secret",""),env.getProperty(p+"room-prefix","xz-support-b5-"),env.getProperty(p+"request-timeout-millis",Integer.class,5000),env.getProperty(p+"token-ttl-seconds",Integer.class,60)),json,ownership);
    }
}
