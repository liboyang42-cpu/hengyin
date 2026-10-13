package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ClubPostMediaReviewOutbox;
import com.chengyinhub.business.mapper.ClubPostMediaReviewOutboxMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(ClubPostMediaReviewTransactionService.class);
    private static final int MAX_ATTEMPTS = 5;
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
    public boolean retryTransient(ClubPostMediaReviewOutbox task, String errorCode) {
        if (!owned(task)) return false;
        int attempts = task.getAttemptCount() == null ? Integer.MAX_VALUE : task.getAttemptCount();
        int maxAttempts = task.getMaxAttempts() == null ? 0 : task.getMaxAttempts();
        if (maxAttempts > 0 && attempts >= maxAttempts) {
            log.error("ClubPost media review remains retryable beyond alert threshold, taskId={}, attempts={}, code={}",
                    task.getId(), attempts, safeCode(errorCode));
        }
        long delayMillis = (1L << Math.min(Math.max(attempts - 1, 0), 6)) * 60_000L;
        return mapper.markRetryable(task.getId(), task.getOwnerToken(), task.getFenceToken(),
                new Date(System.currentTimeMillis() + delayMillis), safeCode(errorCode)) == 1;
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
