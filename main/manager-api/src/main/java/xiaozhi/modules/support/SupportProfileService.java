package xiaozhi.modules.support;

import xiaozhi.modules.conversation.ConversationProblem;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import xiaozhi.modules.conversation.ConversationModels.CursorPage;
import xiaozhi.modules.support.SupportModels.*;

/** 展示资料不改变登录名、权限或在线租约；头像仅发布已校验的栅格图片。 */
@Service
@ConditionalOnProperty(prefix="features", name={"conversation-enabled","support-handoff-enabled"}, havingValue="true")
public class SupportProfileService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public SupportProfileService(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    private static ConversationProblem problem(int status,String code) {return new ConversationProblem(status,code);}
    private static String text(String value,int max) {
        String result=value==null?"":value.trim();
        if(result.length()>max || result.codePoints().anyMatch(c -> Character.isISOControl(c) && c!='\n' && c!='\t'))
            throw problem(400,"INVALID_PUBLIC_PROFILE");
        return result;
    }
    public void save(long user, PublicProfile input) {
        if(input==null)return; // Old clients changing staff permissions retain existing display information.
        String name=text(input.displayName(),80),title=text(input.title(),120),bio=text(input.bio(),1000);
        if(input.listed()==null || input.specialties()==null || input.specialties().size()>12)throw problem(400,"INVALID_PUBLIC_PROFILE");
        List<String> specialties=input.specialties().stream().map(v -> text(v,40)).toList();
        if(specialties.stream().anyMatch(String::isEmpty) || specialties.stream().distinct().count()!=specialties.size())throw problem(400,"INVALID_PUBLIC_PROFILE");
        String avatar=input.avatarUrl()==null?"":input.avatarUrl().trim();
        byte[] content=null; String version=null,mime=null;
        if(avatar.startsWith("data:")) {
            content=image(avatar); mime="image/png"; version=UUID.randomUUID().toString().replace("-","");
            avatar="/api/v1/support/avatars/"+user+"/"+version;
        } else if(!avatar.isEmpty()) {
            boolean own=avatar.matches("/api/v1/support/avatars/"+user+"/[a-f0-9]{32}");
            if(own) {
                if(jdbc.queryForList("SELECT user_id FROM support_public_profile WHERE user_id=? AND avatar_url=?",user,avatar).isEmpty())throw problem(400,"INVALID_PROFILE_AVATAR");
            } else {
                try {
                    URI uri=URI.create(avatar);
                    if(avatar.length()>1000 || !Set.of("https","http").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null)
                        throw new IllegalArgumentException();
                } catch(IllegalArgumentException e) {throw problem(400,"INVALID_PROFILE_AVATAR");}
            }
        }
        String encoded;
        try { encoded=json.writeValueAsString(specialties); } catch(Exception e) {throw new IllegalStateException(e);}
        jdbc.update("""
            INSERT INTO support_public_profile(user_id,display_name,title,bio,specialties_json,listed,avatar_url)
            VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE display_name=VALUES(display_name),title=VALUES(title),
            bio=VALUES(bio),specialties_json=VALUES(specialties_json),listed=VALUES(listed),avatar_url=VALUES(avatar_url),updated_at=CURRENT_TIMESTAMP(3)
            """,user,name,title,bio,encoded,input.listed(),avatar);
        if(content!=null)jdbc.update("UPDATE support_public_profile SET avatar_content=?,avatar_mime=?,avatar_version=? WHERE user_id=?",content,mime,version,user);
        else if(!avatar.startsWith("/api/v1/support/avatars/"))jdbc.update("UPDATE support_public_profile SET avatar_content=NULL,avatar_mime=NULL,avatar_version=NULL WHERE user_id=?",user);
    }
    private static byte[] image(String data) {
        if(data.length()>700000 || !data.matches("data:image/(?:png|jpeg);base64,[A-Za-z0-9+/=]+"))throw problem(400,"INVALID_PROFILE_AVATAR");
        try {
            byte[] raw=Base64.getDecoder().decode(data.substring(data.indexOf(',')+1));
            if(raw.length>512*1024)throw new IllegalArgumentException();
            try(var stream=ImageIO.createImageInputStream(new ByteArrayInputStream(raw))) {
                var readers=ImageIO.getImageReaders(stream);
                if(!readers.hasNext())throw new IllegalArgumentException();
                var reader=readers.next();
                try {
                    reader.setInput(stream);
                    int width=reader.getWidth(0),height=reader.getHeight(0);
                    if(width<1 || height<1 || width>1024 || height>1024)throw new IllegalArgumentException();
                    BufferedImage decoded=reader.read(0);
                    var output=new ByteArrayOutputStream();
                    ImageIO.write(decoded,"png",output);
                    if(output.size()>2*1024*1024)throw new IllegalArgumentException();
                    return output.toByteArray();
                } finally {reader.dispose();}
            }
        } catch(Exception e) {throw problem(400,"INVALID_PROFILE_AVATAR");}
    }
    private List<String> specialties(String raw) {
        if(raw==null)return List.of();
        try {return json.readValue(raw,json.getTypeFactory().constructCollectionType(List.class,String.class));}
        catch(Exception e){throw new IllegalStateException("Invalid public profile specialties",e);}
    }
    public List<AgentSummary> enrich(List<AgentSummary> agents) {
        if(agents.isEmpty())return agents;
        Map<Long,PublicProfile> profiles=new HashMap<>();
        String placeholders=String.join(",",Collections.nCopies(agents.size(),"?"));
        jdbc.query("SELECT user_id,display_name,title,bio,specialties_json,listed,avatar_url FROM support_public_profile WHERE user_id IN ("+placeholders+")",rs -> {
            profiles.put(rs.getLong("user_id"),new PublicProfile(rs.getString("display_name"),rs.getString("avatar_url"),rs.getString("title"),rs.getString("bio"),specialties(rs.getString("specialties_json")),rs.getBoolean("listed")));
        },agents.stream().map(AgentSummary::userId).toArray());
        return agents.stream().map(a -> new AgentSummary(a.userId(),a.username(),a.accountEnabled(),a.role(),a.enabled(),a.capacityText(),a.accepting(),a.presenceExpiresAt(),a.roles(),profiles.getOrDefault(a.userId(),new PublicProfile(a.username(),"","","",List.of(),true)))).toList();
    }
    public CursorPage<Expert> experts(Long actor,long before,int limit) {
        if(actor==null || actor<=0)throw problem(401,"AUTHENTICATION_REQUIRED");
        if(before<0 || limit<1 || limit>100)throw problem(400,"INVALID_CURSOR_OR_LIMIT");
        List<Expert> rows=jdbc.query("""
            SELECT p.user_id,COALESCE(NULLIF(d.display_name,''),u.username) AS display_name,
            d.avatar_url,d.title,d.bio,d.specialties_json,
            (p.expert_accepting=1 AND p.expert_presence_expires_at>CURRENT_TIMESTAMP(3)) AS online,
            ((SELECT COUNT(*) FROM support_request r WHERE r.assigned_agent_user_id=p.user_id AND r.status='active')>=p.capacity_text) AS busy
            FROM support_agent_profile p JOIN sys_user u ON u.id=p.user_id
            LEFT JOIN support_public_profile d ON d.user_id=p.user_id
            WHERE p.enabled=1 AND u.status=1 AND COALESCE(d.listed,1)=1
            AND JSON_CONTAINS(COALESCE(p.roles_json,JSON_ARRAY(p.role)),JSON_QUOTE('expert'))
            AND p.user_id<? ORDER BY p.user_id DESC LIMIT ?
            """,(rs,n)->new Expert(rs.getLong("user_id"),rs.getString("display_name"),Objects.toString(rs.getString("avatar_url"),""),Objects.toString(rs.getString("title"),""),Objects.toString(rs.getString("bio"),""),specialties(rs.getString("specialties_json")),rs.getBoolean("online"),rs.getBoolean("busy")),before==0?Long.MAX_VALUE:before,limit+1);
        List<Expert> items=rows.stream().limit(limit).toList();
        return new CursorPage<>(items,items.isEmpty()?before:items.getLast().userId(),rows.size()>limit);
    }
    public record Avatar(byte[] bytes,String mime) { }
    public Avatar avatar(long user,String version) {
        if(user<=0 || !version.matches("[a-f0-9]{32}"))throw problem(404,"PROFILE_AVATAR_NOT_FOUND");
        List<Avatar> rows=jdbc.query("""
            SELECT d.avatar_content,d.avatar_mime FROM support_public_profile d
            JOIN support_agent_profile p ON p.user_id=d.user_id JOIN sys_user u ON u.id=p.user_id
            WHERE d.user_id=? AND d.avatar_version=? AND d.avatar_content IS NOT NULL AND p.enabled=1 AND u.status=1
            """,(rs,n)->new Avatar(rs.getBytes("avatar_content"),rs.getString("avatar_mime")),user,version);
        if(rows.isEmpty())throw problem(404,"PROFILE_AVATAR_NOT_FOUND");
        return rows.getFirst();
    }
}
