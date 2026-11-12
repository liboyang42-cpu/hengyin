package com.chengyinhub.business.service.orienteering;

import com.chengyinhub.business.service.orienteering.EventScoreVersioning.ResultVersion;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 成绩争议与原奖励恢复。恢复指向完赛时封存的 originalRewardCenti / 原 requestId，
 * 不新造发奖请求，也不改成长个人榜。originalRewardCenti 不是 scoreCenti。
 * 原奖励事实回读走 {@link OriginalRewardReadSource}（orientation.originalRewardRead）；
 * 不复制私有 OrientationOriginalRewardReadService，不另造发奖账。
 */
public final class EventResultDispute {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_RESTORED = 1;
    public static final int STATUS_REJECTED = 2;
    public static final String OUTCOME_NOT_FOUND = "NOT_FOUND";
    public static final String OUTCOME_PENDING = "PENDING";
    public static final String OUTCOME_RESTORED = "RESTORED";
    public static final String OUTCOME_REJECTED = "REJECTED";
    public static final String ORIGINAL_REWARD_READ = "orientation.originalRewardRead";

    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_.:-]{8,64}");

    private final EventScoreVersioning scores;
    private final OriginalRewardReadSource rewardRead;
    private final AtomicLong seq = new AtomicLong(1);
    private final Map<String, Dispute> byRequest = new ConcurrentHashMap<String, Dispute>();
    private final Map<Long, Dispute> byId = new ConcurrentHashMap<Long, Dispute>();
    private final Map<String, RestoreReceipt> restores = new ConcurrentHashMap<String, RestoreReceipt>();

    public EventResultDispute(EventScoreVersioning scores) {
        this(scores, null);
    }

    public EventResultDispute(EventScoreVersioning scores, OriginalRewardReadSource rewardRead) {
        if (scores == null) throw new IllegalArgumentException("成绩版本真源不能为空");
        this.scores = scores;
        this.rewardRead = rewardRead;
    }

    public Dispute open(String resultRequestId, String reason, String requestId) {
        String key = requireKey(requestId);
        ResultVersion original = scores.readback(resultRequestId);
        if (reason == null || reason.trim().isEmpty()) {
            throw new OrienteeringEventException(OrienteeringEventException.INVALID_ARGUMENT, "争议原因不能为空");
        }
        Dispute created = new Dispute(seq.getAndIncrement(), key, original.requestId, original.version,
                original.originalRewardCenti, original.rewardKind, reason.trim(), STATUS_PENDING, new Date());
        Dispute previous = byRequest.putIfAbsent(key, created);
        if (previous != null) {
            if (!previous.sameOpen(created)) {
                throw new OrienteeringEventException(OrienteeringEventException.IDEMPOTENCY_CONFLICT,
                        "同一争议键不能改写");
            }
            return previous;
        }
        byId.put(created.id, created);
        return created;
    }

    public Dispute readback(String requestId) {
        Dispute found = requestId == null ? null : byRequest.get(requestId);
        if (found == null) {
            throw new OrienteeringEventException(OrienteeringEventException.NOT_FOUND, "没有这条争议回执");
        }
        return found;
    }

    public OriginalReceipt recoverOriginal(String requestId) {
        return recoverUnknown(requestId);
    }

    /**
     * Missing / blank requestId stays Java {@code null} with typed {@code NOT_FOUND}.
     * Never-seen ids return {@code NOT_FOUND} without occupying the ledger.
     */
    public OriginalReceipt recoverUnknown(String requestId) {
        String id = parseKey(requestId);
        if (id == null) {
            return OriginalReceipt.notFound(null);
        }
        Dispute found = byRequest.get(id);
        if (found == null) {
            return OriginalReceipt.notFound(id);
        }
        return new OriginalReceipt(found.requestId, outcomeOf(found.status), false, found);
    }

    /**
     * 按原完赛请求恢复奖励快照。回执引用原 requestId 与 originalRewardCenti，
     * 不生成新的发奖 request。
     */
    public RestoreReceipt restoreOriginalReward(String disputeRequestId, String restoreRequestId) {
        throw new OrienteeringEventException(OrienteeringEventException.WAITING_SOURCE,
                "原奖励恢复需要原奖励事实", ORIGINAL_REWARD_READ);
    }

    /**
     * 恢复消费原奖励事实。事实缺失或不可用时不标 RESTORED，也不把占位 0 当成已恢复。
     */
    public RestoreReceipt restoreOriginalReward(String disputeRequestId, String restoreRequestId,
            long memberId, long sessionId) {
        String key = requireKey(restoreRequestId);
        Dispute dispute = readback(disputeRequestId);
        RestoreReceipt existing = restores.get(key);
        if (existing != null) {
            if (!existing.disputeRequestId.equals(disputeRequestId)) {
                throw new OrienteeringEventException(OrienteeringEventException.IDEMPOTENCY_CONFLICT,
                        "同一恢复键不能改写");
            }
            return existing;
        }
        if (dispute.status != STATUS_PENDING) {
            throw new OrienteeringEventException(OrienteeringEventException.ALREADY_RESOLVED, "争议已处理");
        }
        if (rewardRead == null) {
            throw new OrienteeringEventException(OrienteeringEventException.WAITING_SOURCE,
                    "原奖励恢复需要原奖励事实", ORIGINAL_REWARD_READ);
        }
        Map<String, Object> fact = rewardRead.readPersonalNaturalFinish(memberId, sessionId);
        Integer reward = confirmedRewardCenti(fact);
        if (reward == null) {
            throw new OrienteeringEventException(OrienteeringEventException.WAITING_SOURCE,
                    "原奖励事实未确认", ORIGINAL_REWARD_READ);
        }
        ResultVersion original = scores.readback(dispute.originalResultRequestId);
        RestoreReceipt receipt = new RestoreReceipt(key, dispute.requestId, original.requestId, original.version,
                reward, original.rewardKind, original.frozenRuleVersion);
        restores.put(key, receipt);
        dispute.status = STATUS_RESTORED;
        dispute.restoreRequestId = key;
        return receipt;
    }

    private static Integer confirmedRewardCenti(Map<String, Object> fact) {
        if (fact == null) {
            return null;
        }
        Object state = fact.get("state");
        if ("PARTIAL".equals(state) || "UNAVAILABLE".equals(state)) {
            return null;
        }
        if (!"AVAILABLE".equals(state) || !(fact.get("originalRewardCenti") instanceof Number)) {
            return null;
        }
        return Integer.valueOf(((Number) fact.get("originalRewardCenti")).intValue());
    }

    public RestoreReceipt readRestore(String restoreRequestId) {
        RestoreReceipt found = restoreRequestId == null ? null : restores.get(restoreRequestId);
        if (found == null) {
            throw new OrienteeringEventException(OrienteeringEventException.NOT_FOUND, "没有这条原奖励恢复回执");
        }
        return found;
    }

    /**
     * 新实例从原奖励只读口回读完赛发放事实。缺端口 WAITING_SOURCE {@link #ORIGINAL_REWARD_READ}；
     * 非法 member/session 返回 {@code null}；未见过的键返回 {@code null}，不占争议账。
     * 不复制私有 mapper XML，不把 scoreCenti 当成奖励。
     */
    public Map<String, Object> recoverFromOriginalRewardRead(long memberId, long sessionId) {
        if (memberId <= 0L || sessionId <= 0L) {
            return null;
        }
        if (rewardRead == null) {
            throw new OrienteeringEventException(OrienteeringEventException.WAITING_SOURCE,
                    "原奖励回读需要 orientation.originalRewardRead", ORIGINAL_REWARD_READ);
        }
        Map<String, Object> fact = rewardRead.readPersonalNaturalFinish(memberId, sessionId);
        if (fact == null) {
            return null;
        }
        return fact;
    }

    /** 只读原完赛发放事实；不发奖、不 insert。 */
    public interface OriginalRewardReadSource {
        Map<String, Object> readPersonalNaturalFinish(long memberId, long sessionId);
    }

    static String parseKey(String requestId) {
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
            return null;
        }
        return requestId;
    }

    private static String requireKey(String requestId) {
        String id = parseKey(requestId);
        if (id == null) {
            throw new OrienteeringEventException(OrienteeringEventException.INVALID_ARGUMENT, "幂等键无效");
        }
        return id;
    }

    private static String outcomeOf(int status) {
        if (status == STATUS_RESTORED) return OUTCOME_RESTORED;
        if (status == STATUS_REJECTED) return OUTCOME_REJECTED;
        return OUTCOME_PENDING;
    }

    public static final class OriginalReceipt {
        public final String originalRequestId;
        public final String outcome;
        public final boolean mintedNewRequest;
        public final Dispute dispute;

        OriginalReceipt(String originalRequestId, String outcome, boolean mintedNewRequest, Dispute dispute) {
            this.originalRequestId = originalRequestId;
            this.outcome = outcome;
            this.mintedNewRequest = mintedNewRequest;
            this.dispute = dispute;
        }

        static OriginalReceipt notFound(String requestId) {
            return new OriginalReceipt(requestId, OUTCOME_NOT_FOUND, false, null);
        }
    }

    public static final class Dispute {
        public final long id;
        public final String requestId;
        public final String originalResultRequestId;
        public final int originalResultVersion;
        public final Integer originalRewardCenti;
        public final String rewardKind;
        public final String reason;
        volatile int status;
        volatile String restoreRequestId;
        public final Date openedAt;

        Dispute(long id, String requestId, String originalResultRequestId, int originalResultVersion,
                Integer originalRewardCenti, String rewardKind, String reason, int status, Date openedAt) {
            this.id = id;
            this.requestId = requestId;
            this.originalResultRequestId = originalResultRequestId;
            this.originalResultVersion = originalResultVersion;
            this.originalRewardCenti = originalRewardCenti;
            this.rewardKind = rewardKind;
            this.reason = reason;
            this.status = status;
            this.restoreRequestId = null;
            this.openedAt = openedAt;
        }

        public int status() { return status; }
        public String restoreRequestId() { return restoreRequestId; }

        boolean sameOpen(Dispute other) {
            return originalResultRequestId.equals(other.originalResultRequestId) && reason.equals(other.reason);
        }
    }

    public static final class RestoreReceipt {
        public final String requestId;
        public final String disputeRequestId;
        public final String originalResultRequestId;
        public final int originalResultVersion;
        public final Integer restoredRewardCenti;
        public final String rewardKind;
        public final long frozenRuleVersion;

        RestoreReceipt(String requestId, String disputeRequestId, String originalResultRequestId,
                       int originalResultVersion, Integer restoredRewardCenti, String rewardKind, long frozenRuleVersion) {
            this.requestId = requestId;
            this.disputeRequestId = disputeRequestId;
            this.originalResultRequestId = originalResultRequestId;
            this.originalResultVersion = originalResultVersion;
            this.restoredRewardCenti = restoredRewardCenti;
            this.rewardKind = rewardKind;
            this.frozenRuleVersion = frozenRuleVersion;
        }
    }
}
