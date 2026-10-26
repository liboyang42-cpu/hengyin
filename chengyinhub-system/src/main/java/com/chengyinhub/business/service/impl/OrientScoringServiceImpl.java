package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OrientScoreAppeal;
import com.chengyinhub.business.domain.OrientScoreRecovery;
import com.chengyinhub.business.domain.OrientScoreSyncReceipt;
import com.chengyinhub.business.domain.OrientScoreEntry;
import com.chengyinhub.business.domain.OrientScoreSettlement;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.domain.UmsMemberPointsDetail;
import com.chengyinhub.business.domain.vo.LeaderboardItemVO;
import com.chengyinhub.business.mapper.OrientScoringMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.mapper.UmsMemberPointsDetailMapper;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.IOrientScoringService;
import com.chengyinhub.business.service.support.OrientScoringException;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.system.service.ISysConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * F1b 城市定向计分。所有写路径先行锁会话(topic_route_session FOR UPDATE),同一局的入账/结算/冲正串行。
 * 结算投影与积分/XP 发放在同一事务里:uk(session_id) 抢占成功才发奖,失败整体回滚 ⇒ 恰好一次。
 */
@Service
public class OrientScoringServiceImpl implements IOrientScoringService {
    static final String CFG_ENABLED = "orient.scoring.enabled";
    static final String CFG_TASK_CENTI = "orient.scoring.task_centi";
    static final String CFG_POINTS_RATE = "orient.scoring.points_rate_pct";
    static final String CFG_XP_RATE = "orient.scoring.xp_rate_pct";
    static final String CFG_WINDOW_HOURS = "orient.scoring.dispute_window_hours";
    static final int DEFAULT_TASK_CENTI = 10000;
    static final int DEFAULT_WINDOW_HOURS = 24;
    /** ums_member_points_detail.event_type:城市定向局终结算。 */
    static final long EVENT_ORIENT_SETTLEMENT = 21L;
    static final String POINTS_KEY_PREFIX = "orient-settlement:";

    private static final Pattern IDEM_KEY = Pattern.compile("[A-Za-z0-9:_\\-.]{1,96}");
    private static final Pattern CODE = Pattern.compile("[A-Z0-9_]{1,64}");
    private static final Set<String> DIMENSIONS = Set.of(OrientScoreEntry.DIM_TASK, OrientScoreEntry.DIM_PUZZLE,
            OrientScoreEntry.DIM_PENALTY, OrientScoreEntry.DIM_BONUS, OrientScoreEntry.DIM_ADJUSTMENT);
    private static final Set<String> SOURCES = Set.of("PROGRESS", "ANTI_CHEAT", "APPEAL", "DISPUTE", "ADMIN", "LIFECYCLE");

    @Autowired @org.springframework.context.annotation.Lazy private com.chengyinhub.business.service.ICmsRegistrationService scoringRegistrations;
    @Autowired @org.springframework.context.annotation.Lazy private com.chengyinhub.business.service.ICmsActivityService scoringActivities;
    @Autowired @org.springframework.context.annotation.Lazy private com.chengyinhub.business.service.IGameSessionRuntimeService scoringGameSessions;
    @Autowired private OrientScoringMapper mapper;
    @Autowired private ISysConfigService configService;
    @Autowired private ICmsTopicService cmsTopicService;
    @Autowired private IGrowthService growthService;
    @Autowired private UmsMemberMapper umsMemberMapper;
    @Autowired private UmsMemberPointsDetailMapper pointsDetailMapper;

    // ---------------------------------------------------------------- 开关

    @Override
    public boolean isEnabled() {
        String value = configService.selectConfigByKey(CFG_ENABLED);
        return value != null && "true".equalsIgnoreCase(value.trim());
    }

    @Override
    public boolean isActiveFor(CmsTopic topic) {
        return topic != null && Integer.valueOf(ProductType.ORIENTEERING).equals(topic.getProductType()) && isEnabled();
    }

    private void requireEnabled() {
        if (!isEnabled()) throw new OrientScoringException(OrientScoringException.DISABLED, "城市定向计分暂未开放");
    }

    private void requireActive(TopicRouteSession session) {
        CmsTopic topic = cmsTopicService.selectCmsTopicById(session.getTopicId());
        if (!isActiveFor(topic)) {
            throw new OrientScoringException(OrientScoringException.NOT_APPLICABLE, "该主题不使用城市定向计分");
        }
    }

    private int intConfig(String key, int fallback, int min, int max) {
        String value = configService.selectConfigByKey(key);
        try {
            if (value == null || value.trim().isEmpty()) return fallback;
            int parsed = Integer.parseInt(value.trim());
            return parsed < min || parsed > max ? fallback : parsed;
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    // ---------------------------------------------------------------- 会话

    private TopicRouteSession lockOwned(Long memberId, Long sessionId) {
        TopicRouteSession session = sessionId == null ? null : mapper.selectSessionForUpdate(sessionId);
        if (session == null || (memberId != null && !memberId.equals(session.getMemberId()))) {
            // 非本人与不存在同一回复,不泄露他人会话是否存在
            throw new OrientScoringException(OrientScoringException.SESSION_NOT_FOUND, "对局不存在");
        }
        return session;
    }

    private TopicRouteSession readOwned(Long memberId, Long sessionId) {
        TopicRouteSession session = sessionId == null ? null : mapper.selectSessionById(sessionId);
        if (session == null || (memberId != null && !memberId.equals(session.getMemberId()))) {
            throw new OrientScoringException(OrientScoringException.SESSION_NOT_FOUND, "对局不存在");
        }
        return session;
    }

    static boolean ended(TopicRouteSession session) {
        Integer status = session.getStatus();
        return status != null && (status == TopicRouteSession.STATUS_COMPLETED || status == TopicRouteSession.STATUS_ENDED);
    }

    private Date endedAt(TopicRouteSession session) {
        if (session.getCompletedAt() != null) return session.getCompletedAt();
        return session.getUpdateTime() != null ? session.getUpdateTime() : session.getCreateTime();
    }

    private Date windowDeadline(TopicRouteSession session) {
        Date end = endedAt(session);
        if (end == null) return null;
        long hours = intConfig(CFG_WINDOW_HOURS, DEFAULT_WINDOW_HOURS, 0, 24 * 90);
        return new Date(end.getTime() + hours * 3600_000L);
    }

    // ---------------------------------------------------------------- 读

    @Override
    public Map<String, Object> breakdown(Long memberId, Long sessionId) {
        requireEnabled();
        TopicRouteSession session = readOwned(memberId, sessionId);
        requireActive(session);
        List<OrientScoreEntry> entries = mapper.selectEntriesBySession(session.getId());
        Map<String, Long> byDimension = new LinkedHashMap<>();
        for (String dim : List.of(OrientScoreEntry.DIM_TASK, OrientScoreEntry.DIM_PUZZLE, OrientScoreEntry.DIM_BONUS,
                OrientScoreEntry.DIM_PENALTY, OrientScoreEntry.DIM_ADJUSTMENT)) {
            byDimension.put(dim, 0L);
        }
        long total = 0L;
        List<Map<String, Object>> timeline = new ArrayList<>();
        for (OrientScoreEntry e : entries) {
            long delta = e.getDeltaCenti() == null ? 0L : e.getDeltaCenti();
            total += delta;
            byDimension.merge(e.getDimension(), delta, Long::sum);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("entryId", e.getId());
            row.put("at", e.getCreateTime());
            row.put("dimension", e.getDimension());
            row.put("deltaCenti", delta);
            row.put("runningCenti", total);
            row.put("reasonCode", e.getReasonCode());
            row.put("sourceType", e.getSourceType());
            row.put("nodeId", e.getNodeId());
            row.put("reversesEntryId", e.getReversesEntryId());
            timeline.add(row);
        }
        OrientScoreSettlement settlement = mapper.selectSettlementBySession(session.getId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", session.getId());
        data.put("activityId", session.getActivityId());
        data.put("topicId", session.getTopicId());
        data.put("status", session.getStatus());
        data.put("pendingReviewCount", pendingReviews(session));
        data.put("startedAt", session.getCreateTime());
        data.put("endedAt", ended(session) ? endedAt(session) : null);
        data.put("totalCenti", total);
        data.put("byDimension", byDimension);
        data.put("timeline", timeline);
        data.put("settlement", settlement == null ? null : settlementReceipt(settlement, false));
        data.put("disputeWindowDeadline", ended(session) ? windowDeadline(session) : null);
        data.put("appeals", mapper.selectAppealsBySession(session.getId()));
        List<OrientScoreRecovery> recoveries = mapper.selectRecoveriesBySession(session.getId());
        data.put("recoveries", memberId == null ? recoveries : recoveries.stream().map(OrientScoreRecovery::playerView).toList());
        List<OrientScoreAppeal> disputes = new ArrayList<>();
        for (OrientScoreAppeal d : mapper.selectDisputesBySession(session.getId())) {
            if (memberId == null || memberId.equals(d.getMemberId())) disputes.add(d);
        }
        data.put("disputes", disputes);
        return data;
    }

    @Override
    public Map<String, Object> verify(Long sessionId) {
        TopicRouteSession session = readOwned(null, sessionId);
        OrientScoreSettlement settlement = mapper.selectSettlementBySession(session.getId());
        long all = 0L;
        long upToWatermark = 0L;
        int count = 0;
        long watermark = settlement == null || settlement.getLedgerWatermarkId() == null ? 0L : settlement.getLedgerWatermarkId();
        for (OrientScoreEntry e : mapper.selectEntriesBySession(session.getId())) {
            long delta = e.getDeltaCenti() == null ? 0L : e.getDeltaCenti();
            all += delta;
            if (e.getId() != null && e.getId() <= watermark) {
                upToWatermark += delta;
                count++;
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", session.getId());
        data.put("ledgerTotalCenti", all);
        data.put("settled", settlement != null);
        if (settlement != null) {
            data.put("projectionTotalCenti", settlement.getTotalCenti());
            data.put("ledgerAtWatermarkCenti", upToWatermark);
            data.put("postSettlementDeltaCenti", all - upToWatermark);
            data.put("consistent", settlement.getTotalCenti() != null && settlement.getTotalCenti() == upToWatermark
                    && settlement.getEntryCount() != null && settlement.getEntryCount() == count);
        } else {
            data.put("consistent", true);
        }
        return data;
    }

    // ---------------------------------------------------------------- 入账

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> syncSession(Long memberId, Long sessionId) {
        requireEnabled();
        TopicRouteSession session = lockOwned(memberId, sessionId);
        requireActive(session);
        int added = accrue(session);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", session.getId());
        data.put("added", added);
        data.put("totalCenti", sum(mapper.selectEntriesBySession(session.getId())));
        return data;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> syncSession(Long memberId, Long sessionId, String idempotencyKey) {
        requireEnabled();
        String key = requireKey(idempotencyKey);
        if (memberId == null) throw new OrientScoringException(OrientScoringException.SESSION_NOT_FOUND, "对局不存在");
        TopicRouteSession session = lockOwned(memberId, sessionId);
        requireActive(session);
        OrientScoreSyncReceipt existing = mapper.selectSyncReceiptForUpdate(sessionId, key);
        if (existing != null) return syncReceipt(existing);
        OrientScoreSyncReceipt receipt = new OrientScoreSyncReceipt();
        receipt.setSessionId(sessionId);
        receipt.setMemberId(memberId);
        receipt.setIdempotencyKey(key);
        receipt.setAdded(accrue(session));
        receipt.setTotalCenti(sum(mapper.selectEntriesBySession(sessionId)));
        if (mapper.insertSyncReceipt(receipt) != 1) throw new IllegalStateException("同步回执写入失败");
        return syncReceipt(receipt);
    }

    private Map<String, Object> syncReceipt(OrientScoreSyncReceipt receipt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("state", "SYNCED");
        data.put("kind", KIND_SYNC);
        data.put("sessionId", receipt.getSessionId());
        data.put("memberId", receipt.getMemberId());
        data.put("idempotencyKey", receipt.getIdempotencyKey());
        data.put("added", receipt.getAdded());
        data.put("totalCenti", receipt.getTotalCenti());
        return data;
    }

    private int pendingReviews(TopicRouteSession session) {
        String instance = session.getPlayInstanceId() == null ? "default" : session.getPlayInstanceId();
        return mapper.countPendingProgress(session.getMemberId(), session.getActivityId(), session.getTopicId(), instance)
                + mapper.countPendingAlternatives(session.getMemberId(), session.getActivityId(), session.getTopicId(), instance);
    }

    /** 已通过任务入账。已结算的局冻结(投影之后只允许冲正/申诉修正)。返回新增条数。 */
    int accrue(TopicRouteSession session) {
        if (mapper.selectSettlementBySession(session.getId()) != null) return 0;
        int taskCenti = intConfig(CFG_TASK_CENTI, DEFAULT_TASK_CENTI, 0, 1_000_000);
        int added = 0;
        List<PlayerNodeProgress> cleared = mapper.selectClearedProgress(session.getMemberId(), session.getActivityId(),
                session.getTopicId(), session.getPlayInstanceId() == null ? "default" : session.getPlayInstanceId());
        for (PlayerNodeProgress p : cleared) {
            if (p.getNodeId() == null) continue;
            if (accrueOnce(session, OrientScoreEntry.DIM_TASK, taskCenti, "TASK_CLEARED", p.getNodeId(),
                    "task:node:" + p.getNodeId())) added++;
            Integer puzzle = p.getPuzzleScore();
            if (puzzle != null && puzzle > 0 && accrueOnce(session, OrientScoreEntry.DIM_PUZZLE, puzzleCenti(puzzle),
                    "PUZZLE_SCORE", p.getNodeId(), "puzzle:node:" + p.getNodeId())) added++;
        }
        for (Long nodeId : mapper.selectCompletedAlternativeNodes(session.getMemberId(), session.getActivityId(),
                session.getTopicId(), session.getPlayInstanceId() == null ? "default" : session.getPlayInstanceId())) {
            // Same logical task and same key as the primary route, never a second TASK award.
            if (accrueOnce(session, OrientScoreEntry.DIM_TASK, taskCenti, "TASK_CLEARED", nodeId,
                    "task:node:" + nodeId)) added++;
        }
        return added;
    }

    static int puzzleCenti(int score) {
        try {
            return Math.multiplyExact(score, 100);
        } catch (ArithmeticException overflow) {
            throw invalid("谜题分值超出可入账范围");
        }
    }

    /** 系统入账:同键已存在即视为已入(首值为准,规则后续调整不回写历史)。 */
    private boolean accrueOnce(TopicRouteSession session, String dimension, int delta, String reason, Long nodeId,
                               String key) {
        if (mapper.selectEntryByIdem(session.getId(), key) != null) return false;
        OrientScoreEntry entry = newEntry(session, dimension, delta, reason, "PROGRESS", "node:" + nodeId, nodeId,
                null, key, "SYSTEM", null);
        try {
            return mapper.insertEntry(entry) == 1;
        } catch (DuplicateKeyException raced) {
            return false;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrientScoreEntry appendEntry(Long sessionId, String dimension, int deltaCenti, String reasonCode,
                                        String sourceType, String sourceRef, Long nodeId, String idempotencyKey) {
        requireEnabled();
        if (dimension == null || !DIMENSIONS.contains(dimension) || sourceType == null || !SOURCES.contains(sourceType)
                || reasonCode == null || !CODE.matcher(reasonCode).matches()) {
            throw invalid("计分维度/来源/原因码不合法");
        }
        String key = requireKey(idempotencyKey);
        TopicRouteSession session = lockOwned(null, sessionId);
        requireActive(session);
        OrientScoreEntry entry = newEntry(session, dimension, deltaCenti, reasonCode, sourceType,
                sourceRef == null ? null : truncate(sourceRef, 96), nodeId, null, key, "SYSTEM", null);
        return insertIdempotent(entry);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrientScoreEntry reverseEntry(Long entryId, String reasonCode, Long operatorId, String idempotencyKey) {
        requireEnabled();
        OrientScoreEntry original = entryId == null ? null : mapper.selectEntryById(entryId);
        if (original == null) throw new OrientScoringException(OrientScoringException.NOT_FOUND, "流水不存在");
        TopicRouteSession session = lockOwned(null, original.getSessionId());
        return reverseLocked(session, original, reasonCode, "ADMIN", "admin:" + operatorId, operatorId,
                requireKey(idempotencyKey));
    }

    private OrientScoreEntry reverseLocked(TopicRouteSession session, OrientScoreEntry original, String reasonCode,
                                           String sourceType, String sourceRef, Long operatorId, String key) {
        if (original.getReversesEntryId() != null) throw invalid("冲正条目不能再被冲正,请追加修正条目");
        String reason = reasonCode == null || !CODE.matcher(reasonCode).matches() ? "REVERSAL" : reasonCode;
        int reversedDelta;
        try {
            reversedDelta = Math.negateExact(original.getDeltaCenti() == null ? 0 : original.getDeltaCenti());
        } catch (ArithmeticException overflow) {
            throw invalid("原流水分值超出可冲正范围");
        }
        OrientScoreEntry reversal = newEntry(session, original.getDimension(),
                reversedDelta, reason, sourceType, sourceRef,
                original.getNodeId(), original.getId(), key, "ADMIN", operatorId);
        OrientScoreEntry replay = mapper.selectEntryByIdem(session.getId(), key);
        if (replay != null) return sameOrConflict(replay, reversal);
        if (mapper.selectEntryByReverses(original.getId()) != null) {
            throw new OrientScoringException(OrientScoringException.ALREADY_REVERSED, "该条流水已被冲正");
        }
        return insertIdempotent(reversal);
    }

    private OrientScoreEntry insertIdempotent(OrientScoreEntry entry) {
        OrientScoreEntry existing = mapper.selectEntryByIdem(entry.getSessionId(), entry.getIdempotencyKey());
        if (existing != null) return sameOrConflict(existing, entry);
        try {
            if (mapper.insertEntry(entry) != 1) throw new IllegalStateException("计分流水写入失败");
            return entry;
        } catch (DuplicateKeyException duplicate) {
            existing = mapper.selectEntryByIdem(entry.getSessionId(), entry.getIdempotencyKey());
            if (existing != null) return sameOrConflict(existing, entry);
            if (entry.getReversesEntryId() != null) {
                throw new OrientScoringException(OrientScoringException.ALREADY_REVERSED, "该条流水已被冲正");
            }
            throw duplicate;
        }
    }

    private OrientScoreEntry sameOrConflict(OrientScoreEntry existing, OrientScoreEntry wanted) {
        if (wanted.getRequestFingerprint().equals(existing.getRequestFingerprint())) return existing;
        throw new OrientScoringException(OrientScoringException.IDEMPOTENCY_CONFLICT, "幂等键已用于不同的请求");
    }

    private OrientScoreEntry newEntry(TopicRouteSession session, String dimension, int delta, String reason,
                                      String sourceType, String sourceRef, Long nodeId, Long reverses, String key,
                                      String operatorType, Long operatorId) {
        if (delta == Integer.MIN_VALUE) throw invalid("计分分值超出可冲正范围");
        OrientScoreEntry e = new OrientScoreEntry();
        e.setSessionId(session.getId());
        e.setActivityId(session.getActivityId() == null ? 0L : session.getActivityId());
        e.setTopicId(session.getTopicId());
        e.setMemberId(session.getMemberId());
        e.setDimension(dimension);
        e.setDeltaCenti(delta);
        e.setReasonCode(reason);
        e.setSourceType(sourceType);
        e.setSourceRef(sourceRef);
        e.setNodeId(nodeId);
        e.setReversesEntryId(reverses);
        e.setIdempotencyKey(key);
        e.setOperatorType(operatorType);
        e.setOperatorId(operatorId);
        e.setRequestFingerprint(fingerprint(session.getId(), dimension, delta, reason, sourceType, sourceRef, nodeId,
                reverses));
        e.setCreateTime(new Date());
        return e;
    }

    // ---------------------------------------------------------------- 结算

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> settle(Long memberId, Long sessionId, String idempotencyKey) {
        requireEnabled();
        String key = requireKey(idempotencyKey);
        if (memberId == null) throw new OrientScoringException(OrientScoringException.SESSION_NOT_FOUND, "对局不存在");
        return settleLocked(lockOwned(memberId, sessionId), key);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> settleBySystem(Long sessionId, String idempotencyKey) {
        requireEnabled();
        return settleLocked(lockOwned(null, sessionId), requireKey(idempotencyKey));
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void assertCompletionAllowed(Long sessionId) {
        requireEnabled();
        TopicRouteSession session = lockOwned(null, sessionId);
        requireActive(session);
        if (mapper.selectSettlementBySession(sessionId) == null && pendingReviews(session) > 0) {
            throw new OrientScoringException(OrientScoringException.REVIEW_PENDING, "本局仍有任务待审核，不能结算");
        }
    }

    private Map<String, Object> settleLocked(TopicRouteSession session, String key) {
        requireActive(session);
        OrientScoreSettlement existing = mapper.selectSettlementBySession(session.getId());
        if (existing != null) return settlementReceipt(existing, true);
        if (!ended(session)) {
            throw new OrientScoringException(OrientScoringException.SESSION_NOT_ENDED, "对局尚未结束,不能结算");
        }
        if (pendingReviews(session) > 0) {
            throw new OrientScoringException(OrientScoringException.REVIEW_PENDING, "本局仍有任务待审核，不能结算");
        }
        accrue(session);
        List<OrientScoreEntry> entries = mapper.selectEntriesBySession(session.getId());
        long total = sum(entries);
        long watermark = 0L;
        for (OrientScoreEntry e : entries) {
            if (e.getId() != null && e.getId() > watermark) watermark = e.getId();
        }
        int pointsRate = intConfig(CFG_POINTS_RATE, 0, 0, 100_000);
        int xpRate = intConfig(CFG_XP_RATE, 0, 0, 100_000);
        int points = project(total, pointsRate);
        int xp = project(total, xpRate);

        OrientScoreSettlement s = new OrientScoreSettlement();
        s.setSessionId(session.getId());
        s.setActivityId(session.getActivityId() == null ? 0L : session.getActivityId());
        s.setTopicId(session.getTopicId());
        s.setMemberId(session.getMemberId());
        s.setTotalCenti(total);
        s.setEntryCount(entries.size());
        s.setLedgerWatermarkId(watermark);
        s.setPointsAwarded(points);
        s.setXpAwarded(xp);
        s.setPointsRatePct(pointsRate);
        s.setXpRatePct(xpRate);
        s.setIdempotencyKey(key);
        s.setRequestFingerprint(fingerprint(session.getId(), total, entries.size(), watermark, pointsRate, xpRate,
                points, xp));
        s.setCreateTime(new Date());
        try {
            if (mapper.insertSettlement(s) != 1) throw new IllegalStateException("结算投影写入失败");
        } catch (DuplicateKeyException raced) {
            OrientScoreSettlement winner = mapper.selectSettlementBySession(session.getId());
            if (winner != null) return settlementReceipt(winner, true);
            throw raced;
        }
        // 投影行抢占成功之后才发奖;任一步失败整笔回滚(含投影行),不会出现「有投影没奖励」或反之
        if (points > 0) awardPoints(session, points);
        if (xp > 0) growthService.addExp(session.getMemberId(), xp, "城市定向结算 session=" + session.getId());
        return settlementReceipt(s, false);
    }

    /** 分数(1/100 分) × 折算百分比 → 整数奖励;负分不扣奖励,向下取整。 */
    static int project(long totalCenti, int ratePct) {
        if (totalCenti <= 0 || ratePct <= 0) return 0;
        // Saturate before multiplication; preserve the existing floor/cap policy without long overflow.
        if (totalCenti > (long) Integer.MAX_VALUE * 10_000L / ratePct) return Integer.MAX_VALUE;
        long value = totalCenti * ratePct / 10_000L;
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private void awardPoints(TopicRouteSession session, int points) {
        Long memberId = session.getMemberId();
        if (umsMemberMapper.lockMemberById(memberId) == null) {
            throw new IllegalStateException("结算积分会员不存在 memberId=" + memberId);
        }
        Long before = umsMemberMapper.selectPointsForUpdate(memberId);
        if (before == null) throw new IllegalStateException("结算积分会员余额回读失败 memberId=" + memberId);
        UmsMemberPointsDetail detail = new UmsMemberPointsDetail();
        detail.setMemberId(memberId);
        detail.setCurrentPoints(before);
        detail.setAfterPoints(before + points);
        detail.setChangePoints((long) points);
        detail.setChangeReason("城市定向结算");
        detail.setEventType(EVENT_ORIENT_SETTLEMENT);
        detail.setEventId(session.getId());
        detail.setRewardBusinessKey(POINTS_KEY_PREFIX + session.getId());
        detail.setChangeType(1L);
        detail.setDelFlag(0);
        detail.setCreateTime(new Date());
        // 投影行已用 uk(session_id) 保证本局只走到这里一次;uk_member_reward_business 是第二道防线,
        // 撞键说明投影与积分流水不一致,fail closed 回滚整笔结算而不是静默跳过
        if (pointsDetailMapper.insertOrientSettlementPointsDetail(detail) != 1) {
            throw new IllegalStateException("结算积分流水写入失败 session=" + session.getId());
        }
        if (umsMemberMapper.addPoints(memberId, points) != 1) {
            throw new IllegalStateException("结算积分余额更新失败 memberId=" + memberId);
        }
    }

    private Map<String, Object> settlementReceipt(OrientScoreSettlement s, boolean replayed) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("state", "SETTLED");
        r.put("replayed", replayed);
        r.put("settlementId", s.getId());
        r.put("sessionId", s.getSessionId());
        r.put("totalCenti", s.getTotalCenti());
        r.put("entryCount", s.getEntryCount());
        r.put("ledgerWatermarkId", s.getLedgerWatermarkId());
        r.put("pointsAwarded", s.getPointsAwarded());
        r.put("xpAwarded", s.getXpAwarded());
        r.put("idempotencyKey", s.getIdempotencyKey());
        r.put("fingerprint", s.getRequestFingerprint());
        r.put("settledAt", s.getCreateTime());
        return r;
    }

    // ---------------------------------------------------------------- 申诉 / 争议

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrientScoreAppeal submitAppeal(Long memberId, Long sessionId, Long entryId, Long nodeId, String reason,
                                          String idempotencyKey) {
        requireEnabled();
        String key = requireKey(idempotencyKey);
        String text = requireReason(reason);
        if (memberId == null) throw new OrientScoringException(OrientScoringException.SESSION_NOT_FOUND, "对局不存在");
        if (entryId == null && (nodeId == null || nodeId <= 0)) throw invalid("请指定申诉的计分条目或任务");
        String fp = fingerprint("APPEAL", sessionId, entryId, nodeId, text);
        OrientScoreAppeal replay = mapper.selectAppealByIdem(memberId, key);
        if (replay != null) return appealSameOrConflict(replay, fp);
        TopicRouteSession session = lockOwned(memberId, sessionId);
        requireActive(session);
        if (ended(session) && windowClosed(session)) {
            throw new OrientScoringException(OrientScoringException.WINDOW_CLOSED, "已超过申诉期限");
        }
        if (entryId != null) {
            OrientScoreEntry entry = mapper.selectEntryById(entryId);
            if (entry == null || !session.getId().equals(entry.getSessionId())) {
                throw new OrientScoringException(OrientScoringException.NOT_FOUND, "计分条目不存在");
            }
        }
        if (mapper.countOpenAppeals(session.getId(), entryId, nodeId) > 0) {
            throw new OrientScoringException(OrientScoringException.ALREADY_OPEN, "该条目已有待处理的申诉");
        }
        OrientScoreAppeal appeal = new OrientScoreAppeal();
        appeal.setSessionId(session.getId());
        appeal.setMemberId(memberId);
        appeal.setEntryId(entryId);
        appeal.setNodeId(nodeId);
        appeal.setReason(text);
        appeal.setStatus(OrientScoreAppeal.STATUS_PENDING);
        appeal.setIdempotencyKey(key);
        appeal.setRequestFingerprint(fp);
        Date now = new Date();
        appeal.setCreateTime(now);
        appeal.setUpdateTime(now);
        try {
            if (mapper.insertAppeal(appeal) != 1) throw new IllegalStateException("申诉写入失败");
        } catch (DuplicateKeyException raced) {
            OrientScoreAppeal winner = mapper.selectAppealByIdem(memberId, key);
            if (winner != null) return appealSameOrConflict(winner, fp);
            throw raced;
        }
        return appeal;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrientScoreAppeal submitDispute(Long memberId, Long sessionId, String reason, String idempotencyKey) {
        requireEnabled();
        String key = requireKey(idempotencyKey);
        String text = requireReason(reason);
        if (memberId == null) throw new OrientScoringException(OrientScoringException.SESSION_NOT_FOUND, "对局不存在");
        String fp = fingerprint("DISPUTE", sessionId, text);
        OrientScoreAppeal replay = mapper.selectDisputeByIdem(memberId, key);
        if (replay != null) return appealSameOrConflict(replay, fp);
        TopicRouteSession session = lockOwned(memberId, sessionId);
        requireActive(session);
        if (!ended(session)) {
            throw new OrientScoringException(OrientScoringException.SESSION_NOT_ENDED, "对局结束后才能提出成绩争议");
        }
        Date deadline = windowDeadline(session);
        if (deadline == null || new Date().after(deadline)) {
            throw new OrientScoringException(OrientScoringException.WINDOW_CLOSED, "已超过成绩争议期限");
        }
        if (mapper.countOpenDisputes(session.getId(), memberId) > 0) {
            throw new OrientScoringException(OrientScoringException.ALREADY_OPEN, "本局已有待处理的成绩争议");
        }
        OrientScoreAppeal dispute = new OrientScoreAppeal();
        dispute.setSessionId(session.getId());
        dispute.setMemberId(memberId);
        dispute.setReason(text);
        dispute.setStatus(OrientScoreAppeal.STATUS_PENDING);
        dispute.setWindowDeadline(deadline);
        dispute.setIdempotencyKey(key);
        dispute.setRequestFingerprint(fp);
        Date now = new Date();
        dispute.setCreateTime(now);
        dispute.setUpdateTime(now);
        try {
            if (mapper.insertDispute(dispute) != 1) throw new IllegalStateException("成绩争议写入失败");
        } catch (DuplicateKeyException raced) {
            OrientScoreAppeal winner = mapper.selectDisputeByIdem(memberId, key);
            if (winner != null) return appealSameOrConflict(winner, fp);
            throw raced;
        }
        return dispute;
    }

    private boolean windowClosed(TopicRouteSession session) {
        Date deadline = windowDeadline(session);
        return deadline == null || new Date().after(deadline);
    }

    private OrientScoreAppeal appealSameOrConflict(OrientScoreAppeal existing, String fingerprint) {
        if (fingerprint.equals(existing.getRequestFingerprint())) return existing;
        throw new OrientScoringException(OrientScoringException.IDEMPOTENCY_CONFLICT, "幂等键已用于不同的请求");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrientScoreAppeal resolveAppeal(Long appealId, boolean accept, Integer adjustCenti, String note,
                                           String handledBy, Long operatorId) {
        requireEnabled();
        OrientScoreAppeal appeal = appealId == null ? null : mapper.selectAppealById(appealId);
        if (appeal == null) throw new OrientScoringException(OrientScoringException.NOT_FOUND, "申诉不存在");
        TopicRouteSession session = lockOwned(null, appeal.getSessionId());
        appeal = mapper.selectAppealById(appealId);
        if (appeal.getStatus() == null || appeal.getStatus() != OrientScoreAppeal.STATUS_PENDING) {
            throw new OrientScoringException(OrientScoringException.ALREADY_RESOLVED, "申诉已处理");
        }
        Long resolutionEntryId = null;
        if (accept) {
            String key = "appeal:" + appeal.getId();
            OrientScoreEntry resolution;
            if (adjustCenti == null) {
                OrientScoreEntry target = appeal.getEntryId() == null ? null : mapper.selectEntryById(appeal.getEntryId());
                if (target == null) throw invalid("漏记类申诉采纳时必须给出修正分值");
                resolution = reverseLocked(session, target, "APPEAL_ACCEPTED", "APPEAL", "appeal:" + appeal.getId(),
                        operatorId, key);
            } else {
                if (adjustCenti == 0) throw invalid("修正分值不能为 0");
                resolution = newEntry(session, OrientScoreEntry.DIM_ADJUSTMENT, adjustCenti, "APPEAL_ACCEPTED",
                        "APPEAL", "appeal:" + appeal.getId(), appeal.getNodeId(), null, key, "ADMIN", operatorId);
                resolution = insertIdempotent(resolution);
            }
            resolutionEntryId = resolution.getId();
        }
        OrientScoreAppeal update = new OrientScoreAppeal();
        update.setId(appeal.getId());
        update.setStatus(accept ? OrientScoreAppeal.STATUS_ACCEPTED : OrientScoreAppeal.STATUS_REJECTED);
        update.setResolutionEntryId(resolutionEntryId);
        update.setResolutionNote(note == null ? null : truncate(note.trim(), 500));
        update.setHandledBy(handledBy == null ? null : truncate(handledBy, 64));
        Date now = new Date();
        update.setHandledAt(now);
        update.setUpdateTime(now);
        if (mapper.resolveAppeal(update) != 1) {
            throw new OrientScoringException(OrientScoringException.ALREADY_RESOLVED, "申诉已处理");
        }
        return mapper.selectAppealById(appeal.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrientScoreAppeal resolveDispute(Long disputeId, boolean accept, String note, String handledBy) {
        requireEnabled();
        OrientScoreAppeal dispute = disputeId == null ? null : mapper.selectDisputeById(disputeId);
        if (dispute == null) throw new OrientScoringException(OrientScoringException.NOT_FOUND, "成绩争议不存在");
        OrientScoreAppeal update = new OrientScoreAppeal();
        update.setId(dispute.getId());
        update.setStatus(accept ? OrientScoreAppeal.STATUS_ACCEPTED : OrientScoreAppeal.STATUS_REJECTED);
        update.setResolutionNote(note == null ? null : truncate(note.trim(), 500));
        update.setHandledBy(handledBy == null ? null : truncate(handledBy, 64));
        Date now = new Date();
        update.setHandledAt(now);
        update.setUpdateTime(now);
        if (mapper.resolveDispute(update) != 1) {
            throw new OrientScoringException(OrientScoringException.ALREADY_RESOLVED, "成绩争议已处理");
        }
        return mapper.selectDisputeById(dispute.getId());
    }

    @Override
    public List<OrientScoreAppeal> listAppeals(Integer status, int limit) {
        return mapper.selectAppealsByStatus(status, Math.max(1, Math.min(limit, 200)));
    }

    @Override
    public List<OrientScoreAppeal> listDisputes(Integer status, int limit) {
        return mapper.selectDisputesByStatus(status, Math.max(1, Math.min(limit, 200)));
    }

    // ---------------------------------------------------------------- 回执回读

    @Override
    public Map<String, Object> receipt(Long memberId, String kind, Long sessionId, String idempotencyKey) {
        requireEnabled();
        String key = requireKey(idempotencyKey);
        if (memberId == null) throw new OrientScoringException(OrientScoringException.SESSION_NOT_FOUND, "对局不存在");
        Map<String, Object> notFound = new LinkedHashMap<>();
        notFound.put("state", "NOT_FOUND");
        notFound.put("kind", kind);
        notFound.put("idempotencyKey", key);
        if (KIND_SYNC.equals(kind)) {
            TopicRouteSession session = readOwned(memberId, sessionId);
            OrientScoreSyncReceipt receipt = mapper.selectSyncReceipt(session.getId(), key);
            return receipt == null ? notFound : syncReceipt(receipt);
        }
        if (KIND_SETTLE.equals(kind)) {
            TopicRouteSession session = readOwned(memberId, sessionId);
            OrientScoreSettlement s = mapper.selectSettlementBySession(session.getId());
            // 结算按局恰好一次:换键重试也读回同一份回执
            return s == null ? notFound : settlementReceipt(s, true);
        }
        OrientScoreAppeal found;
        if (KIND_APPEAL.equals(kind)) {
            found = mapper.selectAppealByIdem(memberId, key);
        } else if (KIND_DISPUTE.equals(kind)) {
            found = mapper.selectDisputeByIdem(memberId, key);
        } else {
            throw invalid("kind 只能是 SYNC/SETTLE/APPEAL/DISPUTE");
        }
        if (found == null) return notFound;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("state", found.getStatus() != null && found.getStatus() == OrientScoreAppeal.STATUS_PENDING
                ? "PENDING" : found.getStatus() != null && found.getStatus() == OrientScoreAppeal.STATUS_ACCEPTED
                ? "ACCEPTED" : "REJECTED");
        r.put("kind", kind);
        r.put("idempotencyKey", key);
        r.put("record", found);
        return r;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrientScoreRecovery submitRecovery(Long memberId, Long sessionId, String kind) {
        requireEnabled();
        if (memberId == null) throw new OrientScoringException(OrientScoringException.SESSION_NOT_FOUND, "对局不存在");
        if (!Set.of(KIND_SYNC, KIND_SETTLE, KIND_APPEAL, KIND_DISPUTE).contains(kind == null ? "" : kind)) throw invalid("历史操作类型不合法");
        TopicRouteSession session = lockOwned(memberId, sessionId);
        requireActive(session);
        OrientScoreRecovery existing = mapper.selectRecoveryForUpdate(sessionId, kind);
        if (existing != null) return existing;
        OrientScoreRecovery recovery = new OrientScoreRecovery();
        recovery.setSessionId(sessionId); recovery.setMemberId(memberId); recovery.setKind(kind);
        recovery.setStatus(0); recovery.setCreateTime(new Date());
        if (mapper.insertRecovery(recovery) != 1) throw new IllegalStateException("人工核对登记失败");
        return recovery;
    }

    @Override
    public List<OrientScoreRecovery> listRecoveries(Integer status, int limit) {
        return mapper.selectRecoveriesByStatus(status, Math.max(1, Math.min(limit, 200)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrientScoreRecovery reviewRecovery(Long id, Long referenceId, String note, String handledBy) {
        requireEnabled();
        String text = requireReason(note);
        OrientScoreRecovery row = id == null ? null : mapper.selectRecoveryById(id);
        if (row == null) throw new OrientScoringException(OrientScoringException.NOT_FOUND, "人工核对记录不存在");
        TopicRouteSession session = lockOwned(null, row.getSessionId());
        row = mapper.selectRecoveryForUpdate(session.getId(), row.getKind());
        if (!Integer.valueOf(0).equals(row.getStatus())) throw new OrientScoringException(OrientScoringException.ALREADY_RESOLVED, "人工核对已记录");
        // References must be actual same-owner/session facts; no arbitrary success or NOT_FOUND attestation.
        if (referenceId != null) {
            if (KIND_SETTLE.equals(row.getKind())) {
                OrientScoreSettlement settlement = mapper.selectSettlementBySession(session.getId());
                if (settlement == null || !referenceId.equals(settlement.getId())
                        || !row.getMemberId().equals(settlement.getMemberId())) throw invalid("关联结算不属于本局本人");
            } else if (KIND_APPEAL.equals(row.getKind()) || KIND_DISPUTE.equals(row.getKind())) {
                OrientScoreAppeal record = KIND_APPEAL.equals(row.getKind()) ? mapper.selectAppealById(referenceId) : mapper.selectDisputeById(referenceId);
                if (record == null || !session.getId().equals(record.getSessionId())
                        || !row.getMemberId().equals(record.getMemberId())) throw invalid("关联申请不属于本局本人");
            } else throw invalid("同步核对不接受不明流水作为原请求凭据");
        }
        if (!Boolean.TRUE.equals(verify(session.getId()).get("consistent"))) throw invalid("计分复算不一致，请先处理账本差异");
        row.setReferenceId(referenceId); row.setResolutionNote(text);
        row.setHandledBy(handledBy == null ? null : truncate(handledBy, 64)); row.setHandledAt(new Date());
        row.setStatus(1);
        if (mapper.reviewRecovery(row) != 1) throw new OrientScoringException(OrientScoringException.ALREADY_RESOLVED, "人工核对已记录");
        return row;
    }

    @Override
    public Map<String, Object> leaderboard(Long memberId, Long sessionId) {
        requireEnabled();
        if (memberId == null) throw new OrientScoringException(OrientScoringException.SESSION_NOT_FOUND, "对局不存在");
        TopicRouteSession session = readOwned(memberId, sessionId);
        requireActive(session);
        requireLeaderboardAccess(memberId, session);
        boolean route = com.chengyinhub.business.service.support.LeaderboardRankPolicy.isRouteRanked(
                cmsTopicService.selectCmsTopicById(session.getTopicId()));
        List<Map<String, Object>> rows = mapper.selectRankedBoard(session.getActivityId() == null ? 0L : session.getActivityId(),
                session.getTopicId(), memberId, route, 50);
        List<Map<String, Object>> list = new ArrayList<>();
        Map<String, Object> me = null;
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            Object id = valueIgnoreCase(row, "memberId");
            boolean mine = id instanceof Number n && n.longValue() == memberId;
            item.put("rank", String.valueOf(valueIgnoreCase(row, "boardRank")));
            item.put("scoreCenti", valueIgnoreCase(row, "scoreCenti"));
            Object elapsed = valueIgnoreCase(row, "elapsedMs");
            item.put("elapsedMs", elapsed == null ? null : elapsed.toString());
            Object reached = valueIgnoreCase(row, "reachedEnding");
            item.put("reachedEnding", Boolean.TRUE.equals(reached) || reached instanceof Number n && n.intValue() == 1);
            item.put("mine", mine);
            // Do not expose other players' session or member identifiers through this read model.
            item.put("rowKey", String.valueOf(valueIgnoreCase(row, "displayRow")));
            if (mine) me = item;
            Object index = valueIgnoreCase(row, "displayRow");
            if (index instanceof Number n && n.longValue() <= 50) list.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("mode", route ? "ROUTE" : "SCORE");
        result.put("list", list);
        result.put("me", me);
        return result;
    }

    /** Keep the existing /api/play/leaderboard entitlement and visibility policy. Owning an old run is insufficient. */
    private void requireLeaderboardAccess(Long memberId, TopicRouteSession session) {
        boolean activity = session.getActivityId() != null && session.getActivityId() > 0;
        if (activity) {
            com.chengyinhub.business.domain.CmsActivity current = scoringActivities.selectCmsActivityById(session.getActivityId());
            if (current == null || !session.getTopicId().equals(current.getTopicId())) throw invalid("活动与主题不匹配");
        }
        com.chengyinhub.business.domain.CmsRegistration probe = new com.chengyinhub.business.domain.CmsRegistration();
        probe.setMemberId(memberId); probe.setOwnerType(activity ? 2L : 1L);
        probe.setOwnerId(activity ? session.getActivityId() : session.getTopicId()); probe.setRegistrationStatus(2);
        List<com.chengyinhub.business.domain.CmsRegistration> registrations = scoringRegistrations.selectCmsRegistrationList(probe);
        Date now = new Date();
        boolean admitted = registrations != null && registrations.stream().anyMatch(registration -> registration != null
                && (activity || registration.getExpiresAt() == null || registration.getExpiresAt().after(now)));
        if (!admitted) throw new OrientScoringException("ORIENT_FORBIDDEN", activity ? "请先报名并完成支付" : "请先购买有效自玩通行证");
        if (activity && scoringGameSessions.isRouteLeaderboardHidden(session.getActivityId())) {
            throw new OrientScoringException("ORIENT_FORBIDDEN", "主理人暂未开放本场排行榜");
        }
    }

    // ---------------------------------------------------------------- 排行榜附加

    @Override
    public void decorateLeaderboard(CmsTopic topic, Long activityId, Long topicId, List<LeaderboardItemVO> list,
                                    LeaderboardItemVO me) {
        if (!isActiveFor(topic)) return;
        List<Long> memberIds = new ArrayList<>();
        if (list != null) for (LeaderboardItemVO row : list) if (row.getMemberId() != null) memberIds.add(row.getMemberId());
        if (me != null && me.getMemberId() != null && !memberIds.contains(me.getMemberId())) memberIds.add(me.getMemberId());
        if (memberIds.isEmpty()) return;
        Map<Long, Long> totals = new HashMap<>();
        for (Map<String, Object> row : mapper.selectBoardTotals(activityId == null ? 0L : activityId, topicId, memberIds)) {
            Object member = valueIgnoreCase(row, "memberId");
            Object total = valueIgnoreCase(row, "totalCenti");
            if (member instanceof Number m) totals.put(m.longValue(), total instanceof Number t ? t.longValue() : 0L);
        }
        if (list != null) for (LeaderboardItemVO row : list) row.setScoreCenti(totals.get(row.getMemberId()));
        if (me != null) me.setScoreCenti(totals.get(me.getMemberId()));
    }

    private static Object valueIgnoreCase(Map<String, Object> row, String key) {
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) return e.getValue();
        }
        return null;
    }

    // ---------------------------------------------------------------- 工具

    static long sum(List<OrientScoreEntry> entries) {
        long total = 0L;
        for (OrientScoreEntry e : entries) total += e.getDeltaCenti() == null ? 0 : e.getDeltaCenti();
        return total;
    }

    private static String requireKey(String key) {
        if (key == null || !IDEM_KEY.matcher(key).matches()) throw invalid("幂等键缺失或格式不合法");
        return key;
    }

    private static String requireReason(String reason) {
        String text = reason == null ? "" : reason.trim();
        if (text.isEmpty() || text.length() > 500) throw invalid("请填写 1-500 字的理由");
        return text;
    }

    private static OrientScoringException invalid(String message) {
        return new OrientScoringException(OrientScoringException.INVALID_ARGUMENT, message);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    static String fingerprint(Object... parts) {
        StringBuilder canonical = new StringBuilder();
        for (Object part : parts) canonical.append(part == null ? "∅" : part.toString()).append('\u001f');
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
