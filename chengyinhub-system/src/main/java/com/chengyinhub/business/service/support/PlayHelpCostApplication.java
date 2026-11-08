package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.PlayAttemptLog;
import com.chengyinhub.business.domain.UmsMemberPointsDetail;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IUmsMemberPointsDetailService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

/**
 * BE-16 提示代价适配器：每局每级的「事实抢占 → 抢积分流水 → 原子扣减」。
 *
 * <p><b>事务边界</b>：本类三个入口都是 {@code Propagation.MANDATORY}，必须在 G4 的外层事务里调用。
 * 哪个步骤抛错，整个事务回滚 —— 于是「扣失败不解锁、解锁失败不扣」由数据库事务保证，不靠代码补偿。
 * 禁止在事务内 catch 后继续返回：那会留下已写事实却不回滚。
 *
 * <p><b>幂等按维度分层，每层都有已存在的唯一键做闸，不靠应用层「先查再写」</b>：
 * <ol>
 *   <li><b>请求重试</b>（同一 requestId 重发）：归 G4 的原会话回执链。本类不重复实现；
 *       具体是 {@code game_command_receipt} 还是 {@code AdvancedGameRuntimeServiceImpl} 的
 *       {@code play_attempt_log.attempt_type=8} 回执，由 G0 按 G4 实际选的链定稿，见 contract §3.1。</li>
 *   <li><b>每局每级</b>（换 requestId 重买同一级）：{@code play_attempt_log.uk_play_attempt_idempotency}。
 *       该唯一索引由 {@code migration_20260820_new_city_preference_chain.sql:54} 建立，
 *       本类填充 {@code idempotency_key = help1:<主题>:<活动>:<节点>:<局身份base64>:L<级别>}。</li>
 *   <li><b>钱</b>（同一次收费只落一行）：{@code ums_member_points_detail.uk_member_reward_business
 *       (member_id, reward_business_key)}。该键由 {@code migration_p1_funds_core_uniqueness_20260724.sql:220-230}
 *       建立并在同文件从 {@code information_schema.statistics} 回读校验定义，
 *       本类填充 {@code reward_business_key = play-help:<sha256前32hex>}（非空、每局每级唯一）。</li>
 * </ol>
 *
 * <p><b>为什么没把 {@code uk_member_event_once(member, event_type, event_id)} 当每局每级</b>：
 * 它是会员+事件维度。同一会员第二局回到同一节点，{@code (member,14,nodeId)} 直接撞键，
 * 结果只能是「第二局不能扣（漏服务）」或「误判已解锁（漏扣）」。
 * 本节去重靠的是上面那条 {@code reward_business_key} 唯一键，<b>不是</b>把 {@code event_id} 置 NULL 来「绕过」：
 * {@code event_id} 置 NULL 只是让这一条本不表达每局语义的旧唯一键不误伤，
 * 行本身仍被 {@code reward_business_key} 严格约束。
 * （同一结论见 BE-16/M.7 与 G12：「不能直接把它包装成按局/按级收费」。）
 *
 * <p><b>旧提示收费语义不变</b>：{@code /api/play/puzzle/hint}、{@code /unlock-hint} 与
 * {@code PuzzlePlayServiceImpl} 一行未改；它们的 {@code (member,14,nodeId)} 一次性解锁仍在。
 * 因此同一节点不能同时提供新旧两条收费入口 —— 冻结配置有 {@code hintLadder} 时由 G4 只走本节，
 * 无 {@code hintLadder} 时回落到旧链（BE-16「无阶梯配置即保持旧行为」）。这条是两个入口的共用闸，归 G0 接线。
 *
 * <p><b>只接有真实账本的代价</b>：积分 {@code ums_member.point} 是唯一可扣余额。
 * 宝石/金币在本树没有任何账本（{@code HelpPoolConfig}/{@code AppClaimFirstQuestHelpPack} 零命中），
 * {@code PlayHelpUsagePolicy} 已把这些层标为不可用，本类拿不到它们也就不会假扣款。XP 是发放预算不是余额，同样不接。
 */
@Service
public class PlayHelpCostApplication {

    /**
     * 复用既有 `attempt_type=5`（谜题提示）这一个类，不新增 attempt_type。
     *
     * <p>理由：`v_node_quality` 只排除 `attempt_type = 5`（`migration_20260820_questo_puzzle_atmosphere.sql:26`），
     * 新开一个类会把帮助事实算进 `attempt_cnt`，静默污染通过率/放弃率/独立人数；改那个视图属于新 DDL。
     *
     * <p>与旧提示行的区分靠两处，不靠类型码：本节事实 `result` 为 NULL（旧行只会是 1/2），
     * 且 `idempotency_key` 一定是 `help1:` 前缀的每局每级键。因此旧链的
     * `coalesce(max(result), 0)` 与 `max(result)` 都忽略本行，旧提示等级语义零变化。
     */
    public static final int ATTEMPT_TYPE_HELP = PlayAttemptLog.ATTEMPT_TYPE_PUZZLE_HINT;

    /** 既有积分规则事件「解锁提示」（`cms_points_setting.type=1, event_type=14`）继续沿用，不新增费率事件。 */
    public static final long POINTS_EVENT_TYPE_HELP = 14L;

    private static final String FACT_SCHEMA = "play-help-fact:v1";
    private static final String REQUEST_SCHEMA = "play-help-request:v1";
    private static final String CHANGE_REASON_PREFIX = "提示扣减";

    public enum ClaimState {
        /** 本事务拿到该局该级的唯一事实。 */
        CLAIMED,
        /** 之前已经解锁：可重看内容，本次 0 扣。 */
        ALREADY_UNLOCKED
    }

    public record Claim(ClaimState state, int level, boolean free, int chargedPoints, String businessKey) {
    }

    /** 调用方唯一需要消费的结果：{@code applied=false && alreadyUnlocked=true} 表示重看。 */
    public record Charge(boolean applied, boolean alreadyUnlocked, int level, int chargedPoints,
            long balanceAfter, String businessKey) {
    }

    /** 业务失败；必须让它穿过事务边界到控制器再映射文案。 */
    public static class PlayHelpCostException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;

        public PlayHelpCostException(String code, String message) {
            super(message);
            this.code = code;
        }

        public PlayHelpCostException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private final PlayAttemptLogMapper attempts;
    private final UmsMemberMapper members;
    private final IUmsMemberService memberService;
    private final IUmsMemberPointsDetailService pointsDetails;
    private final ObjectMapper json;

    @Autowired
    public PlayHelpCostApplication(PlayAttemptLogMapper attempts, UmsMemberMapper members,
            IUmsMemberService memberService, IUmsMemberPointsDetailService pointsDetails, ObjectMapper json) {
        this.attempts = attempts;
        this.members = members;
        this.memberService = memberService;
        this.pointsDetails = pointsDetails;
        this.json = json;
    }

    /**
     * G4 唯一应调用的入口：请求回执 → 本局本级事实 → 抢流水 → 原子扣减。
     *
     * @param freeQuota 来自冻结的 {@code hintLadder[].freeQuota}；≥1 表示该级本局首次使用免费
     * @param chargePoints 来自冻结的 {@code hintLadder[].cost.points}；必须 ≥0
     * @param requestId 客户端动作身份。同名重发只落一次结果；同名异 payload 报冲突。
     *        传 null 表示调用方没有动作身份（只能做每局每级去重，不能证明「同一次动作」）。
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public Charge use(long memberId, String scopeKey, int level, int freeQuota, int chargePoints,
            String requestId) {
        if (memberId <= 0) throw new PlayHelpCostException("LOGIN_REQUIRED", "请先登录");
        if (chargePoints < 0 || freeQuota < 0) {
            throw new PlayHelpCostException(PlayHelpUsagePolicy.REASON_CONFIG_INVALID, "提示代价配置无效");
        }
        PlayHelpUsagePolicy.Scope scope = scope(scopeKey);
        boolean free = freeQuota >= 1;
        String factKey = PlayHelpUsagePolicy.levelFactKey(memberId, scopeKey, level);
        String fingerprint = PlayHelpUsagePolicy.factFingerprint(memberId, scopeKey, level, free, chargePoints);
        String requestKey = requestKey(memberId, scopeKey, requestId);
        String requestFingerprint = requestKey == null ? null
                : PlayHelpUsagePolicy.requestFingerprint(memberId, scopeKey, level, free, chargePoints);

        PlayAttemptLog prior = loadFact(factKey);
        if (prior != null) {
            Charge replayed = replay(prior, memberId, level, fingerprint);
            return recordRequest(requestKey, memberId, scope, level, requestFingerprint, replayed);
        }

        Long locked = members.lockMemberById(memberId);
        if (locked == null) throw new PlayHelpCostException("LOGIN_REQUIRED", "用户状态不可用,请重新登录");
        long balance = currentPoints(memberId);
        if (!free && balance < chargePoints) {
            throw new PlayHelpCostException("POINTS_INSUFFICIENT", "积分不足");
        }

        Claim claim = claim(memberId, scope, factKey, level, free, chargePoints, fingerprint);
        // 撞键由 claim 内读赢家判定：同指纹 = 已解锁，异指纹/异身份 = 抛冲突（不会返回 CONFLICT 状态）。
        if (claim.state() == ClaimState.ALREADY_UNLOCKED) {
            Charge replayed = new Charge(false, true, level, claim.chargedPoints(), balance, claim.businessKey());
            return recordRequest(requestKey, memberId, scope, level, requestFingerprint, replayed);
        }
        Charge applied = claim.free() || claim.chargedPoints() == 0
                ? new Charge(true, false, level, 0, balance, null)
                : debit(memberId, scope, level, claim.chargedPoints(), balance, claim.businessKey());
        return recordRequest(requestKey, memberId, scope, level, requestFingerprint, applied);
    }

    /** 兼容入口：调用方拿不到动作身份时只能按每局每级去重，见 {@link #use(long, String, int, int, int, String)}。 */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public Charge use(long memberId, String scopeKey, int level, int freeQuota, int chargePoints) {
        return use(memberId, scopeKey, level, freeQuota, chargePoints, null);
    }

    /**
     * 抢占每局每级事实。唯一键撞车时读赢家：同指纹 = 已解锁（0 扣），异指纹 = 冲突。
     * 不对外暴露为独立事务；G4 应只调 {@link #use}。
     */
    Claim claim(long memberId, PlayHelpUsagePolicy.Scope scope, String factKey, int level, boolean free,
            int chargePoints, String fingerprint) {
        PlayAttemptLog log = new PlayAttemptLog();
        log.setMemberId(memberId);
        log.setTopicId(scope.topicId());
        log.setNodeId(scope.nodeId());
        log.setActivityId(scope.activityId());
        log.setAttemptType(ATTEMPT_TYPE_HELP);
        // result 留空：这条事实只说「用过这一级帮助」，既不是答对(1)也不是答错(0)/放弃(2)。
        // 填 1 会让只按 result 统计的通关/质量读数把它算成通过。
        log.setResult(null);
        log.setResultJson(factJson(memberId, scope, level, free, chargePoints, fingerprint));
        log.setIdempotencyKey(factKey);
        log.setRequestFingerprint(fingerprint);
        log.setCreatedAt(new Date());
        try {
            if (attempts.insertPlayAttemptLog(log) <= 0) {
                throw new PlayHelpCostException("HELP_STATE_UNAVAILABLE", "提示状态暂时不可用,请重试");
            }
        } catch (DuplicateKeyException duplicate) {
            PlayAttemptLog winner = loadFact(factKey);
            if (winner == null) throw duplicate;
            // 校验身份与代价指纹；不一致时 replay 直接抛冲突，绝不按「已解锁」放行。
            replay(winner, memberId, level, fingerprint);
            return new Claim(ClaimState.ALREADY_UNLOCKED, level, true, 0, null);
        }
        String businessKey = free || chargePoints == 0 ? null
                : PlayHelpUsagePolicy.pointsBusinessKey(memberId, PlayHelpUsagePolicy.scopeKey(scope), level);
        return new Claim(ClaimState.CLAIMED, level, free, chargePoints, businessKey);
    }

    /**
     * 收费：先抢积分流水唯一键（并发幂等真闸），再原子扣余额。
     * 顺序照原实现：先扣再插会让后到请求撞键时已经重复扣款。
     */
    Charge debit(long memberId, PlayHelpUsagePolicy.Scope scope, int level, int chargePoints, long balance,
            String businessKey) {
        if (chargePoints <= 0) return new Charge(true, false, level, 0, balance, null);
        String key = businessKey == null
                ? PlayHelpUsagePolicy.pointsBusinessKey(memberId, PlayHelpUsagePolicy.scopeKey(scope), level) : businessKey;
        UmsMemberPointsDetail row = new UmsMemberPointsDetail();
        row.setMemberId(memberId);
        row.setCurrentPoints(balance);
        row.setAfterPoints(balance - chargePoints);
        row.setChangePoints((long) chargePoints);
        row.setChangeReason(CHANGE_REASON_PREFIX + " L" + level + " node=" + scope.nodeId());
        row.setEventType(POINTS_EVENT_TYPE_HELP);
        // event_id 必须为 NULL：唯一键 uk_member_event_once 对 NULL 互不相等，
        // 这样第二局同名节点不会被 member+event+node 误判成「已解锁」。
        row.setEventId(null);
        row.setRewardBusinessKey(key);
        row.setChangeType(2L);
        row.setDelFlag(0);
        row.setCreateTime(new Date());
        try {
            pointsDetails.insertUmsMemberPointsDetail(row);
        } catch (DuplicateKeyException duplicate) {
            throw new PlayHelpCostException("PLAY_HELP_CHARGE_CONFLICT", "提示扣费记录冲突，请核对原回执", duplicate);
        }
        try {
            memberService.deductPoints(memberId, chargePoints);
        } catch (RuntimeException insufficient) {
            // 抛出让外层事务回滚已写的事实行与流水行：余额不足零副作用。
            throw new PlayHelpCostException("POINTS_INSUFFICIENT", "积分不足", insufficient);
        }
        return new Charge(true, false, level, chargePoints, balance - chargePoints, key);
    }

    /**
     * fail-closed：单事务内不需要补偿；跨事务补偿要么删事实（未扣费时）、要么退款（已扣费时），
     * 后者是资金政策，未获授权。因此存在事实行时**拒绝**，绝不静默删记录或反向加钱。
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void release(long memberId, String scopeKey, int level) {
        if (memberId <= 0) throw new PlayHelpCostException("LOGIN_REQUIRED", "请先登录");
        String factKey = PlayHelpUsagePolicy.levelFactKey(memberId, scopeKey, level);
        PlayAttemptLog prior = loadFact(factKey);
        if (prior == null) return;
        throw new PlayHelpCostException("PLAY_HELP_RELEASE_UNSUPPORTED",
                "提示事实不能跨事务回滚：已扣费需走退款/对账，未扣费请让外层事务回滚");
    }

    private Charge replay(PlayAttemptLog prior, long memberId, int level, String fingerprint) {
        if (prior.getMemberId() == null || prior.getMemberId() != memberId
                || prior.getAttemptType() == null || prior.getAttemptType() != ATTEMPT_TYPE_HELP) {
            throw new PlayHelpCostException("PLAY_HELP_LEVEL_CONFLICT", "该级提示已被其它身份占用");
        }
        String original = prior.getRequestFingerprint();
        if (original == null || !original.equals(fingerprint)) {
            throw new PlayHelpCostException("PLAY_HELP_LEVEL_CONFLICT", "该级提示已按其它代价解锁，不能改价重扣");
        }
        // 本次动作没有花任何代价：这一层在更早的某次动作里就已经解锁。
        // 首次的金额留在那条事实行与积分流水里，由它自己承载，不在这里重复报告，
        // 否则界面会把「重看」显示成又一次扣款。
        return new Charge(false, true, level, 0, currentPointsForReport(memberId), null);
    }

    /**
     * 记下这次请求的结果。与每局每级事实分开：一个维度是「这次动作」，另一个是「这级已解锁」。
     *
     * <p>放在扣款之后写：撞键说明同名请求已存在。
     * 同指纹 = 原请求重发 → 回放原结果；异指纹 = 同一个 requestId 换了层级/代价 → 冲突，
     * 绝不把它当成一次新的成功（也不因此重复扣款）。
     *
     * <p>行内 {@code resultJson} 记的 chargedPoints 已经是最终值（含「已解锁所以 0 扣」），
     * 所以回放不会对外报告一个没发生过的扣款。
     */
    private Charge recordRequest(String requestKey, long memberId, PlayHelpUsagePolicy.Scope scope, int level,
            String requestFingerprint, Charge settled) {
        if (requestKey == null) return settled;
        PlayAttemptLog log = new PlayAttemptLog();
        log.setMemberId(memberId);
        log.setTopicId(scope.topicId());
        log.setNodeId(scope.nodeId());
        log.setActivityId(scope.activityId());
        log.setAttemptType(ATTEMPT_TYPE_HELP);
        log.setResult(null);
        log.setResultJson(requestJson(scope, level, requestFingerprint, settled));
        log.setIdempotencyKey(requestKey);
        log.setRequestFingerprint(requestFingerprint);
        log.setCreatedAt(new Date());
        try {
            if (attempts.insertPlayAttemptLog(log) <= 0) {
                throw new PlayHelpCostException("HELP_STATE_UNAVAILABLE", "提示状态暂时不可用,请重试");
            }
            return settled;
        } catch (DuplicateKeyException duplicate) {
            return replayRequest(requestKey, memberId, level, requestFingerprint);
        }
    }

    private Charge replayRequest(String requestKey, long memberId, int level, String requestFingerprint) {
        PlayAttemptLog winner = loadFact(requestKey);
        if (winner == null) {
            // 并发同名请求已插行但未提交：结果待确认，不能当成失败后重扣。
            throw new PlayHelpCostException("PLAY_HELP_RESULT_PENDING",
                    "该次帮助请求结果仍待确认，请用原请求重试");
        }
        if (winner.getMemberId() == null || winner.getMemberId() != memberId
                || winner.getAttemptType() == null || winner.getAttemptType() != ATTEMPT_TYPE_HELP) {
            throw new PlayHelpCostException("PLAY_HELP_REQUEST_CONFLICT", "该请求编号已被其它身份占用");
        }
        try {
            JsonNode view = winner.getResultJson() == null ? null : json.readTree(winner.getResultJson());
            if (view == null) {
                throw new PlayHelpCostException("HELP_STATE_UNAVAILABLE", "提示状态暂时不可用,请重试");
            }
            int replayedLevel = view.path("level").asInt(0);
            String original = view.path("fingerprint").asText("");
            if (replayedLevel != level || requestFingerprint == null || !original.equals(requestFingerprint)) {
                throw new PlayHelpCostException("PLAY_HELP_REQUEST_CONFLICT",
                        "同一请求编号的层级或代价已改变，不能当作新的成功");
            }
            String businessKey = view.path("businessKey").isTextual() ? view.path("businessKey").asText() : null;
            return new Charge(false, view.path("alreadyUnlocked").asBoolean(false), replayedLevel,
                    view.path("chargedPoints").asInt(0), currentPointsForReport(memberId), businessKey);
        } catch (com.fasterxml.jackson.core.JsonProcessingException broken) {
            throw new PlayHelpCostException("HELP_STATE_UNAVAILABLE", "提示状态暂时不可用,请重试", broken);
        }
    }

    private static String requestKey(long memberId, String scopeKey, String requestId) {
        if (requestId == null || requestId.isBlank()) return null;
        try {
            return PlayHelpUsagePolicy.requestFactKey(memberId, scopeKey, requestId);
        } catch (IllegalArgumentException invalid) {
            throw new PlayHelpCostException(PlayHelpUsagePolicy.REASON_REQUEST_ID_INVALID,
                    "请求编号无效");
        }
    }

    private String requestJson(PlayHelpUsagePolicy.Scope scope, int level, String fingerprint, Charge settled) {
        ObjectNode fact = json.createObjectNode();
        fact.put("schema", REQUEST_SCHEMA);
        fact.put("topicId", scope.topicId());
        if (scope.activityId() != null) fact.put("activityId", scope.activityId());
        fact.put("nodeId", scope.nodeId());
        fact.put("playInstance", scope.playInstance());
        fact.put("level", level);
        fact.put("alreadyUnlocked", settled.alreadyUnlocked());
        fact.put("chargedPoints", settled.chargedPoints());
        if (settled.businessKey() != null) fact.put("businessKey", settled.businessKey());
        fact.put("fingerprint", fingerprint);
        return fact.toString();
    }

    private PlayAttemptLog loadFact(String factKey) {
        try {
            return attempts.selectByIdempotencyKey(factKey);
        } catch (RuntimeException broken) {
            throw new PlayHelpCostException("HELP_STATE_UNAVAILABLE", "提示状态暂时不可用,请重试", broken);
        }
    }

    private PlayHelpUsagePolicy.Scope scope(String scopeKey) {
        try {
            return PlayHelpUsagePolicy.parseScope(scopeKey);
        } catch (IllegalArgumentException invalid) {
            throw new PlayHelpCostException(invalid.getMessage() == null
                    ? PlayHelpUsagePolicy.REASON_SCOPE_INVALID : invalid.getMessage(), "缺少本局身份或参数有误");
        }
    }

    /** 锁内当前积分：走 `selectPointsForUpdate`，不用会被 RR 快照/一级缓存污染的普通读。 */
    private long currentPoints(long memberId) {
        Long points = members.selectPointsForUpdate(memberId);
        return points == null ? 0L : points;
    }

    /**
     * 回放分支只做展示性读数：不参与扣减判定，也不因读失败改写已成立的事实。
     */
    private long currentPointsForReport(long memberId) {
        try {
            return currentPoints(memberId);
        } catch (RuntimeException unavailable) {
            return -1L;
        }
    }

    private String factJson(long memberId, PlayHelpUsagePolicy.Scope scope, int level, boolean free,
            int chargePoints, String fingerprint) {
        ObjectNode fact = json.createObjectNode();
        fact.put("schema", FACT_SCHEMA);
        fact.put("topicId", scope.topicId());
        if (scope.activityId() != null) fact.put("activityId", scope.activityId());
        fact.put("nodeId", scope.nodeId());
        fact.put("playInstance", scope.playInstance());
        fact.put("level", level);
        fact.put("free", free);
        fact.put("chargedPoints", free ? 0 : chargePoints);
        if (!free && chargePoints > 0) {
            fact.put("businessKey", PlayHelpUsagePolicy.pointsBusinessKey(memberId, PlayHelpUsagePolicy.scopeKey(scope), level));
        }
        fact.put("fingerprint", fingerprint);
        return fact.toString();
    }
}
