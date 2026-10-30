package xiaozhi.modules.supportmedia;

import static xiaozhi.modules.supportmedia.MediaStore.*;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import xiaozhi.modules.conversation.ConversationProblem;
import xiaozhi.modules.supportmedia.livekit.*;
import xiaozhi.modules.supportmedia.livekit.LiveKitAdapter.*;

/** Bounded durable compensation; every network call occurs after committing its fenced intent. */
@Component
@ConditionalOnProperty(prefix="features",name={"conversation-enabled","support-handoff-enabled"},havingValue="true")
public class MediaMaintenance {
    private final MediaService service;private final MediaStore store;private final MediaPolicy policy;private final LiveKitAdapter livekit;
    public MediaMaintenance(MediaService service,MediaStore store,MediaPolicy policy,LiveKitAdapter livekit){this.service=service;this.store=store;this.policy=policy;this.livekit=livekit;}
    @Scheduled(fixedDelayString="${support-media.maintenance-delay-ms:3000}")
    public void tick(){expire();if(livekit.configured()){processOutbox();poll();advanceTransfers();}}
    public void expire(){
        for(var hint:store.rows("SELECT id FROM support_media_session WHERE active_slot=1 ORDER BY id LIMIT 50"))try{store.tx(()->{
            var s=policy.lock(number(hint,"id"),null);String state=string(s,"status");if(state.equals("ending"))return null;
            if(!service.enabled()){service.finish(s,"ending","MEDIA_DISABLED_DRAINING");return null;}
            try{policy.currentRequired(s);}catch(ConversationProblem revoked){service.finish(s,"ending","AUTHORIZATION_REVOKED");return null;}
            if(state.equals("invited") && !instant(s,"expires_at").isAfter(Instant.now())){service.finish(s,"expired","INVITATION_EXPIRED");return null;}
            var t=store.pending(number(s,"id"),true);if(t!=null){boolean invalid=false;try{policy.profile(number(t,"target_agent_user_id"),false);}catch(ConversationProblem revoked){invalid=true;}
                if(invalid || !instant(t,"expires_at").isAfter(Instant.now())){boolean ambiguous=string(t,"status").equals("committing");service.abortTransfer(s,t,invalid?"failed":"expired",ambiguous?"TRANSFER_INTERRUPTED_RECONNECT_REQUIRED":invalid?"TARGET_REVOKED":"TRANSFER_EXPIRED",true);if(ambiguous)store.jdbc().update("UPDATE support_media_session SET status='reconnecting' WHERE id=?",s.get("id"));}}
            if(Set.of("connecting","reconnecting","active").contains(state)){var r=store.generation(number(s,"id"),(int)number(s,"current_generation"));var members=store.members(number(r,"id"));Instant cutoff=Instant.now().minusSeconds(service.reconnectSeconds);boolean timedOut=members.stream().anyMatch(m->{Instant seen=instant(m,"last_seen_at");return (seen==null?instant(r,"created_at"):seen).isBefore(cutoff);});if(timedOut)service.finish(s,"ending","MEDIA_CONNECTION_TIMEOUT");}
            return null;
        });}catch(ConversationProblem | org.springframework.dao.TransientDataAccessException ignored){/* Next bounded pass re-evaluates current state. */}
    }
    private record Work(Map<String,Object> task,Map<String,Object> room,String lease) { }
    private boolean valid(Map<String,Object> task,Map<String,Object> s,Map<String,Object> r){
        String kind=string(task,"kind"),status=string(s,"status");boolean current=number(r,"generation")==number(s,"current_generation");
        if(kind.equals("delete_room"))return Set.of("cleanup_pending","retired","cleaned").contains(string(r,"status")) && (!current || !Set.of("invited","connecting","active","reconnecting").contains(status));
        if(kind.equals("remove_participant")){
            if(task.get("transfer_id")==null)return true;
            var t=store.transfer(number(task,"transfer_id"),false);return string(t,"status").equals("committing") && number(t,"control_epoch")==number(s,"control_epoch") && current && Objects.equals(s.get("assignment_id"),t.get("source_assignment_id"));
        }
        if(!kind.equals("create_room") || !string(r,"status").equals("preparing") || !Set.of("connecting","active","reconnecting").contains(status))return false;
        if(current)return true;var t=store.pending(number(s,"id"),false);return t!=null && number(t,"target_generation")==number(r,"generation") && Set.of("preparing","ready","committing").contains(string(t,"status"));
    }
    public void processOutbox(){
        var candidates=store.rows("SELECT id FROM support_media_outbox WHERE (status='queued' AND available_at<=CURRENT_TIMESTAMP(3)) OR (status='running' AND lease_expires_at<CURRENT_TIMESTAMP(3)) ORDER BY id LIMIT 12");
        for(var candidate:candidates){Work work=store.tx(()->{
            var hint=store.outbox(number(candidate,"id"),false);var s=policy.lock(number(hint,"session_id"),null);var task=store.outbox(number(hint,"id"),true);String status=string(task,"status");if(status.equals("done") || status.equals("obsolete") || status.equals("running") && instant(task,"lease_expires_at").isAfter(Instant.now()))return null;
            var r=store.room(number(task,"room_id"),true);if(!valid(task,s,r)){store.jdbc().update("UPDATE support_media_outbox SET status='obsolete',lease_token=NULL,lease_expires_at=NULL WHERE id=?",task.get("id"));return null;}
            String lease=MediaService.uid();store.jdbc().update("UPDATE support_media_outbox SET status='running',attempt=attempt+1,lease_token=?,lease_expires_at=? WHERE id=?",lease,time(Instant.now().plusSeconds(30)),task.get("id"));return new Work(new HashMap<>(task),new HashMap<>(r),lease);
        });if(work==null)continue;
            RoomInfo created=null;String failure=null;try{switch(string(work.task(),"kind")){case "create_room"->created=livekit.createRoom(MediaService.owned(work.room()));case "remove_participant"->livekit.removeParticipant(MediaService.owned(work.room()),string(work.task(),"identity"));case "delete_room"->livekit.deleteRoom(MediaService.owned(work.room()));default->throw new IllegalStateException("Invalid stored media intent");}}
            catch(LiveKitFailure ex){failure="LIVEKIT_"+ex.code().name();}catch(RuntimeException ex){failure="LIVEKIT_UNAVAILABLE";}
            final RoomInfo roomCreated=created;final String error=failure;
            store.tx(()->{
                var s=policy.lock(number(work.task(),"session_id"),null);var task=store.outbox(number(work.task(),"id"),true);if(!work.lease().equals(string(task,"lease_token")) || !"running".equals(string(task,"status")))return null;
                if(error!=null){long delay=Math.min(60,1L<<Math.min(6,(int)number(task,"attempt")));store.jdbc().update("UPDATE support_media_outbox SET status='queued',lease_token=NULL,lease_expires_at=NULL,error_code=?,available_at=? WHERE id=?",error,time(Instant.now().plusSeconds(delay)),task.get("id"));return null;}
                var r=store.room(number(task,"room_id"),true);if(!valid(task,s,r)){store.jdbc().update("UPDATE support_media_outbox SET status='obsolete',lease_token=NULL,lease_expires_at=NULL WHERE id=?",task.get("id"));return null;}
                String kind=string(task,"kind");if(kind.equals("create_room")){store.jdbc().update("UPDATE support_media_room SET status='ready',livekit_sid=? WHERE id=? AND status='preparing'",roomCreated.sid(),r.get("id"));}
                else if(kind.equals("delete_room")){store.jdbc().update("UPDATE support_media_room SET status='cleaned' WHERE id=?",r.get("id"));store.jdbc().update("UPDATE support_media_member SET status='revoked',reservation_status='released',ready_at=NULL,revoked_at=COALESCE(revoked_at,CURRENT_TIMESTAMP(3)) WHERE room_id=?",r.get("id"));
                    if(store.jdbc().queryForObject("SELECT COUNT(*) FROM support_media_room WHERE session_id=? AND status<>'cleaned'",Long.class,s.get("id"))==0){boolean ending=string(s,"status").equals("ending");store.jdbc().update("UPDATE support_media_session SET cleanup_status='complete',status=IF(status='ending','ended',status) WHERE id=?",s.get("id"));if(ending)service.event(s,"media.ended",Map.of());}}
                store.jdbc().update("UPDATE support_media_outbox SET status='done',lease_token=NULL,lease_expires_at=NULL,error_code=NULL,completed_at=CURRENT_TIMESTAMP(3) WHERE id=?",task.get("id"));return null;
            });
        }
    }
    public void poll(){
        for(var hint:store.rows("SELECT * FROM support_media_room WHERE status IN ('ready','active','retired','cleanup_pending') OR (status='cleaned' AND retired_at>DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 15 MINUTE)) ORDER BY CASE WHEN status IN ('ready','active') THEN 0 ELSE 1 END,COALESCE(last_polled_at,created_at),id LIMIT 12")){
            if(Set.of("retired","cleanup_pending","cleaned").contains(string(hint,"status"))){store.tx(()->{var s=policy.lock(number(hint,"session_id"),null);var r=store.room(number(hint,"id"),true);service.queue(s,r,null,"delete_room",null,true);store.jdbc().update("UPDATE support_media_room SET last_polled_at=CURRENT_TIMESTAMP(3) WHERE id=?",r.get("id"));return null;});continue;}
            List<Participant> observed;try{observed=livekit.listParticipants(MediaService.owned(hint));}
            catch(LiveKitFailure ex){if(ex.code()==LiveKitFailure.Code.NOT_FOUND)missing(hint);continue;}
            store.tx(()->{var s=policy.lock(number(hint,"session_id"),null);var r=store.room(number(hint,"id"),true);if(!Set.of("ready","active").contains(string(r,"status")))return null;var members=store.members(number(r,"id"));boolean complete=true;
                for(var m:members){var p=observed.stream().filter(o->o.identity().equals(string(m,"identity")) && o.state().equals("ACTIVE")).findFirst().orElse(null);if(p==null){complete=false;store.jdbc().update("UPDATE support_media_member SET ready_at=NULL,receiving_audio=0,receiving_video=0 WHERE id=?",m.get("id"));}else{store.jdbc().update("UPDATE support_media_member SET last_seen_at=CURRENT_TIMESTAMP(3),ready_at=IF(participant_sid IS NULL OR participant_sid<>?,NULL,ready_at),participant_sid=? WHERE id=?",p.sid(),p.sid(),m.get("id"));}}
                for(var p:observed)if(members.stream().noneMatch(m->string(m,"identity").equals(p.identity()) && !"revoked".equals(string(m,"status"))))service.queue(s,r,null,"remove_participant",p.identity(),true);
                if(!complete && number(r,"generation")==number(s,"current_generation") && "active".equals(string(s,"status"))){store.jdbc().update("UPDATE support_media_session SET status='reconnecting' WHERE id=?",s.get("id"));service.event(s,"media.reconnecting",Map.of());}
                store.jdbc().update("UPDATE support_media_room SET last_polled_at=CURRENT_TIMESTAMP(3) WHERE id=?",r.get("id"));return null;});
        }
    }
    private void missing(Map<String,Object> hint){store.tx(()->{var s=policy.lock(number(hint,"session_id"),null);var r=store.room(number(hint,"id"),true);if(!Set.of("ready","active").contains(string(r,"status")))return null;store.jdbc().update("UPDATE support_media_room SET status='preparing',livekit_sid=NULL,last_polled_at=CURRENT_TIMESTAMP(3) WHERE id=?",r.get("id"));store.jdbc().update("UPDATE support_media_member SET ready_at=NULL,receiving_audio=0,receiving_video=0 WHERE room_id=?",r.get("id"));if(number(r,"generation")==number(s,"current_generation"))store.jdbc().update("UPDATE support_media_session SET status='reconnecting' WHERE id=?",s.get("id"));var t=store.pending(number(s,"id"),true);service.queue(s,r,t!=null && number(t,"target_generation")==number(r,"generation")?t:null,"create_room",null,true);return null;});}
    public void advanceTransfers(){
        for(var hint:store.rows("SELECT id,session_id FROM support_media_transfer WHERE status IN ('ready','committing') ORDER BY id LIMIT 8")){
            var preparation=store.tx(()->{var s=policy.lock(number(hint,"session_id"),null);var t=store.transfer(number(hint,"id"),true);if(!Set.of("ready","committing").contains(string(t,"status")))return null;try{policy.currentRequired(s);policy.profile(number(t,"target_agent_user_id"),false);}catch(ConversationProblem denied){service.abortTransfer(s,t,"failed","AUTHORIZATION_REVOKED",true);return null;}if(number(t,"control_epoch")!=number(s,"control_epoch") || !Objects.equals(t.get("source_assignment_id"),s.get("assignment_id")))return null;var r=store.generation(number(s,"id"),(int)number(t,"target_generation"));if(!service.bothReady(r))return null;return new HashMap<>(r);});if(preparation==null)continue;
            List<Participant> observed;try{observed=livekit.listParticipants(MediaService.owned(preparation));}catch(LiveKitFailure ex){continue;}
            store.tx(()->{var s=policy.lock(number(hint,"session_id"),null);var t=store.transfer(number(hint,"id"),true);if(!Set.of("ready","committing").contains(string(t,"status")) || number(t,"control_epoch")!=number(s,"control_epoch"))return null;var target=store.generation(number(s,"id"),(int)number(t,"target_generation"));if(!service.bothReady(target))return null;service.checkObserved(store.members(number(target,"id")),observed);if(!service.receptionCurrent(store.members(number(target,"id")),observed))return null;
                for(var m:store.members(number(target,"id")))if(observed.stream().noneMatch(p->p.identity().equals(string(m,"identity")) && p.sid().equals(string(m,"participant_sid"))))return null;
                if(string(t,"status").equals("ready")){store.jdbc().update("UPDATE support_media_transfer SET status='committing' WHERE id=?",t.get("id"));store.jdbc().update("UPDATE support_media_session SET status='reconnecting' WHERE id=?",s.get("id"));var old=store.generation(number(s,"id"),(int)number(t,"source_generation"));for(var m:store.members(number(old,"id")))service.queue(s,old,t,"remove_participant",string(m,"identity"),false);service.event(s,"media.transfer.committing",Map.of("transfer_id",Long.toString(number(t,"id"))));return null;}
                long done=store.jdbc().queryForObject("SELECT COUNT(*) FROM support_media_outbox WHERE transfer_id=? AND kind='remove_participant' AND status='done'",Long.class,t.get("id"));if(done!=2)return null;
                policy.currentRequired(s);var profile=policy.profile(number(t,"target_agent_user_id"),false);int assignments=store.rows("SELECT id FROM support_assignment WHERE agent_user_id=? AND released_at IS NULL FOR UPDATE",t.get("target_agent_user_id")).size();int reservations=service.reservedTextSlots(number(t,"target_agent_user_id"));if(assignments+reservations>number(profile,"capacity_text")){service.abortTransfer(s,t,"failed","TARGET_CAPACITY_CHANGED",true);return null;}
                if(!instant(t,"expires_at").isAfter(Instant.now()))return null;
                service.event(s,"media.transfer.completed",Map.of("transfer_id",Long.toString(number(t,"id"))));var conversation=service.conversations.byId(string(s,"conversation_id"),true);store.jdbc().update("UPDATE support_assignment SET released_at=CURRENT_TIMESTAMP(3),read_through_seq=?,read_through_event_seq=? WHERE id=? AND released_at IS NULL",conversation.lastSeq(),conversation.lastEventSeq(),s.get("assignment_id"));String assignment=MediaService.uid();store.jdbc().update("INSERT INTO support_assignment(id,request_id,conversation_id,agent_user_id) VALUES(?,?,?,?)",assignment,s.get("request_id"),s.get("conversation_id"),t.get("target_agent_user_id"));
                store.jdbc().update("UPDATE support_request SET assigned_agent_user_id=?,target_agent_user_id=?,assigned_at=CURRENT_TIMESTAMP(3) WHERE id=? AND status='active'",t.get("target_agent_user_id"),t.get("target_agent_user_id"),s.get("request_id"));store.jdbc().update("UPDATE ai_conversation SET control_epoch=control_epoch+1 WHERE id=?",s.get("conversation_id"));
                if(store.jdbc().update("UPDATE support_media_session SET assignment_id=?,agent_user_id=?,current_generation=?,control_epoch=control_epoch+1,status='active' WHERE id=? AND control_epoch=? AND assignment_id=?",assignment,t.get("target_agent_user_id"),t.get("target_generation"),s.get("id"),t.get("control_epoch"),t.get("source_assignment_id"))!=1)throw problem(409,"MEDIA_EPOCH_CHANGED");
                store.jdbc().update("UPDATE support_media_transfer SET status='completed',completed_at=CURRENT_TIMESTAMP(3) WHERE id=?",t.get("id"));var old=store.generation(number(s,"id"),(int)number(t,"source_generation"));store.jdbc().update("UPDATE support_media_room SET status='cleanup_pending',retired_at=CURRENT_TIMESTAMP(3) WHERE id=?",old.get("id"));store.jdbc().update("UPDATE support_media_member SET status='revoked',reservation_status='cleanup_pending',ready_at=NULL,revoked_at=CURRENT_TIMESTAMP(3) WHERE room_id=?",old.get("id"));store.jdbc().update("UPDATE support_media_room SET status='active' WHERE id=?",target.get("id"));service.queue(store.session(number(s,"id"),true),old,null,"delete_room",null,true);return null;
            });
        }
    }
}
