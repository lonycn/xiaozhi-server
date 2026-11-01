package xiaozhi.modules.externalidentity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import xiaozhi.modules.externalidentity.ExternalModels.*;

class ExternalInvitationControllerTest {
    @Test void queriesOnlyCurrentSourceTokenAndOwnerAndChecksRevocationTwice() {
        var identity=mock(ExternalIdentityService.class); var authority=mock(ExternalMediaAuthority.class);
        var db=mock(JdbcTemplate.class); var request=mock(HttpServletRequest.class); var response=mock(HttpServletResponse.class);
        var principal=new Principal(17,"hash",23,42,"issuer","subject",new Tenant("org","x"),new Scope(List.of(),List.of()),Instant.now().plusSeconds(60));
        when(identity.required(request)).thenReturn(principal);
        when(db.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
        new ExternalInvitationController(identity,authority,db).list(0,50,request,response);
        verify(authority,times(2)).validate(23,17,42);
        verify(db).query(argThat(sql -> sql.contains("c.owner_user_id=?") && sql.contains("x.external_source_id IS NULL") && sql.contains("x.external_source_id=t.source_id") && sql.contains("x.external_token_id=t.id") && sql.contains("p.tenant_type=?") && sql.contains("i.issuer=?")),
                any(RowMapper.class),eq(17L),eq(23L),eq(42L),eq(42L),eq(42L),eq("org"),eq("x"),eq("issuer"),eq("subject"),eq(0L),eq(51));
    }
    @Test void rejectsRevokedSourceBeforeQuery() {
        var identity=mock(ExternalIdentityService.class); var authority=mock(ExternalMediaAuthority.class);
        var db=mock(JdbcTemplate.class); var request=mock(HttpServletRequest.class); var response=mock(HttpServletResponse.class);
        when(identity.required(request)).thenReturn(new Principal(17,"hash",23,42,"issuer","subject",new Tenant("org","x"),new Scope(List.of(),List.of()),Instant.now().plusSeconds(60)));
        when(authority.validate(23,17,42)).thenThrow(new ExternalProblem(403,"EXTERNAL_MEDIA_BINDING_DENIED"));
        assertThrows(ExternalProblem.class,()->new ExternalInvitationController(identity,authority,db).list(0,50,request,response));
        verifyNoInteractions(db);
    }
}
