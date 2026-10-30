package xiaozhi.modules.supportmedia;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import xiaozhi.modules.conversation.ConversationProblem;
import xiaozhi.common.jdbc.TimestampColumnMapRowMapper;

@Repository
public class MediaStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    public MediaStore(JdbcTemplate jdbc,PlatformTransactionManager manager){this.jdbc=jdbc;this.transactions=new TransactionTemplate(manager);}
    public JdbcTemplate jdbc(){return jdbc;}
    public <T> T tx(Supplier<T> work){return transactions.execute(status->work.get());}
    public List<Map<String,Object>> rows(String sql,Object... args){return jdbc.query(sql,new TimestampColumnMapRowMapper(),args);}
    public Map<String,Object> one(String sql,Object... args){var values=rows(sql,args);if(values.size()!=1)throw new org.springframework.dao.IncorrectResultSizeDataAccessException(1,values.size());return values.getFirst();}
    public Map<String,Object> session(long id,boolean lock){return required("support_media_session",id,lock);}
    public Map<String,Object> transfer(long id,boolean lock){return required("support_media_transfer",id,lock);}
    public Map<String,Object> room(long id,boolean lock){return required("support_media_room",id,lock);}
    public Map<String,Object> outbox(long id,boolean lock){return required("support_media_outbox",id,lock);}
    private Map<String,Object> required(String table,long id,boolean lock){var rows=rows("SELECT * FROM "+table+" WHERE id=?"+(lock?" FOR UPDATE":""),id);if(rows.isEmpty())throw problem(404,"MEDIA_NOT_FOUND");return rows.getFirst();}
    public Map<String,Object> generation(long session,int generation){var rows=rows("SELECT * FROM support_media_room WHERE session_id=? AND generation=? FOR UPDATE",session,generation);if(rows.isEmpty())throw problem(404,"MEDIA_ROOM_NOT_FOUND");return rows.getFirst();}
    public Map<String,Object> pending(long session,boolean lock){var rows=rows("SELECT * FROM support_media_transfer WHERE session_id=? AND active_slot=1"+(lock?" FOR UPDATE":""),session);return rows.isEmpty()?null:rows.getFirst();}
    public List<Map<String,Object>> members(long room){return rows("SELECT * FROM support_media_member WHERE room_id=? ORDER BY user_id",room);}
    public Map<String,Object> member(long room,long actor){return members(room).stream().filter(m->number(m,"user_id")==actor).findFirst().orElseThrow(()->problem(404,"MEDIA_MEMBER_NOT_FOUND"));}
    public static long number(Map<String,Object> row,String key){Object value=row.get(key);return value instanceof Number n?n.longValue():value==null?0:Long.parseLong(value.toString());}
    public static String string(Map<String,Object> row,String key){Object value=row.get(key);return value==null?null:value.toString();}
    public static Instant instant(Map<String,Object> row,String key){Object value=row.get(key);if(value==null)return null;if(value instanceof Timestamp t)return t.toInstant();if(value instanceof Instant i)return i;throw new IllegalStateException("Media datetime requires timestamp-aware JDBC mapping");}
    public static Timestamp time(Instant value){return value==null?null:Timestamp.from(value);}
    public static boolean yes(Map<String,Object> row,String key){Object value=row.get(key);return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue()!=0;}
    public static ConversationProblem problem(int status,String code){return new ConversationProblem(status,code);}
}
