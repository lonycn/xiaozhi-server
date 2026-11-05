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
import java.util.Set;

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
    private org.springframework.beans.factory.ObjectProvider<xiaozhi.modules.esp32integration.Esp32Policies> esp32Policies;
    @org.springframework.beans.factory.annotation.Autowired public void setEsp32Policies(org.springframework.beans.factory.ObjectProvider<xiaozhi.modules.esp32integration.Esp32Policies> value){esp32Policies=value;}
    private xiaozhi.modules.esp32integration.Esp32Policies devicePolicy(){return esp32Policies==null?null:esp32Policies.getIfAvailable();}

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final ConversationService conversations;
    private xiaozhi.modules.ai.AiExecutionService aiExecution;
    private org.springframework.beans.factory.ObjectProvider<xiaozhi.modules.realtime.RealtimeHooks> realtime;
    @org.springframework.beans.factory.annotation.Autowired
    public void setRealtime(org.springframework.beans.factory.ObjectProvider<xiaozhi.modules.realtime.RealtimeHooks> value) { realtime=value; }
    private xiaozhi.modules.supportsummary.SupportSummaryService summaries;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSummaries(xiaozhi.modules.supportsummary.SupportSummaryService value) { summaries=value; }
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
        internalStaff();
        actor(actor);
        if (userId == null || userId <= 0 || body == null
                || !("support_agent".equals(body.role()) || "expert".equals(body.role()))
                || body.enabled() == null || body.capacityText() == null
                || body.capacityText() < 1 || body.capacityText() > 20) throw problem(400, "INVALID_AGENT_PROFILE");
        List<String> roles = updatedRoles(body);
        return transactions.execute(tx -> {
            administrator(actor);
            // Existing profiles lock before accounts, matching claim/presence lock order.
            jdbc.queryForList("SELECT user_id FROM support_agent_profile WHERE user_id = ? FOR UPDATE", userId);
            if (jdbc.queryForList("SELECT id FROM sys_user WHERE id = ? AND status = 1 FOR UPDATE", userId).isEmpty())
                throw problem(404, "ENABLED_USER_NOT_FOUND");
            jdbc.update("""
                    INSERT INTO support_agent_profile(user_id,role,enabled,capacity_text,roles_json)
                    VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE role=VALUES(role),roles_json=VALUES(roles_json),enabled=VALUES(enabled),
                    capacity_text=VALUES(capacity_text),
                    accepting=IF(VALUES(enabled)=1 AND JSON_CONTAINS(VALUES(roles_json),JSON_QUOTE('support_agent')),accepting,0),
                    expert_accepting=IF(VALUES(enabled)=1 AND JSON_CONTAINS(VALUES(roles_json),JSON_QUOTE('expert')),expert_accepting,0),
                    updated_at=CURRENT_TIMESTAMP(3)
                    """, userId, body.role(), body.enabled(), body.capacityText(), encode(roles));
            if (publicProfiles != null) publicProfiles.save(userId, body.profile());
            if(media()!=null)media().accountChanged(userId);
            return rawProfile(userId, false);
        });
    }

    /** Read-only restoration of the signed-in staff profile; this does not renew presence. */
    public Agent me(Long actor) {
        internalStaff();
        long user = actor(actor);
        if (jdbc.queryForList("SELECT id FROM sys_user WHERE id=? AND status=1", user).isEmpty())
            throw problem(403, "ENABLED_SUPPORT_ACCOUNT_REQUIRED");
        List<Agent> rows = jdbc.query("SELECT * FROM support_agent_profile WHERE user_id=? AND enabled=1",
                SupportService::agentRow, user);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** Profile administration never implies permission to read conversations. */
    public CursorPage<AgentSummary> agents(Long actor, long before, int limit) {
        internalStaff();
        long user = actor(actor);
        pagination(before, limit);
        administrator(user);
        List<AgentSummary> rows = jdbc.query("""
                SELECT p.*, u.username, u.status AS account_status FROM support_agent_profile p
                JOIN sys_user u ON u.id=p.user_id WHERE p.user_id < ? ORDER BY p.user_id DESC LIMIT ?
                """, (rs,n) -> new AgentSummary(rs.getLong("user_id"), rs.getString("username"),
                rs.getInt("account_status") == 1, rs.getString("role"), rs.getBoolean("enabled"),
                rs.getInt("capacity_text"), primaryPresence(rs).accepting(), primaryPresence(rs).presenceExpiresAt(), roles(rs)),
                before == 0 ? Long.MAX_VALUE : before, limit + 1);
        List<AgentSummary> items = rows.stream().limit(limit).toList();
        return new CursorPage<>(publicProfiles == null ? items : publicProfiles.enrich(items), items.isEmpty() ? before : items.getLast().userId(), rows.size() > limit);
    }

    private SupportProfileService publicProfiles;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setPublicProfiles(SupportProfileService profiles) { this.publicProfiles=profiles; }

    /** Only live assignment rows can restore the active desk after a page reload. */
    public CursorPage<Request> assigned(Long actor, long before, int limit) { return assigned(actor,before,limit,null); }

    public CursorPage<Request> assigned(Long actor, long before, int limit, String serviceType) {
        internalStaff();
        long user = actor(actor);
        pagination(before, limit);
        Agent staff = profile(user, false);
        if(serviceType != null) requireRole(staff,serviceType(serviceType));
        List<Request> rows = jdbc.query("""
                SELECT r.* FROM support_request r WHERE r.status='active' AND r.assigned_agent_user_id=?
                AND EXISTS (SELECT 1 FROM support_agent_profile p WHERE p.user_id=? AND JSON_CONTAINS(COALESCE(p.roles_json,JSON_ARRAY(p.role)),JSON_QUOTE(r.service_type)))
                AND (? IS NULL OR r.service_type=?) AND r.list_sequence < ? AND EXISTS (SELECT 1 FROM support_assignment a
                WHERE a.request_id=r.id AND a.agent_user_id=? AND a.released_at IS NULL)
                ORDER BY r.list_sequence DESC LIMIT ?
                """, SupportService::requestRow, user, user, serviceType, serviceType, before == 0 ? Long.MAX_VALUE : before, user, limit + 1);
        List<Request> items = rows.stream().limit(limit).toList();
        return new CursorPage<>(items, items.isEmpty() ? before : items.getLast().listSequence(), rows.size() > limit);
    }

    public Agent presence(Long actor, PresenceUpdate body) {
        internalStaff();
        long user = actor(actor);
        if (body == null || body.accepting() == null) throw problem(400, "ACCEPTING_REQUIRED");
        return transactions.execute(tx -> {
            Agent staff = profile(user, true);
            String type = serviceType(body.serviceType() == null ? staff.role() : body.serviceType());
            requireRole(staff, type);
            String prefix = "expert".equals(type) ? "expert_" : "";
            jdbc.update("UPDATE support_agent_profile SET " + prefix + "accepting=?, "
                    + prefix + "presence_expires_at=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 45 SECOND), "
                    + "updated_at=CURRENT_TIMESTAMP(3) WHERE user_id=?", body.accepting(), user);
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
        String requestedType = body.serviceType();
        if(requestedType != null) serviceType(requestedType);
        String hash = hash(body.requestedMode() + ":" + body.targetAgentUserId()
                + (requestedType == null ? "" : ":" + requestedType));
        return transactions.execute(tx -> {
            Conv conv = conversation(conversationId, true);
            owner(conv, user);
            if(devicePolicy()!=null)devicePolicy().requireNoHuman(conversationId);
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
            String type = body.serviceType() == null ? "support_agent" : body.serviceType();
            if (body.targetAgentUserId() != null) {
                // Read only: target profile lock after conversation would invert claim order.
                Agent target = profile(body.targetAgentUserId(),false);
                if(body.serviceType() == null) type=target.role();
                requireRole(target,type);
                if(body.targetAgentUserId() == user) throw problem(403,"OWNER_CANNOT_CLAIM");
            }
            List<Request> active = jdbc.query("SELECT * FROM support_request WHERE conversation_id=? AND active_slot=1 FOR UPDATE",
                    SupportService::requestRow, conversationId);
            if (!active.isEmpty()) {
                Request existing = active.getFirst();
                if (!Objects.equals(body.targetAgentUserId(), existing.targetAgentUserId())
                        || !body.requestedMode().equals(existing.requestedMode()) || !type.equals(existing.serviceType())) throw problem(409, "ACTIVE_SUPPORT_REQUEST_EXISTS");
                rememberKey(conversationId, body.clientRequestId(), existing.requestId(), hash);
                return existing;
            }
            String requestId = id();
            jdbc.update("""
                    INSERT INTO support_request(id,conversation_id,client_request_id,request_hash,requested_mode,target_agent_user_id,service_type)
                    VALUES (?,?,?,?,?,?,?)
                    """, requestId, conversationId, body.clientRequestId(), hash, body.requestedMode(), body.targetAgentUserId(), type);
            rememberKey(conversationId, body.clientRequestId(), requestId, hash);
            state(conversationId, "HUMAN_QUEUED", true);
            event(conversationId, "support.requested", requestId);
            event(conversationId, "support.queued", requestId);
            if(summaries!=null)summaries.createForHandoff(conversationId);
            return request(requestId, false);
        });
    }

    public CursorPage<Request> queue(Long actor, long before, int limit) { return queue(actor,before,limit,null); }

    public CursorPage<Request> queue(Long actor, long before, int limit, String serviceType) {
        internalStaff();
        long user = actor(actor);
        pagination(before, limit);
        Agent staff = profile(user, false);
        if(serviceType != null) requireRole(staff,serviceType(serviceType));
        List<Request> rows = jdbc.query("""
                SELECT * FROM support_request WHERE status='queued'
                AND EXISTS (SELECT 1 FROM support_agent_profile p WHERE p.user_id=? AND JSON_CONTAINS(COALESCE(p.roles_json,JSON_ARRAY(p.role)),JSON_QUOTE(support_request.service_type)))
                AND (? IS NULL OR service_type=?) AND list_sequence < ?
                AND (target_agent_user_id IS NULL OR target_agent_user_id=?) ORDER BY list_sequence DESC LIMIT ?
                """, SupportService::requestRow, user, serviceType, serviceType, before == 0 ? Long.MAX_VALUE : before, user, limit + 1);
        List<Request> items = rows.stream().limit(limit).toList();
        return new CursorPage<>(items, items.isEmpty() ? before : items.getLast().listSequence(), rows.size() > limit);
    }

    public Request get(Long actor, String requestId) {
        long user = actor(actor);
        Request req = request(requestId, false);
        Conv conv = conversation(req.conversationId(), false);
        if (conv.owner() == user) return req;
        internalStaff();
        // A past assignment grants a bounded ledger view, not live mutable request metadata.
        requireRole(profile(user, false),req.serviceType());
        if (!"active".equals(req.status()) || !Objects.equals(req.assignedAgentUserId(), user)
                || jdbc.queryForObject("SELECT COUNT(*) FROM support_assignment WHERE request_id=? AND agent_user_id=? AND released_at IS NULL",
                    Long.class, requestId, user) == 0) throw problem(404, "SUPPORT_REQUEST_NOT_FOUND");
        return req;
    }

    public Request claim(Long actor, String requestId) {
        internalStaff();
        long user = actor(actor);
        Request hint = request(requestId, false);
        return transactions.execute(tx -> {
            Agent agent = profile(user, true);
            Conv conv = conversation(hint.conversationId(), true);
            Request req = request(requestId, true);
            requireRole(agent,req.serviceType());
            if (conv.owner() == user) throw problem(403, "OWNER_CANNOT_CLAIM");
            if ("active".equals(req.status()) && Objects.equals(req.assignedAgentUserId(), user)) {
                current(req, user);
                return req;
            }
            if (!"queued".equals(req.status())) throw problem(409, "REQUEST_NOT_QUEUED");
            if (req.targetAgentUserId() != null && req.targetAgentUserId() != user) throw problem(403, "TARGET_AGENT_REQUIRED");
            String prefix = "expert".equals(req.serviceType()) ? "expert_" : "";
            Long online = jdbc.queryForObject("SELECT COUNT(*) FROM support_agent_profile WHERE user_id=? AND enabled=1 AND "
                    + prefix + "accepting=1 AND " + prefix + "presence_expires_at>CURRENT_TIMESTAMP(3)", Long.class, user);
            if (online == 0) throw problem(409, "AGENT_NOT_ACCEPTING");
            // A locking read sees latest commits even under MySQL REPEATABLE READ.
            int occupied = jdbc.queryForList(
                    "SELECT id FROM support_assignment WHERE agent_user_id=? AND released_at IS NULL FOR UPDATE", user).size();
            if(media()!=null)occupied+=media().reservedTextSlots(user);
            if (occupied >= agent.capacityText()) throw problem(409, "AGENT_AT_CAPACITY");
            jdbc.update("INSERT INTO support_assignment(id,request_id,conversation_id,agent_user_id,service_type) VALUES (?,?,?,?,?)",
                    id(), requestId, req.conversationId(), user, req.serviceType());
            jdbc.update("UPDATE support_request SET status='active',assigned_agent_user_id=?,assigned_at=CURRENT_TIMESTAMP(3) WHERE id=?",
                    user, requestId);
            state(req.conversationId(), "HUMAN_ACTIVE", false);
            event(req.conversationId(), "support.taken_over", requestId);
            return request(requestId, false);
        });
    }

    public Message send(Long actor, String requestId, SendMessage body) {
        internalStaff();
        long user = actor(actor);
        Request hint = request(requestId, false);
        return transactions.execute(tx -> {
            Agent agent = profile(user, true);
            conversation(hint.conversationId(), true);
            Request req = request(requestId, true);
            current(req, user);
            // ConversationService validates all ordered text/location/media parts and performs
            // attachment binding in this same message transaction after current-assignment checks.
            Message message = conversations.sendSupport(user, req.conversationId(), body, req.serviceType());
            jdbc.update("UPDATE support_request SET first_response_at=COALESCE(first_response_at,CURRENT_TIMESTAMP(3)) WHERE id=?", requestId);
            return message;
        });
    }

    public Request resolve(Long actor, String requestId, EndRequest body) {
        internalStaff();
        long user = actor(actor);
        String reason = reason(body);
        Request hint = request(requestId, false);
        return transactions.execute(tx -> {
            profile(user, true);
            conversation(hint.conversationId(), true);
            Request req = request(requestId, true);
            requireRole(profile(user,false),req.serviceType());
            if ("resolved".equals(req.status()) && Objects.equals(req.assignedAgentUserId(), user)) return req;
            current(req, user);
            jdbc.update("UPDATE support_request SET status='resolved',ended_at=CURRENT_TIMESTAMP(3),end_reason=? WHERE id=?", reason, requestId);
            state(req.conversationId(), "RESOLVED", false);
            event(req.conversationId(), "support.resolved", requestId);
            release(req);
            return request(requestId, false);
        });
    }

    /** End only this active request; retries cannot terminate a later handoff. */
    public Request endByOwner(Long actor, String requestId, EndRequest body) {
        long user = actor(actor);
        String reason = reason(body);
        Request hint = request(requestId, false);
        return transactions.execute(tx -> {
            Conv conv = conversation(hint.conversationId(), true);
            owner(conv, user);
            Request req = request(requestId, true);
            if ("resolved".equals(req.status())) return req;
            if (!"active".equals(req.status()) || !"HUMAN_ACTIVE".equals(conv.state()))
                throw problem(409, "REQUEST_NOT_ACTIVE");
            if (jdbc.queryForList("SELECT id FROM support_assignment WHERE request_id=? AND agent_user_id=? AND released_at IS NULL FOR UPDATE",
                    requestId, req.assignedAgentUserId()).isEmpty()) throw problem(409, "ASSIGNMENT_CHANGED");
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
        internalStaff();
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

    /** Same request/history, explicit role queue transition; a retry never repeats release or events. */
    public Request transfer(Long actor,String requestId,Transfer body) {
        internalStaff();
        long user=actor(actor);
        if(body==null || body.clientRequestId()==null || !body.clientRequestId().matches("[a-zA-Z0-9][a-zA-Z0-9_.:-]{0,63}")
                || (body.targetAgentUserId()!=null && body.targetAgentUserId()<=0))throw problem(400,"INVALID_TRANSFER_REQUEST");
        serviceType(body.serviceType()); reason(new EndRequest(body.reason()));
        Request hint=request(requestId,false);
        String digest=hash(encode(body));
        return transactions.execute(tx->{
            // Match media lock order even when two agents transfer towards each other.
            var users=new java.util.TreeSet<Long>();users.add(user);if(body.targetAgentUserId()!=null)users.add(body.targetAgentUserId());
            for(long id:users)jdbc.queryForList("SELECT user_id FROM support_agent_profile WHERE user_id=? FOR UPDATE",id);
            Agent staff=profile(user,true);
            Conv conv=conversation(hint.conversationId(),true);
            Request req=request(requestId,true);
            var prior=jdbc.queryForList("SELECT request_hash,from_service_type,response_json FROM support_request_transfer WHERE request_id=? AND actor_user_id=? AND client_request_id=? FOR UPDATE",requestId,user,body.clientRequestId());
            if(!prior.isEmpty()) {
                if(!digest.equals(prior.getFirst().get("request_hash")))throw problem(409,"CLIENT_REQUEST_ID_REUSED");
                requireRole(staff,(String)prior.getFirst().get("from_service_type"));
                try{return json.readValue(prior.getFirst().get("response_json").toString(),Request.class);}
                catch(JsonProcessingException e){throw new IllegalStateException(e);}
            }
            if(body.targetAgentUserId()!=null)requireRole(profile(body.targetAgentUserId(),false),body.serviceType());
            requireRole(staff,req.serviceType());current(req,user);
            if(req.serviceType().equals(body.serviceType()))throw problem(409,"DIFFERENT_SERVICE_TYPE_REQUIRED");
            if(Objects.equals(body.targetAgentUserId(),conv.owner()))throw problem(403,"OWNER_CANNOT_CLAIM");
            jdbc.update("INSERT INTO support_request_transfer(id,request_id,actor_user_id,client_request_id,request_hash,from_service_type,service_type,target_agent_user_id,reason) VALUES (?,?,?,?,?,?,?,?,?)",
                    id(),requestId,user,body.clientRequestId(),digest,req.serviceType(),body.serviceType(),body.targetAgentUserId(),body.reason());
            state(req.conversationId(),"HUMAN_QUEUED",true);
            event(req.conversationId(),"support.transferred",requestId);
            release(req);
            jdbc.update("UPDATE support_request SET status='queued',service_type=?,target_agent_user_id=?,assigned_agent_user_id=NULL,assigned_at=NULL WHERE id=?",
                    body.serviceType(),body.targetAgentUserId(),requestId);
            Request result=request(requestId,false);
            jdbc.update("UPDATE support_request_transfer SET response_json=? WHERE request_id=? AND actor_user_id=? AND client_request_id=?",encode(result),requestId,user,body.clientRequestId());
            return result;
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
        requireRole(profile(user,false),req.serviceType());
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
                rs.getInt("capacity_text"), primaryPresence(rs).accepting(), primaryPresence(rs).presenceExpiresAt(), roles(rs),
                Map.of("support_agent", presenceRow(rs, "support_agent"), "expert", presenceRow(rs, "expert")));
    }
    private static Presence primaryPresence(ResultSet rs) throws SQLException { return presenceRow(rs, rs.getString("role")); }
    private static Presence presenceRow(ResultSet rs, String type) throws SQLException {
        String prefix = "expert".equals(type) ? "expert_" : "";
        return new Presence(rs.getBoolean(prefix + "accepting"), instant(rs, prefix + "presence_expires_at"));
    }
    private static Request requestRow(ResultSet rs, int row) throws SQLException {
        return new Request(rs.getString("id"),rs.getLong("list_sequence"),rs.getString("conversation_id"),
                rs.getString("client_request_id"),rs.getString("status"),rs.getString("requested_mode"),
                rs.getObject("target_agent_user_id",Long.class),rs.getObject("assigned_agent_user_id",Long.class),
                instant(rs,"requested_at"),instant(rs,"assigned_at"),instant(rs,"first_response_at"),
                instant(rs,"ended_at"),rs.getString("end_reason"),Objects.requireNonNullElse(rs.getString("service_type"),"support_agent"));
    }
    private static List<String> roles(ResultSet rs) throws SQLException {
        String encoded=rs.getString("roles_json");
        if(encoded==null)return List.of(rs.getString("role"));
        try { return List.copyOf(new ObjectMapper().readValue(encoded,new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){})); }
        catch(JsonProcessingException e){throw new IllegalStateException("Invalid support roles",e);}
    }
    private static List<String> updatedRoles(AgentUpdate body) {
        List<String> result=body.roles()==null?List.of(body.role()):body.roles();
        if(result.isEmpty() || result.size()>2 || !result.contains(body.role())
                || result.stream().anyMatch(r->!Set.of("support_agent","expert").contains(r==null?"":r))
                || result.stream().distinct().count()!=result.size())throw problem(400,"INVALID_AGENT_ROLES");
        return List.copyOf(result);
    }
    private static String serviceType(String type) {
        if(!"support_agent".equals(type) && !"expert".equals(type))throw problem(400,"INVALID_SERVICE_TYPE");
        return type;
    }
    private static void requireRole(Agent agent,String type) {
        if(!agent.roles().contains(type))throw problem(403,"SERVICE_ROLE_REQUIRED");
    }
    private String encode(Object value) {
        try{return json.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException(e);}
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
    private static void internalStaff() {
        var attributes=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if(attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet
                && servlet.getRequest().getAttribute(xiaozhi.modules.externalidentity.ExternalIdentityService.ATTRIBUTE)
                instanceof xiaozhi.modules.externalidentity.ExternalModels.Principal)
            throw problem(403,"INTERNAL_STAFF_IDENTITY_REQUIRED");
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
