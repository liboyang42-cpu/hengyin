package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.PlayAttemptLog;
import com.chengyinhub.business.domain.PlayHelpRunQuota;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.mapper.PlayHelpRunQuotaMapper;
import com.chengyinhub.business.domain.GameRuntimeSession;
import com.chengyinhub.business.mapper.GameRuntimeSessionMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.IGameSessionRuntimeService;
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
 * BE-16 整局共享免费提示额度。
 *
 * <p><b>用户定稿</b>：免费 + 既有可兑换积分；每局共享 N 次；重看免费；局内净分/跳过 0；
 * 全局榜不变。N 只从本局冻结配置取（模板 {@code advancedConfigJson} 根层
 * {@code hintFreeUsesPerRun}），客户端不能传 N。
 *
 * <p><b>载体</b>：个人局沿用原实例与会员；已验证共享局使用
 * {@code shared-game:<原 gameSessionId>} 与 team owner ID，不含节点、操作人或 authorityEpoch。
 * 现有 owner_member_id 在该命名空间承载 team owner ID，不改变个人局字段语义。
 * 首次创建结转可确定归属的旧 actor/node 已用次数；绑定不明或冻结 N 冲突时保留旧池。
 *
 * <p><b>锁与并发</b>：同一 scope 的消费都落在同一行：
 * {@code INSERT ... ON DUPLICATE KEY UPDATE} 先冻结 total，再
 * {@code UPDATE ... SET quota_used=quota_used+1 WHERE quota_used<quota_total}。
 * 跨节点并发抢最后一份额度时，只有一个 UPDATE 返回 1；另一个返回 0 并抛
 * {@link #REASON_EXHAUSTED}，其外层事务连同已写事实/流水一起回滚。
 *
 * <p><b>请求幂等</b>：复用 {@code play_attempt_log.uk_play_attempt_idempotency}，
 * 键 {@code help1qreq:<sha256(scopeNoNode|requestId)前32hex>}，指纹绑定
 * {@code nodeId + level + chargePoints}。同一 requestId 重发不二次消耗；同 requestId 换
 * 节点/层级/代价直接报 {@link #REASON_REQUEST_CONFLICT}，不冒充新动作。
 *
 * <p><b>归属（按原局种类分开守，绝不互相代替）</b>：
 * <ul>
 *   <li>{@link PlayHelpUsagePolicy.RunKind#TOPIC_ROUTE}：
 *       {@code topic_route_session.selectByOwner(activityId, topicId, memberId, playInstanceId)}，
 *       要求本人 + 该实例 + 进行中。当前 schema 由
 *       {@code uk_topic_route_owner(activity_id,topic_id,member_id,play_instance_id)} 定义，
 *       没有多人共享 session 关系；因此不合并多人钱包，也不允许同 run 异 member 偷池。</li>
 *   <li>{@link PlayHelpUsagePolicy.RunKind#ADVANCED_GAME}：
 *       {@code game_runtime_session.selectAccessibleById(sessionId, memberId)}，
 *       再核同一主题、同一场次、状态为进行中。**不能**拿 topic_route_session 代查 ——
 *       高级局在那张表里没有行，硬查会把整个功能判 403。</li>
 * </ul>
 * 两类原局的实例名都可能是 {@code game:<id>}，所以 kind **必须**是显式的、由服务端
 * resolve 入口赋值；客户端无法切换。
 *
 * <p>共享池只在既有 sharedRouteSnapshot 校验原局、队伍、实例与权威版本后使用；
 * 写入持有 assertSharedRouteWritable 的原局事务锁，跨成员、跨节点不重开额度。
 * G5 首用事实与积分流水仍按实际会员隔离。
 *
 * <p><b>与 G5 的分工</b>：积分仍只由 {@link PlayHelpCostApplication} 扣；本类只扣免费次数。
 * {@link #use} 在外层事务里先按剩余额度决定本次是否免费，再调用 G5；只有 G5 真落了一次首次使用
 * （{@code applied && !alreadyUnlocked}）才消费额度。G5 抛余额不足/事务失败时额度消费尚未发生；
 * 若额度在并发中被抢走，消费返回 0 会抛异常，让 G5 已写的事实/流水整笔回滚。
 */
@Service
public class PlayHelpRunQuotaApplication {

    public static final String REASON_SCOPE_INVALID = "PLAY_HELP_SCOPE_INVALID";
    public static final String REASON_SCOPE_FORBIDDEN = "PLAY_HELP_SCOPE_FORBIDDEN";
    public static final String REASON_SCOPE_CLOSED = "PLAY_HELP_SCOPE_CLOSED";
    public static final String REASON_EXHAUSTED = "PLAY_HELP_RUN_QUOTA_EXHAUSTED";
    public static final String REASON_CONFIG_CONFLICT = "PLAY_HELP_RUN_QUOTA_CONFIG_CONFLICT";
    public static final String REASON_REQUEST_INVALID = "PLAY_HELP_REQUEST_INVALID";
    public static final String REASON_REQUEST_CONFLICT = "PLAY_HELP_REQUEST_CONFLICT";
    public static final String REASON_RESULT_PENDING = "PLAY_HELP_RESULT_PENDING";
    public static final String REASON_STATE_UNAVAILABLE = "HELP_STATE_UNAVAILABLE";
    public static final String REASON_LEVEL_CONFLICT = "PLAY_HELP_LEVEL_CONFLICT";

    private static final String REQUEST_CLAIM_PREFIX = "help1qreq:";
    private static final String REQUEST_SCHEMA = "play-help-run-quota-request:v1";

    private final PlayHelpCostApplication costApplication;
    private final PlayAttemptLogMapper attempts;
    private final PlayHelpRunQuotaMapper quotas;
    private final TopicRouteSessionMapper sessions;
    private final GameRuntimeSessionMapper gameSessions;
    private final ObjectMapper json;
    private final IGameSessionRuntimeService sharedRuns;

    public PlayHelpRunQuotaApplication(PlayHelpCostApplication costApplication,
            PlayAttemptLogMapper attempts, PlayHelpRunQuotaMapper quotas,
            TopicRouteSessionMapper sessions, GameRuntimeSessionMapper gameSessions, ObjectMapper json) {
        this(costApplication, attempts, quotas, sessions, gameSessions, json, null);
    }

    @Autowired
    public PlayHelpRunQuotaApplication(PlayHelpCostApplication costApplication,
            PlayAttemptLogMapper attempts, PlayHelpRunQuotaMapper quotas,
            TopicRouteSessionMapper sessions, GameRuntimeSessionMapper gameSessions, ObjectMapper json,
            IGameSessionRuntimeService sharedRuns) {
        this.costApplication = costApplication;
        this.attempts = attempts;
        this.quotas = quotas;
        this.sessions = sessions;
        this.gameSessions = gameSessions;
        this.json = json;
        this.sharedRuns = sharedRuns;
    }

    /**
     * 一次帮助使用的唯一入口：归属 → 请求认领 → 按整局剩余额度决定免费/收费 → 调 G5 →
     * 首次免费且确实收费的层才消费额度。
     *
     * <p><b>免费次数只有一个来源</b>：整局冻结的 {@code hintFreeUsesPerRun}。字段缺省或为 0
     * 都表示<strong>本局 0 次免费</strong>，绝不回退到逐层 {@code freeQuota} 造出第二套正额度。
     * 逐层 {@code freeQuota} 已由 V2 保存期拒绝为 0，这里的
     * {@code ignoredLegacyFreeQuota} 只为保持 G0 现有调用形状，<b>不参与任何判定</b>。
     *
     * <p>标价 0 的层是作者定价为免，**不消耗**整局额度：额度只替代真实积分代价。
     *
     * @param configuredUses 冻结的整局 N；null 与 0 等价（本局 0 次免费）
     * @param ignoredLegacyFreeQuota 已退休的逐层免费标志，忽略；保留参数以免 G0 改行
     * @return {@code charge} 是 G5 的真实结果；{@code remainingFreeUses} 是本次之后的剩余
     */
    @Transactional(rollbackFor = Exception.class)
    public QuotaOutcome use(long memberId, PlayHelpUsagePolicy.Scope scope, int level,
            Integer configuredUses, int ignoredLegacyFreeQuota, int chargePoints, String requestId) {
        requireActor(memberId, scope);
        if (level <= 0) throw new PlayHelpRunQuotaException(REASON_SCOPE_INVALID, "缺少帮助层级");
        if (ignoredLegacyFreeQuota < 0 || chargePoints < 0) {
            throw new PlayHelpRunQuotaException(PlayHelpRunQuotaPolicy.REASON_CONFIG_INVALID,
                    "提示代价配置无效");
        }
        String request = requestId(requestId);
        String scopeKey = PlayHelpUsagePolicy.scopeKey(scope);
        // 归属先行：未授权 actor 不回数据、不进入任何收费/额度分支。
        GameRuntimeSession runtime = requireOwner(memberId, scope);

        int total = configuredUses == null ? 0 : configuredTotal(configuredUses);
        QuotaScope pool = quotaScope(memberId, scope, runtime, total, true);
        boolean runQuota = total > 0;

        // 重看：该层已有首用事实，直接交给 G5 的重放分支；绝不消耗额度。
        Boolean originalFree = originalFree(scopeKey, level, memberId);
        if (originalFree != null) {
            PlayHelpCostApplication.Charge replay = costApplication.use(memberId, scopeKey, level,
                    originalFree ? 1 : 0, chargePoints, request);
            return new QuotaOutcome(replay, runQuota, false, remaining(pool, scope, total));
        }

        // 额度只替代真实积分代价；标价 0 的层是作者定价为免，不得白白吃掉整局额度。
        boolean wantFree = runQuota && chargePoints > 0 && remaining(pool, scope, total) > 0;

        String requestKey = requestClaimKey(memberId, scope, pool, request);
        String fingerprint = requestFingerprint(memberId, scope, level, chargePoints);
        RequestClaim claim = claimRequest(memberId, scope, level, chargePoints, requestKey, fingerprint);
        if (claim.state == ClaimState.ALREADY) {
            // 同一请求已成功提交：只回放，不再扣额度；用首次事实还原免费/收费口径，避免 G5 指纹冲突。
            Boolean alreadyFree = originalFree(scopeKey, level, memberId);
            if (alreadyFree == null) {
                throw new PlayHelpRunQuotaException(REASON_RESULT_PENDING,
                        "该次帮助请求结果仍待确认，请用原请求重试");
            }
            PlayHelpCostApplication.Charge replay = costApplication.use(memberId, scopeKey, level,
                    alreadyFree ? 1 : 0, chargePoints, request);
            return new QuotaOutcome(replay, runQuota, false, remaining(pool, scope, total));
        }

        PlayHelpCostApplication.Charge charge = costApplication.use(memberId, scopeKey, level,
                wantFree ? 1 : 0, chargePoints, request);
        boolean consumed = false;
        if (charge.applied() && !charge.alreadyUnlocked() && wantFree) {
            consumed = consumeAfterClaim(pool, scope, total);
        }
        return new QuotaOutcome(charge, runQuota, consumed, remaining(pool, scope, total));
    }

    /** 查询不创建额度行；旧局未配置 N 时仍返回 0。 */

    public int remainingFreeUses(long memberId, PlayHelpUsagePolicy.Scope scope, Integer configuredUses) {
        requireActor(memberId, scope);
        GameRuntimeSession runtime = requireOwner(memberId, scope);
        if (configuredUses == null) return 0;
        int total = configuredTotal(configuredUses);
        return remaining(quotaScope(memberId, scope, runtime, total, false), scope, total);
    }

    /**
     * 低层原子消费，必须在调用方事务内使用；只供 {@link #use} 或「G5 已确认首次使用」的
     * 调用方使用。调用前请先确认该层不是重看。
     *
     * @return true=本次真正消耗一份；false=同一 requestId 已消费过（不再二次消耗）
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public boolean consumeFreeUse(long memberId, PlayHelpUsagePolicy.Scope scope, int level,
            int configuredUses, int chargePoints, String requestId) {
        requireActor(memberId, scope);
        if (level <= 0) throw new PlayHelpRunQuotaException(REASON_SCOPE_INVALID, "缺少帮助层级");
        int total = configuredTotal(configuredUses);
        GameRuntimeSession runtime = requireOwner(memberId, scope);
        QuotaScope pool = quotaScope(memberId, scope, runtime, total, true);
        String request = requestId(requestId);
        String fingerprint = requestFingerprint(memberId, scope, level, chargePoints);
        RequestClaim claim = claimRequest(memberId, scope, level, chargePoints,
                requestClaimKey(memberId, scope, pool, request), fingerprint);
        if (claim.state == ClaimState.ALREADY) return false;
        return consumeAfterClaim(pool, scope, total);
    }

    private boolean consumeAfterClaim(QuotaScope pool, PlayHelpUsagePolicy.Scope scope, int total) {
        long activityId = activityId(scope);
        if (pool.shared()) {
            quotas.insertSharedIfAbsent(scope.topicId(), activityId, pool.instance(), pool.ownerId(),
                    total, pool.initialUsed());
        } else {
            quotas.insertIfAbsent(scope.topicId(), activityId, pool.instance(), pool.ownerId(), total);
        }
        PlayHelpRunQuota frozen = quotas.selectByScope(scope.topicId(), activityId(scope),
                pool.instance(), pool.ownerId());
        if (frozen != null && frozen.getQuotaTotal() != null && frozen.getQuotaTotal() != total) {
            throw new PlayHelpRunQuotaException(REASON_CONFIG_CONFLICT,
                    "本局免费提示次数与已冻结额度不一致，请作者统一后再试");
        }
        int updated;
        try {
            updated = quotas.consumeOne(scope.topicId(), activityId, pool.instance(), pool.ownerId());
        } catch (RuntimeException unavailable) {
            throw new PlayHelpRunQuotaException(REASON_STATE_UNAVAILABLE, "提示额度暂时不可用,请重试", unavailable);
        }
        if (updated != 1) {
            throw new PlayHelpRunQuotaException(REASON_EXHAUSTED, "本局免费提示次数已用完");
        }
        return true;
    }

    private int remaining(QuotaScope pool, PlayHelpUsagePolicy.Scope scope, int configuredTotal) {
        PlayHelpRunQuota row;
        try {
            row = quotas.selectByScope(scope.topicId(), activityId(scope), pool.instance(), pool.ownerId());
        } catch (RuntimeException unavailable) {
            throw new PlayHelpRunQuotaException(REASON_STATE_UNAVAILABLE, "提示额度暂时不可用,请重试", unavailable);
        }
        if (row == null) return Math.max(0, configuredTotal - pool.initialUsed());
        int total = row.getQuotaTotal() == null ? configuredTotal : row.getQuotaTotal();
        if (total != configuredTotal) {
            throw new PlayHelpRunQuotaException(REASON_CONFIG_CONFLICT,
                    "本局免费提示次数与已冻结额度不一致，请作者统一后再试");
        }
        int used = row.getQuotaUsed() == null ? 0 : row.getQuotaUsed();
        return Math.max(0, total - used);
    }

    /**
     * 只读该层首次事实的免费标记；无事实返回 null。事实存在但归属/类型/JSON 不对 -> 冲突。
     */
    private Boolean originalFree(String scopeKey, int level, long memberId) {
        String factKey = PlayHelpUsagePolicy.levelFactKey(memberId, scopeKey, level);
        PlayAttemptLog fact;
        try {
            fact = attempts.selectByIdempotencyKey(factKey);
        } catch (RuntimeException unavailable) {
            throw new PlayHelpRunQuotaException(REASON_STATE_UNAVAILABLE, "提示状态暂时不可用,请重试", unavailable);
        }
        if (fact == null) return null;
        if (fact.getMemberId() == null || fact.getMemberId() != memberId
                || fact.getAttemptType() == null || fact.getAttemptType() != PlayHelpCostApplication.ATTEMPT_TYPE_HELP) {
            throw new PlayHelpRunQuotaException(REASON_LEVEL_CONFLICT, "该级提示已被其它身份占用");
        }
        try {
            JsonNode view = fact.getResultJson() == null ? null : json.readTree(fact.getResultJson());
            JsonNode free = view == null ? null : view.get("free");
            if (free == null || !free.isBoolean()) {
                throw new PlayHelpRunQuotaException(REASON_LEVEL_CONFLICT, "该级提示事实不可用");
            }
            return free.asBoolean();
        } catch (com.fasterxml.jackson.core.JsonProcessingException broken) {
            throw new PlayHelpRunQuotaException(REASON_STATE_UNAVAILABLE, "提示状态暂时不可用,请重试", broken);
        }
    }

    private RequestClaim claimRequest(long memberId, PlayHelpUsagePolicy.Scope scope, int level,
            int chargePoints, String requestKey, String fingerprint) {
        PlayAttemptLog log = new PlayAttemptLog();
        log.setMemberId(memberId);
        log.setTopicId(scope.topicId());
        log.setNodeId(scope.nodeId());
        log.setActivityId(scope.activityId());
        log.setAttemptType(PlayHelpCostApplication.ATTEMPT_TYPE_HELP);
        log.setResult(null);
        log.setResultJson(requestJson(scope, level, chargePoints, fingerprint));
        log.setIdempotencyKey(requestKey);
        log.setRequestFingerprint(fingerprint);
        log.setCreatedAt(new Date());
        try {
            if (attempts.insertPlayAttemptLog(log) <= 0) {
                throw new PlayHelpRunQuotaException(REASON_STATE_UNAVAILABLE, "提示状态暂时不可用,请重试");
            }
            return new RequestClaim(ClaimState.CLAIMED, null);
        } catch (DuplicateKeyException duplicate) {
            PlayAttemptLog winner = attempts.selectByIdempotencyKey(requestKey);
            if (winner == null) {
                throw new PlayHelpRunQuotaException(REASON_RESULT_PENDING,
                        "该次帮助请求结果仍待确认，请用原请求重试");
            }
            if (winner.getMemberId() == null || winner.getMemberId() != memberId
                    || winner.getAttemptType() == null
                    || winner.getAttemptType() != PlayHelpCostApplication.ATTEMPT_TYPE_HELP
                    || winner.getRequestFingerprint() == null
                    || !winner.getRequestFingerprint().equals(fingerprint)) {
                throw new PlayHelpRunQuotaException(REASON_REQUEST_CONFLICT,
                        "同一请求编号的节点、层级或代价已改变，不能当作新的成功");
            }
            return new RequestClaim(ClaimState.ALREADY, winner);
        } catch (PlayHelpRunQuotaException refused) {
            throw refused;
        } catch (RuntimeException unavailable) {
            throw new PlayHelpRunQuotaException(REASON_STATE_UNAVAILABLE, "提示状态暂时不可用,请重试", unavailable);
        }
    }

    private void requireActor(long memberId, PlayHelpUsagePolicy.Scope scope) {
        if (memberId <= 0) throw new PlayHelpRunQuotaException("LOGIN_REQUIRED", "请先登录");
        if (scope == null) throw new PlayHelpRunQuotaException(REASON_SCOPE_INVALID, "缺少本局身份或参数有误");
    }

    /**
     * 归属只复用既有 owner 事实：同一 activity/topic/playInstance 必须正好是当前会员的
     * topic_route_session。不是本人 -> 与不存在同文案，避免探测别人的局。
     */
    private GameRuntimeSession requireOwner(long memberId, PlayHelpUsagePolicy.Scope scope) {
        if (scope.kind() == PlayHelpUsagePolicy.RunKind.ADVANCED_GAME) {
            return requireAdvancedOwner(memberId, scope);
        }
        boolean selfPlayRun = SelfPlayHelpRun.isIdentity(scope.playInstance());
        TopicRouteSession session;
        try {
            // 自玩局:帮助身份冻结在本人 default 自玩局的快照里,按该局行核验,不靠实例列直查。
            session = sessions.selectByOwner(activityId(scope), scope.topicId(), memberId,
                    selfPlayRun ? SelfPlayHelpRun.progressInstance() : scope.playInstance());
        } catch (RuntimeException unavailable) {
            throw new PlayHelpRunQuotaException(REASON_STATE_UNAVAILABLE, "本局状态暂时不可用,请重试", unavailable);
        }
        if (session == null) {
            throw new PlayHelpRunQuotaException(REASON_SCOPE_FORBIDDEN, "原局不存在或无权访问");
        }
        if (selfPlayRun && !scope.playInstance().equals(selfPlayIdentity(session))) {
            // 别人的、旧的(无身份)或已被重开的自玩局:与不存在同文案。
            throw new PlayHelpRunQuotaException(REASON_SCOPE_FORBIDDEN, "原局不存在或无权访问");
        }
        if (!Integer.valueOf(TopicRouteSession.STATUS_RUNNING).equals(session.getStatus())) {
            throw new PlayHelpRunQuotaException(REASON_SCOPE_CLOSED, "本局已结束，不能使用帮助");
        }
        return null;
    }

    private String selfPlayIdentity(TopicRouteSession session) {
        if (!SelfPlayHelpRun.selfPlay(session.getActivityId()) || session.getConfigSnapshotJson() == null) return null;
        try {
            return SelfPlayHelpRun.helpInstance(session, json.readTree(session.getConfigSnapshotJson()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException broken) {
            throw new PlayHelpRunQuotaException(REASON_STATE_UNAVAILABLE, "本局状态暂时不可用,请重试", broken);
        }
    }

    /**
     * 高级玩法局的归属：只认 {@code game_runtime_session.selectAccessibleById(sessionId, memberId)}
     * （本人可访问的个人局或本人在场队伍局），再核主题/场次/状态。
     *
     * <p>这里**不建任何 topic_route_session 行**去满足旧守卫，也不放宽成员判定：
     * 查不到就是无权访问，与路线局同一文案。
     */
    private GameRuntimeSession requireAdvancedOwner(long memberId, PlayHelpUsagePolicy.Scope scope) {
        Long sessionId = scope.advancedSessionId();
        if (sessionId == null) {
            throw new PlayHelpRunQuotaException(REASON_SCOPE_INVALID, "高级局原局身份不合法");
        }
        GameRuntimeSession session;
        try {
            session = gameSessions.selectAccessibleById(sessionId, memberId);
        } catch (RuntimeException unavailable) {
            throw new PlayHelpRunQuotaException(REASON_STATE_UNAVAILABLE, "本局状态暂时不可用,请重试", unavailable);
        }
        if (session == null) {
            throw new PlayHelpRunQuotaException(REASON_SCOPE_FORBIDDEN, "原局不存在或无权访问");
        }
        // 主题与场次必须与作用域一致：防止拿 A 局的 sessionId 去读 B 局的额度/事实。
        if (!scope.topicId().equals(session.getTopicId())
                || !scope.nodeId().equals(session.getNodeId())) {
            throw new PlayHelpRunQuotaException(REASON_SCOPE_FORBIDDEN, "原局不存在或无权访问");
        }
        long sessionActivity = session.getActivityId() == null ? 0L : session.getActivityId();
        if (sessionActivity != activityId(scope)) {
            throw new PlayHelpRunQuotaException(REASON_SCOPE_FORBIDDEN, "原局不存在或无权访问");
        }
        if (!Integer.valueOf(GameRuntimeSession.STATUS_RUNNING).equals(session.getStatus())) {
            throw new PlayHelpRunQuotaException(REASON_SCOPE_CLOSED, "本局已结束，不能使用帮助");
        }
        return session;
    }

    private record QuotaScope(String instance, long ownerId, int initialUsed, boolean shared) {}

    private QuotaScope quotaScope(long memberId, PlayHelpUsagePolicy.Scope scope,
            GameRuntimeSession runtime, int total, boolean writing) {
        QuotaScope personal = new QuotaScope(scope.playInstance(), memberId, 0, false);
        if (runtime == null || !Integer.valueOf(GameRuntimeSession.OWNER_TEAM).equals(runtime.getOwnerType())
                || sharedRuns == null) return personal;
        JsonNode state;
        try {
            state = runtime.getStateJson() == null ? null : json.readTree(runtime.getStateJson());
        } catch (com.fasterxml.jackson.core.JsonProcessingException broken) {
            throw new PlayHelpRunQuotaException(REASON_STATE_UNAVAILABLE, "本局状态暂时不可用,请重试", broken);
        }
        // 无原共享局绑定的历史实例不能猜队伍/局身份，保留其原额度口径。
        if (state == null || !state.path("originalGameSessionId").isIntegralNumber()
                || !state.path("runtimeAuthorityEpoch").isIntegralNumber()) return personal;
        if (writing) {
            if (!state.path("runtimeAuthorityEpoch").canConvertToInt()
                    || state.path("runtimeAuthorityEpoch").asInt() < 0) {
                throw new PlayHelpRunQuotaException(REASON_SCOPE_FORBIDDEN, "原局不存在或无权访问");
            }
            // 先持有原共享局锁，再读取成员/队伍快照，避免等锁期间换队后仍访问旧队池。
            sharedRuns.assertSharedRouteWritable(memberId, scope.topicId(), scope.activityId(),
                    scope.nodeId(), state.path("runtimeAuthorityEpoch").intValue());
        }
        JsonNode shared = sharedRuns.sharedRouteSnapshot(memberId, scope.topicId(), scope.activityId(), scope.nodeId());
        if (shared == null || !shared.path("gameSessionId").isIntegralNumber()
                || !shared.path("gameSessionId").canConvertToLong()
                || !state.path("originalGameSessionId").canConvertToLong()
                || shared.path("gameSessionId").asLong() <= 0
                || !shared.path("teamId").isIntegralNumber() || shared.path("teamId").asLong() <= 0
                || !shared.path("teamId").canConvertToLong()
                || !Long.valueOf(shared.path("teamId").asLong()).equals(runtime.getOwnerId())
                || shared.path("gameSessionId").asLong() != state.path("originalGameSessionId").asLong()
                || !shared.path("authorityEpoch").isIntegralNumber()
                || !shared.path("authorityEpoch").canConvertToInt()
                || !state.path("runtimeAuthorityEpoch").canConvertToInt()
                || shared.path("authorityEpoch").asInt() < 0
                || shared.path("authorityEpoch").asInt() != state.path("runtimeAuthorityEpoch").asInt()
                || !shared.path("runtimeInstance").asText().equals(runtime.getPlayInstanceId())) {
            throw new PlayHelpRunQuotaException(REASON_SCOPE_FORBIDDEN, "原局不存在或无权访问");
        }
        long gameId = shared.path("gameSessionId").asLong();
        long teamId = shared.path("teamId").asLong();
        String instance = "shared-game:" + gameId;
        try {
            PlayHelpRunQuota current = quotas.selectByScope(scope.topicId(), activityId(scope), instance, teamId);
            if (current != null) return new QuotaScope(instance, teamId, 0, true);
            long used = 0;
            for (PlayHelpRunQuota legacy : quotas.selectLegacySharedQuotas(
                    scope.topicId(), activityId(scope), gameId, teamId)) {
                // 旧绑定或冻结 N 无法确定时，整局继续原口径，绝不凭新键补出一池 N。
                if (!Long.valueOf(gameId).equals(legacy.getSharedRunId())
                        || !Integer.valueOf(total).equals(legacy.getQuotaTotal())
                        || legacy.getQuotaUsed() == null || legacy.getQuotaUsed() < 0) return personal;
                used += legacy.getQuotaUsed();
                if (used > Integer.MAX_VALUE) return personal;
            }
            return new QuotaScope(instance, teamId, (int) used, true);
        } catch (RuntimeException unavailable) {
            throw new PlayHelpRunQuotaException(REASON_STATE_UNAVAILABLE, "提示额度暂时不可用,请重试", unavailable);
        }
    }

    private static int configuredTotal(Integer configuredUses) {
        try {
            return PlayHelpRunQuotaPolicy.requireConfigured(configuredUses);
        } catch (IllegalArgumentException invalid) {
            throw new PlayHelpRunQuotaException(PlayHelpRunQuotaPolicy.REASON_CONFIG_INVALID,
                    "本局免费提示次数配置无效");
        }
    }

    private static String requestId(String raw) {
        try {
            return PlayHelpUsagePolicy.requireRequestId(raw);
        } catch (IllegalArgumentException invalid) {
            throw new PlayHelpRunQuotaException(REASON_REQUEST_INVALID, "请求编号无效");
        }
    }

    private static long activityId(PlayHelpUsagePolicy.Scope scope) {
        return scope.activityId() == null ? 0L : scope.activityId();
    }

    private static String runKey(long memberId, PlayHelpUsagePolicy.Scope scope) {
        return memberId + ":" + scope.topicId() + ":" + activityId(scope) + ":" + scope.playInstance();
    }

    private static String requestClaimKey(long memberId, PlayHelpUsagePolicy.Scope scope,
            QuotaScope pool, String requestId) {
        String run = pool.shared() ? memberId + ":" + scope.topicId() + ":" + activityId(scope)
                + ":" + pool.instance() + ":team:" + pool.ownerId() : runKey(memberId, scope);
        return REQUEST_CLAIM_PREFIX + PlayHelpUsagePolicy.digest(run + "|" + requestId, 32);
    }

    private static String requestFingerprint(long memberId, PlayHelpUsagePolicy.Scope scope,
            int level, int chargePoints) {
        return PlayHelpUsagePolicy.digest(REQUEST_SCHEMA + "|" + runKey(memberId, scope) + "|"
                + scope.nodeId() + "|" + level + "|" + chargePoints, 64);
    }

    private String requestJson(PlayHelpUsagePolicy.Scope scope, int level, int chargePoints, String fingerprint) {
        ObjectNode fact = json.createObjectNode();
        fact.put("schema", REQUEST_SCHEMA);
        fact.put("topicId", scope.topicId());
        if (scope.activityId() != null) fact.put("activityId", scope.activityId());
        fact.put("nodeId", scope.nodeId());
        fact.put("playInstance", scope.playInstance());
        fact.put("level", level);
        fact.put("chargePoints", chargePoints);
        fact.put("fingerprint", fingerprint);
        return fact.toString();
    }

    private enum ClaimState { CLAIMED, ALREADY }

    private record RequestClaim(ClaimState state, PlayAttemptLog row) {
    }

    /** 调用方唯一需要消费的结果；{@code charge} 是 G5 的真实回执。 */
    public record QuotaOutcome(PlayHelpCostApplication.Charge charge, boolean runQuotaConfigured,
            boolean freeUseConsumed, int remainingFreeUses) {
        public boolean applied() {
            return charge != null && charge.applied();
        }

        public boolean alreadyUnlocked() {
            return charge != null && charge.alreadyUnlocked();
        }
    }

    /** 明确业务拒绝；继承 IllegalStateException 以便既有 G0 控制器映射 400 文案。 */
    public static class PlayHelpRunQuotaException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final String code;

        public PlayHelpRunQuotaException(String code, String message) {
            super(message);
            this.code = code;
        }

        public PlayHelpRunQuotaException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
