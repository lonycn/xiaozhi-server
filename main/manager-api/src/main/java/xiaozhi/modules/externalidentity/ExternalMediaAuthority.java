package xiaozhi.modules.externalidentity;

import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import java.sql.Connection;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import com.alibaba.druid.pool.DruidDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.context.request.*;
import xiaozhi.common.jdbc.TimestampColumnMapRowMapper;
import xiaozhi.modules.externalidentity.ExternalModels.Principal;

/** Source authorization is read on a fresh, unlocked snapshot. Never lock a source after a conversation. */
@Component
public class ExternalMediaAuthority {
    private static final ExecutorService CHECKOUTS=new ThreadPoolExecutor(0,4,30,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{Thread t=new Thread(r,"external-media-auth-checkout");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final JdbcTemplate db;private final ExternalSettings settings;private final Environment environment;
    public ExternalMediaAuthority(JdbcTemplate db,ExternalSettings settings,Environment environment,PlatformTransactionManager manager){
        this.db=db;this.settings=settings;this.environment=environment;
    }
    public boolean enabled(){return environment.getProperty("features.external-realtime-media-enabled",Boolean.class,false);}
    public Principal caller(){var attributes=RequestContextHolder.getRequestAttributes();return attributes instanceof ServletRequestAttributes servlet && servlet.getRequest().getAttribute(ExternalIdentityService.ATTRIBUTE) instanceof Principal principal?principal:null;}
    public Principal checkedCaller(long actor){var p=caller();if(p!=null){if(p.userId()!=actor)throw denied();validate(p.sourceId(),p.tokenId(),actor);}return p;}
    public void assertCustomer(Map<String,Object> session,long actor,String customerColumn){
        Principal p=caller();long customer=number(session,customerColumn);
        if(p!=null && actor!=customer)throw denied(); // No external principal can act as staff or a transfer target.
        if(actor!=customer)return;
        long source=number(session,"external_source_id"),token=number(session,"external_token_id");
        if(source==0){if(p!=null){
            // A staff invitation carries no customer media capability until explicit acceptance.
            if(!customerColumn.equals("customer_user_id") || !"invited".equals(session.get("status")) || number(session,"inviter_user_id")==customer || p.userId()!=actor)throw denied();
            validate(p.sourceId(),p.tokenId(),actor);
        }return;}
        if(p==null || p.sourceId()!=source || p.tokenId()!=token || p.userId()!=actor)throw denied();
        validate(source,token,customer);
    }
    public Instant validate(Map<String,Object> session,String customerColumn){long source=number(session,"external_source_id");return source==0?null:validate(source,number(session,"external_token_id"),number(session,customerColumn));}
    public Instant validate(long source,long token,long actor){
        // The independent read must never wait behind all outer transactions holding the shared pool.
        // Production Druid checkout has an explicit deadline. Unknown pools fail closed until adapted.
        try(Connection connection=borrow()){
            boolean auto=connection.getAutoCommit(),readOnly=connection.isReadOnly();int isolation=connection.getTransactionIsolation();
            try{connection.setAutoCommit(true);connection.setReadOnly(true);connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            var current=new JdbcTemplate(new SingleConnectionDataSource(connection,true));current.setQueryTimeout(2);
            if(!enabled())throw new ExternalProblem(403,"EXTERNAL_MEDIA_DISABLED");
            settings.keys();
            var values=current.query("SELECT t.expires_at,t.revoked_at,s.expires_at AS source_expires_at,s.revoked_at AS source_revoked_at,s.issuer,p.user_id,CAST(u.status AS SIGNED) AS status,CAST(u.super_admin AS SIGNED) AS super_admin FROM external_access_token t JOIN external_source_session s ON s.id=t.source_id JOIN external_tenant_principal p ON p.id=t.principal_id JOIN sys_user u ON u.id=p.user_id WHERE t.id=? AND t.source_id=?",new TimestampColumnMapRowMapper(),token,source);
            if(values.size()!=1)throw denied();var row=values.getFirst();Instant now=Instant.now();
            Instant expiry=instant(row,"expires_at"),sourceExpiry=instant(row,"source_expires_at");
            if(number(row,"user_id")!=actor || number(row,"status")!=1 || number(row,"super_admin")!=0 || row.get("revoked_at")!=null || row.get("source_revoked_at")!=null || !expiry.isAfter(now) || !sourceExpiry.isAfter(now) || !settings.issuerActive((String)row.get("issuer")))throw denied();
            return expiry.isBefore(sourceExpiry)?expiry:sourceExpiry;
            }finally{connection.setReadOnly(readOnly);connection.setTransactionIsolation(isolation);connection.setAutoCommit(auto);}
        }catch(ExternalProblem failure){throw failure;}catch(Exception unavailable){throw new ExternalProblem(503,"EXTERNAL_MEDIA_AUTHORIZATION_UNAVAILABLE");}
    }
    private Connection borrow()throws java.sql.SQLException {
        DataSource source=db.getDataSource();
        if(source instanceof DruidDataSource pool)return boundedBorrow(pool);
        if(source!=null && source.isWrapperFor(DruidDataSource.class))return boundedBorrow(source.unwrap(DruidDataSource.class));
        if(source instanceof DriverManagerDataSource)return source.getConnection(); // Isolated unpooled test harness.
        throw new java.sql.SQLException("Unsupported bounded authorization datasource");
    }
    private Connection boundedBorrow(DruidDataSource pool)throws java.sql.SQLException {
        // Druid 1.2.20 can spin at maxActive before its own maxWait check. Enforce our deadline outside it.
        AtomicBoolean abandoned=new AtomicBoolean();AtomicReference<Connection> owned=new AtomicReference<>();Future<Connection> pending;
        try{pending=CHECKOUTS.submit(()->{Connection connection=pool.getConnection(1000);owned.set(connection);if(abandoned.get()){Connection late=owned.getAndSet(null);if(late!=null)late.close();throw new java.sql.SQLException("Authorization checkout abandoned");}return connection;});}
        catch(RejectedExecutionException saturated){throw new java.sql.SQLException("Authorization checkout busy");}
        try{Connection result=pending.get(1500,TimeUnit.MILLISECONDS);owned.compareAndSet(result,null);return result;}
        catch(Exception timeout){abandoned.set(true);pending.cancel(true);Connection late=owned.getAndSet(null);if(late!=null)try{late.close();}catch(java.sql.SQLException ignored){}if(timeout instanceof InterruptedException)Thread.currentThread().interrupt();throw new java.sql.SQLException("Authorization checkout unavailable");}
    }
    private static long number(Map<String,Object> row,String key){Object v=row.get(key);return v instanceof Number n?n.longValue():v==null?0:Long.parseLong(v.toString());}
    private static Instant instant(Map<String,Object> row,String key){Object v=row.get(key);if(v instanceof java.sql.Timestamp t)return t.toInstant();if(v instanceof Instant i)return i;throw denied();}
    private static ExternalProblem denied(){return new ExternalProblem(403,"EXTERNAL_MEDIA_BINDING_DENIED");}
}
