package xiaozhi.modules.airuntime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import xiaozhi.modules.conversation.ConversationProblem;

class AiRuntimeControlControllerTest {
    @Test void disabledAndInvalidQueryAreRealProblemsAndAlwaysSetPrivateHeaders(){
        AiRuntimeControl control=mock(AiRuntimeControl.class);var controller=new AiRuntimeControlController(control);
        var request=new MockHttpServletRequest();var response=new MockHttpServletResponse();
        assertEquals(404,assertThrows(ConversationProblem.class,()->controller.get(request,response)).status());assertEquals("private, no-store",response.getHeader("Cache-Control"));assertEquals("nosniff",response.getHeader("X-Content-Type-Options"));
        when(control.enabled()).thenReturn(true);request.addParameter("actor","999");assertEquals(400,assertThrows(ConversationProblem.class,()->controller.get(request,response)).status());verify(control,never()).snapshot(any());
    }
    @Test void actualApiMapperPreservesExplicitUnknownsAndTheStableWireShape()throws Exception{
        var json=new xiaozhi.modules.security.config.WebMvcConfig().jackson2HttpMessageConverter().getObjectMapper();
        var snapshot=new AiRuntimeControl.Snapshot(true,3600,new AiRuntimeControl.Limits(3,32,60,1000),new AiRuntimeControl.Scope(0,0,3,60),null,new AiRuntimeControl.Usage(null,0,0,null,0,0),false,"concurrency_and_request_admission_not_total_cost");
        var wire=json.readTree(json.writeValueAsBytes(snapshot));assertTrue(wire.has("windowSeconds"));assertTrue(wire.has("serviceLimited"));assertTrue(wire.get("service").isNull());assertTrue(wire.get("usage").get("knownTokens").isNull());assertTrue(wire.get("usage").get("knownSpeechCharacters").isNull());assertFalse(wire.toString().contains("owner"));
    }
}
