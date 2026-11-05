package xiaozhi.modules.aicontext;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import xiaozhi.modules.agenttool.*;
import xiaozhi.modules.conversation.ConversationProblem;
import xiaozhi.modules.ai.AiModels.MediaRef;

/** Absence of the new context/public-result tables must not affect an explicitly disabled feature. */
class AiContextFeatureOffTest {
    @Test void defaultDisabledContextAndCoreDoNotQueryAnyDatabaseTables(){
        var db=mock(JdbcTemplate.class);var json=new ObjectMapper();var evidence=new ConversationEvidenceService(db,json);
        var parts=json.createArrayNode();parts.addObject().put("type","text").put("text","previous image");
        evidence.capture("a".repeat(32),"b".repeat(32),10,parts);assertSame(parts,evidence.effectiveParts("a".repeat(32),parts));assertFalse(evidence.hasHistory("a".repeat(32)));
        var refs=List.of(new MediaRef("1","display","image/jpeg","image",null));assertSame(refs,evidence.currentRefs("a".repeat(32),refs));assertTrue(evidence.supplement("a".repeat(32),refs).isEmpty());assertTrue(evidence.forConversation("b".repeat(32),1,10).isEmpty());
        assertEquals(404,assertThrows(ConversationProblem.class,()->evidence.latest(1,"b".repeat(32),10)).status());
        var core=new AgentToolExecutionCore(db,json);assertTrue(core.catalog(new AgentToolExecutionCore.Context("b".repeat(32),1,null,null,false,10)).isEmpty());
        assertThrows(ConversationProblem.class,()->core.execute("server_capabilities",json.createObjectNode(),new AgentToolExecutionCore.Context("b".repeat(32),1,null,null,false,10)));verifyNoInteractions(db);
    }
    @Test void defaultDisabledPublicProjectionAndLegacyCatalogAreCompatibleWithMissingNewTables(){
        var db=mock(JdbcTemplate.class);var json=new ObjectMapper();var manager=mock(PlatformTransactionManager.class);var core=new AgentToolExecutionCore(db,json);
        var results=new PublicToolResultStore(db,json,manager,core);results.publishRun(Map.of(),Map.of(),1,"succeeded");results.publishRealtime(Map.of(),Map.of(),1,"succeeded");
        assertEquals(404,assertThrows(ConversationProblem.class,()->results.page(1L,"a".repeat(32),0,10,null)).status());
        var legacy=new AgentToolService(db,json,manager);legacy.prepareRun("a".repeat(32),"b".repeat(32),1,null);assertTrue(legacy.taskTools("a".repeat(32),1,"c".repeat(64),1).tools().isEmpty());verifyNoInteractions(db);
    }
}
