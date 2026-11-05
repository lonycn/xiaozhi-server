package xiaozhi.modules.airuntime;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import com.alibaba.druid.pool.DruidDataSource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import xiaozhi.modules.conversation.ConversationProblem;

/**
 * Cross-flow admission, not a monetary billing system. Resource creators acquire their existing
 * account/conversation/resource locks first, then this gate. This class NEVER locks a canonical
 * resource, including during reconciliation. Reservations and resource changes commit together.
 */
@Service
public class AiRuntimeControl implements AutoCloseable {
    public enum Flow { AI, REALTIME, MATERIAL, SPEECH }
    public record Limits(long userMaxActive,long serviceMaxActive,long userHourlyRequests,long serviceHourlyRequests) {}
    public record Scope(long active,long hourlyRequests,long remainingActive,long remainingHourlyRequests) {}
    public record Usage(Long knownTokens,long tokenReports,long unknownTokenReports,Long knownSpeechCharacters,long speechReports,long unknownSpeechReports) {}
    public record Snapshot(boolean enabled,long windowSeconds,Limits limits,Scope user,Scope service,Usage usage,boolean serviceLimited,String guarantee) {}
    private record Resource(String key,long owner,Instant created,boolean active,boolean original) {}
    private record Admission(long sequence,String key,long owner,Instant created,Instant expires,boolean active,boolean original) {}
    private final JdbcTemplate db;
    private final Environment env;
    private final TransactionTemplate read;
    private final JdbcTemplate observer;
    private final DruidDataSource observerPool;
    public AiRuntimeControl(JdbcTemplate db,Environment env,PlatformTransactionManager manager) {
        this.db=db;this.env=env;
        DataSource source=db.getDataSource();
        while(source instanceof DelegatingDataSource delegate&&delegate.getTargetDataSource()!=null)source=delegate.getTargetDataSource();
        if(source instanceof DruidDataSource main){
            // A suspended outer transaction still owns its main-pool connection. Reusing that pool
            // can exhaust it with gate waiters. Reserve a genuinely separate, bounded observer pool.
            observerPool=main.cloneDruidDataSource();observerPool.setName("ai-runtime-observer");
            observerPool.setInitialSize(0);observerPool.setMinIdle(0);observerPool.setMaxActive(2);
            observerPool.setMaxWait(1000);observerPool.setMaxWaitThreadCount(2);
            observerPool.setDefaultReadOnly(true);observerPool.setDefaultAutoCommit(true);
            observerPool.setDefaultTransactionIsolation(java.sql.Connection.TRANSACTION_READ_COMMITTED);
            observerPool.setQueryTimeout(3);observerPool.setTestOnBorrow(false);
            observer=new JdbcTemplate(observerPool);read=new TransactionTemplate(new DataSourceTransactionManager(observerPool));
        }else if(source instanceof DriverManagerDataSource){
            // Explicitly supported unpooled fixture path: each read gets a separate physical connection.
            observerPool=null;observer=new JdbcTemplate(source);read=new TransactionTemplate(new DataSourceTransactionManager(source));
        }else{
            // Never silently reuse an unknown production pool and reintroduce the wait cycle.
            observerPool=null;observer=null;read=null;
        }
        if(read!=null){read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            read.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);read.setReadOnly(true);read.setTimeout(3);}
    }
    @jakarta.annotation.PreDestroy @Override public void close(){if(observerPool!=null)observerPool.close();}
    private <T>T observe(java.util.function.Supplier<T> action){
        if(read==null)throw problem(503,"AI_RUNTIME_OBSERVER_UNSUPPORTED");
        try{return read.execute(t->action.get());}
        catch(CannotCreateTransactionException|org.springframework.dao.TransientDataAccessException|org.springframework.dao.RecoverableDataAccessException unavailable){throw problem(503,"AI_RUNTIME_OBSERVER_UNAVAILABLE");}
    }

    public boolean enabled(){return env.getProperty("ai.runtime-control.enabled",Boolean.class,false);}
    public Limits limits(){return new Limits(limit("user-max-active",3),limit("service-max-active",32),limit("user-hourly-requests",60),limit("service-hourly-requests",1000));}
    private long limit(String key,long fallback){long n=env.getProperty("ai.runtime-control."+key,Long.class,fallback);if(n<1||n>1000000)throw new IllegalStateException("Invalid AI runtime limit: "+key);return n;}
    public void admit(Flow flow,String resource,long owner){admit(flow,resource,owner,false);}
    /** An explicit ordinary-run retry consumes a new request, even though it reuses the run ID. */
    public void admitRetry(Flow flow,String resource,long owner){admit(flow,resource,owner,true);}
    private void admit(Flow flow,String resource,long owner,boolean retry){
        if(!enabled())return;requiredTransaction();validate(flow,resource,owner);gate();
        Instant now=now();List<Admission> admissions=admissions(true);List<Resource> canonical=canonical();
        reconcile(admissions,canonical,now);
        admissions=admissions(true);String key=key(flow.name(),resource);
        for(var a:admissions)if(a.key.equals(key)&&a.active){if(a.owner!=owner)throw problem(409,"AI_RUNTIME_RESOURCE_CONFLICT");return;}
        Counts counts=counts(admissions,canonical,now,owner);Limits limits=limits();
        if(counts.userActive>=limits.userMaxActive)throw problem(429,"AI_RUNTIME_USER_CONCURRENCY");
        if(counts.serviceActive>=limits.serviceMaxActive)throw problem(429,"AI_RUNTIME_SERVICE_CONCURRENCY");
        if(counts.userRequests>=limits.userHourlyRequests)throw problem(429,"AI_RUNTIME_USER_HOURLY_BUDGET");
        if(counts.serviceRequests>=limits.serviceHourlyRequests)throw problem(429,"AI_RUNTIME_SERVICE_HOURLY_BUDGET");
        // This expiry only retires missing resources. A canonical active resource is never assumed
        // stopped just because a wall-clock TTL elapsed; its existing flow deadline is authoritative.
        db.update("INSERT INTO ai_runtime_admission(flow,resource_id,owner_user_id,request_kind,expires_at) VALUES(?,?,?,?,DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 1 HOUR))",flow.name(),resource,owner,retry?"retry":"create");
        changes().live.add(key);changes().released.remove(key);
    }
    public void release(Flow flow,String resource){
        if(!enabled())return;requiredTransaction();if(flow==null||resource==null||!resource.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("Invalid runtime resource");
        gate();db.update("UPDATE ai_runtime_admission SET active=0,released_at=CURRENT_TIMESTAMP(3) WHERE flow=? AND resource_id=? AND active=1",flow.name(),resource);
        changes().released.add(key(flow.name(),resource));changes().live.remove(key(flow.name(),resource));
    }
    /** Read-only projection: no resource IDs, prompts, provider credentials, or other owners. */
    public Snapshot snapshot(Long actor){
        if(!enabled())throw problem(404,"AI_RUNTIME_CONTROL_DISABLED");
        if(actor==null||actor<=0)throw problem(401,"AUTHENTICATION_REQUIRED");
        return observe(()->{
            if(observer.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=? AND status=1",Long.class,actor)!=1)throw problem(401,"AUTHENTICATION_REQUIRED");
            boolean administrator=observer.queryForObject("SELECT super_admin FROM sys_user WHERE id=?",Integer.class,actor)==1;
            Instant now=now(observer);var all=admissions(observer,false);var resources=canonicalInReadTransaction();Counts n=counts(all,resources,now,actor);Limits l=limits();
            return new Snapshot(true,3600,l,new Scope(n.userActive,n.userRequests,Math.max(0,l.userMaxActive-n.userActive),Math.max(0,l.userHourlyRequests-n.userRequests)),administrator?new Scope(n.serviceActive,n.serviceRequests,Math.max(0,l.serviceMaxActive-n.serviceActive),Math.max(0,l.serviceHourlyRequests-n.serviceRequests)):null,usage(actor),n.serviceActive>=l.serviceMaxActive||n.serviceRequests>=l.serviceHourlyRequests,"concurrency_and_request_admission_not_total_cost");});
    }
    private void gate(){db.queryForObject("SELECT id FROM ai_runtime_gate WHERE id=1 FOR UPDATE",Integer.class);}
    private void requiredTransaction(){if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("AI admission requires the resource transaction");}
    private static void validate(Flow flow,String resource,long owner){if(flow==null||resource==null||!resource.matches("[a-f0-9]{32}")||owner<=0)throw new IllegalArgumentException("Invalid runtime resource");}
    private Instant now(){return now(db);}
    private Instant now(JdbcTemplate sql){return sql.queryForObject("SELECT CURRENT_TIMESTAMP(3)",Timestamp.class).toInstant();}
    private List<Admission> admissions(boolean lock){return admissions(db,lock);}
    private List<Admission> admissions(JdbcTemplate sql,boolean lock){
        // FOR UPDATE is a current read even if the caller already opened an old RR snapshot.
        return sql.query("SELECT sequence,flow,resource_id,owner_user_id,created_at,expires_at,active,request_kind FROM ai_runtime_admission WHERE active=1 OR created_at>DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 HOUR)"+(lock?" FOR UPDATE":""),(r,n)->new Admission(r.getLong("sequence"),key(r.getString("flow"),r.getString("resource_id")),r.getLong("owner_user_id"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("expires_at").toInstant(),r.getBoolean("active"),"create".equals(r.getString("request_kind"))));
    }
    private List<Resource> canonical(){return observe(()->canonicalInReadTransaction());}
    private List<Resource> canonicalInReadTransaction(){
        List<Resource> all=new ArrayList<>();
        resources(all,"AI","ai_run r JOIN ai_conversation c ON c.id=r.conversation_id","c.owner_user_id","r.status IN ('queued','running','streaming')");
        resources(all,"REALTIME","ai_realtime_session r","r.owner_user_id","r.status IN ('pending','connecting','active')");
        resources(all,"MATERIAL","ai_realtime_material r JOIN ai_realtime_session s ON s.id=r.session_id","s.owner_user_id","r.status IN ('queued','dispatched','prepared') AND s.status IN ('pending','connecting','active')");
        resources(all,"SPEECH","ai_answer_speech_job r","r.owner_user_id","r.status IN ('queued','synthesizing')");
        return all;
    }
    private void resources(List<Resource> target,String flow,String table,String owner,String active){
        String match="a.flow='"+flow+"' AND a.resource_id=r.id";
        String sql="SELECT r.id,"+owner+" AS owner_id,r.created_at,("+active+") AS is_active,NOT EXISTS(SELECT 1 FROM ai_runtime_admission a WHERE "+match+" AND a.request_kind='create') AS original FROM "+table+" WHERE ("+active+") OR r.created_at>DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 HOUR) OR EXISTS(SELECT 1 FROM ai_runtime_admission a WHERE "+match+" AND a.active=1)";
        target.addAll(observer.query(sql,(r,n)->new Resource(key(flow,r.getString("id")),r.getLong("owner_id"),r.getTimestamp("created_at").toInstant(),r.getBoolean("is_active"),r.getBoolean("original"))));
    }
    private void reconcile(List<Admission> admissions,List<Resource> canonical,Instant now){
        Map<String,Resource> resources=new HashMap<>();for(var r:canonical)resources.put(r.key,r);
        for(var a:admissions)if(a.active&&!changes().live.contains(a.key)){var r=resources.get(a.key);if(r!=null&&!r.active||r==null&&!a.expires.isAfter(now))db.update("UPDATE ai_runtime_admission SET active=0,released_at=CURRENT_TIMESTAMP(3) WHERE sequence=? AND active=1",a.sequence);}
    }
    // The RC observer cannot see this transaction's own terminal/retry writes. A synchronization
    // carries only those local changes, suspends with the resource transaction, and disappears on
    // commit/rollback. It prevents a false full gate while atomically replacing an active run.
    private static final class Changes implements TransactionSynchronization {
        final Set<String> released=new HashSet<>(),live=new HashSet<>();
    }
    private Changes changes(){
        if(!TransactionSynchronizationManager.isSynchronizationActive())return new Changes();
        for(var synchronization:TransactionSynchronizationManager.getSynchronizations())if(synchronization instanceof Changes c)return c;
        Changes value=new Changes();TransactionSynchronizationManager.registerSynchronization(value);return value;
    }
    private record Counts(long userActive,long serviceActive,long userRequests,long serviceRequests) {}
    private Counts counts(List<Admission> admissions,List<Resource> canonical,Instant now,long actor){
        Map<String,Long> active=new HashMap<>();Map<String,Resource> resources=new HashMap<>();Set<String> originals=new HashSet<>();long userRequests=0,serviceRequests=0;Instant hour=now.minusSeconds(3600);
        for(var r:canonical){resources.put(r.key,r);if(r.active&&!changes().released.contains(r.key))active.put(r.key,r.owner);}
        for(var a:admissions){if(a.original)originals.add(a.key);if(a.created.isAfter(hour)){serviceRequests++;if(a.owner==actor)userRequests++;}var r=resources.get(a.key);if(a.active&&!changes().released.contains(a.key)&&(changes().live.contains(a.key)||(r==null?a.expires.isAfter(now):r.active)))active.put(a.key,a.owner);}
        for(var r:canonical)if(r.original&&!originals.contains(r.key)&&r.created.isAfter(hour)){serviceRequests++;if(r.owner==actor)userRequests++;}
        return new Counts(active.values().stream().filter(id->id==actor).count(),active.size(),userRequests,serviceRequests);
    }
    private Usage usage(long actor){
        // Each provider-request ledger is counted once. Model-token totals and TTS characters are
        // deliberately separate. Missing reports are not fabricated as zero or as a monetary cap.
        String tokenSql="SELECT SUM(total_tokens) AS known,COUNT(*) AS reports,COALESCE(SUM(total_tokens IS NULL),0) AS unknowns FROM ("
            +"SELECT u.total_tokens FROM ai_provider_request_usage u JOIN ai_run r ON r.id=u.run_id JOIN ai_conversation c ON c.id=r.conversation_id WHERE c.owner_user_id=? AND u.created_at>DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 HOUR) UNION ALL "
            +"SELECT u.total_tokens FROM ai_realtime_response_usage u JOIN ai_realtime_provider_response p ON p.id=u.response_row_id JOIN ai_realtime_session s ON s.id=p.session_id WHERE s.owner_user_id=? AND u.created_at>DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 HOUR) UNION ALL "
            +"SELECT u.total_tokens FROM ai_realtime_perception_usage u JOIN ai_realtime_material m ON m.id=u.material_id JOIN ai_realtime_session s ON s.id=m.session_id WHERE s.owner_user_id=? AND u.created_at>DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 HOUR)) reports";
        var tokens=observer.queryForMap(tokenSql,actor,actor,actor);var speech=observer.queryForMap("SELECT SUM(u.characters) AS known,COUNT(*) AS reports,COALESCE(SUM(u.characters IS NULL),0) AS unknowns FROM ai_answer_speech_usage u JOIN ai_answer_speech_job j ON j.id=u.job_id WHERE j.owner_user_id=? AND u.created_at>DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 HOUR)",actor);
        return new Usage(nullable(tokens.get("known")),number(tokens,"reports"),number(tokens,"unknowns"),nullable(speech.get("known")),number(speech,"reports"),number(speech,"unknowns"));
    }
    private static Long nullable(Object value){return value==null?null:((Number)value).longValue();}
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static String key(String flow,String id){return flow+":"+id;}
    private static ConversationProblem problem(int status,String code){return new ConversationProblem(status,code);}
}
