package xiaozhi.modules.externalidentity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.conversation.ConversationProblem;
import xiaozhi.modules.conversation.ConversationModels.CursorPage;
import xiaozhi.modules.supportmedia.MediaModels.InvitationPreview;

/** Preview follows the persistent issuer/subject/tenant conversation owner; it grants no media authority. */
@RestController
@RequestMapping("/api/v1/external/media-invitations")
@ConditionalOnProperty(prefix="features", name={"external-identity-enabled","external-realtime-media-enabled","conversation-enabled","support-handoff-enabled","support-media-enabled"}, havingValue="true")
public class ExternalInvitationController {
    private final ExternalIdentityService identity;
    private final ExternalMediaAuthority authority;
    private final JdbcTemplate db;
    public ExternalInvitationController(ExternalIdentityService identity, ExternalMediaAuthority authority, JdbcTemplate db) {
        this.identity=identity; this.authority=authority; this.db=db;
    }
    @GetMapping public Result<CursorPage<InvitationPreview>> list(@RequestParam(defaultValue="0") long after,
            @RequestParam(defaultValue="50") int limit, HttpServletRequest request, HttpServletResponse response) {
        ExternalIdentityController.privateResponse(response);
        var p=identity.required(request);
        authority.validate(p.sourceId(),p.tokenId(),p.userId());
        if(after<0 || limit<1 || limit>100) throw new ConversationProblem(400,"INVALID_CURSOR_OR_LIMIT");
        List<InvitationPreview> rows=db.query("""
            SELECT x.id,x.invitation_type,x.session_id,x.conversation_id,x.from_user_id,x.to_user_id,x.status,x.expires_at,
                   x.control_epoch,r.service_type,COALESCE(NULLIF(profile.display_name,''),caller.username) AS caller_name
            FROM (
              SELECT s.id,'session' AS invitation_type,s.id AS session_id,s.conversation_id,
                     s.inviter_user_id AS from_user_id,s.invitee_user_id AS to_user_id,s.customer_user_id,
                     s.external_source_id,s.external_token_id,s.status,s.expires_at,s.control_epoch,s.request_id
              FROM support_media_session s
              JOIN support_assignment a ON a.id=s.assignment_id AND a.agent_user_id=s.inviter_user_id AND a.released_at IS NULL
              WHERE s.status='invited' AND s.expires_at>CURRENT_TIMESTAMP(3)
              UNION ALL
              SELECT tr.id,'transfer',s.id,s.conversation_id,tr.requester_user_id,s.customer_user_id,s.customer_user_id,
                     s.external_source_id,s.external_token_id,tr.status,tr.expires_at,s.control_epoch,s.request_id
              FROM support_media_transfer tr JOIN support_media_session s ON s.id=tr.session_id
              WHERE tr.status IN ('invited','preparing','ready') AND tr.customer_approved_at IS NULL
                AND tr.expires_at>CURRENT_TIMESTAMP(3) AND s.external_source_id IS NOT NULL
            ) x JOIN ai_conversation c ON c.id=x.conversation_id
            JOIN support_request r ON r.id=x.request_id AND r.status='active'
            JOIN sys_user caller ON caller.id=x.from_user_id AND caller.status=1
            LEFT JOIN support_public_profile profile ON profile.user_id=x.from_user_id
            JOIN external_access_token t ON t.id=? AND t.source_id=?
            JOIN external_source_session src ON src.id=t.source_id
            JOIN external_tenant_principal p ON p.id=t.principal_id AND p.user_id=c.owner_user_id
            JOIN external_identity i ON i.id=p.identity_id AND i.issuer=src.issuer AND i.external_subject=src.external_subject
            WHERE c.owner_user_id=? AND x.customer_user_id=? AND x.to_user_id=?
              AND p.tenant_type=? AND p.tenant_id=? AND i.issuer=? AND i.external_subject=?
              AND ((x.external_source_id IS NULL AND x.external_token_id IS NULL)
                   OR (x.external_source_id=t.source_id AND x.external_token_id=t.id)) AND x.id>?
            ORDER BY x.id LIMIT ?
            """,(rs,n)->new InvitationPreview(Long.toString(rs.getLong("id")),rs.getString("invitation_type"),Long.toString(rs.getLong("session_id")),
                    rs.getString("conversation_id"),Long.toString(rs.getLong("from_user_id")),
                    Long.toString(rs.getLong("to_user_id")),rs.getString("status"),rs.getTimestamp("expires_at").toInstant(),
                    rs.getString("caller_name"), "expert".equals(rs.getString("service_type"))?"expert":"human",rs.getLong("control_epoch")),
                p.tokenId(),p.sourceId(),p.userId(),p.userId(),p.userId(),p.tenant().type(),p.tenant().id(),p.issuer(),p.subject(),after,limit+1);
        var items=rows.stream().limit(limit).toList();
        authority.validate(p.sourceId(),p.tokenId(),p.userId());
        return new Result<CursorPage<InvitationPreview>>().ok(new CursorPage<>(items,items.isEmpty()?after:Long.parseLong(items.getLast().id()),rows.size()>limit));
    }
}
