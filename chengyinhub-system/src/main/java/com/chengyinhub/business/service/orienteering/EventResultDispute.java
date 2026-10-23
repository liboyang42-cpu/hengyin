package com.chengyinhub.business.service.orienteering;

import com.chengyinhub.business.service.orienteering.EventScoreVersioning.ResultVersion;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 成绩争议与原奖励恢复。恢复指向完赛时封存的 originalRewardCenti / 原 requestId，
 * 不新造发奖请求，也不改成长个人榜。
 */
public final class EventResultDispute {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_RESTORED = 1;
    public static final int STATUS_REJECTED = 2;

    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_.:-]{8,64}");

    private final EventScoreVersioning scores;
    private final AtomicLong seq = new AtomicLong(1);
    private final Map<String, Dispute> byRequest = new ConcurrentHashMap<String, Dispute>();
    private final Map<Long, Dispute> byId = new ConcurrentHashMap<Long, Dispute>();
    private final Map<String, RestoreReceipt> restores = new ConcurrentHashMap<String, RestoreReceipt>();

    public EventResultDispute(EventScoreVersioning scores) {
        if (scores == null) throw new IllegalArgumentException("成绩版本真源不能为空");
        this.scores = scores;
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

    /**
     * 按原完赛请求恢复奖励快照。回执引用原 requestId 与 originalRewardCenti，
     * 不生成新的发奖 request。
     */
    public RestoreReceipt restoreOriginalReward(String disputeRequestId, String restoreRequestId) {
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
        ResultVersion original = scores.readback(dispute.originalResultRequestId);
        RestoreReceipt receipt = new RestoreReceipt(key, dispute.requestId, original.requestId, original.version,
                original.originalRewardCenti, original.rewardKind, original.frozenRuleVersion);
        restores.put(key, receipt);
        dispute.status = STATUS_RESTORED;
        dispute.restoreRequestId = key;
        return receipt;
    }

    public RestoreReceipt readRestore(String restoreRequestId) {
        RestoreReceipt found = restoreRequestId == null ? null : restores.get(restoreRequestId);
        if (found == null) {
            throw new OrienteeringEventException(OrienteeringEventException.NOT_FOUND, "没有这条原奖励恢复回执");
        }
        return found;
    }

    private static String requireKey(String requestId) {
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
            throw new OrienteeringEventException(OrienteeringEventException.INVALID_ARGUMENT, "幂等键无效");
        }
        return requestId;
    }

    public static final class Dispute {
        public final long id;
        public final String requestId;
        public final String originalResultRequestId;
        public final int originalResultVersion;
        public final int originalRewardCenti;
        public final String rewardKind;
        public final String reason;
        volatile int status;
        volatile String restoreRequestId;
        public final Date openedAt;

        Dispute(long id, String requestId, String originalResultRequestId, int originalResultVersion,
                int originalRewardCenti, String rewardKind, String reason, int status, Date openedAt) {
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
        public final int restoredRewardCenti;
        public final String rewardKind;
        public final long frozenRuleVersion;

        RestoreReceipt(String requestId, String disputeRequestId, String originalResultRequestId,
                       int originalResultVersion, int restoredRewardCenti, String rewardKind, long frozenRuleVersion) {
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
