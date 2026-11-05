package xiaozhi.modules.support;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import xiaozhi.modules.conversation.ConversationModels.CursorPage;
import xiaozhi.modules.externalidentity.ExternalPathPolicy;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.support.SupportModels.Expert;

class SupportDirectoryControllerTest {
    @Test void endUsersSeeOnlyDirectoryAndNeverStaffAdministration() throws Exception {
        assertTrue(ExternalPathPolicy.allowed("GET","/api/v1/support/experts"));
        assertFalse(ExternalPathPolicy.allowed("POST","/api/v1/support/experts"));
        assertFalse(ExternalPathPolicy.allowed("GET","/api/v1/support/agents"));
        assertEquals(java.util.Set.of("before","limit"),ExternalPathPolicy.queryKeys("GET","/api/v1/support/experts"));
        var profiles=mock(SupportProfileService.class);
        when(profiles.experts(7L,0,50)).thenReturn(new CursorPage<>(List.of(new Expert(42L,"王工程师","","工程师","",List.of("冷库"),false,false)),42L,false));
        var mvc=MockMvcBuilders.standaloneSetup(new SupportDirectoryController(profiles)).build();
        try(var identity=mockStatic(SecurityUser.class)) {
            identity.when(SecurityUser::getUserId).thenReturn(7L);
            mvc.perform(get("/api/v1/support/experts?user_id=9")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].display_name").value("王工程师"))
                .andExpect(jsonPath("$.data.items[0].username").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].roles").doesNotExist());
            verify(profiles).experts(7L,0,50);
        }
    }
}
