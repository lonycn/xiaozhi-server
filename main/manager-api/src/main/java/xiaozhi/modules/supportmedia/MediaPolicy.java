package xiaozhi.modules.supportmedia;

import static xiaozhi.modules.supportmedia.MediaStore.*;
import java.util.*;
import org.springframework.stereotype.Component;
import xiaozhi.modules.conversation.ConversationDao;
import xiaozhi.modules.supportmedia.livekit.*;
import xiaozhi.modules.supportmedia.livekit.LiveKitAdapter.*;

/** Database ownership is mandatory even when a room happens to have our prefix. */
@Component
public class MediaPolicy implements RoomOwnership {
    private final MediaStore store;
    private final ConversationDao conversations;
    public MediaPolicy(MediaStore store,ConversationDao conversations){this.store=store;this.conversations=conversations;}
    public Map<String,Object> lock(long id,Long extra) {
        Map<String,Object> hint=store.session(id,false),pending=store.pending(id,false);TreeSet<Long> users=new TreeSet<>();
        users.add(number(hint,"customer_user_id"));users.add(number(hint,"agent_user_id"));if(pending!=null)users.add(number(pending,"target_agent_user_id"));if(extra!=null)users.add(extra);
        lockUsers(users);conversations.byId(string(hint,"conversation_id"),true);
        store.rows("SELECT id FROM support_request WHERE id=? FOR UPDATE",hint.get("request_id"));Map<String,Object> current=store.session(id,true);
        if(number(hint,"agent_user_id")!=number(current,"agent_user_id"))throw problem(409,"MEDIA_EPOCH_CHANGED");return current;
    }
    public void lockUsers(Collection<Long> users) {
        for(long user:new TreeSet<>(users))store.rows("SELECT user_id FROM support_agent_profile WHERE user_id=? FOR UPDATE",user);
        for(long user:new TreeSet<>(users))store.rows("SELECT id FROM sys_user WHERE id=? FOR UPDATE",user);
    }
    public void account(long actor) {if(store.rows("SELECT id FROM sys_user WHERE id=? AND status=1 FOR UPDATE",actor).isEmpty())throw problem(404,"MEDIA_NOT_FOUND");}
    public Map<String,Object> profile(long actor,boolean accepting) {
        var rows=store.rows("SELECT p.* FROM support_agent_profile p JOIN sys_user u ON u.id=p.user_id AND u.status=1 WHERE p.user_id=? AND p.enabled=1 AND p.role IN ('support_agent','expert')"+(accepting?" AND p.accepting=1 AND p.presence_expires_at>CURRENT_TIMESTAMP(3)":"")+" FOR UPDATE",actor);
        if(rows.isEmpty())throw problem(409,"MEDIA_AGENT_UNAVAILABLE");return rows.getFirst();
    }
    public boolean current(Map<String,Object> s) {
        return !store.rows("SELECT a.id FROM support_assignment a JOIN support_request r ON r.id=a.request_id WHERE a.id=? AND a.request_id=? AND a.agent_user_id=? AND a.released_at IS NULL AND r.status='active' AND r.assigned_agent_user_id=a.agent_user_id FOR UPDATE",s.get("assignment_id"),s.get("request_id"),s.get("agent_user_id")).isEmpty();
    }
    public void currentRequired(Map<String,Object> s){account(number(s,"customer_user_id"));profile(number(s,"agent_user_id"),false);if(!current(s))throw problem(409,"MEDIA_ASSIGNMENT_CHANGED");}
    public void actor(Map<String,Object> s,long actor,boolean targetAllowed) {
        if(actor==number(s,"customer_user_id")){account(actor);return;}
        if(actor==number(s,"agent_user_id") && current(s)){account(actor);profile(actor,false);return;}
        var transfer=targetAllowed?store.pending(number(s,"id"),false):null;
        if(transfer!=null && actor==number(transfer,"target_agent_user_id")){account(actor);profile(actor,false);return;}
        throw problem(404,"MEDIA_NOT_FOUND");
    }
    public void textCapacity(long user) {
        Map<String,Object> profile=profile(user,true);
        int assignments=store.rows("SELECT id FROM support_assignment WHERE agent_user_id=? AND released_at IS NULL FOR UPDATE",user).size();
        int reserved=store.rows("SELECT id FROM support_media_transfer WHERE target_agent_user_id=? AND active_slot=1 FOR UPDATE",user).size();
        if(assignments+reserved>=number(profile,"capacity_text"))throw problem(409,"AGENT_AT_CAPACITY");
    }
    @Override public boolean allowed(OwnedRoom room,Operation operation,String identity) {
        try {
            // A caller may already have an older RR snapshot before waiting for
            // the conversation lock. Authorization needs current reads there.
            // Outside a transaction these reads remain short autocommit reads;
            // no database lock is held while the adapter performs network IO.
            boolean locking=org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
            String currentRead=locking?" FOR UPDATE":"";
            var rows=store.rows("SELECT * FROM support_media_room WHERE session_id=? AND generation=? AND room_name=?"+currentRead,Long.parseLong(room.sessionId()),room.generation(),room.roomName());if(rows.size()!=1)return false;
            var r=rows.getFirst();var s=store.session(number(r,"session_id"),locking);String status=string(s,"status");long roomId=number(r,"id");
            if(operation==Operation.LIST || operation==Operation.GET)return true;
            if(operation==Operation.REMOVE || operation==Operation.DELETE) {
                String kind=operation==Operation.REMOVE?"remove_participant":"delete_room";
                return Boolean.TRUE.equals(store.jdbc().queryForObject("SELECT EXISTS(SELECT 1 FROM support_media_outbox WHERE room_id=? AND kind=? AND status='running' AND lease_expires_at>CURRENT_TIMESTAMP(3) AND (identity=? OR (identity IS NULL AND ? IS NULL)))",Boolean.class,roomId,kind,identity,identity));
            }
            if(!Set.of("connecting","active","reconnecting").contains(status))return false;
            var pending=store.pending(number(s,"id"),locking);boolean preparation=pending!=null && number(pending,"target_generation")==room.generation() && Set.of("preparing","ready","committing").contains(string(pending,"status")) && pending.get("customer_approved_at")!=null && pending.get("target_accepted_at")!=null;
            if(number(s,"current_generation")!=room.generation() && !preparation)return false;
            if(operation==Operation.CREATE)return "preparing".equals(string(r,"status"));
            if(operation!=Operation.TOKEN || !Set.of("ready","active").contains(string(r,"status")))return false;
            var members=store.rows("SELECT * FROM support_media_member WHERE room_id=? ORDER BY user_id"+currentRead,roomId);var member=members.stream().filter(m->Objects.equals(identity,string(m,"identity"))).findFirst().orElse(null);
            if(member==null || member.get("consented_at")==null || !Set.of("accepted","active").contains(string(member,"status")))return false;
            if(members.size()!=2 || members.stream().anyMatch(m->m.get("consented_at")==null || string(m,"status").equals("revoked")))return false;
            account(number(member,"user_id"));account(number(s,"customer_user_id"));profile(number(s,"agent_user_id"),false);
            // Read-only ownership checks do not acquire locks around network I/O.
            boolean assignment=!store.rows("SELECT a.id FROM support_assignment a JOIN support_request q ON q.id=a.request_id WHERE a.id=? AND a.released_at IS NULL AND q.status='active' AND q.assigned_agent_user_id=a.agent_user_id AND a.agent_user_id=?"+currentRead,s.get("assignment_id"),s.get("agent_user_id")).isEmpty();
            if(!assignment)return false;
            if(preparation)profile(number(pending,"target_agent_user_id"),false);
            return true;
        } catch(RuntimeException denied){return false;}
    }
}
