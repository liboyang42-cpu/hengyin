package com.chengyinhub.business.service.support;

import com.chengyinhub.common.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 主题数值规则 journeyRules 的单一配置校验入口(R2 旅程体验模组化第二轮,主题级一份)。
 *
 * <p>风格与 {@link AdvancedGameConfigValidator} 一致:非法抛 {@link IllegalArgumentException}
 * 中文人话;缺省值补齐;空配置返回 null = 未启用(老主题行为完全不变)。
 *
 * <p>与高级玩法配置的分工:这里只保证数值规则**自身自洽**,不查键注册表 ——
 * {@code recovery.exhaustTag} 有没有被主题注册,由发布期对账(见 CmsTopicServiceImpl
 * assertStateKeysRegistered);也不做任何检定逻辑(B5 另派)。
 */
public class JourneyRulesValidator {

    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_JSON_BYTES = 16 * 1024;

    /** 键名正则的 tag 分支:力竭标记必须是 tag.* 具名状态键。 */
    private static final Pattern EXHAUST_TAG =
            Pattern.compile("^tag\\.[a-z][a-z0-9_]{0,47}$");

    // 缺省值 = 契约示例值。缺项补齐后服务端与客户端拿到同一份完整规则,
    // 免得「作者没填」在运行期被各自解释成不同的数。
    private static final int DEFAULT_HP_MAX = 10;
    private static final int DEFAULT_HP_INIT = 10;
    private static final int DEFAULT_LUCK_MAX = 3;
    private static final int DEFAULT_LUCK_INIT = 3;
    private static final int DEFAULT_DC_EASY = 10;
    private static final int DEFAULT_DC_MEDIUM = 13;
    private static final int DEFAULT_DC_HARD = 16;
    private static final int DEFAULT_DAMAGE_EASY = 1;
    private static final int DEFAULT_DAMAGE_MEDIUM = 2;
    private static final int DEFAULT_DAMAGE_HARD = 3;
    private static final int DEFAULT_STATION_END_IF_HURT = 2;
    private static final int DEFAULT_STAGE_REST_HP_FLOOR = 6;
    private static final int DEFAULT_STAGE_REST_LUCK_FLOOR = 1;
    private static final int DEFAULT_EXHAUST_REST_HP = 5;
    private static final int DEFAULT_FUMBLE_EXTRA_DAMAGE = 1;
    private static final int DEFAULT_MOD_CAP = 6;

    /* 思维阁(2026-09-22 定层):念头是主题级、跨章节的累积状态,定义与 hp/luck 同层。
       效果目前只有一种 —— 想通了,幸运上限 +1。要别的效果再加字段(加字段不破存量)。 */
    private static final Pattern THOUGHT_KEY = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");
    private static final int MAX_THOUGHTS = 8;
    private static final int MAX_THOUGHT_NAME = 20;
    private static final int MAX_THOUGHT_DESC = 60;
    private static final int MAX_THOUGHT_NEED = 100000;
    private static final int MAX_THOUGHT_CONDITIONS = 8;
    /** 与 AdvancedGameConfigValidator.STATE_TAG 同一条:选项效果 ADD_TAG 只会贴这种标签。 */
    private static final Pattern THOUGHT_TAG = Pattern.compile("^tag\\.[a-z][a-z0-9_]{0,47}$");

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 空配置代表未启用;非空配置经校验后返回紧凑、稳定的 JSON(只含契约字段)。 */
    public String validateAndNormalize(String rawJson) {
        if (StringUtils.isBlank(rawJson)) {
            return null;
        }
        if (rawJson.getBytes(StandardCharsets.UTF_8).length > 64 * 1024) {
            throw new IllegalArgumentException("旅程数值规则不能超过 64 KiB");
        }
        final JsonNode parsed;
        try {
            parsed = objectMapper.readTree(rawJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("旅程数值规则不是合法 JSON");
        }
        if (!(parsed instanceof ObjectNode)) {
            throw new IllegalArgumentException("旅程数值规则必须是对象");
        }
        ObjectNode root = (ObjectNode) parsed;
        if (!root.has("attributeDefinition") && rawJson.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw new IllegalArgumentException("旅程数值规则不能超过 16 KiB");
        }
        JsonNode schemaVersion = root.path("schemaVersion");
        if (!schemaVersion.isIntegralNumber() || !schemaVersion.canConvertToInt()
                || schemaVersion.intValue() != SCHEMA_VERSION) {
            throw new IllegalArgumentException("旅程数值规则版本不支持");
        }
        boolean checkEnabled = boolField(root, "checkEnabled", false);

        JsonNode hpNode = objectField(root, "hp");
        int hpMax = intField(hpNode, "hp.max", DEFAULT_HP_MAX);
        requireRange("hp.max", hpMax, 1, 20);
        int hpInit = defaultedInt(hpNode, "hp.init", DEFAULT_HP_INIT, hpMax);
        requireRange("hp.init", hpInit, 1, hpMax);
        boolean hpEnabled = boolField(hpNode, "hp.enabled", true);

        JsonNode luckNode = objectField(root, "luck");
        int luckMax = intField(luckNode, "luck.max", DEFAULT_LUCK_MAX);
        requireRange("luck.max", luckMax, 0, 5);
        int luckInit = defaultedInt(luckNode, "luck.init", DEFAULT_LUCK_INIT, luckMax);
        requireRange("luck.init", luckInit, 0, luckMax);
        boolean luckEnabled = boolField(luckNode, "luck.enabled", true);

        JsonNode tiersNode = objectField(root, "tiers");
        JsonNode easyNode = objectField(tiersNode, "tiers.easy");
        JsonNode mediumNode = objectField(tiersNode, "tiers.medium");
        JsonNode hardNode = objectField(tiersNode, "tiers.hard");
        int easyDc = tierDc(easyNode, "easy", DEFAULT_DC_EASY);
        int mediumDc = tierDc(mediumNode, "medium", DEFAULT_DC_MEDIUM);
        int hardDc = tierDc(hardNode, "hard", DEFAULT_DC_HARD);
        // 文档二 E1:作者只挑一档,难度线、情境加值上限与动量按档覆盖;没配 difficulty 的老主题一字不变。
        JourneyDifficulty difficulty = JourneyDifficulty.parse(root.get("difficulty"));
        if (difficulty != null) {
            easyDc = difficulty.easyDc;
            mediumDc = difficulty.mediumDc;
            hardDc = difficulty.hardDc;
        }
        if (!(easyDc < mediumDc && mediumDc < hardDc)) {
            throw new IllegalArgumentException("三档难度的 dc 必须 easy < medium < hard");
        }
        int easyDamage = tierDamage(easyNode, "easy", DEFAULT_DAMAGE_EASY);
        int mediumDamage = tierDamage(mediumNode, "medium", DEFAULT_DAMAGE_MEDIUM);
        int hardDamage = tierDamage(hardNode, "hard", DEFAULT_DAMAGE_HARD);

        JsonNode recoveryNode = objectField(root, "recovery");
        int stationEndIfHurt = intField(recoveryNode, "recovery.stationEndIfHurt",
                DEFAULT_STATION_END_IF_HURT);
        requireRange("recovery.stationEndIfHurt", stationEndIfHurt, 0, hpMax);
        int stageRestHpFloor = intField(recoveryNode, "recovery.stageRestHpFloor",
                DEFAULT_STAGE_REST_HP_FLOOR);
        requireRange("recovery.stageRestHpFloor", stageRestHpFloor, 0, hpMax);
        int stageRestLuckFloor = intField(recoveryNode, "recovery.stageRestLuckFloor",
                DEFAULT_STAGE_REST_LUCK_FLOOR);
        requireRange("recovery.stageRestLuckFloor", stageRestLuckFloor, 0, luckMax);
        int exhaustRestHp = intField(recoveryNode, "recovery.exhaustRestHp", DEFAULT_EXHAUST_REST_HP);
        requireRange("recovery.exhaustRestHp", exhaustRestHp, 0, hpMax);
        String exhaustTag = optionalExhaustTag(recoveryNode);

        int fumbleExtraDamage = intField(root, "fumbleExtraDamage", DEFAULT_FUMBLE_EXTRA_DAMAGE);
        requireRange("fumbleExtraDamage", fumbleExtraDamage, 0, 3);
        int modCap = intField(root, "modCap", DEFAULT_MOD_CAP);
        requireRange("modCap", modCap, 0, 10);
        if (difficulty != null) modCap = difficulty.modCap;

        ObjectNode out = objectMapper.createObjectNode();
        out.put("schemaVersion", SCHEMA_VERSION);
        out.put("checkEnabled", checkEnabled);
        if (difficulty != null) out.put("difficulty", difficulty.value);
        if (root.has("stateEnabled")) out.put("stateEnabled", boolField(root, "stateEnabled", false));
        if (root.has("attributes")) out.set("attributes", root.has("attributeBinding")
                ? AuthorAttributeBinding.projection(root.get("attributes")) : JourneyInitialState.normalize(root.get("attributes")));
        if (root.has("attributeBinding")) out.set("attributeBinding", AuthorAttributeBinding.normalize(root.get("attributeBinding")));
        ArrayNode thoughts = normalizeThoughts(root.get("thoughts"));
        if (thoughts != null) out.set("thoughts", thoughts);
        ObjectNode hpOut = out.putObject("hp");
        hpOut.put("enabled", hpEnabled);
        hpOut.put("max", hpMax);
        hpOut.put("init", hpInit);
        ObjectNode luckOut = out.putObject("luck");
        luckOut.put("enabled", luckEnabled);
        luckOut.put("max", luckMax);
        luckOut.put("init", luckInit);
        ObjectNode tiersOut = out.putObject("tiers");
        putTier(tiersOut, "easy", easyDc, easyDamage);
        putTier(tiersOut, "medium", mediumDc, mediumDamage);
        putTier(tiersOut, "hard", hardDc, hardDamage);
        ObjectNode recoveryOut = out.putObject("recovery");
        recoveryOut.put("stationEndIfHurt", stationEndIfHurt);
        recoveryOut.put("stageRestHpFloor", stageRestHpFloor);
        recoveryOut.put("stageRestLuckFloor", stageRestLuckFloor);
        recoveryOut.put("exhaustRestHp", exhaustRestHp);
        if (exhaustTag != null) {
            recoveryOut.put("exhaustTag", exhaustTag);
        }
        out.put("fumbleExtraDamage", fumbleExtraDamage);
        out.put("modCap", modCap);
        // F2 T04:只在作者显式配置时输出 —— 缺省的老主题规范化结果一字不变(冻结快照与摘要稳定)。
        if (root.has("natAuto") && !root.get("natAuto").isNull()) {
            out.put("natAuto", boolField(root, "natAuto", true));
        }
        // F6 RPG P1:同 natAuto,只在作者显式配置时输出;缺省老主题规范化结果一字不变。
        com.fasterxml.jackson.databind.node.ObjectNode rpg = JourneyRpg.normalize(root.get("rpg"), checkEnabled,
                root.has("stateEnabled") && boolField(root, "stateEnabled", false));
        if (rpg != null) {
            if (difficulty != null) difficulty.applyMomentum(rpg);
            out.set("rpg", rpg);
        }
        if (root.has("statLinks")) {
            try {
                com.chengyinhub.business.domain.JourneyRules rules = objectMapper.treeToValue(out,
                        com.chengyinhub.business.domain.JourneyRules.class);
                out.set("statLinks", JourneyStatLinks.normalize(root.get("statLinks"), rules));
            } catch (IllegalArgumentException invalid) { throw invalid; }
            catch (Exception invalid) { throw new IllegalArgumentException("状态联动规则无法解析", invalid); }
        }
        if (root.has("attributeDefinition")) out.set("attributeDefinition", AuthorAttributeBinding.validateDefinition(root.get("attributeDefinition")));
        try {
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            throw new IllegalArgumentException("旅程数值规则无法序列化");
        }
    }

    /**
     * 思维阁的念头清单。没配就是没有 —— 不给空数组,免得「作者没配」和「配了又删空」
     * 在运行期被解释成两种东西。
     */
    private ArrayNode normalizeThoughts(JsonNode source) {
        if (source == null || source.isNull() || source.isMissingNode()) return null;
        if (!source.isArray()) throw new IllegalArgumentException("念头清单必须是数组");
        if (source.size() == 0) return null;
        if (source.size() > MAX_THOUGHTS) {
            throw new IllegalArgumentException("念头最多 " + MAX_THOUGHTS + " 个");
        }
        ArrayNode out = objectMapper.createArrayNode();
        Set<String> seen = new HashSet<String>();
        for (JsonNode row : source) {
            if (!(row instanceof ObjectNode)) throw new IllegalArgumentException("念头必须是对象");
            String key = row.path("key").asText("").trim();
            if (!THOUGHT_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("念头标识不合法:" + key);
            }
            if (!seen.add(key)) {
                throw new IllegalArgumentException("念头标识重复:" + key + ";同一个 key 的进度会互相覆盖");
            }
            String name = row.path("name").asText("").trim();
            if (name.isEmpty() || name.length() > MAX_THOUGHT_NAME) {
                throw new IllegalArgumentException("念头要有名字,且不超过 " + MAX_THOUGHT_NAME + " 字");
            }
            String desc = row.path("desc").asText("").trim();
            if (desc.length() > MAX_THOUGHT_DESC) {
                throw new IllegalArgumentException("念头的一句话不能超过 " + MAX_THOUGHT_DESC + " 字");
            }
            // 想通方式(2026-09-22 用户裁决「伏笔不能只是微信步数」):走够步数 need,或满足 doneWhen,
            // 至少一种。得到方式:读到伏笔块,或满足 when(两条都可选,任一条件满足即算)。
            ArrayNode when = normalizeThoughtConditions(row.get("when"), name);
            ArrayNode doneWhen = normalizeThoughtConditions(row.get("doneWhen"), name);
            JsonNode needNode = row.get("need");
            Integer need = null;
            if (needNode != null && !needNode.isNull()) {
                if (!needNode.isIntegralNumber() || !needNode.canConvertToInt()) {
                    throw new IllegalArgumentException("念头要走多少步才想通,必须是范围内的整数");
                }
                need = needNode.asInt();
                if (need < 1 || need > MAX_THOUGHT_NEED) {
                    throw new IllegalArgumentException("念头的步数阈值要在 1 到 " + MAX_THOUGHT_NEED + " 之间");
                }
            }
            if (need == null && doneWhen == null) {
                throw new IllegalArgumentException("念头「" + name + "」没有想通的方式:配步数,或配一个选项条件");
            }
            ObjectNode item = out.addObject();
            item.put("key", key);
            item.put("name", name);
            if (!desc.isEmpty()) item.put("desc", desc);
            if (need != null) item.put("need", need.intValue());
            if (when != null) item.set("when", when);
            if (doneWhen != null) item.set("doneWhen", doneWhen);
        }
        return out;
    }

    /**
     * 念头的条件:只认两种 —— 选项效果贴的标签(HAS_TAG tag.xxx)与到过某站(NODE_COMPLETED)。
     * 多条之间任一满足即算(「答出这些特定选项里的任何一个」)。不认识的一律拒,
     * 不许静默当成没配 —— 那会让作者以为配好了,玩家却永远得不到。
     */
    private ArrayNode normalizeThoughtConditions(JsonNode source, String name) {
        if (source == null || source.isNull() || source.isMissingNode()) return null;
        if (!source.isArray()) throw new IllegalArgumentException("念头「" + name + "」的条件必须是数组");
        if (source.size() == 0) return null;
        if (source.size() > MAX_THOUGHT_CONDITIONS) {
            throw new IllegalArgumentException("念头「" + name + "」的条件最多 " + MAX_THOUGHT_CONDITIONS + " 条");
        }
        ArrayNode out = objectMapper.createArrayNode();
        for (JsonNode c : source) {
            String op = c.path("op").asText("");
            ObjectNode item = out.addObject();
            if ("HAS_TAG".equals(op)) {
                String tag = c.path("value").asText("").trim();
                if (!THOUGHT_TAG.matcher(tag).matches()) {
                    throw new IllegalArgumentException("念头「" + name + "」的选项条件要引用 tag. 开头的标签:" + tag);
                }
                item.put("op", op);
                item.put("value", tag);
            } else if ("NODE_COMPLETED".equals(op)) {
                long nodeId = thoughtNodeId(c.path("nodeId"));
                if (nodeId <= 0L) throw new IllegalArgumentException("念头「" + name + "」的站点条件缺站点");
                item.put("op", op);
                item.put("nodeId", nodeId);
            } else {
                throw new IllegalArgumentException("念头「" + name + "」的条件只能是选项标签或到过某站:" + op);
            }
        }
        return out;
    }

    private static long thoughtNodeId(JsonNode value) {
        if (value.isIntegralNumber() && value.canConvertToLong()) return value.longValue();
        // Long 字符串供小程序无损传参；拒绝小数、指数、溢出与隐式类型转换。
        if (value.isTextual() && value.textValue().matches("[1-9][0-9]{0,18}")) {
            try { return Long.parseLong(value.textValue()); }
            catch (NumberFormatException invalid) { return 0L; }
        }
        return 0L;
    }

    private static void putTier(ObjectNode tiers, String name, int dc, int damage) {
        ObjectNode tier = tiers.putObject(name);
        tier.put("dc", dc);
        tier.put("damage", damage);
    }

    private void requireRange(String field, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(field + " 须为 " + min + " 至 " + max);
        }
    }

    private JsonNode objectField(JsonNode parent, String field) {
        if (parent == null) {
            return null;
        }
        JsonNode node = parent.get(simpleName(field));
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException(field + " 必须是对象");
        }
        return node;
    }

    private boolean boolField(JsonNode parent, String field, boolean defaultValue) {
        if (parent == null) {
            return defaultValue;
        }
        JsonNode node = parent.get(simpleName(field));
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (!node.isBoolean()) {
            throw new IllegalArgumentException(field + " 必须是布尔值");
        }
        return node.asBoolean();
    }

    /** 读整数字段;缺省用 defaultValue。非整数(含小数、字符串)一律拒。 */
    private int intField(JsonNode parent, String field, int defaultValue) {
        JsonNode node = parent == null ? null : parent.get(simpleName(field));
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw new IllegalArgumentException(field + " 必须是整数");
        }
        return node.asInt();
    }

    /** 缺省值补齐的整数:显式值照读(非整数拒),缺省值按 max 夹取(默认值不会自己越界)。 */
    private int defaultedInt(JsonNode parent, String field, int defaultValue, int max) {
        return intField(parent, field, Math.min(defaultValue, max));
    }

    private int tierDc(JsonNode tier, String name, int defaultValue) {
        int dc = intField(tier, "tiers." + name + ".dc", defaultValue);
        requireRange("tiers." + name + ".dc", dc, 5, 25);
        return dc;
    }

    private int tierDamage(JsonNode tier, String name, int defaultValue) {
        int damage = intField(tier, "tiers." + name + ".damage", defaultValue);
        requireRange("tiers." + name + ".damage", damage, 0, 5);
        return damage;
    }

    private String optionalExhaustTag(JsonNode recovery) {
        if (recovery == null) {
            return null;
        }
        JsonNode node = recovery.get("exhaustTag");
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new IllegalArgumentException("recovery.exhaustTag 必须是字符串");
        }
        String tag = node.asText().trim();
        if (tag.isEmpty()) {
            return null;
        }
        if (!EXHAUST_TAG.matcher(tag).matches()) {
            throw new IllegalArgumentException(
                    "recovery.exhaustTag 必须是 tag.* 状态键(如 tag.injured),且满足键名正则");
        }
        return tag;
    }

    /** "hp.max" → "max":ObjectNode 里字段名不带路径前缀。 */
    private static String simpleName(String field) {
        int dot = field.lastIndexOf('.');
        return dot < 0 ? field : field.substring(dot + 1);
    }
}
