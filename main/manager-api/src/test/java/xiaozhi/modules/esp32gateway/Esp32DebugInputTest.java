package xiaozhi.modules.esp32gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.sys.service.SysParamsService;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class Esp32DebugInputTest {
    static final String DEVICE="d".repeat(32), CONVERSATION="c".repeat(32), SESSION="a".repeat(32);
    @Test void textIsBoundedAndNeverAllowedThroughGenericMcpCommand() {
        Esp32GatewayService.validateInput(CONVERSATION,"<测试>\n文字", "key");
        for(String value:List.of("", " ", "x".repeat(2001), "text\u0000")) assertThrows(ResponseStatusException.class,()->Esp32GatewayService.validateInput(CONVERSATION,value,"key"));
        assertThrows(ResponseStatusException.class,()->Esp32GatewayService.validateInput("not-a-conversation","hello","key"));
        assertThrows(ResponseStatusException.class,()->Esp32GatewayService.validateCommand(Esp32GatewayService.TEXT_INPUT,Map.of("text","hello")));
    }
    @Test void literalTextAndOwnerReachTheDedicatedQueueOnlyAfterConnectionAndSessionChecks() throws Exception {
        var db=mock(JdbcTemplate.class); var service=spy(new Esp32GatewayService(db,mock(SysParamsService.class),new ObjectMapper(),true));
        var context=new Esp32GatewayService.DeviceContext(DEVICE,1L,"agent","binding","connection",2,"gateway",Long.MAX_VALUE);
        doReturn(context).when(service).current(DEVICE);
        when(db.queryForList(eq("SELECT * FROM esp32_connection WHERE device_id=? FOR UPDATE"),eq(DEVICE))).thenReturn(List.of(Map.of("capabilities","[\"gateway.text_input\"]")));
        when(db.queryForList(startsWith("SELECT id,conversation_id FROM esp32_device_session"), any(Object[].class))).thenReturn(List.of(Map.of("id",SESSION,"conversation_id",CONVERSATION)));
        when(db.queryForList(startsWith("SELECT * FROM esp32_command WHERE device_id=? AND owner_id=? AND idempotency_key=?"), any(Object[].class))).thenReturn(List.of());
        when(db.queryForObject(startsWith("SELECT COUNT(*) FROM esp32_command"),eq(Integer.class),eq(DEVICE))).thenReturn(0);
        doAnswer(call->Map.of("id",call.getArgument(1),"state","pending")).when(service).command(eq(1L),anyString());
        assertThrows(ResponseStatusException.class,()->service.submitTextOwner(2L,DEVICE,CONVERSATION,"hello","key"));
        verify(db,never()).update(startsWith("INSERT INTO esp32_command"), any(Object[].class));
        assertThrows(ResponseStatusException.class,()->service.submitTextOwner(1L,DEVICE,"b".repeat(32),"hello","key"));
        verify(db,never()).update(startsWith("INSERT INTO esp32_command"), any(Object[].class));
        assertEquals("pending",service.submitTextOwner(1L,DEVICE,CONVERSATION,"<测试>\n文字","key").get("state"));
        verify(db).update(startsWith("INSERT INTO esp32_command"),anyString(),eq(DEVICE),eq(1L),eq("agent"),eq("binding"),eq("connection"),eq(2L),eq("gateway"),eq("key"),anyString(),eq(Esp32GatewayService.TEXT_INPUT),argThat((String payload)->payload.contains("<测试>")&&payload.contains("\\n文字")&&payload.contains(SESSION)),anyLong(),anyLong());
    }
    @Test void encodedLegacyMacUsesTheBoundedLiteralBodyFilter() throws Exception {
        byte[] text="{\"text\":\"<literal>\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var request=new org.springframework.mock.web.MockHttpServletRequest();request.setMethod("POST");
        request.setRequestURI("/api/v1/esp32/devices/02%3A21%3A64%3A63%3A44%3A5a/text-input");request.setContent(text);
        var response=new org.springframework.mock.web.MockHttpServletResponse();
        new Esp32DebugInputBoundary.InputFilter().doFilter(request,response,(r,out)->assertArrayEquals(text,(byte[])r.getAttribute(Esp32DebugInputBoundary.BODY)));
        assertEquals(200,response.getStatus());
    }
    @Test void controllerPreservesLiteralTextAndRejectsUnknownDuplicateOrOversizedBodies() throws Exception {
        var service=mock(Esp32GatewayService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new Esp32GatewayController(service)).setControllerAdvice(new Esp32GatewayAdvice()).addFilters(new Esp32DebugInputBoundary.InputFilter()).build();
        try(var security=mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUserId).thenReturn(1L);
            when(service.submitTextOwner(eq(1L),eq(DEVICE),eq(CONVERSATION),eq("<test>\n文字"),eq("key"))).thenReturn(Map.of("id","command","state","pending"));
            String valid=new ObjectMapper().writeValueAsString(Map.of("conversationId",CONVERSATION,"text","<test>\n文字","idempotencyKey","key"));
            mvc.perform(post("/api/v1/esp32/devices/"+DEVICE+"/text-input").contentType(MediaType.APPLICATION_JSON).content(valid)).andExpect(status().isOk());
            verify(service).submitTextOwner(1L,DEVICE,CONVERSATION,"<test>\n文字","key");
            for(String body:List.of(valid.replace("\"key\"","\"key\",\"fence\":{}"),valid.replace("\"key\"","\"key\",\"text\":\"again\""),"x".repeat(16385))) {
                mvc.perform(post("/api/v1/esp32/devices/"+DEVICE+"/text-input").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
            }
            mvc.perform(post("/api/v1/esp32/devices/"+DEVICE+"/text-input?other=1").contentType(MediaType.APPLICATION_JSON).content(valid)).andExpect(status().isBadRequest());
            verifyNoMoreInteractions(service);
        }
    }
}
