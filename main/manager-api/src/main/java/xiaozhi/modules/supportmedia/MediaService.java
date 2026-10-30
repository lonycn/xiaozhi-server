package xiaozhi.modules.supportmedia;

import static xiaozhi.modules.supportmedia.MediaStore.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import xiaozhi.modules.conversation.ConversationDao;
import xiaozhi.modules.conversation.ConversationModels.CursorPage;
import xiaozhi.modules.conversation.ConversationProblem;
import xiaozhi.modules.supportmedia.MediaModels.*;
import xiaozhi.modules.supportmedia.livekit.*;
import xiaozhi.modules.supportmedia.livekit.LiveKitAdapter.*;

/** MySQL owns responsibility and consent. External media operations are short, fenced saga steps. */
@Service
@ConditionalOnProperty(prefix="features",name={"conversation-enabled","support-handoff-enabled"},havingValue="true")
public class MediaService implements SupportMediaHooks {
    final MediaStore store;final MediaPolicy policy;final LiveKitAdapter livekit;final ConversationDao conversations;
    private final ObjectMapper json;final int inviteSeconds,transferSeconds,readySeconds,reconnectSeconds;
    private final String roomPrefix;
    private final Environment environment;
    private org.springframework.beans.factory.ObjectProvider<xiaozhi.modules.annotation.AnnotationService> annotationProvider;
    @org.springframework.beans.factory.annotation.Autowired
    public void setAnnotationProvider(org.springframework.beans.factory.ObjectProvider<xiaozhi.modules.annotation.AnnotationService> provider){annotationProvider=provider;}
    private static final Set<String> LIVE=Set.of("connecting","active","reconnecting");
    private static final Set<String> TERMINAL=Set.of("ended","rejected","canceled","expired","failed");
    public MediaService(MediaStore store,MediaPolicy policy,LiveKitAdapter livekit,ConversationDao conversations,ObjectMapper json,Environment environment){
        this.store=store;this.policy=policy;this.livekit=livekit;this.conversations=conversations;this.json=json;this.environment=environment;
        inviteSeconds=bound(environment,"support-media.invitation-seconds",45,10,120);transferSeconds=bound(environment,"support-media.transfer-seconds",60,20,180);readySeconds=bound(environment,"support-media.ready-seconds",15,5,30);reconnectSeconds=bound(environment,"support-media.reconnect-seconds",60,15,300);roomPrefix=environment.getProperty("support-media.livekit.room-prefix","xz-support-b5-");
    }
    boolean enabled(){return environment.getProperty("features.support-media-enabled",Boolean.class,false);}
    private void publishingEnabled(){if(!enabled())throw problem(404,"SUPPORT_MEDIA_DISABLED");}
    private static int bound(Environment e,String key,int fallback,int min,int max){return Math.max(min,Math.min(max,e.getProperty(key,Integer.class,fallback)));}
    static long id(String value){try{if(value==null || !value.matches("[1-9][0-9]{0,18}"))throw new IllegalArgumentException();return Long.parseLong(value);}catch(IllegalArgumentException ex){throw problem(404,"MEDIA_NOT_FOUND");}}
    static long actor(Long user){if(user==null || user<=0)throw problem(401,"AUTHENTICATION_REQUIRED");return user;}
    static String uid(){return UUID.randomUUID().toString().replace("-","");}
    static long next(){return IdWorker.getId();}
    private static void client(String value){if(value==null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}"))throw problem(400,"INVALID_MEDIA_CLIENT_ID");}
    private static void epoch(Map<String,Object> s,Long expected){if(expected==null || expected!=number(s,"control_epoch"))throw problem(409,"MEDIA_EPOCH_CHANGED");}
    private static void consent(Consent c,String mode){if(c==null || !Boolean.TRUE.equals(c.microphone()) || c.camera()==null || (mode.equals("audio") && c.camera()) || (Boolean.TRUE.equals(c.allowSnapshots()) && !c.camera()))throw problem(400,"EXPLICIT_MEDIA_CONSENT_REQUIRED");}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException("Cannot encode media state");}}
    static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException(ex);}}
    private <T> T conflict(java.util.function.Supplier<T> work){try{return store.tx(work);}catch(DuplicateKeyException ex){throw problem(409,"MEDIA_CAPACITY_OR_CLIENT_CONFLICT");}catch(PessimisticLockingFailureException ex){throw problem(409,"MEDIA_CONFLICT_RETRY");}}
    private <T> T mutate(long session,long actor,Long expected,boolean target,Function<Map<String,Object>,T> work){publishingEnabled();return conflict(()->{var s=policy.lock(session,null);policy.actor(s,actor,target);epoch(s,expected);return work.apply(s);});}
    private Map<String,Object> room(long session,int generation){return store.generation(session,generation);}
    private List<Map<String,Object>> members(long room){return store.rows("SELECT * FROM support_media_member WHERE room_id=? ORDER BY user_id FOR UPDATE",room);}
    private Map<String,Object> member(long room,long user){return members(room).stream().filter(m->number(m,"user_id")==user).findFirst().orElseThrow(()->problem(404,"MEDIA_MEMBER_NOT_FOUND"));}
    private void acceptMember(Map<String,Object> m,Consent c,String mode){
        consent(c,mode);String digest=hash(encode(List.of(c.microphone(),c.camera(),Boolean.TRUE.equals(c.allowSnapshots()))));
        if(m.get("consented_at")!=null){if(!digest.equals(string(m,"consent_hash")))throw problem(409,"MEDIA_CONSENT_IMMUTABLE");return;}
        store.jdbc.update("UPDATE support_media_member SET consent_microphone=1,consent_camera=?,consent_snapshots=?,consent_hash=?,consented_at=CURRENT_TIMESTAMP(3),status='accepted',reservation_status='held' WHERE id=? AND consented_at IS NULL",c.camera(),Boolean.TRUE.equals(c.allowSnapshots()),digest,m.get("id"));
    }
    private Map<String,Object> newRoom(long session,int generation){
        if(!roomPrefix.matches("[A-Za-z0-9_-]{3,48}-"))throw problem(503,"MEDIA_ROOM_PREFIX_NOT_CONFIGURED");
        long id=next();String name=roomPrefix+session+"-g"+generation+"-"+uid();store.jdbc.update("INSERT INTO support_media_room(id,session_id,generation,room_name) VALUES(?,?,?,?)",id,session,generation,name);return store.room(id,true);
    }
    private void newMember(Map<String,Object> r,long user,String role){store.jdbc.update("INSERT INTO support_media_member(id,session_id,room_id,user_id,role,identity) VALUES(?,?,?,?,?,?)",next(),r.get("session_id"),r.get("id"),user,role,"xz5-"+uid());}
    void event(Map<String,Object> s,String type,Map<String,Object> data){var conv=conversations.byId(string(s,"conversation_id"),true);long sequence=conv.lastEventSeq()+1;Map<String,Object> body=new LinkedHashMap<>(data);body.put("session_id",Long.toString(number(s,"id")));conversations.insertEvent(uid(),conv.id(),sequence,type,encode(body));conversations.advance(conv.id(),conv.lastSeq(),sequence);}
    void queue(Map<String,Object> s,Map<String,Object> r,Map<String,Object> t,String kind,String identity,boolean repeat){
        String key=kind+":"+r.get("id")+(identity==null?"":":"+identity)+(t==null?"":":"+t.get("id"));
        store.jdbc.update("INSERT INTO support_media_outbox(id,session_id,room_id,transfer_id,kind,identity,intent_key,control_epoch) VALUES(?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE status=IF(? AND status IN ('done','obsolete'),'queued',status),available_at=IF(? AND status IN ('done','obsolete'),CURRENT_TIMESTAMP(3),available_at),control_epoch=IF(status='queued',VALUES(control_epoch),control_epoch)",next(),s.get("id"),r.get("id"),t==null?null:t.get("id"),kind,identity,key,s.get("control_epoch"),repeat,repeat);
    }
    public Session create(Long user,Create body){
        publishingEnabled();long actor=actor(user);if(body==null || body.requestId()==null || !body.requestId().matches("[a-f0-9]{32}") || body.mode()==null || !Set.of("audio","video").contains(body.mode()))throw problem(400,"INVALID_MEDIA_REQUEST");client(body.clientSessionId());consent(body.consent(),body.mode());if(!livekit.configured())throw problem(503,"MEDIA_NOT_CONFIGURED");
        String digest=hash(encode(List.of(body.requestId(),body.mode(),body.consent().microphone(),body.consent().camera(),Boolean.TRUE.equals(body.consent().allowSnapshots()))));
        var hint=store.rows("SELECT r.*,c.owner_user_id FROM support_request r JOIN ai_conversation c ON c.id=r.conversation_id WHERE r.id=?",body.requestId());if(hint.isEmpty() || hint.getFirst().get("assigned_agent_user_id")==null)throw problem(404,"SUPPORT_REQUEST_NOT_FOUND");
        return conflict(()->{
            var h=hint.getFirst();long customer=number(h,"owner_user_id"),agent=number(h,"assigned_agent_user_id");policy.lockUsers(List.of(customer,agent));conversations.byId(string(h,"conversation_id"),true);
            var q=store.one("SELECT * FROM support_request WHERE id=? FOR UPDATE",body.requestId());if(!"active".equals(string(q,"status")) || number(q,"assigned_agent_user_id")!=agent)throw problem(409,"MEDIA_ASSIGNMENT_CHANGED");policy.account(customer);policy.profile(agent,false);if(actor!=customer && actor!=agent)throw problem(404,"MEDIA_NOT_FOUND");
            var prior=store.rows("SELECT * FROM support_media_session WHERE inviter_user_id=? AND client_session_id=? FOR UPDATE",actor,body.clientSessionId());if(!prior.isEmpty()){var s=prior.getFirst();if(!digest.equals(string(s,"request_hash")))throw problem(409,"CLIENT_MEDIA_ID_REUSED");return view(s);}
            var assignments=store.rows("SELECT id FROM support_assignment WHERE request_id=? AND agent_user_id=? AND released_at IS NULL FOR UPDATE",body.requestId(),agent);if(assignments.size()!=1)throw problem(409,"MEDIA_ASSIGNMENT_CHANGED");
            long session=next();store.jdbc.update("INSERT INTO support_media_session(id,conversation_id,request_id,assignment_id,customer_user_id,agent_user_id,inviter_user_id,invitee_user_id,client_session_id,request_hash,mode,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",session,q.get("conversation_id"),q.get("id"),assignments.getFirst().get("id"),customer,agent,actor,actor==customer?agent:customer,body.clientSessionId(),digest,body.mode(),time(Instant.now().plusSeconds(inviteSeconds)));
            var r=newRoom(session,1);newMember(r,customer,"customer");newMember(r,agent,"agent");acceptMember(member(number(r,"id"),actor),body.consent(),body.mode());var s=store.session(session,true);event(s,"media.invited",Map.of());return view(s);
        });
    }
    Session view(Map<String,Object> s){
        var t=store.pending(number(s,"id"),true);List<Member> people=new ArrayList<>();var rooms=store.rows("SELECT * FROM support_media_room WHERE session_id=? AND (generation=? OR generation=?) ORDER BY generation FOR UPDATE",s.get("id"),s.get("current_generation"),t==null?-1:t.get("target_generation"));
        for(var r:rooms)for(var m:members(number(r,"id"))){Instant ready=instant(m,"ready_at");people.add(new Member(Long.toString(number(m,"user_id")),string(m,"role"),(int)number(r,"generation"),string(m,"identity"),string(m,"status"),new Consent(yes(m,"consent_microphone"),yes(m,"consent_camera"),yes(m,"consent_snapshots")),ready!=null && ready.isAfter(Instant.now().minusSeconds(readySeconds)) && (yes(m,"receiving_audio") || yes(m,"receiving_video")),yes(m,"receiving_audio"),yes(m,"receiving_video")));}
        return new Session(Long.toString(number(s,"id")),string(s,"conversation_id"),string(s,"request_id"),string(s,"mode"),string(s,"status"),number(s,"control_epoch"),(int)number(s,"current_generation"),t==null?null:(int)number(t,"target_generation"),string(s,"assignment_id"),Long.toString(number(s,"agent_user_id")),people,t==null?null:transferView(s,t),string(s,"cleanup_status"),string(s,"error_code"),instant(s,"expires_at"),instant(s,"created_at"),instant(s,"ended_at"));
    }
    Transfer transferView(Map<String,Object> s,Map<String,Object> t){return new Transfer(Long.toString(number(t,"id")),Long.toString(number(t,"session_id")),Long.toString(number(t,"target_agent_user_id")),(int)number(t,"target_generation"),string(t,"status"),number(s,"control_epoch"),t.get("customer_approved_at")!=null,t.get("target_accepted_at")!=null,string(t,"error_code"),instant(t,"expires_at"),instant(t,"created_at"));}
    public Session get(Long user,String session){long actor=actor(user);return store.tx(()->{var s=policy.lock(id(session),null);policy.actor(s,actor,true);return view(s);});}
    public Session current(Long user,String conversation){long actor=actor(user);if(conversation==null || !conversation.matches("[a-f0-9]{32}"))throw problem(404,"CONVERSATION_NOT_FOUND");
        var rows=store.rows("SELECT id FROM support_media_session WHERE conversation_id=? AND active_slot=1",conversation);if(rows.isEmpty()){policy.account(actor);new xiaozhi.modules.conversation.ConversationAccessService(conversations).detail(actor,conversation,false);return null;}return get(user,Long.toString(number(rows.getFirst(),"id")));
    }
    public Session accept(Long user,String session,Accept body){long actor=actor(user);if(body==null)throw problem(400,"INVALID_REQUEST");return mutate(id(session),actor,body.expectedEpoch(),false,s->{policy.currentRequired(s);if(actor!=number(s,"invitee_user_id"))throw problem(404,"MEDIA_NOT_FOUND");if(!string(s,"status").equals("invited")){if(LIVE.contains(string(s,"status"))){acceptMember(member(number(room(number(s,"id"),1),"id"),actor),body.consent(),string(s,"mode"));return view(s);}throw problem(409,"MEDIA_INVITATION_INACTIVE");}if(!instant(s,"expires_at").isAfter(Instant.now()))throw problem(409,"MEDIA_INVITATION_EXPIRED");var r=room(number(s,"id"),1);acceptMember(member(number(r,"id"),actor),body.consent(),string(s,"mode"));store.jdbc.update("UPDATE support_media_session SET status='connecting',control_epoch=control_epoch+1 WHERE id=?",s.get("id"));s=store.session(number(s,"id"),true);queue(s,r,null,"create_room",null,false);event(s,"media.accepted",Map.of());return view(s);});}
    public Session reject(Long user,String session,Epoch body){long actor=actor(user);if(body==null)throw problem(400,"INVALID_REQUEST");return mutate(id(session),actor,body.expectedEpoch(),false,s->{if(actor!=number(s,"invitee_user_id"))throw problem(404,"MEDIA_NOT_FOUND");if(string(s,"status").equals("rejected"))return view(s);if(!string(s,"status").equals("invited"))throw problem(409,"MEDIA_INVITATION_INACTIVE");finish(s,"rejected","INVITATION_REJECTED");return view(store.session(number(s,"id"),true));});}
    public Session end(Long user,String session,End body){long actor=actor(user);if(body==null || body.reason()!=null && body.reason().length()>200)throw problem(400,"INVALID_REQUEST");return mutate(id(session),actor,body.expectedEpoch(),false,s->{if(TERMINAL.contains(string(s,"status")))return view(s);finish(s,string(s,"status").equals("invited")?"canceled":"ending",null);return view(store.session(number(s,"id"),true));});}
    void finish(Map<String,Object> s,String state,String error){
        long session=number(s,"id");var t=store.pending(session,true);if(t!=null)abortTransfer(s,t,"canceled","MEDIA_ENDED",false);
        store.jdbc.update("UPDATE support_media_session SET status=?,control_epoch=control_epoch+1,error_code=?,cleanup_status='pending',ended_at=COALESCE(ended_at,CURRENT_TIMESTAMP(3)) WHERE id=?",state,error,session);s=store.session(session,true);
        store.jdbc.update("UPDATE support_media_member SET status='revoked',revoked_at=COALESCE(revoked_at,CURRENT_TIMESTAMP(3)),reservation_status='cleanup_pending',ready_at=NULL WHERE session_id=? AND reservation_status<>'released'",session);
        for(var r:store.rows("SELECT * FROM support_media_room WHERE session_id=?",session)){store.jdbc.update("UPDATE support_media_room SET status='cleanup_pending',retired_at=COALESCE(retired_at,CURRENT_TIMESTAMP(3)) WHERE id=?",r.get("id"));queue(s,r,null,"delete_room",null,true);}event(s,"media."+(state.equals("ending")?"ending":state),Map.of());
    }
    private record TokenPlan(Map<String,Object> session,Map<String,Object> room,Map<String,Object> member) { }
    public Token token(Long user,String session,TokenRequest body){
        long actor=actor(user);if(body==null || body.roomGeneration()==null || body.roomGeneration()<1)throw problem(400,"INVALID_REQUEST");
        TokenPlan plan=mutate(id(session),actor,body.expectedEpoch(),true,s->{policy.currentRequired(s);if(!LIVE.contains(string(s,"status")))throw problem(409,"MEDIA_NOT_CONNECTING");var r=room(number(s,"id"),body.roomGeneration());if(!Set.of("ready","active").contains(string(r,"status")))throw problem(409,"MEDIA_ROOM_PREPARING");var m=member(number(r,"id"),actor);if(!policy.allowed(owned(r),Operation.TOKEN,string(m,"identity")))throw problem(403,"MEDIA_TOKEN_DENIED");return new TokenPlan(new HashMap<>(s),new HashMap<>(r),new HashMap<>(m));});
        JoinToken token=livekit.issueToken(owned(plan.room()),string(plan.member(),"identity"),yes(plan.member(),"consent_microphone"),yes(plan.member(),"consent_camera"));
        return store.tx(()->{var s=policy.lock(id(session),null);policy.actor(s,actor,true);epoch(s,body.expectedEpoch());if(!policy.allowed(owned(plan.room()),Operation.TOKEN,string(plan.member(),"identity")))throw problem(403,"MEDIA_TOKEN_DENIED");List<String> sources=new ArrayList<>();if(yes(plan.member(),"consent_microphone"))sources.add("microphone");if(yes(plan.member(),"consent_camera"))sources.add("camera");return new Token(token.serverUrl(),token.token(),token.expiresAt(),session,number(s,"control_epoch"),body.roomGeneration(),string(plan.member(),"identity"),sources);});
    }
    static OwnedRoom owned(Map<String,Object> r){return new OwnedRoom(Long.toString(number(r,"session_id")),number(r,"generation"),string(r,"room_name"));}
    public Session ready(Long user,String session,Ready body){
        long actor=actor(user);if(body==null || body.roomGeneration()==null || !Boolean.TRUE.equals(body.transportReady()) || body.receivingAudio()==null || body.receivingVideo()==null || body.participantSid()==null || !body.participantSid().matches("[A-Za-z0-9_-]{1,100}") || body.peerSid()==null || !body.peerSid().matches("[A-Za-z0-9_-]{1,100}"))throw problem(400,"INVALID_MEDIA_READY");
        var r=mutate(id(session),actor,body.expectedEpoch(),true,s->{policy.currentRequired(s);var room=room(number(s,"id"),body.roomGeneration());var m=member(number(room,"id"),actor);if(!policy.allowed(owned(room),Operation.TOKEN,string(m,"identity")))throw problem(403,"MEDIA_READY_DENIED");return new HashMap<>(room);});
        List<Participant> observed=livekit.listParticipants(owned(r));
        return mutate(id(session),actor,body.expectedEpoch(),true,s->{policy.currentRequired(s);var current=room(number(s,"id"),body.roomGeneration());var m=member(number(current,"id"),actor);var expected=members(number(current,"id"));checkObserved(expected,observed);
            Participant own=observed.stream().filter(p->p.identity().equals(string(m,"identity"))).findFirst().orElseThrow();Participant peer=observed.stream().filter(p->!p.identity().equals(string(m,"identity"))).findFirst().orElseThrow();if(!own.sid().equals(body.participantSid()) || !peer.sid().equals(body.peerSid()))throw problem(409,"MEDIA_SID_CHANGED");
            if(Boolean.TRUE.equals(body.receivingAudio()) && peer.tracks().stream().noneMatch(track->track.source().equals("MICROPHONE") && !track.muted()) || Boolean.TRUE.equals(body.receivingVideo()) && peer.tracks().stream().noneMatch(track->track.source().equals("CAMERA") && !track.muted()))throw problem(400,"INVALID_MEDIA_RECEIVE_EVIDENCE");
            store.jdbc.update("UPDATE support_media_member SET participant_sid=?,peer_sid=?,ready_at=CURRENT_TIMESTAMP(3),last_seen_at=CURRENT_TIMESTAMP(3),receiving_audio=?,receiving_video=?,status='active' WHERE id=? AND revoked_at IS NULL",body.participantSid(),body.peerSid(),body.receivingAudio(),body.receivingVideo(),m.get("id"));
            if(bothReady(current) && receptionCurrent(expected,observed)) {if(number(current,"generation")==number(s,"current_generation")){if(!string(s,"status").equals("active")){store.jdbc.update("UPDATE support_media_session SET status='active' WHERE id=?",s.get("id"));store.jdbc.update("UPDATE support_media_room SET status='active' WHERE id=?",current.get("id"));event(s,"media.active",Map.of());}}else {var t=store.pending(number(s,"id"),true);if(t!=null && number(t,"target_generation")==number(current,"generation") && Set.of("preparing","ready").contains(string(t,"status"))){store.jdbc.update("UPDATE support_media_transfer SET status='ready' WHERE id=?",t.get("id"));event(s,"media.transfer.ready",Map.of("transfer_id",Long.toString(number(t,"id"))));}}}
            store.jdbc.update("UPDATE support_media_room SET last_polled_at=CURRENT_TIMESTAMP(3) WHERE id=?",current.get("id"));return view(store.session(number(s,"id"),true));});
    }
    void checkObserved(List<Map<String,Object>> members,List<Participant> observed){
        if(members.size()!=2 || observed.size()!=2)throw problem(409,"MEDIA_PARTICIPANTS_NOT_READY");
        for(var m:members){if(m.get("consented_at")==null || string(m,"status").equals("revoked"))throw problem(409,"MEDIA_CONSENT_REQUIRED");var p=observed.stream().filter(item->item.identity().equals(string(m,"identity"))).findFirst().orElseThrow(()->problem(409,"MEDIA_PARTICIPANTS_NOT_READY"));if(!"ACTIVE".equals(p.state()))throw problem(409,"MEDIA_PARTICIPANTS_NOT_READY");for(var track:p.tracks()){String source=track.source();if(source.equals("CAMERA") && !yes(m,"consent_camera") || source.equals("MICROPHONE") && !yes(m,"consent_microphone") || !Set.of("CAMERA","MICROPHONE").contains(source))throw problem(409,"MEDIA_TRACK_NOT_CONSENTED");}}
    }
    boolean receptionCurrent(List<Map<String,Object>> members,List<Participant> observed) {
        for(var m:members){var peer=observed.stream().filter(p->!p.identity().equals(string(m,"identity"))).findFirst().orElse(null);if(peer==null)return false;
            if(yes(m,"receiving_audio") && peer.tracks().stream().noneMatch(track->track.source().equals("MICROPHONE") && !track.muted()))return false;
            if(yes(m,"receiving_video") && peer.tracks().stream().noneMatch(track->track.source().equals("CAMERA") && !track.muted()))return false;
        }return true;
    }
    boolean bothReady(Map<String,Object> r){var members=members(number(r,"id"));if(members.size()!=2)return false;Instant cutoff=Instant.now().minusSeconds(readySeconds);for(var m:members){Instant ready=instant(m,"ready_at");if(ready==null || !ready.isAfter(cutoff) || !"active".equals(string(m,"status")) || m.get("participant_sid")==null || !(yes(m,"receiving_audio") || yes(m,"receiving_video")))return false;}return Objects.equals(members.get(0).get("participant_sid"),members.get(1).get("peer_sid")) && Objects.equals(members.get(1).get("participant_sid"),members.get(0).get("peer_sid"));}
    public Transfer createTransfer(Long user,String session,CreateTransfer body){
        publishingEnabled();long actor=actor(user);if(body==null || body.targetAgentUserId()==null || body.targetAgentUserId()<=0)throw problem(400,"INVALID_TRANSFER_REQUEST");client(body.clientTransferId());
        return conflict(()->{var s=policy.lock(id(session),body.targetAgentUserId());policy.actor(s,actor,false);policy.currentRequired(s);long target=body.targetAgentUserId();if(target==number(s,"customer_user_id") || target==number(s,"agent_user_id"))throw problem(400,"INVALID_TRANSFER_TARGET");String digest=hash(encode(List.of(session,target)));
            var prior=store.rows("SELECT * FROM support_media_transfer WHERE requester_user_id=? AND client_transfer_id=? FOR UPDATE",actor,body.clientTransferId());if(!prior.isEmpty()){var t=prior.getFirst();if(!digest.equals(string(t,"request_hash")))throw problem(409,"CLIENT_TRANSFER_ID_REUSED");return transferView(s,t);}
            epoch(s,body.expectedEpoch());if(!"active".equals(string(s,"status")))throw problem(409,"MEDIA_NOT_ACTIVE");if(store.pending(number(s,"id"),true)!=null)throw problem(409,"MEDIA_TRANSFER_ACTIVE");policy.textCapacity(target);long transfer=next();int generation=(int)number(s,"next_generation");store.jdbc.update("UPDATE support_media_session SET control_epoch=control_epoch+1,next_generation=next_generation+1 WHERE id=?",s.get("id"));s=store.session(number(s,"id"),true);
            store.jdbc.update("INSERT INTO support_media_transfer(id,session_id,requester_user_id,source_assignment_id,source_generation,target_generation,target_agent_user_id,client_transfer_id,request_hash,control_epoch,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",transfer,s.get("id"),actor,s.get("assignment_id"),s.get("current_generation"),generation,target,body.clientTransferId(),digest,s.get("control_epoch"),time(Instant.now().plusSeconds(transferSeconds)));
            var r=newRoom(number(s,"id"),generation);newMember(r,number(s,"customer_user_id"),"customer");newMember(r,target,"agent");event(s,"media.transfer.invited",Map.of("transfer_id",Long.toString(transfer)));return transferView(s,store.transfer(transfer,true));});
    }
    public Transfer getTransfer(Long user,String transfer){long actor=actor(user);var hint=store.transfer(id(transfer),false);return store.tx(()->{var s=policy.lock(number(hint,"session_id"),number(hint,"target_agent_user_id"));var t=store.transfer(id(transfer),true);policy.account(actor);if(actor!=number(s,"customer_user_id") && !(actor==number(s,"agent_user_id") && policy.current(s)) && !(actor==number(t,"target_agent_user_id") && Set.of("invited","preparing","ready","committing").contains(string(t,"status"))))throw problem(404,"MEDIA_TRANSFER_NOT_FOUND");return transferView(s,t);});}
    public Transfer approveTransfer(Long user,String transfer,Accept body,boolean target){
        long actor=actor(user);if(body==null)throw problem(400,"INVALID_REQUEST");var hint=store.transfer(id(transfer),false);
        return mutate(number(hint,"session_id"),actor,body.expectedEpoch(),true,s->{policy.currentRequired(s);var t=store.transfer(id(transfer),true);if(actor!=(target?number(t,"target_agent_user_id"):number(s,"customer_user_id")))throw problem(404,"MEDIA_TRANSFER_NOT_FOUND");if(!Set.of("invited","preparing","ready").contains(string(t,"status")) || !instant(t,"expires_at").isAfter(Instant.now()) || number(t,"control_epoch")!=number(s,"control_epoch"))throw problem(409,"MEDIA_TRANSFER_INACTIVE");policy.profile(number(t,"target_agent_user_id"),false);var r=room(number(s,"id"),(int)number(t,"target_generation"));acceptMember(member(number(r,"id"),actor),body.consent(),string(s,"mode"));store.jdbc.update("UPDATE support_media_transfer SET "+(target?"target_accepted_at":"customer_approved_at")+"=COALESCE("+(target?"target_accepted_at":"customer_approved_at")+",CURRENT_TIMESTAMP(3)) WHERE id=?",t.get("id"));t=store.transfer(number(t,"id"),true);if(t.get("customer_approved_at")!=null && t.get("target_accepted_at")!=null){store.jdbc.update("UPDATE support_media_transfer SET status='preparing' WHERE id=? AND status='invited'",t.get("id"));queue(s,r,t,"create_room",null,false);}return transferView(s,store.transfer(number(t,"id"),true));});
    }
    public Transfer rejectTransfer(Long user,String transfer,Epoch body,boolean cancel){long actor=actor(user);if(body==null)throw problem(400,"INVALID_REQUEST");var hint=store.transfer(id(transfer),false);return mutate(number(hint,"session_id"),actor,body.expectedEpoch(),true,s->{var t=store.transfer(id(transfer),true);if(cancel){if(actor!=number(t,"requester_user_id") && actor!=number(s,"customer_user_id"))throw problem(404,"MEDIA_TRANSFER_NOT_FOUND");}else if(actor!=number(t,"target_agent_user_id") && actor!=number(s,"customer_user_id"))throw problem(404,"MEDIA_TRANSFER_NOT_FOUND");if("committing".equals(string(t,"status")))throw problem(409,"MEDIA_TRANSFER_COMMITTING");if(!Set.of("invited","preparing","ready").contains(string(t,"status")))return transferView(s,t);abortTransfer(s,t,cancel?"canceled":"rejected",cancel?"TRANSFER_CANCELED":"TRANSFER_REJECTED",true);return transferView(store.session(number(s,"id"),true),store.transfer(number(t,"id"),true));});}
    void abortTransfer(Map<String,Object> s,Map<String,Object> t,String status,String error,boolean bump){
        if(!Set.of("invited","preparing","ready","committing").contains(string(t,"status")))return;
        store.jdbc.update("UPDATE support_media_transfer SET status=?,error_code=?,completed_at=CURRENT_TIMESTAMP(3) WHERE id=?",status,error,t.get("id"));if(bump)store.jdbc.update("UPDATE support_media_session SET control_epoch=control_epoch+1 WHERE id=?",s.get("id"));s=store.session(number(s,"id"),true);var r=room(number(s,"id"),(int)number(t,"target_generation"));
        store.jdbc.update("UPDATE support_media_room SET status='cleanup_pending',retired_at=COALESCE(retired_at,CURRENT_TIMESTAMP(3)) WHERE id=?",r.get("id"));store.jdbc.update("UPDATE support_media_member SET status='revoked',reservation_status='cleanup_pending',revoked_at=COALESCE(revoked_at,CURRENT_TIMESTAMP(3)),ready_at=NULL WHERE room_id=?",r.get("id"));queue(s,r,t,"delete_room",null,true);event(s,"media.transfer."+status,Map.of("transfer_id",Long.toString(number(t,"id"))));
    }
    public CursorPage<Invitation> invitations(Long user,long after,int limit){long actor=actor(user);policy.account(actor);if(after<0 || limit<1 || limit>100)throw problem(400,"INVALID_CURSOR_OR_LIMIT");
        var rows=store.rows("SELECT s.id,'session' AS invitation_type,s.id AS session_id,s.conversation_id,s.inviter_user_id AS from_user_id,s.invitee_user_id AS to_user_id,s.status,s.expires_at FROM support_media_session s WHERE s.invitee_user_id=? AND s.status='invited' AND s.expires_at>CURRENT_TIMESTAMP(3) AND s.id>? UNION ALL SELECT t.id,'transfer',t.session_id,s.conversation_id,t.requester_user_id,t.target_agent_user_id,t.status,t.expires_at FROM support_media_transfer t JOIN support_media_session s ON s.id=t.session_id WHERE t.target_agent_user_id=? AND t.status IN ('invited','preparing','ready') AND t.expires_at>CURRENT_TIMESTAMP(3) AND t.id>? ORDER BY id LIMIT ?",actor,after,actor,after,limit+1);
        var items=rows.stream().limit(limit).map(r->new Invitation(Long.toString(number(r,"id")),string(r,"invitation_type"),Long.toString(number(r,"session_id")),string(r,"conversation_id"),Long.toString(number(r,"from_user_id")),Long.toString(number(r,"to_user_id")),string(r,"status"),instant(r,"expires_at"))).toList();return new CursorPage<>(items,items.isEmpty()?after:Long.parseLong(items.getLast().id()),rows.size()>limit);
    }
    public CursorPage<Map<String,Object>> experts(Long user,long after,int limit){long actor=actor(user);policy.account(actor);if(after<0 || limit<1 || limit>100)throw problem(400,"INVALID_CURSOR_OR_LIMIT");
        var rows=store.rows("SELECT p.user_id,u.username,p.role,(p.accepting=1 AND p.presence_expires_at>CURRENT_TIMESTAMP(3) AND NOT EXISTS(SELECT 1 FROM support_media_member m WHERE m.user_id=p.user_id AND m.media_busy_slot=1) AND (SELECT COUNT(*) FROM support_assignment a WHERE a.agent_user_id=p.user_id AND a.released_at IS NULL)+(SELECT COUNT(*) FROM support_media_transfer t WHERE t.target_agent_user_id=p.user_id AND t.active_slot=1)<p.capacity_text) AS available FROM support_agent_profile p JOIN sys_user u ON u.id=p.user_id AND u.status=1 WHERE p.enabled=1 AND p.role IN ('support_agent','expert') AND p.user_id>? ORDER BY p.user_id LIMIT ?",after,limit+1);
        List<Map<String,Object>> items=rows.stream().limit(limit).map(r->{Map<String,Object> out=new LinkedHashMap<>();out.put("user_id",Long.toString(number(r,"user_id")));String name=string(r,"username");out.put("display_name",name==null || name.matches("[+0-9 ()-]{6,}") || name.contains("@")?"专家 "+number(r,"user_id"):name);out.put("role",string(r,"role"));out.put("available",yes(r,"available"));return out;}).toList();return new CursorPage<>(items,items.isEmpty()?after:Long.parseLong((String)items.getLast().get("user_id")),rows.size()>limit);
    }
    @Override public int reservedTextSlots(long actor){return store.rows("SELECT id FROM support_media_transfer WHERE target_agent_user_id=? AND active_slot=1 FOR UPDATE",actor).size();}
    @Override public void responsibilityEnded(String request){
        for(var hint:store.rows("SELECT id FROM support_media_session WHERE request_id=? AND active_slot=1",request)){var s=store.session(number(hint,"id"),true);if(!TERMINAL.contains(string(s,"status")))finish(s,"ending","RESPONSIBILITY_ENDED");}
    }
    @Override public void accountChanged(long actor){
        boolean enabled=Boolean.TRUE.equals(store.jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM sys_user WHERE id=? AND status=1)",Boolean.class,actor));var profiles=store.rows("SELECT enabled FROM support_agent_profile WHERE user_id=?",actor);enabled=enabled && (profiles.isEmpty() || yes(profiles.getFirst(),"enabled"));if(enabled)return;
        for(var hint:store.rows("SELECT DISTINCT s.id,s.conversation_id FROM support_media_session s LEFT JOIN support_media_transfer t ON t.session_id=s.id AND t.active_slot=1 WHERE s.active_slot=1 AND (s.customer_user_id=? OR s.agent_user_id=? OR t.target_agent_user_id=?) ORDER BY s.id",actor,actor,actor)){conversations.byId(string(hint,"conversation_id"),true);var s=store.session(number(hint,"id"),true);if(actor==number(s,"customer_user_id") || actor==number(s,"agent_user_id"))finish(s,"ending","ACCOUNT_REVOKED");else{var t=store.pending(number(s,"id"),true);if(t!=null)abortTransfer(s,t,"failed","TARGET_REVOKED",true);}}
    }
    public xiaozhi.modules.annotation.AnnotationModels.Frame capture(Long user,String session,Capture body) {
        long actor=actor(user);if(body==null || body.roomGeneration()==null || body.capturedAt()==null || body.capturedAt().isBefore(Instant.now().minusSeconds(300)) || body.capturedAt().isAfter(Instant.now().plusSeconds(5)))throw problem(400,"INVALID_LIVE_CAPTURE");
        var annotations=annotationProvider==null?null:annotationProvider.getIfAvailable();if(annotations==null)throw problem(415,"ANNOTATIONS_DISABLED");
        return mutate(id(session),actor,body.expectedEpoch(),false,s->{policy.currentRequired(s);if(actor!=number(s,"agent_user_id") || !"active".equals(string(s,"status")) || body.roomGeneration()!=number(s,"current_generation"))throw problem(403,"LIVE_CAPTURE_DENIED");
            var r=room(number(s,"id"),body.roomGeneration());var customer=member(number(r,"id"),number(s,"customer_user_id"));if(!yes(customer,"consent_camera") || !yes(customer,"consent_snapshots") || !bothReady(r))throw problem(403,"SNAPSHOT_CONSENT_REQUIRED");
            if(!yes(member(number(r,"id"),actor),"receiving_video"))throw problem(403,"SNAPSHOT_VIDEO_NOT_RECEIVED");
            var frame=annotations.createLiveFrame(actor,string(s,"conversation_id"),body.clientFrameId(),body.sourceAttachmentId(),number(s,"id"),body.roomGeneration(),body.capturedAt());
            if(store.jdbc.queryForObject("SELECT COUNT(*) FROM ai_conversation_event WHERE conversation_id=? AND event_type='media.snapshot.saved' AND JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.frame_id'))=?",Long.class,s.get("conversation_id"),frame.id())==0)event(s,"media.snapshot.saved",Map.of("frame_id",frame.id(),"room_generation",body.roomGeneration(),"source_type","client_live_capture","verification","client_declared"));return frame;
        });
    }

    public Map<String,Object> drainStatus(Long user,String conversation) {
        long actor=actor(user);if(conversation!=null && !conversation.matches("[a-f0-9]{32}"))throw problem(404,"CONVERSATION_NOT_FOUND");
        return store.tx(()->{policy.account(actor);
            if(conversation==null){if(!Boolean.TRUE.equals(store.jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM sys_user WHERE id=? AND status=1 AND super_admin=1)",Boolean.class,actor)))throw problem(403,"MEDIA_DRAIN_ADMIN_REQUIRED");}
            else new xiaozhi.modules.conversation.ConversationAccessService(conversations).detail(actor,conversation,true);
            String filter=conversation==null?"":" AND s.conversation_id=?";Object[] parameters=conversation==null?new Object[0]:new Object[]{conversation};
            int sessions=store.jdbc.queryForObject("SELECT COUNT(*) FROM support_media_session s WHERE (s.active_slot=1 OR s.cleanup_status='pending')"+filter,Integer.class,parameters);
            // Repeated watching of an already-confirmed cleaned old room is not an unfinished close.
            int operations=store.jdbc.queryForObject("SELECT COUNT(*) FROM support_media_outbox o JOIN support_media_session s ON s.id=o.session_id JOIN support_media_room r ON r.id=o.room_id WHERE o.status IN ('queued','running') AND (r.status<>'cleaned' OR s.cleanup_status='pending')"+filter,Integer.class,parameters);
            return Map.of("enabled",enabled(),"pending_sessions",sessions,"pending_operations",operations,"cleanup_complete",sessions==0 && operations==0);
        });
    }

}
