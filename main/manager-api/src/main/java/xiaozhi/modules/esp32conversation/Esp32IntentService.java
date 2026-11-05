package xiaozhi.modules.esp32conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import xiaozhi.modules.conversation.ConversationProblem;

import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
@ConditionalOnProperty(name = "features.esp32-intents-enabled", havingValue = "true")
public class Esp32IntentService {
    private static final Set<String> ACTIONS = Set.of("exit", "stop", "reply", "time");
    private static final List<String> WAKE_ADDRESSES = List.of("大冷总管", "你好总管", "总管总管", "你好中管", "中管");
    private static final List<String> EXIT = List.of("退出", "再见", "拜拜", "退下吧", "退下", "退一下吧", "退一下",
            "结束对话", "结束聊天", "不聊了");
    private static final List<String> STOP = List.of("停止", "停止播放", "停止播报", "停止说话", "停止回答", "别说了",
            "别别说了", "不要说了", "不用说了", "停一下", "暂停播放", "暂停一下", "打断一下");
    private static final List<String> STOP_ALIASES = List.of("暂停", "暂停播报", "先停一下", "先别说了");
    private static final String CONTROL_PREFIX = "(?:好的|好了|好嘞|好|嗯|谢谢|没事了|不用了|我?知道了|我?明白了|我?了解了|请|现在){0,4}";
    private static final String COMMAND_ENDING = "[吧了呀啊啦喽]{0,2}";
    private static final String EXIT_COMMAND = command(Stream.concat(EXIT.stream(), Stream.of("推下吧")));
    private static final String STOP_COMMAND = command(STOP.stream());
    private static final String STOP_ALIAS_COMMAND = command(STOP_ALIASES.stream());
    private static final String ALL_CONTROLS = "(?:" + EXIT_COMMAND + "|" + STOP_COMMAND + "|" + STOP_ALIAS_COMMAND + ")";
    private static final Pattern EXIT_PATTERN = Pattern.compile(CONTROL_PREFIX + ALL_CONTROLS + "{0,5}" + EXIT_COMMAND
            + ALL_CONTROLS + "{0,5}(?:谢谢)?");
    private static final Pattern STOP_PATTERN = Pattern.compile(CONTROL_PREFIX + "(?:" + STOP_ALIAS_COMMAND + "){0,5}"
            + STOP_COMMAND + "(?:" + STOP_COMMAND + "|" + STOP_ALIAS_COMMAND + "){0,5}(?:谢谢)?");
    private static final Pattern CANCEL_EXIT = Pattern.compile(CONTROL_PREFIX + "(?:停止|暂停)退出" + COMMAND_ENDING + "(?:谢谢)?");
    private static final Pattern SEPARATE_EXIT = Pattern.compile("(?:停止|暂停)[\\s,，、。；;]+退出");
    private static final Pattern SEPARATORS = Pattern.compile("[\\p{P}\\p{Z}\\s]");
    private static final Policy INITIAL = new Policy("Asia/Shanghai", List.of(
            new Rule("about", "自我介绍", List.of("你是谁", "介绍一下自己"), "reply", "我是你的语音助手。", true),
            new Rule("time", "查询时间",
                    List.of("现在几点", "现在几点了", "今天星期几", "几点", "几点了", "现在是几点", "现在是几点了",
                            "什么时间", "现在什么时间", "现在是什么时间"),
                    "time", "", true)
    ));
    private static final List<Rule> BUILT_INS = List.of(
            new Rule("local-exit", "退出会话", EXIT, "exit", "", true),
            new Rule("local-stop", "停止播报", STOP, "stop", "", true)
    );

    public record Rule(String id, String name, List<String> phrases, String action, String reply, boolean enabled) {}
    public record Policy(String timezone, List<Rule> rules) {}
    public record Draft(long revision, Policy policy) {}
    public record View(long revision, long publishedVersion, Policy draft, List<Rule> builtIns, Policy published) {}
    public record Match(String ruleId, String name, String action, String reply, long version) {}
    private record Head(long revision, long version, Policy draft) {}

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public Esp32IntentService(JdbcTemplate db, ObjectMapper json, PlatformTransactionManager manager) {
        this.db = db;
        this.json = json;
        this.tx = new TransactionTemplate(manager);
    }

    public View view(String agent) {
        Head current = head(agent, false);
        return view(current, version(agent, current.version()));
    }

    public View save(String agent, Draft draft) {
        if (draft == null) throw problem(400, "INTENT_INVALID_POLICY");
        Policy policy = validate(draft.policy());
        return tx.execute(status -> {
            Head current = lockedHead(agent, draft.revision());
            db.update("UPDATE esp32_intent_head SET revision=revision+1,draft_json=? WHERE agent_id=?",
                    encode(policy), agent);
            return view(new Head(current.revision() + 1, current.version(), policy),
                    version(agent, current.version()));
        });
    }

    public View publish(String agent, long revision, long actor, boolean rollback) {
        return tx.execute(status -> {
            Head current = lockedHead(agent, revision);
            if (rollback && current.version() == 0) throw problem(409, "INTENT_NO_PREVIOUS_VERSION");
            Policy policy = rollback ? version(agent, current.version() - 1) : current.draft();
            long next = current.version() + 1;
            String encoded = encode(policy);
            db.update("INSERT INTO esp32_intent_version(agent_id,version,policy_json,published_by) VALUES(?,?,?,?)",
                    agent, next, encoded, actor);
            db.update("UPDATE esp32_intent_head SET revision=revision+1,published_version=?,draft_json=? WHERE agent_id=?",
                    next, encoded, agent);
            return view(new Head(current.revision() + 1, next, policy), policy);
        });
    }

    public Match preview(Policy policy, String text) {
        return match(validate(policy), text, 0);
    }

    public Match resolve(String agent, String text) {
        // The currently published policy is read once in the turn transaction.
        return db.query("""
                SELECT v.version,v.policy_json FROM esp32_intent_head h
                JOIN esp32_intent_version v ON v.agent_id=h.agent_id AND v.version=h.published_version
                WHERE h.agent_id=?
                """, (rs, n) -> new Head(0, rs.getLong(1), decode(rs.getString(2))), agent)
                .stream().findFirst().map(row -> match(row.draft(), text, row.version())).orElse(null);
    }

    private Match match(Policy policy, String text, long version) {
        String phrase = normalize(text);
        if (phrase.isEmpty()) return null;
        String request = withoutWakeAddress(phrase);
        if (request.startsWith("请问")) request = request.substring(2);
        String control = builtIn(phrase, text);
        if (control != null) {
            Rule rule = control.equals("exit") ? BUILT_INS.get(0) : BUILT_INS.get(1);
            return new Match(rule.id(), rule.name(), control, "", version);
        }
        Rule rule = matchingRule(policy, phrase);
        if (rule == null && !request.equals(phrase) && !request.isEmpty()) {
            rule = matchingRule(policy, request);
        }
        if (rule == null) return null;
        String reply = rule.reply();
        if ("time".equals(rule.action())) {
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of(policy.timezone()));
            String[] days = {"一", "二", "三", "四", "五", "六", "日"};
            reply = "现在是%d点%02d分。今天是%d月%d日，星期%s。".formatted(now.getHour(), now.getMinute(),
                    now.getMonthValue(), now.getDayOfMonth(), days[now.getDayOfWeek().getValue() - 1]);
        }
        return new Match(rule.id(), rule.name(), rule.action(), reply, version);
    }

    private Rule matchingRule(Policy policy, String phrase) {
        return policy.rules().stream().filter(rule -> rule.enabled()
                && rule.phrases().stream().anyMatch(p -> normalize(p).equals(phrase))).findFirst().orElse(null);
    }

    private Policy validate(Policy policy) {
        if (policy == null || policy.timezone() == null || policy.timezone().length() > 64
                || policy.rules() == null || policy.rules().size() > 50) {
            throw problem(400, "INTENT_INVALID_POLICY");
        }
        try {
            ZoneId.of(policy.timezone());
        } catch (DateTimeException error) {
            throw problem(400, "INTENT_INVALID_TIMEZONE");
        }
        Set<String> ids = new HashSet<>();
        Set<String> activePhrases = new HashSet<>();
        List<Rule> rules = new ArrayList<>();
        for (Rule rule : policy.rules()) {
            if (rule == null || rule.id() == null || !rule.id().matches("[A-Za-z0-9_-]{1,48}") || !ids.add(rule.id())
                    || !shortText(rule.name(), 48) || rule.action() == null || !ACTIONS.contains(rule.action())
                    || rule.phrases() == null || rule.phrases().isEmpty() || rule.phrases().size() > 16
                    || ("reply".equals(rule.action()) && !shortText(rule.reply(), 400))) {
                throw problem(400, "INTENT_INVALID_RULE");
            }
            Set<String> own = new HashSet<>();
            for (String phrase : rule.phrases()) {
                if (!shortText(phrase, 64)) throw problem(400, "INTENT_INVALID_PHRASE");
                String key = normalize(phrase);
                if (key.isEmpty() || !own.add(key) || (rule.enabled()
                        && (WAKE_ADDRESSES.contains(key) || builtIn(key, phrase) != null || !activePhrases.add(key)))) {
                    throw problem(409, "INTENT_PHRASE_CONFLICT");
                }
            }
            rules.add(new Rule(rule.id(), rule.name().strip(), List.copyOf(rule.phrases()), rule.action(),
                    "reply".equals(rule.action()) ? rule.reply().strip() : "", rule.enabled()));
        }
        return new Policy(policy.timezone(), List.copyOf(rules));
    }

    private Head lockedHead(String agent, long revision) {
        db.update("""
                INSERT INTO esp32_intent_head(agent_id,revision,published_version,draft_json) VALUES(?,0,0,?)
                ON DUPLICATE KEY UPDATE agent_id=agent_id
                """, agent, encode(INITIAL));
        Head current = head(agent, true);
        if (current.revision() != revision) throw problem(409, "INTENT_DRAFT_CHANGED");
        return current;
    }

    private Head head(String agent, boolean lock) {
        return db.query("SELECT revision,published_version,draft_json FROM esp32_intent_head WHERE agent_id=?"
                        + (lock ? " FOR UPDATE" : ""),
                (rs, n) -> new Head(rs.getLong(1), rs.getLong(2), decode(rs.getString(3))), agent)
                .stream().findFirst().orElse(new Head(0, 0, INITIAL));
    }

    private Policy version(String agent, long version) {
        if (version == 0) return new Policy(INITIAL.timezone(), List.of());
        return db.queryForObject("SELECT policy_json FROM esp32_intent_version WHERE agent_id=? AND version=?",
                (rs, n) -> decode(rs.getString(1)), agent, version);
    }

    private View view(Head head, Policy published) {
        return new View(head.revision(), head.version(), head.draft(), BUILT_INS, published);
    }

    private String encode(Policy policy) {
        try {
            return json.writeValueAsString(policy);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot encode intent policy", error);
        }
    }

    private Policy decode(String value) {
        try {
            return json.readValue(value, Policy.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot decode intent policy", error);
        }
    }

    private static boolean shortText(String value, int limit) {
        return value != null && !value.isBlank() && value.length() <= limit;
    }

    private static String normalize(String text) {
        if (text == null || text.length() > 200) return "";
        return SEPARATORS.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC)).replaceAll("");
    }

    private static String builtIn(String phrase, String original) {
        String request = withoutWakeAddress(phrase);
        if (EXIT_PATTERN.matcher(request).matches()
                && (!CANCEL_EXIT.matcher(request).matches() || SEPARATE_EXIT.matcher(original).find())) return "exit";
        if (STOP_PATTERN.matcher(request).matches()) return "stop";
        return null;
    }

    private static String withoutWakeAddress(String phrase) {
        return WAKE_ADDRESSES.stream().filter(phrase::startsWith).findFirst()
                .map(address -> phrase.substring(address.length())).orElse(phrase);
    }

    private static String command(Stream<String> phrases) {
        return "(?:" + String.join("|", phrases.map(Pattern::quote).toList()) + ")" + COMMAND_ENDING;
    }

    private static ConversationProblem problem(int status, String code) {
        return new ConversationProblem(status, code);
    }
}
