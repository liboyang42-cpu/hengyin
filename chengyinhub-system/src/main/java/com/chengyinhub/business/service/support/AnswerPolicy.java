package com.chengyinhub.business.service.support;

import com.chengyinhub.common.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 答案判定政策(BE-08):TYPE 文字题的显式有效别名 + 归一方式。
 *
 * <p><b>为什么住 {@code advancedConfigJson} 根层 {@code answerPolicy},而不是新实体列:</b>
 * 普通文字题({@code questionAnswer},validationMethod=1)与高级问答({@code qa.answerText},
 * mode=TYPE)本来就是同一个作者问题的两种承载,而模板的私有 {@code advanced_config_json}
 * 已经是两者共有的服务端配置:发布冻结时它整段随 {@code nodeConfigs} 落进本局快照,
 * 建局 freezeNodeAnswers 又摘一份进 {@code nodeAnswers}。把政策放在这里,两条判定链读的是
 * 同一份作者意图,不需要成对扩 {@code cms_member_template}/{@code cms_template_library},
 * 也不会出现"改了一处、另一处还是旧答案"的第二真源。
 *
 * <p><b>只做显式别名,不做判分增强:</b>
 * {@code acceptedAliases} 是作者逐条写下的合法写法;比对仍是
 * {@code LEGACY_V1}(去全部空白 + 全角 ASCII→半角 + 忽略大小写)后的全等。
 * 不启用模糊、拼音、语义或 LLM 判分 —— 近似错字仍然判错,选项题仍按稳定选项编码比对,
 * 绝不把 PICK 的显示文案当答案。
 *
 * <p><b>没配政策 = 旧行为逐字不变:</b>
 * {@link #legacy()} 不带任何别名,{@link #matches(String, String)} 退化为原来那一句
 * {@code normalize(canonical).equalsIgnoreCase(normalize(submitted))};
 * 高级 {@code qa.TYPE} 没配政策时走 {@link #matchesLegacyAdvancedType(String, String)},
 * 仍是原来的「首尾空格 + 大小写」逐段比对。老快照没有 {@code answerPolicy} 键时也走这条
 * (见 {@link #of(JsonNode)} / {@link #ofAdvancedConfig(String)})。
 *
 * <p><b>运行期宽容、保存期严格:</b> {@link #of(JsonNode)} 遇到形状不对只退回
 * {@link #legacy()} 并 warn —— 坏政策退回旧行为,不会把玩家卡死在答题页;
 * 坏政策也不会让错答案通过。作者侧的保存/发布闸由
 * {@link AdvancedGameConfigValidator#assertAnswerPolicy(String)} 调 {@link #requireValid(JsonNode)} 严格拒。
 *
 * <p><b>这是 secret:</b> 政策与 canonical answer 同级,
 * {@code AdvancedGamePublicProjection} 白名单不包含本段,公共详情/列表/会话视图/未来 manifest
 * 都不会带出 acceptedAliases;答案揭示仍只走既有 reveal 授权。
 */
public final class AnswerPolicy
{
    private static final Logger LOG = LoggerFactory.getLogger(AnswerPolicy.class);

    /** 模板 {@code advancedConfigJson} 根层键名。 */
    public static final String FIELD = "answerPolicy";
    /** 目前唯一受支持的归一方式:去全部空白 + 全角→半角 + 忽略大小写(既有普通文字题规则)。 */
    public static final String LEGACY_V1 = "LEGACY_V1";
    public static final int CURRENT_VERSION = 1;
    /** 作者能加的别名条数上限;再多就不是"同一答案的写法",而是把题做成了猜词。 */
    public static final int MAX_ALIASES = 20;
    /** 单条别名长度上限,与答案文本同量程。 */
    public static final int MAX_ALIAS_LENGTH = 200;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> KNOWN_FIELDS = Set.of("version", "normalizationPolicy", "acceptedAliases");
    /** 高级 qa.TYPE 的历史写法:一个 answerText 里用、或英文逗号分隔多个合法答案。 */
    private static final String LEGACY_ANSWER_SEPARATOR = "[、,,]";

    private static final AnswerPolicy LEGACY = new AnswerPolicy(LEGACY_V1, Collections.<String>emptyList());

    private final String normalizationPolicy;
    private final List<String> acceptedAliases;

    private AnswerPolicy(String normalizationPolicy, List<String> acceptedAliases)
    {
        this.normalizationPolicy = normalizationPolicy;
        this.acceptedAliases = acceptedAliases;
    }

    /** 旧行为:没有别名,只有 canonical answer。 */
    public static AnswerPolicy legacy()
    {
        return LEGACY;
    }

    /**
     * 读一段政策节点。缺键 / null / 空对象 / 形状不对都退回 {@link #legacy()};
     * 形状不对会 warn —— 静默把作者写的别名丢掉,和静默接受错答案一样难查。
     */
    public static AnswerPolicy of(JsonNode section)
    {
        if (section == null || section.isNull() || section.isMissingNode()) return LEGACY;
        if (!section.isObject() || section.size() == 0) {
            LOG.warn("answerPolicy 形状不可用,按无别名处理:{}", section);
            return LEGACY;
        }
        try {
            requireValid(section);
        } catch (IllegalArgumentException unusablePolicy) {
            LOG.warn("answerPolicy 不可用,按无别名处理:{}", unusablePolicy.getMessage());
            return LEGACY;
        }
        List<String> aliases = new ArrayList<>();
        for (JsonNode alias : section.path("acceptedAliases")) aliases.add(alias.asText().trim());
        return aliases.isEmpty() ? LEGACY : new AnswerPolicy(LEGACY_V1, Collections.unmodifiableList(aliases));
    }

    /**
     * 从模板私有 {@code advancedConfigJson} 读根层 {@link #FIELD}。
     * 空配置、坏 JSON、没有该段一律 = 旧行为(不因此拒绝开局/答题)。
     */
    public static AnswerPolicy ofAdvancedConfig(String advancedConfigJson)
    {
        if (StringUtils.isBlank(advancedConfigJson)) return LEGACY;
        try {
            JsonNode root = MAPPER.readTree(advancedConfigJson);
            return root != null && root.isObject() ? of(root.get(FIELD)) : LEGACY;
        } catch (Exception unusableConfig) {
            return LEGACY;
        }
    }

    /** 保存/发布闸:形状严格。调用方负责把消息透给作者。 */
    public static void requireValid(JsonNode section)
    {
        if (section == null || section.isNull()) return;
        if (!section.isObject()) throw new IllegalArgumentException("答案别名政策格式不正确");
        Iterator<String> names = section.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!KNOWN_FIELDS.contains(name)) throw new IllegalArgumentException("答案别名政策不支持的字段:" + name);
        }
        JsonNode version = section.get("version");
        if (version == null || !version.isIntegralNumber() || version.asInt() != CURRENT_VERSION) {
            throw new IllegalArgumentException("答案别名政策版本只能是 " + CURRENT_VERSION);
        }
        JsonNode normalization = section.get("normalizationPolicy");
        if (normalization != null && !normalization.isNull()
                && (!normalization.isTextual() || !LEGACY_V1.equals(normalization.asText()))) {
            throw new IllegalArgumentException("答案归一方式只能是 " + LEGACY_V1);
        }
        JsonNode aliases = section.get("acceptedAliases");
        if (aliases == null || aliases.isNull()) return;
        if (!aliases.isArray()) throw new IllegalArgumentException("答案别名必须是数组");
        if (aliases.size() > MAX_ALIASES) throw new IllegalArgumentException("答案别名最多 " + MAX_ALIASES + " 条");
        Set<String> seen = new HashSet<>();
        for (JsonNode alias : aliases) {
            if (!alias.isTextual()) throw new IllegalArgumentException("答案别名必须是文本");
            String text = alias.asText().trim();
            if (text.isEmpty()) throw new IllegalArgumentException("答案别名不能为空");
            if (text.length() > MAX_ALIAS_LENGTH) throw new IllegalArgumentException("答案别名不能超过 " + MAX_ALIAS_LENGTH + " 字");
            // 归一后重复 = 作者以为写了两条,实际只有一条;与逐字题目同口径直接拒,不做静默去重。
            if (!seen.add(normalizedKey(text))) throw new IllegalArgumentException("答案别名不能重复");
        }
    }

    /** 归一后的 canonical 是否被提交的答案命中(含显式别名)。canonical 为空 = 这题没有可判定的答案。 */
    public boolean matches(String canonical, String submitted)
    {
        if (StringUtils.isBlank(canonical)) return false;
        return matchesAny(Collections.singletonList(canonical), submitted);
    }

    /** 多个合法写法(高级 qa.TYPE 的历史 answerText)分别与别名一起比对,命中任一即过。 */
    public boolean matchesAny(List<String> canonicals, String submitted)
    {
        String got = normalize(submitted);
        if (got.isEmpty()) return false;
        boolean hasCanonical = false;
        if (canonicals != null) {
            for (String canonical : canonicals) {
                if (StringUtils.isBlank(canonical)) continue;
                hasCanonical = true;
                if (got.equalsIgnoreCase(normalize(canonical))) return true;
            }
        }
        if (!hasCanonical) return false;
        for (String alias : acceptedAliases) {
            if (got.equalsIgnoreCase(normalize(alias))) return true;
        }
        return false;
    }

    /**
     * LEGACY_V1:去全部空白 + 全角 ASCII→半角 + 忽略大小写(调用方比较时用 equalsIgnoreCase)。
     * 这是全链路唯一一份实现 —— 普通 answer、guided/共享判定,以及**配了政策**的高级
     * {@code qa.TYPE} 都调这里,不再各留一份几乎一样的循环。高级 TYPE 没配政策时走
     * {@link #matchesLegacyAdvancedType(String, String)} 保留旧语义。
     * 逐字保留原实现,连全角空格(U+3000)落成半角空格后
     * 不再参与去空白这一点也不改:改它等于悄悄放宽既有判定。
     */
    public static String normalize(String value)
    {
        if (value == null) return "";
        String trimmed = value.trim().replaceAll("\\s+", "");
        StringBuilder normalized = new StringBuilder(trimmed.length());
        for (char c : trimmed.toCharArray()) {
            if (c >= '！' && c <= '～') normalized.append((char) (c - 0xFEE0)); // 全角ASCII→半角
            else if (c == '　') normalized.append(' '); // 全角空格(trim/\\s+ 都不动它,原样落半角空格)
            else normalized.append(c);
        }
        return normalized.toString();
    }

    /** 高级 qa.TYPE 历史语义:答案里用、或英文逗号分隔的每一段都是合法写法。 */
    public static List<String> legacyAnswerTextCandidates(String answerText)
    {
        if (StringUtils.isBlank(answerText)) return Collections.emptyList();
        List<String> candidates = new ArrayList<>();
        for (String one : answerText.split(LEGACY_ANSWER_SEPARATOR)) {
            String trimmed = one.trim();
            if (!trimmed.isEmpty()) candidates.add(trimmed);
        }
        return candidates;
    }

    /**
     * 高级 {@code qa.TYPE} 的历史比对:首尾空格 + 大小写,多个合法答案用、或英文逗号分隔。
     * 这是「没有 answerPolicy」时逐字保留的旧行为 —— 它不删中间空白、不做全角→半角,
     * 与普通文字题的 {@code LEGACY_V1} 刻意分开:统一到 {@code LEGACY_V1} 会悄悄放宽存量模板,
     * 例如把「a b」当成「ab」。作者显式配了政策才进入 {@link #matchesAny}。
     */
    public static boolean matchesLegacyAdvancedType(String answerText, String submitted)
    {
        if (submitted == null) return false;
        String got = submitted.trim().toLowerCase();
        if (got.isEmpty()) return false;
        for (String one : legacyAnswerTextCandidates(answerText)) {
            if (one.toLowerCase().equals(got)) return true;
        }
        return false;
    }

    /** 冻结形状:{@code {"version":1,"normalizationPolicy":"LEGACY_V1","acceptedAliases":[...]}}。 */
    public ObjectNode toJson()
    {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("version", CURRENT_VERSION);
        node.put("normalizationPolicy", normalizationPolicy);
        ArrayNode aliases = node.putArray("acceptedAliases");
        for (String alias : acceptedAliases) aliases.add(alias);
        return node;
    }

    /** 没有别名 = 与旧行为等价;冻结时据此决定写不写 {@code answerPolicy} 键。 */
    public boolean isEmpty()
    {
        return acceptedAliases.isEmpty();
    }

    public String normalizationPolicy()
    {
        return normalizationPolicy;
    }

    public List<String> acceptedAliases()
    {
        return acceptedAliases;
    }

    /** 归一后的小写形态,测试与诊断用;判定请用 {@link #matches}。 */
    static String normalizedKey(String value)
    {
        return normalize(value).toLowerCase(Locale.ROOT);
    }
}
