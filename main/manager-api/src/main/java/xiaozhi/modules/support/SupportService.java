package xiaozhi.modules.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import xiaozhi.modules.conversation.ConversationModels.*;
import xiaozhi.modules.conversation.ConversationProblem;
import xiaozhi.modules.conversation.ConversationService;
import xiaozhi.modules.support.SupportModels.*;

/** MySQL is authoritative for queue, assignment, capacity and the 45-second presence lease. */
@Service
public class SupportService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final ConversationService conversations;
    private xiaozhi.modules.ai.AiExecutionService aiExecution;
    private org.springframework.beans.factory.ObjectProvider<xiaozhi.modules.realtime.RealtimeHooks> realtime;
    @org.springframework.beans.factory.annotation.Autowired
    public void setRealtime(org.springframework.beans.factory.ObjectProvider<xiaozhi.modules.realtime.RealtimeHooks> value) { realtime=value; }
    private org.springframework.beans.factory.ObjectProvider<xiaozhi.modules.supportmedia.SupportMediaHooks> mediaProvider;
    private xiaozhi.modules.supportmedia.SupportMediaHooks mediaForTests;
    @org.springframework.beans.factory.annotation.Autowired
    public void setMediaProvider(org.springframework.beans.factory.ObjectProvider<xiaozhi.modules.supportmedia.SupportMediaHooks> provider){this.mediaProvider=provider;}
    public void setMediaHooksForTests(xiaozhi.modules.supportmedia.SupportMediaHooks hooks){this.mediaForTests=hooks;}
    private xiaozhi.modules.supportmedia.SupportMediaHooks media(){return mediaForTests!=null?mediaForTests:mediaProvider==null?null:mediaProvider.getIfAvailable();}

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAiExecution(xiaozhi.modules.ai.AiExecutionService service) { this.aiExecution = service; }

    public SupportService(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager manager,
                          ConversationService conversations) {
        this.jdbc = jdbc;
        this.json = json;
        this.transactions = new TransactionTemplate(manager);
        this.conversations = conversations;
    }

    public Agent upsertAgent(Long actor, Long userId, AgentUpdate body) {
        actor(actor);
        if (userId == null || userId <= 0 || body == null
                || !("support_agent".equals(body.role()) || "expert".equals(body.role()))
                || body.enabled() == null || body.capacityText() == null
                || body.capacityText() < 1 || body.capacityText() > 20) throw problem(400, "INVALID_AGENT_PROFILE");
        return transactions.execute(tx -> {
            administrator(actor);
            // Existing profiles lock before accounts, matching claim/presence lock order.
            jdbc.queryForList("SELECT user_id FROM support_agent_profile WHERE user_id = ? FOR UPDATE", userId);
            if (jdbc.queryForList("SELECT id FROM sys_user WHERE id = ? AND status = 1 FOR UPDATE", userId).isEmpty())
                throw problem(404, "ENABLED_USER_NOT_FOUND");
            jdbc.update("""
                    INSERT INTO support_agent_profile(user_id,role,enabled,capacity_text)
                    VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE role=VALUES(role),enabled=VALUES(enabled),
                    capacity_text=VALUES(capacity_text),
                    accepting=IF(VALUES(enabled)=1,accepting,0),updated_at=CURRENT_TIMESTAMP(3)
                    """, userId, body.role(), body.enabled(), body.capacityText());
            if(media()!=null)media().accountChanged(userId);
            return rawProfile(userId, false);
        });
    }

    /** Read-only restoration of the signed-in staff profile; this does not renew presence. */
    public Agent me(Long actor) {
        long user = actor(actor);
        if (jdbc.queryForList("SELECT id FROM sys_user WHERE id=? AND status=1", user).isEmpty())
            throw problem(403, "ENABLED_SUPPORT_ACCOUNT_REQUIRED");
        List<Agent> rows = jdbc.query("SELECT * FROM support_agent_profile WHERE user_id=? AND enabled=1",
                SupportService::agentRow, user);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** Profile administration never implies permission to read conversations. */
    public CursorPage<AgentSummary> agents(Long actor, long before, int limit) {
        long user = actor(actor);
        pagination(before, limit);
        administrator(user);
        List<AgentSummary> rows = jdbc.query("""
                SELECT p.*, u.username, u.status AS account_status FROM support_agent_profile p
                JOIN sys_user u ON u.id=p.user_id WHERE p.user_id < ? ORDER BY p.user_id DESC LIMIT ?
                """, (rs,n) -> new AgentSummary(rs.getLong("user_id"), rs.getString("username"),
                rs.getInt("account_status") == 1, rs.getString("role"), rs.getBoolean("enabled"),
                rs.getInt("capacity_text"), rs.getBoolean("accepting"), instant(rs,"presence_expires_at")),
                before == 0 ? Long.MAX_VALUE : before, limit + 1);
        List<AgentSummary> items = rows.stream().limit(limit).toList();
        return new CursorPage<>(items, items.isEmpty() ? before : items.getLast().userId(), rows.size() > limit);
    }

    /** Only live assignment rows can restore the active desk after a page reload. */
    public CursorPage<Request> assigned(Long actor, long before, int limit) {
        long user = actor(actor);
        pagination(before, limit);
        profile(user, false);
        List<Request> rows = jdbc.query("""
                SELECT r.* FROM support_request r WHERE r.status='active' AND r.assigned_agent_user_id=?
                AND r.list_sequence < ? AND EXISTS (SELECT 1 FROM support_assignment a
                WHERE a.request_id=r.id AND a.agent_user_id=? AND a.released_at IS NULL)
                ORDER BY r.list_sequence DESC LIMIT ?
                """, SupportService::requestRow, user, before == 0 ? Long.MAX_VALUE : before, user, limit + 1);
        List<Request> items = rows.stream().limit(limit).toList();
        return new CursorPage<>(items, items.isEmpty() ? before : items.getLast().listSequence(), rows.size() > limit);
    }

    public Agent presence(Long actor, PresenceUpdate body) {
        long user = actor(actor);
        if (body == null || body.accepting() == null) throw problem(400, "ACCEPTING_REQUIRED");
        return transactions.execute(tx -> {
            profile(user, true);
            jdbc.update("""
                    UPDATE support_agent_profile SET accepting=?,
                    presence_expires_at=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 45 SECOND),
                    updated_at=CURRENT_TIMESTAMP(3) WHERE user_id=?
                    """, body.accepting(), user);
            return profile(user, false);
        });
    }

    public Request handoff(Long actor, String conversationId, Handoff body) {
        long user = actor(actor);
        validId(conversationId);
        if (body == null || body.clientRequestId() == null
                || !body.clientRequestId().matches("[a-zA-Z0-9][a-zA-Z0-9_.:-]{0,63}")
                || !"text".equals(body.requestedMode())
                || (body.targetAgentUserId() != null && body.targetAgentUserId() <= 0))
            throw problem(400, "INVALID_HANDOFF_REQUEST");
        String hash = hash(body.requestedMode() + ":" + body.targetAgentUserId());
        return transactions.execute(tx -> {
            Conv conv = conversation(conversationId, true);
            owner(conv, user);
            List<Map<String,Object>> aliases = jdbc.queryForList(
                    "SELECT request_hash,request_id FROM support_request_key WHERE conversation_id=? AND client_request_id=? FOR UPDATE",
                    conversationId, body.clientRequestId());
            if (!aliases.isEmpty()) {
                if (!hash.equals(aliases.getFirst().get("request_hash"))) throw problem(409, "CLIENT_REQUEST_ID_REUSED");
                return request((String) aliases.getFirst().get("request_id"), false);
            }
            List<Map<String,Object>> previous = jdbc.queryForList(
                    "SELECT request_hash,id FROM support_request WHERE conversation_id=? AND client_request_id=? FOR UPDATE",
                    conversationId, body.clientRequestId());
            if (!previous.isEmpty()) {
                if (!hash.equals(previous.getFirst().get("request_hash"))) throw problem(409, "CLIENT_REQUEST_ID_REUSED");
                return request((String) previous.getFirst().get("id"), false);
            }
            List<Request> active = jdbc.query("SELECT * FROM support_request WHERE conversation_id=? AND active_slot=1 FOR UPDATE",
                    SupportService::requestRow, conversationId);
            if (!active.isEmpty()) {
                Request existing = active.getFirst();
                if (!Objects.equals(body.targetAgentUserId(), existing.targetAgentUserId())
                        || !body.requestedMode().equals(existing.requestedMode())) throw problem(409, "ACTIVE_SUPPORT_REQUEST_EXISTS");
                rememberKey(conversationId, body.clientRequestId(), existing.requestId(), hash);
                return existing;
            }
            if (body.targetAgentUserId() != null) {
                // Read only: taking a target's profile lock after conversation would invert claim order.
                profile(body.targetAgentUserId(), false);
            }
            String requestId = id();
            jdbc.update("""
                    INSERT INTO support_request(id,conversation_id,client_request_id,request_hash,requested_mode,target_agent_user_id)
                    VALUES (?,?,?,?,?,?)
                    """, requestId, conversationId, body.clientRequestId(), hash, body.requestedMode(), body.targetAgentUserId());
            rememberKey(conversationId, body.clientRequestId(), requestId, hash);
            state(conversationId, "HUMAN_QUEUED", true);
            event(conversationId, "support.requested", requestId);
            event(conversationId, "support.queued", requestId);
            return request(requestId, false);
        });
    }

    public CursorPage<Request> queue(Long actor, long before, int limit) {
        long user = actor(actor);
        pagination(before, limit);
        profile(user, false);
        List<Request> rows = jdbc.query("""
                SELECT * FROM support_request WHERE status='queued' AND list_sequence < ?
                AND (target_agent_user_id IS NULL OR target_agent_user_id=?) ORDER BY list_sequence DESC LIMIT ?
                """, SupportService::requestRow, before == 0 ? Long.MAX_VALUE : before, user, limit + 1);
        List<Request> items = rows.stream().limit(limit).toList();
        return new CursorPage<>(items, items.isEmpty() ? before : items.getLast().listSequence(), rows.size() > limit);
    }

    public Request get(Long actor, String requestId) {
        long user = actor(actor);
        Request req = request(requestId, false);
        Conv conv = conversation(req.conversationId(), false);
        if (conv.owner() == user) return req;
        // A past assignment grants a bounded ledger view, not live mutable request metadata.
        profile(user, false);
        if (!"active".equals(req.status()) || !Objects.equals(req.assignedAgentUserId(), user)
                || jdbc.queryForObject("SELECT COUNT(*) FROM support_assignment WHERE request_id=? AND agent_user_id=? AND released_at IS NULL",
                    Long.class, requestId, user) == 0) throw problem(404, "SUPPORT_REQUEST_NOT_FOUND");
        return req;
    }

    public Request claim(Long actor, String requestId) {
        long user = actor(actor);
        Request hint = request(requestId, false);
        return transactions.execute(tx -> {
            Agent agent = profile(user, true);
            Conv conv = conversation(hint.conversationId(), true);
            Request req = request(requestId, true);
            if (conv.owner() == user) throw problem(403, "OWNER_CANNOT_CLAIM");
            if ("active".equals(req.status()) && Objects.equals(req.assignedAgentUserId(), user)) {
                current(req, user);
                return req;
            }
            if (!"queued".equals(req.status())) throw problem(409, "REQUEST_NOT_QUEUED");
            if (req.targetAgentUserId() != null && req.targetAgentUserId() != user) throw problem(403, "TARGET_AGENT_REQUIRED");
            Long online = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM support_agent_profile WHERE user_id=? AND enabled=1 AND accepting=1
                    AND presence_expires_at>CURRENT_TIMESTAMP(3)
                    """, Long.class, user);
            if (online == 0) throw problem(409, "AGENT_NOT_ACCEPTING");
            // A locking read sees latest commits even under MySQL REPEATABLE READ.
            int occupied = jdbc.queryForList(
                    "SELECT id FROM support_assignment WHERE agent_user_id=? AND released_at IS NULL FOR UPDATE", user).size();
            if(media()!=null)occupied+=media().reservedTextSlots(user);
            if (occupied >= agent.capacityText()) throw problem(409, "AGENT_AT_CAPACITY");
            jdbc.update("INSERT INTO support_assignment(id,request_id,conversation_id,agent_user_id) VALUES (?,?,?,?)",
                    id(), requestId, req.conversationId(), user);
            jdbc.update("UPDATE support_request SET status='active',assigned_agent_user_id=?,assigned_at=CURRENT_TIMESTAMP(3) WHERE id=?",
                    user, requestId);
            state(req.conversationId(), "HUMAN_ACTIVE", false);
            event(req.conversationId(), "support.taken_over", requestId);
            return request(requestId, false);
        });
    }

    public Message send(Long actor, String requestId, SendMessage body) {
        long user = actor(actor);
        Request hint = request(requestId, false);
        return transactions.execute(tx -> {
            Agent agent = profile(user, true);
            conversation(hint.conversationId(), true);
            Request req = request(requestId, true);
            current(req, user);
            // ConversationService validates all ordered text/location/media parts and performs
            // attachment binding in this same message transaction after current-assignment checks.
            Message message = conversations.sendSupport(user, req.conversationId(), body, agent.role());
            jdbc.update("UPDATE support_request SET first_response_at=COALESCE(first_response_at,CURRENT_TIMESTAMP(3)) WHERE id=?", requestId);
            return message;
        });
    }

    public Request resolve(Long actor, String requestId, EndRequest body) {
        long user = actor(actor);
        String reason = reason(body);
        Request hint = request(requestId, false);
        return transactions.execute(tx -> {
            profile(user, true);
            conversation(hint.conversationId(), true);
            Request req = request(requestId, true);
            if ("resolved".equals(req.status()) && Objects.equals(req.assignedAgentUserId(), user)) return req;
            current(req, user);
            jdbc.update("UPDATE support_request SET status='resolved',ended_at=CURRENT_TIMESTAMP(3),end_reason=? WHERE id=?", reason, requestId);
            state(req.conversationId(), "RESOLVED", false);
            event(req.conversationId(), "support.resolved", requestId);
            release(req);
            return request(requestId, false);
        });
    }

    public Request cancel(Long actor, String requestId, EndRequest body) {
        long user = actor(actor);
        String reason = reason(body);
        Request hint = request(requestId, false);
        return transactions.execute(tx -> {
            Conv conv = conversation(hint.conversationId(), true);
            owner(conv, user);
            Request req = request(requestId, true);
            if ("canceled".equals(req.status())) return req;
            if (!"queued".equals(req.status())) throw problem(409, "ONLY_QUEUED_REQUEST_CAN_CANCEL");
            jdbc.update("UPDATE support_request SET status='canceled',ended_at=CURRENT_TIMESTAMP(3),end_reason=? WHERE id=?", reason, requestId);
            state(req.conversationId(), "RESOLVED", false);
            event(req.conversationId(), "support.canceled", requestId);
            return request(requestId, false);
        });
    }

    public Conversation resumeAi(Long actor, String conversationId) {
        long user = actor(actor);
        return transactions.execute(tx -> {
            Conv conv = conversation(conversationId, true);
            owner(conv, user);
            if (!jdbc.queryForList("SELECT id FROM support_request WHERE conversation_id=? AND active_slot=1 FOR UPDATE", conversationId).isEmpty())
                throw problem(409, "ACTIVE_SUPPORT_REQUEST_EXISTS");
            if (!"AI_ACTIVE".equals(conv.state())) {
                state(conversationId, "AI_ACTIVE", true);
                event(conversationId, "ai.resumed", null);
            }
            return conversations.get(user, conversationId);
        });
    }

    /** Explicit offline recovery; a presence expiry itself never revokes an assignment. */
    public Request requeue(Long actor, String requestId, EndRequest body, boolean administrator) {
        long user = actor(actor);
        reason(body);
        Request hint = request(requestId, false);
        long lockingUser = administrator && hint.assignedAgentUserId() != null ? hint.assignedAgentUserId() : user;
        return transactions.execute(tx -> {
            if (administrator) administrator(user);
            // Administrators must also recover disabled profiles; current assignees require enabled accounts.
            if (administrator) jdbc.queryForList("SELECT user_id FROM support_agent_profile WHERE user_id=? FOR UPDATE", lockingUser);
            else profile(user, true);
            conversation(hint.conversationId(), true);
            Request req = request(requestId, true);
            if (!Objects.equals(req.assignedAgentUserId(), hint.assignedAgentUserId())) throw problem(409, "ASSIGNMENT_CHANGED");
            if (!administrator) current(req, user);
            if (!"active".equals(req.status())) throw problem(409, "REQUEST_NOT_ACTIVE");
            state(req.conversationId(), "HUMAN_QUEUED", true);
            event(req.conversationId(), "support.requeued", requestId);
            release(req);
            // Retain first response SLA; responsibility and assignment time belong to the next claim.
            jdbc.update("UPDATE support_request SET status='queued',assigned_agent_user_id=NULL,assigned_at=NULL WHERE id=?", requestId);
            return request(requestId, false);
        });
    }

    private void rememberKey(String conversationId, String clientRequestId, String requestId, String hash) {
        jdbc.update("INSERT INTO support_request_key(conversation_id,client_request_id,request_id,request_hash) VALUES (?,?,?,?)",
                conversationId, clientRequestId, requestId, hash);
    }

    private void release(Request req) {
        if(media()!=null)media().responsibilityEnded(req.requestId());
        Conv conv = conversation(req.conversationId(), true);
        jdbc.update("""
                UPDATE support_assignment SET released_at=CURRENT_TIMESTAMP(3),read_through_seq=?,read_through_event_seq=?
                WHERE request_id=? AND released_at IS NULL
                """, conv.lastSeq(), conv.lastEventSeq(), req.requestId());
    }

    private void current(Request req, long user) {
        if (!"active".equals(req.status()) || !Objects.equals(req.assignedAgentUserId(), user)
                || jdbc.queryForList("SELECT id FROM support_assignment WHERE request_id=? AND agent_user_id=? AND released_at IS NULL FOR UPDATE",
                    req.requestId(), user).isEmpty()) throw problem(403, "CURRENT_ASSIGNMENT_REQUIRED");
    }

    private Agent rawProfile(long user, boolean lock) {
        List<Agent> rows = jdbc.query("SELECT * FROM support_agent_profile WHERE user_id=?" + (lock ? " FOR UPDATE" : ""),
                SupportService::agentRow, user);
        if (rows.isEmpty()) throw problem(403, "ENABLED_SUPPORT_PROFILE_REQUIRED");
        return rows.getFirst();
    }

    private Agent profile(long user, boolean lock) {
        Agent result = rawProfile(user,lock);
        if (!result.enabled()) throw problem(403, "ENABLED_SUPPORT_PROFILE_REQUIRED");
        if (jdbc.queryForList("SELECT id FROM sys_user WHERE id=? AND status=1" + (lock ? " FOR UPDATE" : ""), user).isEmpty())
            throw problem(403, "ENABLED_SUPPORT_ACCOUNT_REQUIRED");
        return result;
    }

    private Request request(String requestId, boolean lock) {
        validId(requestId);
        List<Request> rows = jdbc.query("SELECT * FROM support_request WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                SupportService::requestRow, requestId);
        if (rows.isEmpty()) throw problem(404, "SUPPORT_REQUEST_NOT_FOUND");
        return rows.getFirst();
    }
    private record Conv(String id, long owner, String state, long lastSeq, long lastEventSeq) { }
    private Conv conversation(String id, boolean lock) {
        validId(id);
        List<Conv> rows = jdbc.query("SELECT * FROM ai_conversation WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                (rs,n) -> new Conv(rs.getString("id"),rs.getLong("owner_user_id"),rs.getString("state"),
                        rs.getLong("last_seq"),rs.getLong("last_event_seq")), id);
        if (rows.isEmpty()) throw problem(404, "CONVERSATION_NOT_FOUND");
        return rows.getFirst();
    }
    private void state(String conversationId, String state, boolean incrementEpoch) {
        if (realtime!=null && realtime.getIfAvailable()!=null) realtime.getObject().cancelInConversation(conversationId,"HUMAN_CONTROL_CHANGED");
        if (aiExecution != null) aiExecution.cancelActive(conversationId);
        jdbc.update("UPDATE ai_conversation SET state=?,control_epoch=control_epoch+?,updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",
                state, incrementEpoch ? 1 : 0, conversationId);
    }
    private void event(String conversationId, String type, String requestId) {
        Conv conv = conversation(conversationId, true);
        long sequence = conv.lastEventSeq() + 1;
        String data;
        try { data = json.writeValueAsString(requestId == null ? Map.of("conversation_id",conversationId)
                : Map.of("conversation_id",conversationId,"request_id",requestId)); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
        jdbc.update("INSERT INTO ai_conversation_event(id,conversation_id,seq,event_type,data_json) VALUES (?,?,?,?,?)",
                id(), conversationId, sequence, type, data);
        jdbc.update("UPDATE ai_conversation SET last_event_seq=?,updated_at=CURRENT_TIMESTAMP(3) WHERE id=?", sequence, conversationId);
    }
    private static Agent agentRow(ResultSet rs, int row) throws SQLException {
        return new Agent(rs.getLong("user_id"), rs.getString("role"), rs.getBoolean("enabled"),
                rs.getInt("capacity_text"), rs.getBoolean("accepting"), instant(rs,"presence_expires_at"));
    }
    private static Request requestRow(ResultSet rs, int row) throws SQLException {
        return new Request(rs.getString("id"),rs.getLong("list_sequence"),rs.getString("conversation_id"),
                rs.getString("client_request_id"),rs.getString("status"),rs.getString("requested_mode"),
                rs.getObject("target_agent_user_id",Long.class),rs.getObject("assigned_agent_user_id",Long.class),
                instant(rs,"requested_at"),instant(rs,"assigned_at"),instant(rs,"first_response_at"),
                instant(rs,"ended_at"),rs.getString("end_reason"));
    }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
    private static void owner(Conv conv, long user) {
        if (conv.owner() != user) throw problem(404, "CONVERSATION_NOT_FOUND");
    }
    private void administrator(long user) {
        if (jdbc.queryForList("SELECT id FROM sys_user WHERE id=? AND status=1 AND super_admin=1",user).isEmpty())
            throw problem(403, "SUPPORT_ADMIN_REQUIRED");
    }
    private static void pagination(long before, int limit) {
        if (before < 0 || limit < 1 || limit > 100) throw problem(400, "INVALID_CURSOR_OR_LIMIT");
    }
    private static long actor(Long user) {
        if (user == null || user <= 0) throw problem(401, "AUTHENTICATION_REQUIRED");
        return user;
    }
    private static void validId(String id) {
        if (id == null || !id.matches("[a-f0-9]{32}")) throw problem(404, "RESOURCE_NOT_FOUND");
    }
    private static String reason(EndRequest body) {
        if (body != null && body.reason() != null && body.reason().length() > 500) throw problem(400, "REASON_MAX_500");
        return body == null ? null : body.reason();
    }
    private static String id() { return UUID.randomUUID().toString().replace("-", ""); }
    private static ConversationProblem problem(int status, String code) { return new ConversationProblem(status,code); }
    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
