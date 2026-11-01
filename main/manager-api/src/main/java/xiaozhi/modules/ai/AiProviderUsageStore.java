package xiaozhi.modules.ai;

import java.util.List;
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
        if(rounds.size()>4 || toolCount==null || toolCount<0 || toolCount>6)throw problem();
        if(rounds.isEmpty()) {if(aggregate!=null || toolCount!=0)throw problem();return;}
        long inputs=0,outputs=0,totals=0,audio=0;boolean complete=true,hasAudio=false;
        for(int i=0;i<rounds.size();i++) {
            ProviderRequest r=rounds.get(i);
            if(r==null || r.round()==null || r.round()!=i+1 || r.providerRequestId()!=null &&
                    (!r.providerRequestId().matches("[A-Za-z0-9_.:/-]{1,200}")))throw problem();
            if(r.finishReason()!=null && !Set.of("stop","length","content_filter","tool_calls","unknown").contains(r.finishReason()))throw problem();
            AiExecutionService.validateUsage(r.usage());
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

    /** Caller holds the active run/lease transaction; one callback inserts all observed rounds atomically. */
    public void record(String runId,int attempt,String model,List<ProviderRequest> rounds) {
        if(rounds==null)return;
        for(ProviderRequest r:rounds) {
            Usage u=r.usage();
            db.update("""
                    INSERT INTO ai_provider_request_usage(run_id,attempt,round_number,model,provider_request_id,finish_reason,
                    input_tokens,output_tokens,total_tokens,input_audio_tokens)
                    VALUES(?,?,?,?,?,?,?,?,?,?)
                    """,runId,attempt,r.round(),model,r.providerRequestId(),r.finishReason(),u==null?null:u.inputTokens(),
                    u==null?null:u.outputTokens(),u==null?null:u.totalTokens(),u==null?null:u.inputAudioTokens());
        }
    }
    private static ConversationProblem problem() {return new ConversationProblem(400,"INVALID_PROVIDER_REQUEST_USAGE");}
}
