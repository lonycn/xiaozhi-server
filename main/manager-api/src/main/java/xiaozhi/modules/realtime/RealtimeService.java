package xiaozhi.modules.realtime;

import static xiaozhi.modules.realtime.RealtimeStore.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.dao.*;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.*;
import xiaozhi.modules.ai.AiExecutionService;
import xiaozhi.modules.conversation.*;
import xiaozhi.modules.conversation.ConversationModels.*;
import xiaozhi.modules.realtime.RealtimeModels.*;

/** The conversation lock is the authority boundary; no provider/network operation occurs in this class. */
@Service
@ConditionalOnProperty(prefix="features",name="conversation-enabled",havingValue="true")
public class RealtimeService implements RealtimeHooks {
    private final RealtimeStore store;
    private final ObjectMapper json;
    private final Environment environment;
    private ObjectProvider<ConversationService> conversations;
    private ObjectProvider<AiExecutionService> ai;
    static final int LEASE_SECONDS=12;
    private static final Set<String> SESSION_ERRORS=Set.of("PROVIDER_AUTH","PROVIDER_RATE_LIMIT","PROVIDER_TIMEOUT","PROVIDER_UNAVAILABLE","PROVIDER_ERROR","WORKER_ERROR","CLIENT_DISCONNECTED","UNSUPPORTED_TOOL","INPUT_LIMIT");
    private static final Set<String> INJECTION_ERRORS=Set.of("PROVIDER_REJECTED","INJECTION_UNCONFIRMED","PROVIDER_TIMEOUT");
    private static final Set<String> FINISH=Set.of("stop","length","content_filter","tool_calls","unknown");
    public RealtimeService(RealtimeStore store,ObjectMapper json,Environment environment){this.store=store;this.json=json;this.environment=environment;}
    @Autowired public void setConversations(ObjectProvider<ConversationService> value){conversations=value;}
    @Autowired public void setAi(ObjectProvider<AiExecutionService> value){ai=value;}
    public boolean enabled(){return environment.getProperty("features.realtime-ai-enabled",Boolean.class,false);}
    private void configured(){
        if(!enabled())throw problem(503,"REALTIME_AI_DISABLED");
        String key=environment.getProperty("realtime.worker.key","");
        try{URI uri=URI.create(environment.getProperty("realtime.worker.public-url",""));
            if(key.length()<32||key.length()>512||!Set.of("ws","wss").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null)
                throw new IllegalArgumentException();
        }catch(Exception ex){throw problem(503,"REALTIME_NOT_CONFIGURED");}
        if(!"qwen3.8-omni-flash-realtime".equals(model()))throw problem(503,"REALTIME_MODEL_NOT_CONFIGURED");
    }
    private String model(){return environment.getProperty("realtime.model","qwen3.8-omni-flash-realtime");}

    public Session create(Long actor,Create body){
        configured();actor(actor);
        if(body==null)throw problem(400,"INVALID_REQUEST");validId(body.conversationId());client(body.clientSessionId());consent(body.consent());
        String requestHash=hash(encode(body));
        try{return store.tx(()->{
            account(actor,true);var c=owned(actor,body.conversationId());
            var duplicate=store.rows("SELECT * FROM ai_realtime_session WHERE conversation_id=? AND client_session_id=? FOR UPDATE",body.conversationId(),body.clientSessionId());
            if(!duplicate.isEmpty()){var previous=duplicate.getFirst();if(!requestHash.equals(previous.get("request_hash")))throw problem(409,"CLIENT_SESSION_ID_REUSED");return view(previous);}
            if(!"AI_ACTIVE".equals(c.get("state")))throw problem(409,"REALTIME_REQUIRES_AI_ACTIVE");
            if(activeInConversation(body.conversationId()))throw problem(409,"REALTIME_ALREADY_ACTIVE");
            if(ai!=null&&ai.getIfAvailable()!=null)ai.getObject().cancelActive(body.conversationId());
            long epoch=number(c,"control_epoch")+1;
            store.jdbc().update("UPDATE ai_conversation SET control_epoch=?,updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",epoch,body.conversationId());
            String id=id();
            store.jdbc().update("INSERT INTO ai_realtime_session(id,conversation_id,owner_user_id,client_session_id,request_hash,control_epoch,model,consent_microphone,consent_camera,context_seq) VALUES(?,?,?,?,?,?,?,1,?,?)",
                    id,body.conversationId(),actor,body.clientSessionId(),requestHash,epoch,model(),Boolean.TRUE.equals(body.consent().camera())?1:0,number(c,"last_seq"));
            var row=store.session(id,true);event(row,"realtime.session.created",Map.of());return view(row);
        });}catch(DuplicateKeyException|PessimisticLockingFailureException ex){throw problem(409,"REALTIME_CONFLICT_RETRY");}
    }
    public Session current(Long actor,String conversationId){actor(actor);validId(conversationId);return store.tx(()->{account(actor,true);owned(actor,conversationId);var rows=store.rows("SELECT * FROM ai_realtime_session WHERE conversation_id=? ORDER BY list_sequence DESC LIMIT 1",conversationId);return rows.isEmpty()?null:view(rows.getFirst());});}
    public Session get(Long actor,String sessionId){return owner(actor,sessionId,(s,c)->view(s));}
    public Ticket ticket(Long actor,String sessionId,Epoch body){configured();return owner(actor,sessionId,(s,c)->{
        expected(s,c,body==null?null:body.expectedEpoch());
        if(!"pending".equals(s.get("status"))||s.get("lease_hash")!=null)throw problem(409,"REALTIME_NOT_CONNECTABLE");
        if(expiration(s,c)!=null)throw problem(409,"REALTIME_SESSION_EXPIRED");
        store.jdbc().update("UPDATE ai_realtime_ticket SET status='expired' WHERE session_id=? AND status='issued'",sessionId);
        String token=secret();Instant expires=Instant.now().plusSeconds(30);
        store.jdbc().update("INSERT INTO ai_realtime_ticket(id,session_id,ticket_hash,expires_at) VALUES(?,?,?,?)",id(),sessionId,hash(token),java.sql.Timestamp.from(expires));
        return new Ticket(sessionId,number(s,"control_epoch"),environment.getProperty("realtime.worker.public-url"),token,expires);
    });}
    public Task consume(Consume body){configured();if(body==null||body.ticket()==null||!body.ticket().matches("[a-f0-9]{64}")||!safe(body.workerId(),100)||!safe(body.serverId(),100))throw problem(400,"INVALID_CONNECT_CLAIM");
        var hints=store.rows("SELECT session_id FROM ai_realtime_ticket WHERE ticket_hash=?",hash(body.ticket()));if(hints.isEmpty())throw problem(409,"TICKET_INVALID");
        String sessionId=string(hints.getFirst(),"session_id");var hint=store.session(sessionId,false);
        return store.tx(()->{
            account(number(hint,"owner_user_id"),true);var c=store.conversation(string(hint,"conversation_id"));var s=store.session(sessionId,true);
            var ticket=store.one("SELECT * FROM ai_realtime_ticket WHERE ticket_hash=? FOR UPDATE",hash(body.ticket()));
            if(!"issued".equals(ticket.get("status"))||!instant(ticket,"expires_at").isAfter(Instant.now())||!"pending".equals(s.get("status"))||s.get("lease_hash")!=null||expiration(s,c)!=null)throw problem(409,"TICKET_INVALID");
            String token=secret();store.jdbc().update("UPDATE ai_realtime_ticket SET status='consumed',consumed_at=CURRENT_TIMESTAMP(3) WHERE id=?",ticket.get("id"));
            store.jdbc().update("UPDATE ai_realtime_session SET status='connecting',worker_id=?,server_id=?,lease_hash=?,lease_expires_at=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL ? SECOND) WHERE id=?",body.workerId(),body.serverId(),hash(token),LEASE_SECONDS,sessionId);
            event(s,"realtime.session.connecting",Map.of());
            return new Task(sessionId,string(s,"conversation_id"),number(s,"control_epoch"),token,body.serverId(),LEASE_SECONDS,string(s,"model"),consent(s),context(s),number(s,"context_seq"),
                    new Limits(2,1800,65536,1920000,190000,921600,1000,16000,128000),new AudioFormat("s16le",16000,1),new AudioFormat("s16le",24000,1));
        });
    }
    public Pulse heartbeat(String sessionId,Heartbeat body){if(body==null||body.inputBusy()==null)throw problem(400,"INVALID_HEARTBEAT");return callback(sessionId,body.leaseToken(),false,s->{
        expireTasks(s);
        if(!active(store.session(sessionId,true)))return new Pulse(false,0,List.of(),List.of());
        store.jdbc().update("UPDATE ai_realtime_session SET lease_expires_at=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL ? SECOND) WHERE id=?",LEASE_SECONDS,sessionId);
        List<Dispatch> injections=new ArrayList<>();List<Command> commands=new ArrayList<>();
        if("active".equals(s.get("status"))&&!body.inputBusy()&&turn(s)==null&&store.rows("SELECT id FROM ai_realtime_injection WHERE session_id=? AND status='dispatched' FOR UPDATE",sessionId).isEmpty()){
            var queued=store.rows("SELECT * FROM ai_realtime_injection WHERE session_id=? AND status='queued' ORDER BY list_sequence LIMIT 1 FOR UPDATE",sessionId);
            if(!queued.isEmpty()){var i=queued.getFirst();store.jdbc().update("UPDATE ai_realtime_injection SET status='dispatched',dispatched_at=CURRENT_TIMESTAMP(3) WHERE id=?",i.get("id"));injections.add(new Dispatch(string(i,"id"),string(i,"message_id"),string(i,"text_content"),string(i,"provider_item_id")));}
        }
        for(var command:store.rows("SELECT * FROM ai_realtime_command WHERE session_id=? AND status='queued' ORDER BY created_at,id FOR UPDATE",sessionId)){
            store.jdbc().update("UPDATE ai_realtime_command SET status='dispatched',dispatched_at=CURRENT_TIMESTAMP(3) WHERE id=?",command.get("id"));command.put("status","dispatched");commands.add(command(command));
        }
        return new Pulse(true,LEASE_SECONDS,injections,commands);
    });}
    public Sent send(Long actor,String sessionId,Send body){configured();if(body==null||body.text()==null||body.text().isBlank()||body.text().length()>8000)throw problem(400,"TEXT_REQUIRED_MAX_8000");client(body.clientMessageId());
        return owner(actor,sessionId,(s,c)->{
            expected(s,c,body.expectedEpoch());if(!active(s)||expiration(s,c)!=null)throw problem(409,"REALTIME_NOT_ACTIVE");
            if(conversations==null)throw problem(503,"REALTIME_NOT_CONFIGURED");
            var message=conversations.getObject().send(actor,string(s,"conversation_id"),new SendMessage(body.clientMessageId(),List.of(new MessagePart("text",body.text(),null,null,null,null,null,null))));
            var task=store.rows("SELECT * FROM ai_realtime_injection WHERE message_id=? AND session_id=?",message.id(),sessionId);if(task.isEmpty())throw problem(409,"MESSAGE_BELONGS_TO_PREVIOUS_CONTEXT");
            return new Sent(message,injection(task.getFirst()));
        });
    }
    public Page<Injection> injections(Long actor,String sessionId,long after,int limit){if(after<0||limit<1||limit>100)throw problem(400,"INVALID_CURSOR_OR_LIMIT");return owner(actor,sessionId,(s,c)->{
        var rows=store.rows("SELECT * FROM ai_realtime_injection WHERE session_id=? AND list_sequence>? ORDER BY list_sequence LIMIT ?",sessionId,after,limit+1);var items=rows.stream().limit(limit).map(this::injection).toList();return new Page<>(items,items.isEmpty()?after:items.getLast().sequence(),rows.size()>limit);
    });}
    public Command interrupt(Long actor,String sessionId,Interrupt body){configured();if(body==null)throw problem(400,"INVALID_REQUEST");client(body.clientCommandId());providerId(body.responseId());return owner(actor,sessionId,(s,c)->{
        expected(s,c,body.expectedEpoch());if(!"active".equals(s.get("status"))||expiration(s,c)!=null)throw problem(409,"REALTIME_NOT_ACTIVE");
        String requestHash=hash(encode(body));var previous=store.rows("SELECT * FROM ai_realtime_command WHERE session_id=? AND client_command_id=? FOR UPDATE",sessionId,body.clientCommandId());
        if(!previous.isEmpty()){if(!requestHash.equals(previous.getFirst().get("request_hash")))throw problem(409,"CLIENT_COMMAND_ID_REUSED");return command(previous.getFirst());}
        var turn=turn(s);if(turn==null||!body.responseId().equals(turn.get("provider_item_id")))throw problem(409,"NO_ACTIVE_RESPONSE");
        if("interrupting".equals(turn.get("status")))throw problem(409,"RESPONSE_ALREADY_INTERRUPTING");
        store.jdbc().update("UPDATE ai_realtime_turn SET status='interrupting' WHERE id=?",turn.get("id"));String id=id();
        store.jdbc().update("INSERT INTO ai_realtime_command(id,session_id,client_command_id,request_hash,response_id,command_type) VALUES(?,?,?,?,?,'interrupt')",id,sessionId,body.clientCommandId(),requestHash,body.responseId());
        event(s,"realtime.response.interrupting",Map.of("response_id",body.responseId(),"message_id",turn.get("message_id")));return command(store.one("SELECT * FROM ai_realtime_command WHERE id=?",id));
    });}
    public Session end(Long actor,String sessionId,Epoch body){return owner(actor,sessionId,(s,c)->{
        if(body==null||body.expectedEpoch()==null||body.expectedEpoch()!=number(s,"control_epoch"))throw problem(409,"REALTIME_EPOCH_CONFLICT");
        if(active(s)){expected(s,c,body.expectedEpoch());terminal(s,"ended",null);}return view(store.session(sessionId,true));
    });}

    /** Called after normal message + attachment binding, before B2 scheduling, in the same transaction. */
    @Override public boolean onUserMessage(String conversationId,String messageId){
        var active=store.rows("SELECT * FROM ai_realtime_session WHERE conversation_id=? AND active_slot=1 FOR UPDATE",conversationId);if(active.isEmpty())return false;
        var s=active.getFirst();var c=store.conversation(conversationId);String reason=expiration(s,c);if(reason!=null){terminal(s,"expired",reason);return false;}
        var message=store.one("SELECT * FROM ai_message WHERE id=?",messageId);StringBuilder text=new StringBuilder();
        for(JsonNode part:decode(string(message,"parts_json"))){if(!"text".equals(part.path("type").asText()))throw problem(409,"REALTIME_TEXT_ONLY");if(!text.isEmpty())text.append('\n');text.append(part.path("text").asText());}
        if(text.isEmpty()||text.length()>8000)throw problem(400,"REALTIME_TEXT_MAX_8000");
        long pending=store.jdbc().queryForObject("SELECT COUNT(*) FROM ai_realtime_injection WHERE session_id=? AND status IN ('queued','dispatched')",Long.class,s.get("id"));if(pending>=10)throw problem(409,"REALTIME_INJECTION_QUEUE_FULL");
        String id=id();store.jdbc().update("INSERT INTO ai_realtime_injection(id,session_id,message_id,provider_item_id,text_content) VALUES(?,?,?,?,?)",id,s.get("id"),messageId,"rt-inject-"+id,text.toString());
        event(s,"realtime.injection.queued",Map.of("injection_id",id,"message_id",messageId));return true;
    }
    @Override public boolean activeInConversation(String conversationId){return !store.rows("SELECT id FROM ai_realtime_session WHERE conversation_id=? AND active_slot=1 FOR UPDATE",conversationId).isEmpty();}
    @Override public void cancelInConversation(String conversationId,String reason){for(var s:store.rows("SELECT * FROM ai_realtime_session WHERE conversation_id=? AND active_slot=1 FOR UPDATE",conversationId))terminal(s,"canceled",reason);}

    public Receipt providerEvent(String sessionId,ProviderEvent body){validate(body);return callback(sessionId,body.leaseToken(),true,s->{
        String requestHash=hash(encode(body));var previous=store.rows("SELECT * FROM ai_realtime_event WHERE session_id=? AND (provider_event_id=? OR sequence=?) FOR UPDATE",sessionId,body.providerEventId(),body.sequence());
        if(!previous.isEmpty()){
            if(previous.size()!=1||!requestHash.equals(previous.getFirst().get("request_hash")))throw problem(409,"REALTIME_EVENT_REUSED");
            return receipt(s,body);
        }
        if(!active(s))throw problem(409,"LEASE_INVALID");
        if(body.sequence()>50000)throw problem(409,"REALTIME_EVENT_LIMIT");
        if(body.sequence()!=number(s,"last_event_sequence")+1)throw problem(409,"REALTIME_EVENT_SEQUENCE_CONFLICT");
        if(!"session.ready".equals(body.type())&&!"session.error".equals(body.type())&&!"active".equals(s.get("status")))throw problem(409,"PROVIDER_NOT_READY");
        apply(s,body);
        store.jdbc().update("INSERT INTO ai_realtime_event(id,session_id,sequence,provider_event_id,event_type,request_hash) VALUES(?,?,?,?,?,?)",id(),sessionId,body.sequence(),body.providerEventId(),body.type(),requestHash);
        store.jdbc().update("UPDATE ai_realtime_session SET last_event_sequence=? WHERE id=?",body.sequence(),sessionId);
        return receipt(store.session(sessionId,true),body);
    });}
    private void apply(Map<String,Object> s,ProviderEvent e){
        String sessionId=string(s,"id");
        switch(e.type()){
            case "session.ready" -> {if(!"connecting".equals(s.get("status")))throw problem(409,"PROVIDER_ALREADY_READY");store.jdbc().update("UPDATE ai_realtime_session SET status='active',provider_session_id=?,connected_at=CURRENT_TIMESTAMP(3) WHERE id=?",e.providerSessionId(),sessionId);event(s,"realtime.session.ready",Map.of("provider_session_id",e.providerSessionId()));}
            case "input.committed" -> {
                var existing=store.rows("SELECT * FROM ai_realtime_turn WHERE session_id=? AND provider_item_id=? FOR UPDATE",sessionId,e.providerItemId());
                if(!existing.isEmpty()){
                    var input=existing.getFirst();if(!"input_audio".equals(input.get("kind"))||input.get("committed_at")!=null)throw problem(409,"PROVIDER_ITEM_REUSED");
                    store.jdbc().update("UPDATE ai_realtime_turn SET committed_at=CURRENT_TIMESTAMP(3) WHERE id=?",input.get("id"));
                }else{
                    turnLimit(sessionId);String message=createMessage(s,"user","","transcribing","provider_audio_transcription");
                    store.jdbc().update("INSERT INTO ai_realtime_turn(id,session_id,provider_item_id,kind,source,status,message_id,output_text,committed_at) VALUES(?,?,?,'input_audio','provider_audio_transcription','streaming',?,'',CURRENT_TIMESTAMP(3))",id(),sessionId,e.providerItemId(),message);
                    event(s,"message.accepted",Map.of("message_id",message,"source","provider_audio_transcription","provider_item_id",e.providerItemId(),"status","transcribing"));
                }
            }
            case "input.transcript" -> {
                var existing=store.rows("SELECT * FROM ai_realtime_turn WHERE session_id=? AND provider_item_id=? FOR UPDATE",sessionId,e.providerItemId());String message;String status=e.text().isBlank()?"failed":"completed";
                if(existing.isEmpty()){
                    turnLimit(sessionId);message=createMessage(s,"user",e.text(),status,"provider_audio_transcription");
                    store.jdbc().update("INSERT INTO ai_realtime_turn(id,session_id,provider_item_id,kind,source,status,message_id,output_text,ended_at) VALUES(?,?,?,'input_audio','provider_audio_transcription','completed',?,?,CURRENT_TIMESTAMP(3))",id(),sessionId,e.providerItemId(),message,e.text());
                }else{
                    var input=existing.getFirst();if(!"input_audio".equals(input.get("kind"))||!"streaming".equals(input.get("status")))throw problem(409,"PROVIDER_ITEM_REUSED");
                    message=string(input,"message_id");store.jdbc().update("UPDATE ai_realtime_turn SET status='completed',output_text=?,ended_at=CURRENT_TIMESTAMP(3) WHERE id=?",e.text(),input.get("id"));message(input,e.text(),status);
                }
                event(s,"realtime.input.transcribed",Map.of("message_id",message,"source","provider_audio_transcription","provider_item_id",e.providerItemId(),"status",status));
            }
            case "response.started" -> {
                if(turn(s)!=null)throw problem(409,"RESPONSE_ALREADY_ACTIVE");
                if(!store.rows("SELECT id FROM ai_realtime_turn WHERE session_id=? AND provider_item_id=? FOR UPDATE",sessionId,e.responseId()).isEmpty())throw problem(409,"PROVIDER_ITEM_REUSED");
                turnLimit(sessionId);long count=store.jdbc().queryForObject("SELECT COUNT(*) FROM ai_realtime_turn WHERE session_id=? AND kind='assistant'",Long.class,sessionId);if(count>=200)throw problem(409,"REALTIME_TURN_LIMIT");
                String message=createMessage(s,"assistant","","streaming","provider_realtime");
                store.jdbc().update("INSERT INTO ai_realtime_turn(id,session_id,provider_item_id,kind,source,status,message_id,output_text) VALUES(?,?,?,'assistant','provider_realtime','streaming',?,'')",id(),sessionId,e.responseId(),message);
                event(s,"assistant.message.created",Map.of("message_id",message,"response_id",e.responseId(),"source","provider_realtime"));
            }
            case "response.delta" -> {
                var turn=response(s,e.responseId());if(!"streaming".equals(turn.get("status")))throw problem(409,"RESPONSE_INTERRUPTED_OR_TERMINAL");
                String text=string(turn,"output_text")+e.text();if(text.length()>128000)throw problem(409,"REALTIME_OUTPUT_LIMIT");
                store.jdbc().update("UPDATE ai_realtime_turn SET output_text=? WHERE id=?",text,turn.get("id"));message(turn,text,"streaming");
                event(s,"assistant.text.delta",Map.of("message_id",turn.get("message_id"),"response_id",e.responseId(),"text",e.text(),"source","provider_realtime"));
            }
            case "response.completed","response.canceled" -> {
                var turn=response(s,e.responseId());if(!Set.of("streaming","interrupting").contains(turn.get("status")))throw problem(409,"RESPONSE_ALREADY_TERMINAL");
                boolean canceled="response.canceled".equals(e.type())||"interrupting".equals(turn.get("status"));String text=canceled?string(turn,"output_text"):e.text();String status=canceled?"canceled":"completed";
                String usage=e.usage()==null?null:encode(e.usage());
                store.jdbc().update("UPDATE ai_realtime_turn SET output_text=?,status=?,finish_reason=?,usage_json=?,ended_at=CURRENT_TIMESTAMP(3) WHERE id=?",text,status,e.finishReason(),usage,turn.get("id"));message(turn,text,status);
                var extra=new LinkedHashMap<String,Object>();extra.put("message_id",turn.get("message_id"));extra.put("response_id",e.responseId());extra.put("source","provider_realtime");extra.put("usage",e.usage());
                event(s,canceled?"assistant.message.canceled":"assistant.message.completed",extra);
                if("tool_calls".equals(e.finishReason()))terminal(s,"failed","UNSUPPORTED_TOOL");
            }
            case "injection.ack","injection.failed" -> {
                var injection=store.one("SELECT * FROM ai_realtime_injection WHERE id=? AND session_id=? FOR UPDATE",e.injectionId(),sessionId);
                if(!"dispatched".equals(injection.get("status")))throw problem(409,"INJECTION_NOT_DISPATCHED");
                if(instant(injection,"dispatched_at")==null||!instant(injection,"dispatched_at").plusSeconds(15).isAfter(Instant.now()))throw problem(409,"INJECTION_ACK_EXPIRED");
                if("injection.ack".equals(e.type())){
                    if(!e.providerItemId().equals(injection.get("provider_item_id")))throw problem(409,"INJECTION_PROVIDER_ITEM_MISMATCH");
                    store.jdbc().update("UPDATE ai_realtime_injection SET status='acknowledged',acknowledged_at=CURRENT_TIMESTAMP(3) WHERE id=?",e.injectionId());
                }else store.jdbc().update("UPDATE ai_realtime_injection SET status='failed',error_code=? WHERE id=?",e.errorCode(),e.injectionId());
                event(s,"realtime."+e.type(),Map.of("injection_id",e.injectionId(),"message_id",injection.get("message_id"),"status","injection.ack".equals(e.type())?"acknowledged":"failed"));
            }
            case "command.ack" -> {
                var command=store.one("SELECT * FROM ai_realtime_command WHERE id=? AND session_id=? FOR UPDATE",e.commandId(),sessionId);var turn=response(s,e.responseId());
                if(instant(command,"dispatched_at")==null||!instant(command,"dispatched_at").plusSeconds(15).isAfter(Instant.now()))throw problem(409,"COMMAND_ACK_EXPIRED");
                if(!"dispatched".equals(command.get("status"))||!e.responseId().equals(command.get("response_id"))||!Set.of("completed","canceled").contains(turn.get("status")))throw problem(409,"COMMAND_NOT_CONFIRMED");
                store.jdbc().update("UPDATE ai_realtime_command SET status='acknowledged',acknowledged_at=CURRENT_TIMESTAMP(3) WHERE id=?",e.commandId());
                event(s,"realtime.command.ack",Map.of("command_id",e.commandId(),"response_id",e.responseId()));
            }
            case "session.error" -> terminal(s,"failed",e.errorCode());
            default -> throw problem(400,"INVALID_PROVIDER_EVENT");
        }
    }

    private interface OwnerAction<T>{T apply(Map<String,Object> session,Map<String,Object> conversation);}
    private <T>T owner(Long actor,String sessionId,OwnerAction<T> action){actor(actor);var hint=store.session(sessionId,false);return store.tx(()->{account(actor,true);var c=owned(actor,string(hint,"conversation_id"));var s=store.session(sessionId,true);return action.apply(s,c);});}
    private <T>T callback(String sessionId,String lease,boolean dedupeTerminal,Function<Map<String,Object>,T> action){configured();if(lease==null||!lease.matches("[a-f0-9]{64}"))throw problem(409,"LEASE_INVALID");var hint=store.session(sessionId,false);return store.tx(()->{
        account(number(hint,"owner_user_id"),true);var c=store.conversation(string(hint,"conversation_id"));var s=store.session(sessionId,true);
        if(!"AI_ACTIVE".equals(c.get("state"))||number(c,"control_epoch")!=number(s,"control_epoch")||!hash(lease).equals(s.get("lease_hash"))||s.get("lease_expires_at")==null||!instant(s,"lease_expires_at").isAfter(Instant.now())||Duration.between(instant(s,"created_at"),Instant.now()).toSeconds()>=1800||("connecting".equals(s.get("status"))&&Duration.between(instant(s,"created_at"),Instant.now()).toSeconds()>=60)||(!dedupeTerminal&&!Set.of("connecting","active").contains(s.get("status"))))throw problem(409,"LEASE_INVALID");
        return action.apply(s);
    });}
    private Map<String,Object> owned(long actor,String conversationId){var c=store.conversation(conversationId);if(number(c,"owner_user_id")!=actor)throw problem(404,"CONVERSATION_NOT_FOUND");return c;}
    private boolean account(long actor,boolean required){var rows=store.rows("SELECT status FROM sys_user WHERE id=? FOR UPDATE",actor);boolean ok=!rows.isEmpty()&&number(rows.getFirst(),"status")==1;if(required&&!ok)throw problem(401,"AUTHENTICATION_REQUIRED");return ok;}
    private void expected(Map<String,Object> s,Map<String,Object> c,Long expected){if(expected==null||expected!=number(s,"control_epoch")||expected!=number(c,"control_epoch")||!"AI_ACTIVE".equals(c.get("state")))throw problem(409,"REALTIME_EPOCH_CONFLICT");}
    private String expiration(Map<String,Object> s,Map<String,Object> c){if(!enabled())return "REALTIME_DISABLED";if(!"AI_ACTIVE".equals(c.get("state"))||number(c,"control_epoch")!=number(s,"control_epoch"))return "CONTROL_CHANGED";Instant now=Instant.now();if(Duration.between(instant(s,"created_at"),now).toSeconds()>=1800)return "SESSION_LIMIT";if(Set.of("pending","connecting").contains(s.get("status"))&&Duration.between(instant(s,"created_at"),now).toSeconds()>=60)return "CONNECT_TIMEOUT";if(s.get("lease_expires_at")!=null&&!instant(s,"lease_expires_at").isAfter(now))return "LEASE_EXPIRED";return null;}
    public void maintain(){
        String condition=enabled()?"(s.created_at<=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1800 SECOND) OR (s.status IN ('pending','connecting') AND s.created_at<=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 60 SECOND)) OR s.lease_expires_at<=CURRENT_TIMESTAMP(3) OR u.id IS NULL OR u.status<>1 OR c.state<>'AI_ACTIVE' OR c.control_epoch<>s.control_epoch)":"1=1";
        for(var hint:store.rows("SELECT s.id,s.owner_user_id,s.conversation_id FROM ai_realtime_session s JOIN ai_conversation c ON c.id=s.conversation_id LEFT JOIN sys_user u ON u.id=s.owner_user_id WHERE s.active_slot=1 AND "+condition+" ORDER BY s.created_at LIMIT 64"))store.tx(()->{
            boolean enabledOwner=account(number(hint,"owner_user_id"),false);var c=store.conversation(string(hint,"conversation_id"));var s=store.session(string(hint,"id"),true);if(!active(s))return null;
            String reason=enabledOwner?expiration(s,c):"ACCOUNT_DISABLED";if(reason!=null)terminal(s,"expired",reason);return null;
        });
        for(var hint:store.rows("SELECT DISTINCT s.id,s.owner_user_id,s.conversation_id FROM ai_realtime_session s JOIN ai_realtime_command q ON q.session_id=s.id WHERE s.active_slot=1 AND ((q.status='queued' AND q.created_at<=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 60 SECOND)) OR (q.status='dispatched' AND q.dispatched_at<=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 15 SECOND))) LIMIT 64"))store.tx(()->{account(number(hint,"owner_user_id"),false);store.conversation(string(hint,"conversation_id"));var s=store.session(string(hint,"id"),true);expireTasks(s);return null;});
        // Task deadlines apply even on a healthy long-lived session; discovery does not hold conversation locks.
        for(var hint:store.rows("SELECT DISTINCT s.id,s.owner_user_id,s.conversation_id FROM ai_realtime_session s JOIN ai_realtime_injection i ON i.session_id=s.id WHERE s.active_slot=1 AND ((i.status='queued' AND i.created_at<=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 60 SECOND)) OR (i.status='dispatched' AND i.dispatched_at<=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 15 SECOND))) LIMIT 64"))store.tx(()->{account(number(hint,"owner_user_id"),false);store.conversation(string(hint,"conversation_id"));var s=store.session(string(hint,"id"),true);expireTasks(s);return null;});
    }
    private void expireTasks(Map<String,Object> s){
        if(!store.rows("SELECT id FROM ai_realtime_command WHERE session_id=? AND ((status='queued' AND created_at<=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 60 SECOND)) OR (status='dispatched' AND dispatched_at<=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 15 SECOND))) FOR UPDATE",s.get("id")).isEmpty()){terminal(s,"failed","COMMAND_UNCONFIRMED");return;}
        for(var i:store.rows("SELECT * FROM ai_realtime_injection WHERE session_id=? AND ((status='queued' AND created_at<=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 60 SECOND)) OR (status='dispatched' AND dispatched_at<=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 15 SECOND))) FOR UPDATE",s.get("id"))){store.jdbc().update("UPDATE ai_realtime_injection SET status='failed',error_code='INJECTION_UNCONFIRMED' WHERE id=?",i.get("id"));event(s,"realtime.injection.failed",Map.of("injection_id",i.get("id"),"message_id",i.get("message_id"),"status","failed","error_code","INJECTION_UNCONFIRMED"));}
    }
    private void terminal(Map<String,Object> s,String status,String error){
        if(!active(s))return;String sessionId=string(s,"id");
        for(var turn:store.rows("SELECT * FROM ai_realtime_turn WHERE session_id=? AND active_slot=1 FOR UPDATE",sessionId)){
            store.jdbc().update("UPDATE ai_realtime_turn SET status='canceled',ended_at=CURRENT_TIMESTAMP(3) WHERE id=?",turn.get("id"));message(turn,string(turn,"output_text"),"canceled");event(s,"assistant.message.canceled",Map.of("message_id",turn.get("message_id"),"response_id",turn.get("provider_item_id"),"source","provider_realtime"));
        }
        for(var input:store.rows("SELECT * FROM ai_realtime_turn WHERE session_id=? AND kind='input_audio' AND status='streaming' FOR UPDATE",sessionId)){
            store.jdbc().update("UPDATE ai_realtime_turn SET status='canceled',ended_at=CURRENT_TIMESTAMP(3) WHERE id=?",input.get("id"));message(input,string(input,"output_text"),"failed");event(s,"realtime.input.failed",Map.of("message_id",input.get("message_id"),"source","provider_audio_transcription","error_code","TRANSCRIPTION_UNAVAILABLE"));
        }
        store.jdbc().update("UPDATE ai_realtime_session SET status=?,error_code=?,ended_at=CURRENT_TIMESTAMP(3) WHERE id=?",status,error,sessionId);
        store.jdbc().update("UPDATE ai_realtime_ticket SET status='expired' WHERE session_id=? AND status='issued'",sessionId);
        for(var i:store.rows("SELECT * FROM ai_realtime_injection WHERE session_id=? AND status IN ('queued','dispatched') FOR UPDATE",sessionId)){
            store.jdbc().update("UPDATE ai_realtime_injection SET status='failed',error_code='INJECTION_UNCONFIRMED' WHERE id=?",i.get("id"));event(s,"realtime.injection.failed",Map.of("injection_id",i.get("id"),"message_id",i.get("message_id"),"status","failed","error_code","INJECTION_UNCONFIRMED"));
        }
        store.jdbc().update("UPDATE ai_realtime_command SET status='failed',error_code='SESSION_TERMINATED' WHERE session_id=? AND status IN ('queued','dispatched')",sessionId);
        event(s,"realtime.session."+status,error==null?Map.of():Map.of("error_code",error));
    }
    private String createMessage(Map<String,Object> s,String sender,String text,String status,String source){String c=string(s,"conversation_id");var conversation=store.conversation(c);long seq=number(conversation,"last_seq")+1;String id=id();String parts=encode(text.isEmpty()?List.of():List.of(Map.of("type","text","text",text)));
        store.jdbc().update("INSERT INTO ai_message(id,conversation_id,seq,client_message_id,sender_type,sender_id,parts_json,request_hash,status) VALUES(?,?,?,?,?,?,?,?,?)",id,c,seq,"rt:"+id,sender,"user".equals(sender)?Long.toString(number(s,"owner_user_id")):"ai",parts,hash(source+":"+id),status);
        store.jdbc().update("UPDATE ai_conversation SET last_seq=?,updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",seq,c);return id;
    }
    private void message(Map<String,Object> turn,String text,String status){store.jdbc().update("UPDATE ai_message SET parts_json=?,status=? WHERE id=?",encode(text.isEmpty()?List.of():List.of(Map.of("type","text","text",text))),status,turn.get("message_id"));}
    private void event(Map<String,Object> s,String type,Map<String,Object> extra){var c=store.conversation(string(s,"conversation_id"));long seq=number(c,"last_event_seq")+1;var data=new LinkedHashMap<String,Object>();data.put("realtime_session_id",s.get("id"));data.put("control_epoch",java.math.BigInteger.valueOf(number(s,"control_epoch")));data.putAll(extra);
        store.jdbc().update("INSERT INTO ai_conversation_event(id,conversation_id,seq,event_type,data_json) VALUES(?,?,?,?,?)",id(),s.get("conversation_id"),seq,type,encode(data));store.jdbc().update("UPDATE ai_conversation SET last_event_seq=?,updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",seq,s.get("conversation_id"));
    }
    private void turnLimit(String sessionId){if(store.jdbc().queryForObject("SELECT COUNT(*) FROM ai_realtime_turn WHERE session_id=?",Long.class,sessionId)>=400)throw problem(409,"REALTIME_TURN_LIMIT");}
    private Map<String,Object> turn(Map<String,Object> s){var rows=store.rows("SELECT * FROM ai_realtime_turn WHERE session_id=? AND active_slot=1 FOR UPDATE",s.get("id"));return rows.isEmpty()?null:rows.getFirst();}
    private Receipt receipt(Map<String,Object> s,ProviderEvent e){String message=eventMessage(s,e);String status=message==null?null:store.jdbc().queryForObject("SELECT status FROM ai_message WHERE id=?",String.class,message);return new Receipt(e.sequence(),view(s),message,status);}
    private String eventMessage(Map<String,Object> s,ProviderEvent event){
        if(event.responseId()!=null||event.providerItemId()!=null){String item=event.responseId()!=null?event.responseId():event.providerItemId();var turns=store.rows("SELECT message_id FROM ai_realtime_turn WHERE session_id=? AND provider_item_id=?",s.get("id"),item);if(!turns.isEmpty())return string(turns.getFirst(),"message_id");}
        if(event.injectionId()!=null){var injections=store.rows("SELECT message_id FROM ai_realtime_injection WHERE session_id=? AND id=?",s.get("id"),event.injectionId());if(!injections.isEmpty())return string(injections.getFirst(),"message_id");}
        return null;
    }
    private Map<String,Object> response(Map<String,Object> s,String responseId){var rows=store.rows("SELECT * FROM ai_realtime_turn WHERE session_id=? AND provider_item_id=? AND kind='assistant' FOR UPDATE",s.get("id"),responseId);if(rows.isEmpty())throw problem(409,"RESPONSE_NOT_STARTED");return rows.getFirst();}
    private Session view(Map<String,Object> s){var turn=turn(s);return new Session(string(s,"id"),string(s,"conversation_id"),string(s,"status"),number(s,"control_epoch"),string(s,"model"),consent(s),string(s,"provider_session_id"),number(s,"last_event_sequence"),turn==null?null:string(turn,"provider_item_id"),turn==null?null:string(turn,"message_id"),string(s,"error_code"),instant(s,"created_at"),instant(s,"connected_at"),instant(s,"ended_at"));}
    private static Consent consent(Map<String,Object> s){return new Consent(yes(s,"consent_microphone"),yes(s,"consent_camera"));}
    private Injection injection(Map<String,Object> i){return new Injection(string(i,"id"),string(i,"session_id"),string(i,"message_id"),number(i,"list_sequence"),string(i,"status"),string(i,"provider_item_id"),string(i,"error_code"),instant(i,"created_at"),instant(i,"acknowledged_at"));}
    private Command command(Map<String,Object> c){return new Command(string(c,"id"),string(c,"session_id"),string(c,"response_id"),string(c,"command_type"),string(c,"status"),string(c,"error_code"));}
    private List<Context> context(Map<String,Object> s){var rows=store.rows("SELECT sender_type,parts_json FROM ai_message WHERE conversation_id=? AND seq<=? AND (sender_type='user' OR (sender_type IN ('assistant','support_agent','expert') AND status='completed')) ORDER BY seq DESC LIMIT 40",s.get("conversation_id"),s.get("context_seq"));List<Context> result=new ArrayList<>();int budget=64000;
        for(var m:rows){StringBuilder text=new StringBuilder();for(JsonNode p:decode(string(m,"parts_json"))){if("text".equals(p.path("type").asText()))text.append(p.path("text").asText()).append('\n');else text.append("[Previous non-text message content omitted]\n");}if(text.isEmpty())continue;String sender=string(m,"sender_type");if("support_agent".equals(sender)||"expert".equals(sender))text.insert(0,"[Previous human reply]\n");String content=text.substring(0,Math.min(budget,text.length()));result.add(new Context("user".equals(sender)?"user":"assistant",content));budget-=content.length();if(budget<=0)break;}
        Collections.reverse(result);return result;
    }
    private static void validate(ProviderEvent e){
        if(e==null||e.sequence()==null||e.sequence()<1||!safe(e.providerEventId(),200)||e.type()==null)throw problem(400,"INVALID_PROVIDER_EVENT");
        Set<String> allowed=switch(e.type()){
            case "session.ready" -> Set.of("providerSessionId");case "input.committed" -> Set.of("providerItemId");case "input.transcript" -> Set.of("providerItemId","text");case "response.started" -> Set.of("responseId");case "response.canceled" -> Set.of("responseId","usage");case "response.delta" -> Set.of("responseId","text");case "response.completed" -> Set.of("responseId","text","finishReason","usage");case "injection.ack" -> Set.of("injectionId","providerItemId");case "injection.failed" -> Set.of("injectionId","errorCode");case "command.ack" -> Set.of("commandId","responseId");case "session.error" -> Set.of("errorCode");default -> throw problem(400,"INVALID_PROVIDER_EVENT");};
        Map<String,Object> values=new HashMap<>();values.put("providerSessionId",e.providerSessionId());values.put("providerItemId",e.providerItemId());values.put("responseId",e.responseId());values.put("injectionId",e.injectionId());values.put("commandId",e.commandId());values.put("text",e.text());values.put("finishReason",e.finishReason());values.put("usage",e.usage());values.put("errorCode",e.errorCode());
        for(var value:values.entrySet())if(value.getValue()!=null&&!allowed.contains(value.getKey()))throw problem(400,"UNEXPECTED_PROVIDER_EVENT_FIELD");
        for(String field:allowed){if(Set.of("usage").contains(field))continue;if(values.get(field)==null)throw problem(400,"MISSING_PROVIDER_EVENT_FIELD");}
        if(e.providerSessionId()!=null)providerId(e.providerSessionId());if(e.providerItemId()!=null)providerId(e.providerItemId());if(e.responseId()!=null)providerId(e.responseId());if(e.injectionId()!=null)validId(e.injectionId());if(e.commandId()!=null)validId(e.commandId());
        if(e.text()!=null&&(e.text().length()>("response.completed".equals(e.type())?128000:16000)||("response.delta".equals(e.type())&&e.text().isEmpty())))throw problem(400,"INVALID_EVENT_TEXT");
        if(e.finishReason()!=null&&!FINISH.contains(e.finishReason()))throw problem(400,"INVALID_FINISH_REASON");
        if(e.usage()!=null&&(e.usage().inputTokens()==null||e.usage().outputTokens()==null||e.usage().totalTokens()==null||e.usage().inputTokens()<0||e.usage().outputTokens()<0||e.usage().totalTokens()<0))throw problem(400,"INVALID_USAGE");
        if("session.error".equals(e.type())&&!SESSION_ERRORS.contains(e.errorCode()))throw problem(400,"INVALID_ERROR_CODE");if("injection.failed".equals(e.type())&&!INJECTION_ERRORS.contains(e.errorCode()))throw problem(400,"INVALID_ERROR_CODE");
    }
    private static void actor(Long actor){if(actor==null||actor<=0)throw problem(401,"AUTHENTICATION_REQUIRED");}
    private static void consent(Consent consent){if(consent==null||!Boolean.TRUE.equals(consent.microphone()))throw problem(400,"MICROPHONE_CONSENT_REQUIRED");}
    private static void client(String value){if(value==null||!value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}"))throw problem(400,"INVALID_CLIENT_ID");}
    private static boolean safe(String value,int limit){return value!=null&&value.matches("[A-Za-z0-9_.:-]{1,"+limit+"}");}
    private static void providerId(String value){if(!safe(value,200))throw problem(400,"INVALID_PROVIDER_ID");}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException("Cannot encode realtime ledger");}}
    private JsonNode decode(String value){try{return json.readTree(value);}catch(Exception ex){throw new IllegalStateException("Invalid realtime ledger");}}
}
