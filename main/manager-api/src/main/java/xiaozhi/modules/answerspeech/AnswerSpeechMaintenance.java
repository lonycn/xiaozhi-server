package xiaozhi.modules.answerspeech;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
/** No provider IO and no synthesis retries: only bounded expiry of already persisted jobs. */
@Component
@ConditionalOnProperty(prefix="features",name="conversation-enabled",havingValue="true")
public class AnswerSpeechMaintenance {
    private final AnswerSpeechService service;
    public AnswerSpeechMaintenance(AnswerSpeechService service){this.service=service;}
    @Scheduled(fixedDelayString="${answer-speech.maintenance-delay-ms:2000}") public void tick(){service.maintain();}
}
