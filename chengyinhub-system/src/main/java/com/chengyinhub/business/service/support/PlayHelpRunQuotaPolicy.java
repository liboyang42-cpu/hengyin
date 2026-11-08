package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 整局共享免费提示额度 N 的冻结读取。
 *
 * <p>配置唯一来源是模板 {@code advancedConfigJson} 根层标量 {@code hintFreeUsesPerRun}，
 * 与 {@code hintLadder} 同级。开局冻结把它写进
 * {@code topic_route_session.config_snapshot_json}：真实冻结块在 {@code nodeConfigs[nodeId]}，
 * 本类也兼容 {@code nodeAnswers[nodeId].advancedConfigJson} 与根层整局标量三种形状。
 *
 * <p><b>N 是整局策略，不是每题策略。</b>{@link #runFreeUses(JsonNode)} 一次性扫完整份冻结快照
 * （根层 + 每个 {@code nodeConfigs} 行 + 每个 {@code nodeAnswers} 行的直层与嵌套
 * {@code advancedConfigJson}）得出唯一的整局值。任何两处显式声明不同即报
 * {@code PLAY_HELP_RUN_QUOTA_DECLARED_CONFLICT} —— 根层标量**不能**掩盖节点上的不同声明，
 * 直层字段**也不能**掩盖嵌套配置里的不同声明。
 *
 * <p>缺省规则（产品口径）：
 * <ul>
 *   <li>本局出现过 {@code hintLadder}（即"新阶梯"配置）但没有任何显式 N → 整局默认 <b>0</b>：
 *       有阶梯就是新规则，新规则默认不送免费次数，必须由作者显式给 N。</li>
 *   <li>整局既无 N 也无阶梯（真正的旧局/旧模板）→ 返回 {@code null}，调用方走原有 G5 逐层语义，
 *       不补默认、不回填。</li>
 *   <li>节点没有声明 N 时**只继承**整局值，不会被强加 0 去制造假冲突。</li>
 * </ul>
 *
 * <p>客户端永远不能传 N；调用方只能从服务端冻结快照取。
 */
public final class PlayHelpRunQuotaPolicy {

    /** 与 hintLadder 同级的根层标量。 */
    public static final String FIELD = "hintFreeUsesPerRun";
    /** 冻结快照里的真实块配置键（{@code TopicRoutePublicationCompiler} 写入）。 */
    public static final String NODE_CONFIGS_KEY = "nodeConfigs";
    /** 冻结快照里的节点答案键（R18-FREEZE）。 */
    public static final String NODE_ANSWERS_KEY = "nodeAnswers";
    /** 各节点声明了不同的整局 N。 */
    public static final String REASON_DECLARED_CONFLICT = "PLAY_HELP_RUN_QUOTA_DECLARED_CONFLICT";

    /** 阶梯段名：它的出现把"旧局"与"新规则局"区分开。 */
    private static final String LADDER_FIELD = "hintLadder";
    /** 节点答案里的嵌套玩法配置串/对象。 */
    private static final String ADVANCED_CONFIG_FIELD = "advancedConfigJson";

    /**
     * 一次游玩真实可用的免费次数上限。不是产品费率，只是防止异常配置把 INT 计数器撑爆；
     * 作者正常配置远小于此值。
     */
    public static final int MAX_RUN_FREE_USES = 1000;

    public static final String REASON_CONFIG_INVALID = "PLAY_HELP_RUN_QUOTA_CONFIG_INVALID";
    public static final String REASON_NOT_CONFIGURED = "PLAY_HELP_RUN_QUOTA_NOT_CONFIGURED";

    private static final ObjectMapper JSON = new ObjectMapper();

    private PlayHelpRunQuotaPolicy() {
    }

    /**
     * 本局整局唯一的 N。
     *
     * @param snapshot 冻结的内容快照（{@code config_snapshot_json}），可为 null
     * @return 整局 N；{@code 0} 表示"新阶梯但作者没给免费次数"；{@code null} 表示真正的旧局
     * @throws IllegalArgumentException 多处显式声明不同，或声明值非法
     */
    public static Integer runFreeUses(JsonNode snapshot) {
        if (snapshot == null || !snapshot.isObject()) return null;
        Set<Integer> declared = new TreeSet<>();
        LadderProbe ladder = new LadderProbe();
        scanOne(snapshot, declared, ladder);
        scanRows(snapshot.path(NODE_CONFIGS_KEY), declared, ladder);
        scanRows(snapshot.path(NODE_ANSWERS_KEY), declared, ladder);
        if (declared.size() > 1) {
            throw new IllegalArgumentException(REASON_DECLARED_CONFLICT + ":" + declared);
        }
        if (declared.size() == 1) return declared.iterator().next();
        // 没有显式 N：新阶梯局默认 0（不送免费次数），真旧局才是 null（走逐层旧语义）。
        return ladder.seen ? Integer.valueOf(0) : null;
    }

    /**
     * 节点级视图。
     *
     * <p>N 是整局策略，所以这里返回的仍是 {@link #runFreeUses(JsonNode)} 的整局值 ——
     * 按节点各返回一个数，就会出现"从第一个节点开局时初始化了另一个 N"。
     * 参数 {@code nodeId} 保留只为公共 API 兼容与调用点可读性，不参与取值。
     *
     * @throws IllegalArgumentException 整局声明冲突或值非法
     */
    public static Integer fromSnapshot(JsonNode snapshot, long nodeId) {
        if (snapshot == null || !snapshot.isObject() || nodeId <= 0) return null;
        return runFreeUses(snapshot);
    }

    /**
     * 从一个节点的冻结答案取 N。
     *
     * <p>直层 {@code hintFreeUsesPerRun} 与嵌套 {@code advancedConfigJson} 里的同名值
     * **必须一致**：只认直层会让嵌套里的不同声明静默失效，正是"两处真源"的漏洞。
     *
     * @param frozenAnswer {@code NodeJourneySnapshot.answer(snapshot, nodeId)} 的结果，可为 null
     * @return N；两处都没有返回 null（由调用方的整局解析决定默认）
     * @throws IllegalArgumentException 直层与嵌套声明不同，或值非法
     * @throws IllegalStateException 嵌套的 {@code advancedConfigJson} 不是合法 JSON
     */
    public static Integer frozenUses(JsonNode frozenAnswer) {
        if (frozenAnswer == null || frozenAnswer.isNull() || frozenAnswer.isMissingNode()) return null;
        Set<Integer> declared = new TreeSet<>();
        scanOne(frozenAnswer, declared, new LadderProbe());
        if (declared.size() > 1) {
            throw new IllegalArgumentException(REASON_DECLARED_CONFLICT + ":" + declared);
        }
        return declared.isEmpty() ? null : declared.iterator().next();
    }

    /**
     * 从 advancedConfigJson 根对象取 N。保存期由 V2 的 validator 归一/校验；
     * 运行期仍在这里再判一次，坏数据 fail-closed。
     */
    public static Integer fromAdvancedConfig(JsonNode root) {
        if (root == null || root.isNull() || root.isMissingNode()) return null;
        if (!root.isObject()) throw new IllegalArgumentException(REASON_CONFIG_INVALID + ":not-object");
        if (!root.has(FIELD)) return null;
        return read(root.get(FIELD));
    }

    /** 调用方已经拿到非 null N 时用它做最终范围校验。 */
    public static int requireConfigured(Integer value) {
        if (value == null) throw new IllegalArgumentException(REASON_NOT_CONFIGURED);
        if (value < 0 || value > MAX_RUN_FREE_USES) {
            throw new IllegalArgumentException(REASON_CONFIG_INVALID + ":range");
        }
        return value;
    }

    // ---------------------------------------------------------------- internals

    /**
     * 读一个"配置形状"的对象：它的直层 {@code hintFreeUsesPerRun}、直层 {@code hintLadder}
     * 是否存在，以及嵌套 {@code advancedConfigJson}（串或对象）里的同名两键。
     *
     * <p>刻意只认这三个键，不递归其它字段：{@code nodeConfigs} / {@code nodeAnswers} 由
     * {@link #scanRows} 逐行调用本方法，根层调用本方法时也不会误把整张表当成一份配置。
     */
    private static void scanOne(JsonNode config, Set<Integer> declared, LadderProbe ladder) {
        if (config == null || !config.isObject()) return;
        if (config.has(FIELD)) declared.add(read(config.get(FIELD)));
        if (hasLadder(config)) ladder.seen = true;
        JsonNode nested = config.get(ADVANCED_CONFIG_FIELD);
        if (nested == null || nested.isNull()) return;
        if (nested.isObject()) {
            scanOne(nested, declared, ladder);
            return;
        }
        if (!nested.isTextual()) return;
        String raw = nested.asText();
        if (raw == null || raw.isBlank()) return;
        final JsonNode parsed;
        try {
            parsed = JSON.readTree(raw);
        } catch (com.fasterxml.jackson.core.JsonProcessingException broken) {
            throw new IllegalStateException("原局玩法配置不可用", broken);
        }
        scanOne(parsed, declared, ladder);
    }

    private static void scanRows(JsonNode rows, Set<Integer> declared, LadderProbe ladder) {
        if (rows == null || !rows.isObject()) return;
        Iterator<Map.Entry<String, JsonNode>> it = rows.fields();
        while (it.hasNext()) {
            scanOne(it.next().getValue(), declared, ladder);
        }
    }

    /** 非空 {@code hintLadder} 数组才算"作者真的配了阶梯"；空数组等同没配。 */
    private static boolean hasLadder(JsonNode config) {
        JsonNode ladder = config.get(LADDER_FIELD);
        return ladder != null && ladder.isArray() && ladder.size() > 0;
    }

    /** 可变的一次性探针，避免为"是否出现阶梯"再扫一遍快照。 */
    private static final class LadderProbe {
        private boolean seen;
    }

    private static Integer read(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToInt()
                || value.asInt() < 0 || value.asInt() > MAX_RUN_FREE_USES) {
            throw new IllegalArgumentException(REASON_CONFIG_INVALID + ":value");
        }
        return value.asInt();
    }
}
