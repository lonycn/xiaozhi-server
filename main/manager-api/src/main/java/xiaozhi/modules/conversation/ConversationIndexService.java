package xiaozhi.modules.conversation;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import xiaozhi.modules.esp32integration.Esp32Policies;

/** Read-only index over existing ledgers. IDs retain their native namespace and write routes. */
@Service
public class ConversationIndexService {
    private final JdbcTemplate db;
    private final Esp32Policies policies;
    @Value("${conversation.ledger-timestamps-utc:false}")
    private boolean ledgerTimestampsUtc;
    public ConversationIndexService(JdbcTemplate db, Esp32Policies policies) { this.db=db; this.policies=policies; }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Item(String id, String rowKey, String kind, String source, String agentId, String agentName,
            String macAddress, String title, String state, Instant updatedAt, long messageCount) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Page(List<Item> items, String nextCursor, boolean hasMore) { }
    private static final String UNION = """
        SELECT c.id AS id, CONVERT(CONCAT('conversation:',c.id) USING utf8mb4) COLLATE utf8mb4_bin AS row_key,
          'conversation' AS kind, CASE WHEN s.id IS NOT NULL THEN 'hardware'
            WHEN origin.client_source IN ('app','web') THEN origin.client_source
            WHEN EXISTS (SELECT 1 FROM external_tenant_principal p WHERE p.user_id=c.owner_user_id) THEN 'app'
            ELSE 'web' END COLLATE utf8mb4_bin AS source,
          CONVERT(c.agent_id USING utf8mb4) COLLATE utf8mb4_bin AS agent_id,
          CONVERT(a.agent_name USING utf8mb4) COLLATE utf8mb4_bin AS agent_name,
          CONVERT(d.mac_address USING utf8mb4) COLLATE utf8mb4_bin AS mac_address,
          CONVERT(c.title USING utf8mb4) COLLATE utf8mb4_bin AS title,
          c.state AS state,c.updated_at AS updated_at,c.last_seq AS message_count
        FROM ai_conversation c LEFT JOIN ai_agent a ON a.id=CONVERT(c.agent_id USING utf8mb4) COLLATE utf8mb4_bin AND a.user_id=c.owner_user_id
        LEFT JOIN (SELECT conversation_id,CASE WHEN JSON_VALID(data_json)
            THEN JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.source')) END AS client_source
            FROM ai_conversation_event WHERE seq=1 AND event_type='conversation.created') origin ON origin.conversation_id=c.id
        LEFT JOIN esp32_device_session s ON s.conversation_id=c.id
        LEFT JOIN ai_device d ON d.id=CONVERT(s.device_id USING utf8mb4) COLLATE utf8mb4_bin AND d.user_id=c.owner_user_id AND d.agent_id=CONVERT(c.agent_id USING utf8mb4) COLLATE utf8mb4_bin
        WHERE c.owner_user_id=?
        UNION ALL
        SELECT h.session_id AS id,
          CONVERT(CONCAT('history:',h.agent_id,':',h.session_id) USING utf8mb4) COLLATE utf8mb4_bin AS row_key,
          'device_history' AS kind,'hardware' AS source,
          CONVERT(h.agent_id USING utf8mb4) COLLATE utf8mb4_bin AS agent_id,
          CONVERT(a.agent_name USING utf8mb4) COLLATE utf8mb4_bin AS agent_name,
          CONVERT(MIN(h.mac_address) USING utf8mb4) COLLATE utf8mb4_bin AS mac_address,
          CONVERT(MAX(t.title) USING utf8mb4) COLLATE utf8mb4_bin AS title,
          'READ_ONLY' AS state,
          CASE WHEN ?=1 THEN TIMESTAMPADD(HOUR,-8,MAX(h.created_at)) ELSE MAX(h.created_at) END AS updated_at,
          COUNT(*) AS message_count
        FROM ai_agent_chat_history h JOIN ai_agent a ON a.id=CONVERT(h.agent_id USING utf8mb4) COLLATE utf8mb4_bin
        LEFT JOIN (SELECT session_id,MAX(title) AS title FROM ai_agent_chat_title GROUP BY session_id) t ON t.session_id=CONVERT(h.session_id USING utf8mb4) COLLATE utf8mb4_bin AND NOT EXISTS (SELECT 1 FROM ai_agent_chat_history other_history WHERE other_history.session_id=h.session_id AND other_history.agent_id<>h.agent_id)
        WHERE a.user_id=? AND ?=1 AND (?='' OR h.mac_address=?)
        GROUP BY h.agent_id,h.session_id,a.agent_name
        """;
    public Page list(long owner, Map<String,String> params, boolean legacyAllowed) {
        if(owner<=0)throw new ConversationProblem(401,"UNAUTHORIZED");
        if(!Set.of("before","limit","source","agent_id","mac_address").containsAll(params.keySet()))throw bad();
        String source=params.getOrDefault("source","all"),agent=params.getOrDefault("agent_id",""),mac=params.getOrDefault("mac_address","");
        if(!Set.of("all","app","web","hardware").contains(source)||agent.length()>32||mac.length()>128||agent.chars().anyMatch(Character::isISOControl)||mac.chars().anyMatch(Character::isISOControl))throw bad();
        int limit;try{limit=Integer.parseInt(params.getOrDefault("limit","50"));}catch(NumberFormatException e){throw bad();}if(limit<1||limit>100)throw bad();
        // Normalize the old Beijing wall-clock values before sorting both ledgers together.
        List<Object> args=new ArrayList<>(List.of(owner,ledgerTimestampsUtc?1:0,owner,legacyAllowed?1:0,mac,mac));
        StringBuilder sql=new StringBuilder("SELECT * FROM ("+UNION+") idx WHERE 1=1");
        if(!source.equals("all")){sql.append(" AND source=?");args.add(source);}
        if(!agent.isEmpty()){sql.append(" AND agent_id=?");args.add(agent);}
        if(!mac.isEmpty()){sql.append(" AND mac_address=?");args.add(mac);}
        String before=params.getOrDefault("before","");
        if(!before.isEmpty()&&!before.equals("0")) {
            var cursor=decode(before);sql.append(" AND (updated_at<? OR (updated_at=? AND row_key<?))");
            Object timestamp=ledgerTimestampsUtc?LocalDateTime.ofInstant(cursor.time,ZoneOffset.UTC):Timestamp.from(cursor.time);
            args.add(timestamp);args.add(timestamp);args.add(cursor.key);
        }
        sql.append(" ORDER BY updated_at DESC,row_key DESC LIMIT ?");args.add(limit+1);
        List<Item> rows=db.query(sql.toString(),(rs,n)->new Item(rs.getString("id"),rs.getString("row_key"),rs.getString("kind"),rs.getString("source"),rs.getString("agent_id"),rs.getString("agent_name"),rs.getString("mac_address"),rs.getString("title"),rs.getString("state"),
                ledgerTimestampsUtc ? rs.getObject("updated_at",LocalDateTime.class).toInstant(ZoneOffset.UTC)
                        : rs.getTimestamp("updated_at").toInstant(),rs.getLong("message_count")),args.toArray());
        var scanned=rows.stream().limit(limit).toList();
        var items=scanned.stream().filter(row->!row.kind.equals("conversation")||!row.source.equals("hardware")||policies.mayRead(row.id,owner)).toList();
        return new Page(items,scanned.isEmpty()?before:encode(scanned.getLast()),rows.size()>limit);
    }
    public Map<String,Object> options(long owner,boolean legacyAllowed) {
        if(owner<=0)throw new ConversationProblem(401,"UNAUTHORIZED");
        if(!legacyAllowed)return Map.of("agents",List.of(),"devices",List.of());
        var agents=db.queryForList("SELECT id,agent_name FROM ai_agent WHERE user_id=? ORDER BY agent_name,id",owner);
        var devices=db.queryForList("""
          SELECT DISTINCT x.agent_id,x.mac_address FROM (
            SELECT CONVERT(h.agent_id USING utf8mb4) COLLATE utf8mb4_bin AS agent_id,CONVERT(h.mac_address USING utf8mb4) COLLATE utf8mb4_bin AS mac_address FROM ai_agent_chat_history h JOIN ai_agent a ON a.id=CONVERT(h.agent_id USING utf8mb4) COLLATE utf8mb4_bin WHERE a.user_id=?
            UNION SELECT CONVERT(d.agent_id USING utf8mb4) COLLATE utf8mb4_bin,CONVERT(d.mac_address USING utf8mb4) COLLATE utf8mb4_bin FROM ai_device d JOIN ai_agent a ON a.id=CONVERT(d.agent_id USING utf8mb4) COLLATE utf8mb4_bin WHERE d.user_id=? AND a.user_id=?
          ) x WHERE x.mac_address IS NOT NULL AND x.mac_address<>'' ORDER BY x.agent_id,x.mac_address
          """,owner,owner,owner);
        return Map.of("agents",agents,"devices",devices);
    }
    private record Cursor(Instant time,String key) { }
    private static Cursor decode(String encoded) {
        try {
            if(encoded.length()>2048)throw bad();
            String[] parts=new String(Base64.getUrlDecoder().decode(encoded),StandardCharsets.UTF_8).split("\\|",2);
            if(parts.length!=2||parts[1].isEmpty()||parts[1].length()>512)throw bad();
            Instant instant=Instant.parse(parts[0]);if(instant.isBefore(Instant.EPOCH)||instant.isAfter(Instant.parse("9999-12-31T23:59:59Z")))throw bad();
            return new Cursor(instant,parts[1]);
        }catch(java.time.DateTimeException | IllegalArgumentException e){throw bad();}
    }
    private static String encode(Item item){return Base64.getUrlEncoder().withoutPadding().encodeToString((item.updatedAt+"|"+item.rowKey).getBytes(StandardCharsets.UTF_8));}
    private static ConversationProblem bad(){return new ConversationProblem(400,"INVALID_CONVERSATION_INDEX_QUERY");}
}
