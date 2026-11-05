package xiaozhi.modules.esp32gateway;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.sql.SQLException;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class Esp32GatewayHttpTest {
 @Test void storageErrorsNeverReturnSqlOrCredentials()throws Exception{
  var service=mock(Esp32GatewayService.class);when(service.register(any())).thenThrow(new BadSqlGrammarException("task","SELECT private_credentials",new SQLException("secret-value")));
  var mvc=MockMvcBuilders.standaloneSetup(new Esp32GatewayController(service)).setControllerAdvice(new Esp32GatewayAdvice()).build();
  mvc.perform(post("/internal/esp32/register").contentType(MediaType.APPLICATION_JSON).content("{}")) .andExpect(status().isServiceUnavailable()).andExpect(content().json("{\"code\":\"gateway_storage_unavailable\"}"));
 }
 @Test void authenticationFailurePreservesRealHttpStatus()throws Exception{
  var service=mock(Esp32GatewayService.class);when(service.register(any())).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED,"credential_invalid"));
  var mvc=MockMvcBuilders.standaloneSetup(new Esp32GatewayController(service)).setControllerAdvice(new Esp32GatewayAdvice()).build();
  mvc.perform(post("/internal/esp32/register").contentType(MediaType.APPLICATION_JSON).content("{}")) .andExpect(status().isUnauthorized()).andExpect(content().json("{\"code\":\"credential_invalid\"}"));
 }
}
