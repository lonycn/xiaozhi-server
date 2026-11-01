package xiaozhi.modules.agenttool;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import xiaozhi.modules.conversation.ConversationProblem;
import xiaozhi.modules.externalidentity.ExternalModels.*;

class AgentToolProtocolTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void existingEvidenceReferenceDataContainerPreservesOnlyWhitelistedBusinessFields()throws Exception {
        var service=new AgentToolService(mock(org.springframework.jdbc.core.JdbcTemplate.class),json,mock(org.springframework.transaction.PlatformTransactionManager.class));
        var evidence=json.readTree("""
                {"record":{"name":"existing evidence fixture","status":"ok","url":"https://private.invalid","credentials":{"password":"secret"}},
                 "project_ids":["project1"],"references":[{"source_record_id":"knowledge1","observed_at":"2026-11-01T00:00:00Z",
                 "project_ids":["project1"],"data":{"title":"Existing contract title","content":"Authorized knowledge text",
                 "api_key":"must-not-escape","user_id":"invented-actor","url":"https://private.invalid",
                 "headers":{"Authorization":"must-not-escape"},"unknown_container":{"content":"must-not-escape"}},"stale":false}],
                 "stale":false,"received_at":"2026-11-01T00:00:01Z"}
                """);
        var filtered=service.sanitize(evidence,0);var reference=filtered.path("references").get(0);
        assertEquals("Existing contract title",reference.path("data").path("title").asText());
        assertEquals("Authorized knowledge text",reference.path("data").path("content").asText());
        assertEquals("knowledge1",reference.path("source_record_id").asText());
        assertEquals("project1",reference.path("project_ids").get(0).asText());
        assertFalse(filtered.toString().contains("must-not-escape"));assertFalse(filtered.toString().contains("private.invalid"));
        assertFalse(reference.path("data").has("user_id"));assertFalse(reference.path("data").has("unknown_container"));
    }
    @Test void nestedEvidenceDataStillHonorsDepthArrayAndTextBudgets()throws Exception {
        var service=new AgentToolService(mock(org.springframework.jdbc.core.JdbcTemplate.class),json,mock(org.springframework.transaction.PlatformTransactionManager.class));
        var nested=json.createObjectNode();var next=nested;for(int i=0;i<10;i++)next=next.putObject("data");
        var depth=assertThrows(ConversationProblem.class,()->service.sanitize(nested,0));assertEquals("TOOL_RESULT_INVALID",depth.getMessage());
        var text=json.createObjectNode();text.putObject("data").put("content","x".repeat(8001));
        assertEquals("TOOL_RESULT_SIZE_LIMIT",assertThrows(ConversationProblem.class,()->service.sanitize(text,0)).getMessage());
        var array=json.createObjectNode();var refs=array.putArray("references");for(int i=0;i<101;i++)refs.addObject().putObject("data").put("title","bounded");
        assertEquals("TOOL_RESULT_SIZE_LIMIT",assertThrows(ConversationProblem.class,()->service.sanitize(array,0)).getMessage());
    }
    @Test void databaseErrorsDoNotExposeSqlOrConnectionDetails()throws Exception {
        var response=new AgentToolExceptionHandler().unavailable(new org.springframework.dao.DataAccessResourceFailureException("SELECT private_token FROM credential_table; jdbc:mysql://secret-host"));
        assertEquals(503,response.getStatusCode().value());
        String body=json.writeValueAsString(response.getBody());assertTrue(body.contains("AGENT_TOOLS_UNAVAILABLE"));
        assertFalse(body.contains("SELECT"));assertFalse(body.contains("credential_table"));assertFalse(body.contains("secret-host"));
    }
    @Test void renewalKeepsExactBearerBindingAndRejectsReplacementIdentity() {
        var tenant=new Tenant("organization","t1");var scope=new Scope(java.util.List.of("p1"),java.util.List.of("d1"));
        var original=new Principal(1,"a".repeat(64),2,3,"xiaozhi-test","s1",tenant,scope,java.time.Instant.now().plusSeconds(300));
        var renewed=new Principal(1,original.tokenHash(),2,3,original.issuer(),original.subject(),tenant,scope,original.expiresAt().plusSeconds(300));
        assertTrue(AgentToolService.sameBinding(original,renewed));
        assertFalse(AgentToolService.sameBinding(original,new Principal(4,"b".repeat(64),2,3,original.issuer(),original.subject(),tenant,scope,renewed.expiresAt())));
        assertFalse(AgentToolService.sameBinding(original,new Principal(1,original.tokenHash(),2,3,original.issuer(),original.subject(),new Tenant("organization","t2"),scope,renewed.expiresAt())));
        assertFalse(AgentToolService.sameBinding(original,new Principal(1,original.tokenHash(),2,3,original.issuer(),original.subject(),tenant,new Scope(java.util.List.of("p2"),java.util.List.of("d1")),renewed.expiresAt())));
    }
    @Test void fixedSchemaRejectsInventedToolsAndTrustedActorArguments()throws Exception {
        assertThrows(ConversationProblem.class,()->AgentToolRegistry.validate("shell",json.readTree("{}")));
        for(String field:new String[]{"user_id","tenant_id","url","headers","sql","role"})
            assertThrows(ConversationProblem.class,()->AgentToolRegistry.validate("get_device",json.readTree("{\"device_id\":\"dev1\",\""+field+"\":\"admin\"}")));
        AgentToolRegistry.validate("get_device",json.readTree("{\"device_id\":\"device_1:2\"}"));
        AgentToolRegistry.validate("search_knowledge",json.readTree("{\"query\":\"低温报警\"}"));
        assertThrows(ConversationProblem.class,()->AgentToolRegistry.validate("get_device",json.readTree("{\"device_id\":\"../all\"}")));
        assertThrows(ConversationProblem.class,()->AgentToolRegistry.validate("get_device",json.readTree("{\"device_id\":7}")));
        assertThrows(ConversationProblem.class,()->AgentToolRegistry.validate("server_capabilities",json.readTree("{\"customer_id\":\"1\"}")));
    }
    @Test void toolsCannotSetExecutionTargetsAndSchemasForbidUnknownFields() {
        for(String name:AgentToolRegistry.NAMES) {
            var declaration=AgentToolRegistry.declaration(name);
            assertFalse(declaration.path("function").path("parameters").path("additionalProperties").asBoolean());
            assertTrue(declaration.path("function").path("parameters").path("required").isArray());
        }
    }
    @Test void rawProtocolRejectsDuplicatesTrailingJsonUnknownFieldsAndCoercions()throws Exception {
        AgentToolService service=mock(AgentToolService.class);
        var controller=new AgentToolWorkerController(service,json);
        String base="{\"lease_token\":\""+"a".repeat(64)+"\",\"attempt\":1,\"control_epoch\":1,\"round\":1,\"calls\":[{\"provider_call_id\":\"call1\",\"name\":\"server_capabilities\",\"arguments\":{}}]}";
        for(String value:new String[]{base+"{}",base.replace("\"attempt\":1","\"attempt\":\"1\""),
                base.replace("\"arguments\":{}","\"arguments\":{\"x\":1,\"x\":2}"),base.replace("\"round\":1","\"round\":1,\"actor_id\":7"),base.replace("\"calls\":[","\"calls\":false,\"unused\":[")}) {
            var request=new MockHttpServletRequest();request.setContent(value.getBytes(StandardCharsets.UTF_8));
            assertThrows(ConversationProblem.class,()->controller.execute("a".repeat(32),request));
        }
        verifyNoInteractions(service);
        var oversized=new MockHttpServletRequest();oversized.setContent(new byte[32769]);
        assertEquals(413,assertThrows(ConversationProblem.class,()->controller.execute("a".repeat(32),oversized)).status());
        var valid=new MockHttpServletRequest();valid.setContent(base.getBytes(StandardCharsets.UTF_8));controller.execute("a".repeat(32),valid);
        verify(service).submitCalls(eq("a".repeat(32)),any());
    }
}
