package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * BE-16 提示阶梯的纯策略层：本局可用层级、剩余免费额度、每级代价，以及每局每级的台账键。
 *
 * <p>依据（私有施工文档 v1.3.2）：BE-16 字段 {@code hintLadder:[{level,type,payload,cost:{points|gems|coins},freeQuota}]}
 * 与验收①「同一次提示重试不重复扣」；M.7 层级 {@code TEXT/IMAGE/LETTER/ANSWER/SKIP/LOCATION} 与
 * 「同一级重新查看不算新使用」。
 *
 * <p>本类只做判定，不读库、不发提示正文、不判分：
 * <ul>
 *   <li>提示正文与获准片段由 G4 从冻结配置取；</li>
 *   <li>分数后果（-30 分、跳过记 0、榜单资格）由 G6/G4 结算，本类不读不写分数；</li>
 *   <li>M.7 的 30 分/2 宝石/5 宝石/1 金币/300 每站是竞品实测与 {@code hint_probe.sh} 可调模型参数，
 *       <b>不是</b>城瘾费率，任何默认值都不从这里产生。</li>
 * </ul>
 *
 * <p>无 {@code hintLadder} 时 {@link #levels(JsonNode)} 返回空表，调用方回落到既有
 * {@code PuzzlePlayService.requestHint}/{@code unlockNodeHint} 旧链（BE-16「无阶梯配置即保持旧行为」）。
 */
public final class PlayHelpUsagePolicy {

    public static final String TYPE_TEXT = "TEXT";
    public static final String TYPE_IMAGE = "IMAGE";
    public static final String TYPE_LETTER = "LETTER";
    /** BE-16 原文是「letter/digit」两级同形；`PlayHelpContent` 已按首字母/首个数字分别实现。 */
    public static final String TYPE_DIGIT = "DIGIT";
    public static final String TYPE_ANSWER = "ANSWER";
    public static final String TYPE_SKIP = "SKIP";
    public static final String TYPE_LOCATION = "LOCATION";

    /** 该层可用。 */
    public static final String OK = null;
    public static final String REASON_TYPE_UNSUPPORTED = "HELP_TYPE_UNSUPPORTED";
    public static final String REASON_GEMS_LEDGER_ABSENT = "GEMS_LEDGER_ABSENT";
    public static final String REASON_COINS_LEDGER_ABSENT = "COINS_LEDGER_ABSENT";
    public static final String REASON_LOCATION_NOT_WIRED = "LOCATION_TOOLS_NOT_WIRED";
    public static final String REASON_CONFIG_INVALID = "HELP_LEVEL_CONFIG_INVALID";
    public static final String REASON_LADDER_INVALID = "HELP_LADDER_INVALID";
    public static final String REASON_SCOPE_INVALID = "PLAY_HELP_SCOPE_INVALID";
    public static final String REASON_REQUEST_ID_INVALID = "PLAY_HELP_REQUEST_ID_INVALID";
    /** 作用域缺本局身份(playInstance):高级局/路线局都必须带,否则额度无法按局隔离。 */
    public static final String REASON_RUN_REQUIRED = "PLAY_HELP_RUN_REQUIRED";
    // 逐层 freeQuota 的「>1 不可用」判定**已删除**:保存闸(V2)现在只允许层内 freeQuota=0,
    // 免费额度统一由整局 hintFreeUsesPerRun 表达。运行期再到 >1 只可能是历史脏数据,
    // 按「首次免费」读即可 —— 这里再加一条拒绝只会把老局的一层标成不可用。

    /** `play_attempt_log.idempotency_key` 是 VARCHAR(128)：超长直接失败，不静默截断。 */
    public static final int MAX_LEDGER_KEY_LENGTH = 128;
    /** 与既有 `game_command_receipt.request_id VARCHAR(64)` 同口径。 */
    public static final int MAX_REQUEST_ID_LENGTH = 64;
    /** 原局身份上限：UUID(36) 或 `game:<id>` 都远小于此值；超长说明不是一局。 */
    private static final int MAX_PLAY_INSTANCE_LENGTH = 64;
    private static final String SCOPE_PREFIX = "help1";
    /**
     * 高级玩法局的作用域前缀。**故意与 {@link #SCOPE_PREFIX} 不同**：
     * 共享路线局的实例名也是 {@code game:<id>}，只按实例名区分会让两类局共用池与事实键。
     * 换个前缀既能把 kind 落到持久身份里，又让所有既有 {@code help1:...} 旧键保持逐字节不变。
     */
    private static final String ADVANCED_SCOPE_PREFIX = "help1a";
    private static final String REQUEST_KEY_PREFIX = "help1req:";
    private static final String POINTS_KEY_PREFIX = "play-help:";

    private PlayHelpUsagePolicy() {
    }

    /**
     * 原局种类。**只由服务端已核验的 resolve 入口赋值**，客户端永远传不进来。
     *
     * <p>为什么必须有它：两类原局是不同实体（{@code topic_route_session} 与
     * {@code game_runtime_session}），归属守卫、状态机、以及额度/事实/回执的唯一键都不一样。
     * 而 {@code TopicRouteSession} 的共享局实例名恰好也是 {@code "game:<id>"}，
     * 只按 instance 字符串区分会让两类局共用同一个池、同一批事实键。
     */
    public enum RunKind {
        /** 个人/队伍路线局，原局身份在 {@code topic_route_session}。 */
        TOPIC_ROUTE,
        /** 纯高级玩法局，原局身份在 {@code game_runtime_session}。 */
        ADVANCED_GAME
    }

    /**
     * 一局的一站。{@code playInstance} 必须是原运行身份
     * （{@code PlayOriginalRun.progressInstance()} 或高级局的 {@code game:<sessionId>}），
     * 无局时的 {@code "default"} 不是一局。
     */
    public record Scope(Long topicId, Long activityId, Long nodeId, String playInstance, RunKind kind) {
        public Scope {
            if (topicId == null || topicId <= 0) throw new IllegalArgumentException(REASON_SCOPE_INVALID + ":topicId");
            if (nodeId == null || nodeId <= 0) throw new IllegalArgumentException(REASON_SCOPE_INVALID + ":nodeId");
            if (activityId != null && activityId <= 0) activityId = null;
            String run = playInstance == null ? null : playInstance.trim();
            if (run == null || run.isEmpty() || "default".equalsIgnoreCase(run)) {
                throw new IllegalArgumentException(REASON_RUN_REQUIRED);
            }
            if (run.length() > MAX_PLAY_INSTANCE_LENGTH) {
                throw new IllegalArgumentException(REASON_SCOPE_INVALID + ":playInstance");
            }
            playInstance = run;
            // 缺省沿用旧语义：4 参调用点（存量调用方与旧测试）一律是路线局。
            if (kind == null) kind = RunKind.TOPIC_ROUTE;
        }

        /** 旧 4 参构造：源码兼容，等价于 {@link RunKind#TOPIC_ROUTE}。 */
        public Scope(Long topicId, Long activityId, Long nodeId, String playInstance) {
            this(topicId, activityId, nodeId, playInstance, RunKind.TOPIC_ROUTE);
        }

        /**
         * 高级玩法局的作用域。{@code sessionId} 是**服务端已核验**的
         * {@code game_runtime_session.id}，实例名固定为 {@code game:<id>}。
         */
        public static Scope advanced(Long topicId, Long activityId, Long nodeId, long sessionId) {
            if (sessionId <= 0) throw new IllegalArgumentException(REASON_SCOPE_INVALID + ":sessionId");
            return new Scope(topicId, activityId, nodeId, "game:" + sessionId, RunKind.ADVANCED_GAME);
        }

        /** 高级局对应的 session id；非高级局返回 null。 */
        public Long advancedSessionId() {
            if (kind != RunKind.ADVANCED_GAME) return null;
            String run = playInstance();
            if (!run.startsWith("game:")) return null;
            try {
                long id = Long.parseLong(run.substring("game:".length()));
                return id > 0 ? Long.valueOf(id) : null;
            } catch (NumberFormatException invalid) {
                return null;
            }
        }
    }

    /** 一层的配置；{@code reason == null} 表示可用。 */
    public record LevelSpec(int level, String type, int freeQuota, int costPoints, String reason) {
        /** 「同一级重新查看不算新使用」⇒ 单局内该级只有一次使用，免费额度按「首次是否免费」表达。 */
        public boolean freeOnFirstUse() {
            return freeQuota >= 1;
        }

        public boolean usable() {
            return reason == null;
        }
    }

    /** 下发给玩家的一层（GET /api/play/help 的面）；只含代价与状态，不含提示正文。 */
    public record LevelView(int level, String type, boolean usable, boolean unlocked, boolean free,
            int costPoints, int freeQuota, String reason) {
    }

    /**
     * 解析作者配置的层级，保持作者书写顺序；不补层、不排序、不强制六层。
     *
     * @throws IllegalArgumentException 配置结构损坏（非数组、level 重复/非正）——保存期由
     *         {@code AdvancedGameConfigValidator} 校验，运行期遇到说明数据已损坏，fail-closed。
     */
    public static List<LevelSpec> levels(JsonNode hintLadder) {
        List<LevelSpec> specs = new ArrayList<>();
        if (hintLadder == null || hintLadder.isMissingNode() || hintLadder.isNull()) return specs;
        if (!hintLadder.isArray()) throw new IllegalArgumentException(REASON_LADDER_INVALID + ":not-array");
        java.util.HashSet<Integer> seen = new java.util.HashSet<>();
        for (JsonNode entry : hintLadder) {
            if (entry == null || !entry.isObject()) throw new IllegalArgumentException(REASON_LADDER_INVALID + ":entry");
            JsonNode levelNode = entry.path("level");
            if (!levelNode.isInt() && !levelNode.isIntegralNumber()) {
                throw new IllegalArgumentException(REASON_LADDER_INVALID + ":level");
            }
            int level = levelNode.asInt();
            if (level <= 0 || !seen.add(level)) throw new IllegalArgumentException(REASON_LADDER_INVALID + ":level");
            String type = entry.path("type").asText("").trim().toUpperCase(Locale.ROOT);
            int freeQuota = intOrZero(entry.path("freeQuota"));
            Cost cost = parseCost(entry.path("cost"));
            String reason = verdict(type, freeQuota, cost);
            specs.add(new LevelSpec(level, type, freeQuota, cost.points(), reason));
        }
        return specs;
    }

    /**
     * 旧口径(本局未声明整局 N)的每级视图:免费按**层**的 {@code freeQuota} 表达。
     * 老模板一字不改 —— 这里改口径就等于悄悄改了已发布旧题的收费。
     */
    public static List<LevelView> views(JsonNode hintLadder, Set<Integer> unlockedLevels) {
        Set<Integer> unlocked = unlockedLevels == null ? Set.of() : unlockedLevels;
        List<LevelSpec> specs = levels(hintLadder);
        List<LevelView> views = new ArrayList<>(specs.size());
        for (LevelSpec spec : specs) {
            boolean unlockedLevel = unlocked.contains(spec.level());
            if (!spec.usable()) {
                views.add(new LevelView(spec.level(), spec.type(), false, unlockedLevel, false,
                        spec.costPoints(), spec.freeQuota(), spec.reason()));
                continue;
            }
            boolean free = unlockedLevel || spec.freeOnFirstUse();
            views.add(new LevelView(spec.level(), spec.type(), true, unlockedLevel, free,
                    unlockedLevel ? 0 : free ? 0 : spec.costPoints(), spec.freeQuota(), OK));
        }
        return views;
    }

    /**
     * 每层「可用 / 已解锁 / 免费 / 实际代价」。
     *
     * <p>免费口径按用户 2026-11-08 定稿执行:**整局共享 N 次**，不是逐层各 0|1 次。
     * 首解锁一层消耗一次免费额度；已解锁的层重看始终免费（{@code costPoints=0}）。
     *
     * @param remainingFreeUses 本局**还剩**几次免费额度，由整局额度账本给出
     *                          （G7 {@code PlayHelpRunQuota*}；本类只消费，不计数、不落账）
     */
    public static List<LevelView> views(JsonNode hintLadder, Set<Integer> unlockedLevels,
            int remainingFreeUses) {
        Set<Integer> unlocked = unlockedLevels == null ? Set.of() : unlockedLevels;
        int remaining = Math.max(0, remainingFreeUses);
        List<LevelSpec> specs = levels(hintLadder);
        List<LevelView> views = new ArrayList<>(specs.size());
        for (LevelSpec spec : specs) {
            boolean unlockedLevel = unlocked.contains(spec.level());
            if (!spec.usable()) {
                views.add(new LevelView(spec.level(), spec.type(), false, unlockedLevel, false,
                        spec.costPoints(), spec.freeQuota(), spec.reason()));
                continue;
            }
            // 已解锁重看恒免费；否则只要有剩余免费额度就免费，额度用尽才显示作者配置的积分代价。
            boolean free = unlockedLevel || remaining > 0;
            views.add(new LevelView(spec.level(), spec.type(), true, unlockedLevel, free,
                    unlockedLevel ? 0 : free ? 0 : spec.costPoints(), spec.freeQuota(), OK));
        }
        return views;
    }

    // 整局共享免费次数 N 的解析**不在这里**:G7 {@code PlayHelpRunQuotaPolicy} 是唯一真源
    // (根层标量优先、多节点声明冲突即拒)。本类只消费调用方给出的剩余次数,不自己读配置 ——
    // 两处各读一遍,一处取当前节点、一处取整局,迟早给出不同的数。

    /** 每局每级的台账 scope：含会员、主题、活动、节点与原局身份。 */
    public static String scopeKey(Scope scope) {
        if (scope == null) throw new IllegalArgumentException(REASON_SCOPE_INVALID + ":null");
        String encodedRun = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(scope.playInstance().getBytes(StandardCharsets.UTF_8));
        String prefix = scope.kind() == RunKind.ADVANCED_GAME ? ADVANCED_SCOPE_PREFIX : SCOPE_PREFIX;
        String key = prefix + ":" + scope.topicId() + ":" + (scope.activityId() == null ? 0L : scope.activityId())
                + ":" + scope.nodeId() + ":" + encodedRun;
        requireKeyLength(key, "scopeKey");
        return key;
    }

    /** 反解 scopeKey；非法或含「无局」身份一律失败，不放行。 */
    public static Scope parseScope(String scopeKey) {
        if (scopeKey == null || scopeKey.isBlank()) throw new IllegalArgumentException(REASON_SCOPE_INVALID + ":blank");
        String[] parts = scopeKey.split(":", -1);
        if (parts.length != 5
                || (!SCOPE_PREFIX.equals(parts[0]) && !ADVANCED_SCOPE_PREFIX.equals(parts[0]))) {
            throw new IllegalArgumentException(REASON_SCOPE_INVALID + ":format");
        }
        RunKind kind = ADVANCED_SCOPE_PREFIX.equals(parts[0]) ? RunKind.ADVANCED_GAME : RunKind.TOPIC_ROUTE;
        Long topicId = positiveLong(parts[1], "topicId");
        long activityRaw = positiveLong(parts[2], "activityId");
        Long nodeId = positiveLong(parts[3], "nodeId");
        String playInstance;
        try {
            playInstance = new String(Base64.getUrlDecoder().decode(parts[4]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(REASON_SCOPE_INVALID + ":playInstance");
        }
        return new Scope(topicId, activityRaw <= 0 ? null : activityRaw, nodeId, playInstance, kind);
    }

    /** 每局每级事实键：`play_attempt_log.idempotency_key`，由既有唯一索引做并发闸。 */
    /**
     * 每局每级的事实键。**必须带会员**:一局里多个玩家共用同一个 playInstance,
     * 只按局+节点+层建键会让 B 玩家撞上 A 玩家已经用过的行(A 免费、B 被当已解锁),
     * 也可能让 A 的解锁挡住 B 的收费。会员是动作主体,不能只放在指纹里。
     */
    public static String levelFactKey(long memberId, String scopeKey, int level) {
        if (memberId <= 0) throw new IllegalArgumentException(REASON_SCOPE_INVALID + ":memberId");
        if (level <= 0) throw new IllegalArgumentException(REASON_CONFIG_INVALID + ":level");
        String key = scopeKey + ":M" + memberId + ":L" + level;
        requireKeyLength(key, "levelFactKey");
        return key;
    }

    /** 每局每级扣款键：`ums_member_points_detail.reward_business_key`，由既有唯一索引做并发闸。 */
    public static String pointsBusinessKey(long memberId, String scopeKey, int level) {
        if (memberId <= 0) throw new IllegalArgumentException(REASON_SCOPE_INVALID + ":memberId");
        if (level <= 0) throw new IllegalArgumentException(REASON_CONFIG_INVALID + ":level");
        return POINTS_KEY_PREFIX + digest(memberId + "|" + scopeKey + ":L" + level, 32);
    }

    /**
     * 同一事实键下的代价指纹：同局同级但代价/免费判定变了，必须报冲突，不能复用旧结果冒充成功。
     */
    public static String factFingerprint(long memberId, String scopeKey, int level, boolean free,
            int chargedPoints) {
        return digest("play-help-fact:v1|" + memberId + "|" + scopeKey + "|" + level + "|" + free
                + "|" + chargedPoints, 64);
    }

    /**
     * 一次请求的回执键：同一 requestId 只允许一个结果，不同 requestId 才可能是新动作。
     * 用摘要而不是拼接原始 requestId：客户端可能传长串，而 {@code idempotency_key} 只有 128。
     */
    public static String requestFactKey(long memberId, String scopeKey, String requestId) {
        if (memberId <= 0) throw new IllegalArgumentException(REASON_SCOPE_INVALID + ":memberId");
        String request = requireRequestId(requestId);
        String key = REQUEST_KEY_PREFIX + digest(memberId + "|" + scopeKey + "|" + request, 32);
        requireKeyLength(key, "requestFactKey");
        return key;
    }

    /** 同一次请求的身份指纹：requestId 相同但层级/代价变了，必须报冲突，不能按旧结果冒充成功。 */
    public static String requestFingerprint(long memberId, String scopeKey, int level, boolean free,
            int chargedPoints) {
        return digest("play-help-request:v1|" + memberId + "|" + scopeKey + "|" + level + "|" + free
                + "|" + chargedPoints, 64);
    }

    /** 与既有 {@code game_command_receipt.request_id VARCHAR(64)} 同一长度口径。 */
    public static String requireRequestId(String requestId) {
        String value = requestId == null ? null : requestId.trim();
        if (value == null || value.isEmpty() || value.length() > MAX_REQUEST_ID_LENGTH) {
            throw new IllegalArgumentException(REASON_REQUEST_ID_INVALID);
        }
        return value;
    }

    public static String digest(String raw, int hexChars) {
        if (raw == null) throw new IllegalArgumentException(REASON_SCOPE_INVALID + ":digest");
        if (hexChars <= 0 || hexChars > 64 || (hexChars & 1) == 1) {
            throw new IllegalArgumentException("digest length");
        }
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : bytes) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return hex.substring(0, hexChars);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Cost(int points, String reason) {
    }

    /**
     * 只接受 {@code points}；`gems`/`coins` 在城瘾没有真实账本（BE-16 迁移靠
     * `HelpPoolConfig`，本树零命中），出现正数即该级不可用，绝不假扣款。
     */
    private static Cost parseCost(JsonNode cost) {
        if (cost == null || cost.isMissingNode() || cost.isNull()) return new Cost(0, OK);
        if (!cost.isObject()) return new Cost(0, REASON_CONFIG_INVALID);
        int points = 0;
        String reason = OK;
        var fields = cost.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            JsonNode value = cost.path(field);
            if ("points".equals(field)) {
                if (value.isNumber() && value.isIntegralNumber() && value.asInt() >= 0) {
                    points = value.asInt();
                } else {
                    reason = REASON_CONFIG_INVALID;
                }
                continue;
            }
            boolean zero = value.isNumber() && value.asDouble() == 0d;
            if ("gems".equals(field)) {
                if (!zero && !REASON_CONFIG_INVALID.equals(reason)) reason = REASON_GEMS_LEDGER_ABSENT;
                continue;
            }
            if ("coins".equals(field)) {
                if (!zero && !REASON_CONFIG_INVALID.equals(reason)) reason = REASON_COINS_LEDGER_ABSENT;
                continue;
            }
            reason = REASON_CONFIG_INVALID;
        }
        return new Cost(points, reason);
    }

    private static String verdict(String type, int freeQuota, Cost cost) {
        if (freeQuota < 0) return REASON_CONFIG_INVALID;
        if (cost.reason() != null) return cost.reason();
        if (TYPE_LOCATION.equals(type)) return REASON_LOCATION_NOT_WIRED;
        return switch (type) {
            case TYPE_TEXT, TYPE_IMAGE, TYPE_LETTER, TYPE_DIGIT, TYPE_ANSWER, TYPE_SKIP -> OK;
            default -> REASON_TYPE_UNSUPPORTED;
        };
    }

    private static int intOrZero(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return 0;
        if (!node.isIntegralNumber()) return -1;
        return node.asInt();
    }

    private static Long positiveLong(String raw, String field) {
        try {
            long value = Long.parseLong(raw);
            if (value < 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(REASON_SCOPE_INVALID + ":" + field);
        }
    }

    private static void requireKeyLength(String key, String what) {
        if (key.length() > MAX_LEDGER_KEY_LENGTH) {
            throw new IllegalArgumentException(REASON_SCOPE_INVALID + ":" + what + "-too-long");
        }
    }
}
