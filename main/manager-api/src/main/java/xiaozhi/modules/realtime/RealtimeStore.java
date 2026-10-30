package xiaozhi.modules.realtime;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import xiaozhi.common.jdbc.TimestampColumnMapRowMapper;
import xiaozhi.modules.conversation.ConversationProblem;

/** Private fields are accessed via target methods, including when Spring wraps this repository in an AOP proxy. */
@Repository
public class RealtimeStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private static final SecureRandom RANDOM=new SecureRandom();
    public RealtimeStore(JdbcTemplate jdbc,PlatformTransactionManager manager){this.jdbc=jdbc;transactions=new TransactionTemplate(manager);}
    public JdbcTemplate jdbc(){return jdbc;}
    public <T>T tx(Supplier<T> body){return transactions.execute(t->body.get());}
    public List<Map<String,Object>> rows(String sql,Object...args){return jdbc.query(sql,new TimestampColumnMapRowMapper(),args);}
    public Map<String,Object> one(String sql,Object...args){var rows=rows(sql,args);if(rows.isEmpty())throw problem(404,"REALTIME_SESSION_NOT_FOUND");return rows.getFirst();}
    public Map<String,Object> session(String id,boolean lock){validId(id);return one("SELECT * FROM ai_realtime_session WHERE id=?"+(lock?" FOR UPDATE":""),id);}
    public Map<String,Object> conversation(String id){validId(id);return one("SELECT * FROM ai_conversation WHERE id=? FOR UPDATE",id);}
    public static String id(){return UUID.randomUUID().toString().replace("-","");}
    public static String secret(){byte[] value=new byte[32];RANDOM.nextBytes(value);return HexFormat.of().formatHex(value);}
    public static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}
    public static String string(Map<String,Object> row,String key){return (String)row.get(key);}
    public static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    public static boolean yes(Map<String,Object> row,String key){Object v=row.get(key);return Boolean.TRUE.equals(v)||(v instanceof Number n&&n.intValue()!=0);}
    public static Instant instant(Map<String,Object> row,String key){Object value=row.get(key);if(value==null)return null;if(value instanceof Timestamp t)return t.toInstant();if(value instanceof Instant i)return i;throw new IllegalStateException("Unmapped JDBC datetime");}
    public static boolean active(Map<String,Object> row){return Set.of("pending","connecting","active").contains(row.get("status"));}
    public static void validId(String id){if(id==null||!id.matches("[a-f0-9]{32}"))throw problem(404,"REALTIME_SESSION_NOT_FOUND");}
    public static ConversationProblem problem(int status,String code){return new ConversationProblem(status,code);}
}
