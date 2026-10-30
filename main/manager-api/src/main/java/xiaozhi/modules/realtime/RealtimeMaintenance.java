package xiaozhi.modules.realtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Remains alive when only realtime is disabled so outstanding leases/history terminate visibly. */
@Component
@ConditionalOnProperty(prefix="features",name="conversation-enabled",havingValue="true")
public class RealtimeMaintenance {
    private final RealtimeService service;
    public RealtimeMaintenance(RealtimeService service){this.service=service;}
    @Scheduled(fixedDelayString="${realtime.maintenance-delay-ms:2000}") public void tick(){service.maintain();}
}
