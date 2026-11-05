package xiaozhi.modules.ai;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import xiaozhi.modules.ai.AiModels.*;
import xiaozhi.modules.conversation.ConversationProblem;

/** Actual per-provider-request reports; absent usage stays unknown, never zero-filled. */
public final class AiProviderUsageStore {
    private final JdbcTemplate db;
    public AiProviderUsageStore(JdbcTemplate db) { this.db=db; }

    public void validate(List<ProviderRequest> rounds,Usage aggregate,Integer toolCount) {
        if(rounds==null) {
            if(toolCount!=null)throw problem();
            return;
        }
        validateReport(rounds);
        if(toolCount==null || toolCount<0 || toolCount>6)throw problem();
        if(rounds.isEmpty()) {if(aggregate!=null || toolCount!=0)throw problem();return;}
        long inputs=0,outputs=0,totals=0,audio=0;boolean complete=true,hasAudio=false;
        for(int i=0;i<rounds.size();i++) {
            ProviderRequest r=rounds.get(i);
            if(r.usage()==null) {complete=false;continue;}
            try {
                inputs=Math.addExact(inputs,r.usage().inputTokens()); outputs=Math.addExact(outputs,r.usage().outputTokens());
                totals=Math.addExact(totals,r.usage().totalTokens());
                if(r.usage().inputAudioTokens()!=null) {hasAudio=true;audio=Math.addExact(audio,r.usage().inputAudioTokens());}
            } catch(ArithmeticException overflow) {throw problem();}
        }
        if(!complete) {if(aggregate!=null)throw problem();return;}
        if(aggregate==null || aggregate.inputTokens()!=inputs || aggregate.outputTokens()!=outputs || aggregate.totalTokens()!=totals
                || (hasAudio ? aggregate.inputAudioTokens()==null || aggregate.inputAudioTokens()!=audio : aggregate.inputAudioTokens()!=null))throw problem();
    }

    /** A prefix of finalized requests only; no pending zero/unknown placeholder may replace a row. */
    public void validateReport(List<ProviderRequest> rounds){
        if(rounds==null||rounds.size()>4)throw problem();
        for(int i=0;i<rounds.size();i++){
            ProviderRequest r=rounds.get(i);
            if(r==null||r.round()==null||r.round()!=i+1||r.providerRequestId()!=null&&!r.providerRequestId().matches("[A-Za-z0-9_.:/-]{1,200}"))throw problem();
            if(r.finishReason()!=null&&!Set.of("stop","length","content_filter","tool_calls","unknown").contains(r.finishReason()))throw problem();
            AiExecutionService.validateUsage(r.usage());Usage u=r.usage();
            if(u!=null&&(u.inputTokens()>1000000000L||u.outputTokens()>1000000000L||u.totalTokens()>1000000000L))throw problem();
        }
    }

    /** Caller holds the run row lock; ordinary terminal and historical audit share exact idempotency. */
    public void record(String runId,int attempt,String model,List<ProviderRequest> rounds) {
        if(rounds==null)return;
        validateReport(rounds);
        for(ProviderRequest r:rounds) {
            Usage u=r.usage();
            var old=db.queryForList("SELECT model,provider_request_id,finish_reason,input_tokens,output_tokens,total_tokens,input_audio_tokens FROM ai_provider_request_usage WHERE run_id=? AND attempt=? AND round_number=?",runId,attempt,r.round());
            if(!old.isEmpty()){
                var row=old.getFirst();if(!Objects.equals(row.get("model"),model)||!Objects.equals(row.get("provider_request_id"),r.providerRequestId())||!Objects.equals(row.get("finish_reason"),r.finishReason())
                        ||!sameNumber(row,"input_tokens",u==null?null:u.inputTokens())||!sameNumber(row,"output_tokens",u==null?null:u.outputTokens())
                        ||!sameNumber(row,"total_tokens",u==null?null:u.totalTokens())||!sameNumber(row,"input_audio_tokens",u==null?null:u.inputAudioTokens()))
                    throw new ConversationProblem(409,"PROVIDER_REQUEST_USAGE_CONFLICT");
                continue;
            }
            db.update("""
                    INSERT INTO ai_provider_request_usage(run_id,attempt,round_number,model,provider_request_id,finish_reason,
                    input_tokens,output_tokens,total_tokens,input_audio_tokens)
                    VALUES(?,?,?,?,?,?,?,?,?,?)
                    """,runId,attempt,r.round(),model,r.providerRequestId(),r.finishReason(),u==null?null:u.inputTokens(),
                    u==null?null:u.outputTokens(),u==null?null:u.totalTokens(),u==null?null:u.inputAudioTokens());
        }
    }
    /** A terminal aggregate cannot omit provider requests that were already independently audited. */
    public void recordTerminal(String runId,int attempt,String model,List<ProviderRequest> rounds){
        long observed=db.queryForObject("SELECT COUNT(*) FROM ai_provider_request_usage WHERE run_id=? AND attempt=?",Long.class,runId,attempt);
        if(observed>(rounds==null?0:rounds.size()))throw new ConversationProblem(409,"PROVIDER_REQUEST_USAGE_CONFLICT");
        record(runId,attempt,model,rounds);
    }
    private static boolean sameNumber(Map<String,Object> row,String key,Long value){return row.get(key)==null?value==null:value!=null&&((Number)row.get(key)).longValue()==value;}
    private static ConversationProblem problem() {return new ConversationProblem(400,"INVALID_PROVIDER_REQUEST_USAGE");}
}
