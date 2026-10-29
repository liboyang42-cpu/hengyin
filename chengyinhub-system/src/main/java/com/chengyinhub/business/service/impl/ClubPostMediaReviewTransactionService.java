package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ClubPostMediaReviewOutbox;
import com.chengyinhub.business.mapper.ClubPostMediaReviewOutboxMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Short database transactions for enqueue, claim, and fenced state receipts. */
@Service
public class ClubPostMediaReviewTransactionService {
    private static final int MAX_ATTEMPTS = ClubPostMediaReviewOutbox.MAX_ATTEMPTS;
    private final ClubPostMediaReviewOutboxMapper mapper;

    public ClubPostMediaReviewTransactionService(ClubPostMediaReviewOutboxMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public int enqueueUpdatedMedia(Long postId, Integer postVersion, Long submitterMemberId, String images) {
        if (postId == null || postId <= 0 || postVersion == null || postVersion < 0
                || submitterMemberId == null || submitterMemberId <= 0) {
            throw new IllegalArgumentException("ClubPost media review identity is invalid");
        }
        Map<String, String> mediaByHash = mediaByHash(images);
        int inserted = 0;
        for (Map.Entry<String, String> media : mediaByHash.entrySet()) {
            ClubPostMediaReviewOutbox task = new ClubPostMediaReviewOutbox();
            task.setPostId(postId);
            task.setPostVersion(postVersion);
            task.setSubmitterMemberId(submitterMemberId);
            task.setMediaHash(media.getKey());
            task.setState(ClubPostMediaReviewOutbox.STATE_PENDING);
            task.setAttemptCount(0);
            task.setMaxAttempts(MAX_ATTEMPTS);
            int rows = mapper.insertIgnore(task);
            if (rows == 1) {
                inserted++;
                continue;
            }
            ClubPostMediaReviewOutbox existing = mapper.selectByKey(postId, postVersion, media.getKey());
            if (existing == null || !submitterMemberId.equals(existing.getSubmitterMemberId())) {
                throw new IllegalStateException("ClubPost media review identity collision");
            }
        }
        return inserted;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public ClubPostMediaReviewOutbox claim(Long taskId) {
        if (taskId == null || taskId <= 0) return null;
        String ownerToken = UUID.randomUUID().toString().replace("-", "");
        // A lease ending proves neither non-dispatch nor provider failure. Never reclaim it.
        mapper.retireUnsafeById(taskId);
        if (mapper.claim(taskId, ownerToken) != 1) return null;
        ClubPostMediaReviewOutbox claimed = mapper.selectOwned(taskId, ownerToken);
        if (claimed == null) throw new IllegalStateException("ClubPost media review claim receipt missing");
        return claimed;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean markSubmitted(ClubPostMediaReviewOutbox task, String traceId) {
        if (!owned(task) || traceId == null || traceId.trim().isEmpty()) return false;
        return mapper.markSubmitted(task.getId(), task.getOwnerToken(), task.getFenceToken(),
                WxContentCallbackInboxServiceImpl.sha256Hex(traceId.trim())) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public int retireUnsafe(int limit) {
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("Invalid media review batch limit");
        return mapper.retireUnsafeBatch(limit);
    }

    /** Only the caller's pre-dispatch local failure is evidence for automatic retry. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean retryTransient(ClubPostMediaReviewOutbox task, String errorCode) {
        if (!owned(task)) return false;
        if (!ClubPostMediaReviewOutbox.PRE_DISPATCH_UNAVAILABLE.equals(errorCode)) {
            return manual(task, null, ClubPostMediaReviewOutbox.OUTCOME_UNKNOWN);
        }
        int attempts = task.getAttemptCount() == null ? Integer.MAX_VALUE : task.getAttemptCount();
        int budget = task.getMaxAttempts() == null ? 0 : Math.min(MAX_ATTEMPTS, task.getMaxAttempts());
        if (budget <= 0 || attempts >= budget) {
            return manual(task, null, ClubPostMediaReviewOutbox.ATTEMPTS_EXHAUSTED);
        }
        long delayMillis = (1L << Math.min(Math.max(attempts - 1, 0), 6)) * 60_000L;
        if (mapper.markRetryable(task.getId(), task.getOwnerToken(), task.getFenceToken(),
                new Date(System.currentTimeMillis() + delayMillis), errorCode) == 1) return true;
        // SQL, not the worker's snapshot, owns the final budget and fence decision.
        return manual(task, null, ClubPostMediaReviewOutbox.ATTEMPTS_EXHAUSTED);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean markUnknown(ClubPostMediaReviewOutbox task, String traceId) {
        return owned(task) && manual(task, traceId, ClubPostMediaReviewOutbox.OUTCOME_UNKNOWN);
    }

    private boolean manual(ClubPostMediaReviewOutbox task, String traceId, String code) {
        String traceHash = traceId == null || traceId.trim().isEmpty() ? null
                : WxContentCallbackInboxServiceImpl.sha256Hex(traceId.trim());
        return mapper.markManualReview(task.getId(), task.getOwnerToken(), task.getFenceToken(),
                traceHash, code) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean markStale(ClubPostMediaReviewOutbox task, String errorCode) {
        return owned(task) && mapper.markStale(task.getId(), task.getOwnerToken(), task.getFenceToken(),
                safeCode(errorCode)) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean markPoisoned(ClubPostMediaReviewOutbox task, String errorCode) {
        return owned(task) && mapper.markPoisoned(task.getId(), task.getOwnerToken(), task.getFenceToken(),
                safeCode(errorCode)) == 1;
    }

    static Map<String, String> mediaByHash(String images) {
        Map<String, String> mediaByHash = new LinkedHashMap<>();
        if (images == null || images.trim().isEmpty()) return mediaByHash;
        for (String raw : images.split("[,;]")) {
            String media = raw == null ? "" : raw.trim();
            if (!media.isEmpty()) {
                mediaByHash.putIfAbsent(WxContentCallbackInboxServiceImpl.sha256Hex(media), media);
            }
        }
        return mediaByHash;
    }

    private boolean owned(ClubPostMediaReviewOutbox task) {
        return task != null && task.getId() != null && task.getId() > 0
                && task.getOwnerToken() != null && task.getOwnerToken().matches("(?i)[0-9a-f]{32}")
                && task.getFenceToken() != null && task.getFenceToken() > 0;
    }

    private String safeCode(String errorCode) {
        String code = errorCode == null ? "DISPATCH_ERROR" : errorCode.trim().toUpperCase();
        if (!code.matches("[A-Z0-9_]{1,64}")) return "DISPATCH_ERROR";
        return code;
    }
}
