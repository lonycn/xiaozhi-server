package xiaozhi.modules.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import xiaozhi.modules.ai.AiModels.UsageAudit;
import xiaozhi.modules.conversation.ConversationProblem;

/** Historical attempt capability authorizes scalar cost audit only, never content or state transitions. */
@Service
public class AiProviderUsageAudit {
    private final JdbcTemplate db;private final TransactionTemplate tx;private final AiProviderUsageStore usage;
    public AiProviderUsageAudit(JdbcTemplate db,PlatformTransactionManager manager){this.db=db;usage=new AiProviderUsageStore(db);tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);}
    /** Caller owns the current claim transaction; a failed claim rolls this insert back too. */
    public void dispatched(String run,int attempt,long epoch,String token,String model){
        check(run,attempt,token);if(model==null||!model.matches("[A-Za-z0-9_.:-]{1,160}"))throw problem(400,"INVALID_PROVIDER_DISPATCH");
        db.update("INSERT INTO ai_provider_attempt_dispatch(run_id,attempt,lease_hash,control_epoch,model) VALUES(?,?,?,?,?)",run,attempt,hash(token),epoch,model);
    }
    public Map<String,Boolean> record(String run,UsageAudit body){
        if(body==null)throw problem(400,"INVALID_PROVIDER_USAGE_AUDIT");check(run,body.attempt(),body.leaseToken());
        if(body.providerRequests()==null||body.providerRequests().isEmpty())throw problem(400,"INVALID_PROVIDER_USAGE_AUDIT");usage.validateReport(body.providerRequests());
        return tx.execute(status->{
            // Serialize with ordinary complete/fail/claim. Read the ID only: no historical
            // source authorization, message text, result or current lease is exposed here.
            if(db.queryForList("SELECT id FROM ai_run WHERE id=? FOR UPDATE",run).size()!=1)throw problem(409,"PROVIDER_USAGE_ATTEMPT_INVALID");
            var rows=db.queryForList("SELECT lease_hash,model FROM ai_provider_attempt_dispatch WHERE run_id=? AND attempt=?",run,body.attempt());
            if(rows.size()!=1||!MessageDigest.isEqual(hash(body.leaseToken()).getBytes(StandardCharsets.US_ASCII),((String)rows.getFirst().get("lease_hash")).getBytes(StandardCharsets.US_ASCII)))throw problem(409,"PROVIDER_USAGE_ATTEMPT_INVALID");
            usage.record(run,body.attempt(),(String)rows.getFirst().get("model"),body.providerRequests());return Map.of("recorded",true);
        });
    }
    private static void check(String run,Integer attempt,String token){if(run==null||!run.matches("[a-f0-9]{32}")||attempt==null||attempt<1||attempt>3||token==null||!token.matches("[a-f0-9]{64}"))throw problem(400,"INVALID_PROVIDER_USAGE_AUDIT");}
    private static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private static ConversationProblem problem(int status,String code){return new ConversationProblem(status,code);}
}
