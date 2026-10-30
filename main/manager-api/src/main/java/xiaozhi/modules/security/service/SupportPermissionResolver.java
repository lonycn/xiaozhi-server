package xiaozhi.modules.security.service;

import java.util.HashSet;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Resolves current account and support profile on every authorization check. */
@Service
public class SupportPermissionResolver {
    private static final Set<String> STAFF_PERMISSIONS = Set.of(
            "support:queue:read", "support:request:claim", "support:message:send",
            "support:presence:write", "support:request:resolve", "support:history:read");
    private final JdbcTemplate jdbc;
    private final boolean enabled;

    public SupportPermissionResolver(JdbcTemplate jdbc,
            @Value("${features.support-handoff-enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    public Set<String> resolve(Long userId) {
        if (!enabled || userId == null) {
            return Set.of();
        }
        Set<String> permissions = new HashSet<>();
        jdbc.query("SELECT u.super_admin, p.role, p.enabled FROM sys_user u "
                + "LEFT JOIN support_agent_profile p ON p.user_id = u.id "
                + "WHERE u.id = ? AND u.status = 1", rs -> {
            if (rs.getInt("super_admin") == 1) {
                permissions.add("support:agents:manage");
            }
            String role = rs.getString("role");
            if (rs.getInt("enabled") == 1 && ("support_agent".equals(role) || "expert".equals(role))) {
                permissions.addAll(STAFF_PERMISSIONS);
            }
        }, userId);
        return permissions;
    }
}
