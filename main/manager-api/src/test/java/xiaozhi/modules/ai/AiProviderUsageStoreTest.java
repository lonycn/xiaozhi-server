package xiaozhi.modules.ai;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import xiaozhi.modules.ai.AiModels.*;
import xiaozhi.modules.conversation.ConversationProblem;

class AiProviderUsageStoreTest {
    final AiProviderUsageStore store=new AiProviderUsageStore(null);
    ProviderRequest request(int round,Usage usage) {return new ProviderRequest(round,"request-"+round,usage,"stop");}
    @Test void acceptsKnownAggregateIncludingAudioAndLegacyCallbacks() {
        store.validate(List.of(request(1,new Usage(2L,3L,5L,1L)),request(2,new Usage(4L,5L,9L))),new Usage(6L,8L,14L,1L),1);
        store.validate(null,new Usage(2L,3L,5L),null);
        store.validate(List.of(),null,0);
    }
    @Test void missingRoundUsageMustRemainUnknown() {
        var rounds=List.of(request(1,new Usage(2L,3L,5L)),request(2,null));
        store.validate(rounds,null,1);
        assertThrows(ConversationProblem.class,()->store.validate(rounds,new Usage(2L,3L,5L),1));
    }
    @Test void rejectsForgedTotalsOrderIdentifiersAndBudgets() {
        assertThrows(ConversationProblem.class,()->store.validate(List.of(request(1,new Usage(2L,3L,5L))),new Usage(3L,3L,6L),1));
        assertThrows(ConversationProblem.class,()->store.validate(List.of(request(2,null)),null,1));
        assertThrows(ConversationProblem.class,()->store.validate(List.of(new ProviderRequest(1,"key\nsecret",null,"stop")),null,1));
        assertThrows(ConversationProblem.class,()->store.validate(List.of(request(1,null)),null,7));
        assertThrows(ConversationProblem.class,()->store.validate(null,null,0));
        assertThrows(ConversationProblem.class,()->store.validate(List.of(),new Usage(0L,0L,0L),0));
    }
    @Test void textFamilyGatePreservesNonToolAndMultimodalModels() {
        assertTrue(AiExecutionService.textToolsSupported("qwen-flash"));
        assertTrue(AiExecutionService.textToolsSupported("qwen-plus-latest"));
        for(String value:List.of("test-model","qwen3-vl-plus","qwen3.8-omni-flash","qwen3.8-omni-flash-realtime"))
            assertFalse(AiExecutionService.textToolsSupported(value));
    }
}
