package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ClubPost;
import com.chengyinhub.business.domain.ClubPostMediaReviewOutbox;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.ClubPostMapper;
import com.chengyinhub.business.mapper.ClubPostMediaReviewOutboxMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IClubPostMediaReviewService;
import com.chengyinhub.business.service.IWxContentCallbackInboxService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

@Service
public class ClubPostMediaReviewServiceImpl implements IClubPostMediaReviewService {
    private static final Logger log = LoggerFactory.getLogger(ClubPostMediaReviewServiceImpl.class);
    private static final int BATCH_LIMIT = 50;
    /** A running external call is not cancelled; after this elapsed time no new task is claimed. */
    private static final long NEW_TASK_START_BUDGET_NANOS = 25_000_000_000L;
    private static final long BACKLOG_ALERT_COUNT = 100L;
    private static final long BACKLOG_ALERT_AGE_MILLIS = 10L * 60L * 1000L;
    private static final long BACKLOG_ALARM_COOLDOWN_MILLIS = 10L * 60L * 1000L;

    private final ClubPostMediaReviewOutboxMapper outboxMapper;
    private final ClubPostMediaReviewTransactionService transactionService;
    private final ClubPostMapper postMapper;
    private final UmsMemberMapper memberMapper;
    private final IWxContentSecurityService securityService;
    private final IWxContentCallbackInboxService callbackInboxService;
    private final AlarmService alarmService;
    private final AtomicLong nextBacklogAlarmAtMillis = new AtomicLong(0L);
    private LongSupplier nanoTime = System::nanoTime;

    public ClubPostMediaReviewServiceImpl(ClubPostMediaReviewOutboxMapper outboxMapper,
                                          ClubPostMediaReviewTransactionService transactionService,
                                          ClubPostMapper postMapper,
                                          UmsMemberMapper memberMapper,
                                          IWxContentSecurityService securityService,
                                          IWxContentCallbackInboxService callbackInboxService,
                                          AlarmService alarmService) {
        this.outboxMapper = outboxMapper;
        this.transactionService = transactionService;
        this.postMapper = postMapper;
        this.memberMapper = memberMapper;
        this.securityService = securityService;
        this.callbackInboxService = callbackInboxService;
        this.alarmService = alarmService;
    }

    @Override
    public int enqueueUpdatedMedia(Long postId, Integer postVersion, Long submitterMemberId, String images) {
        return transactionService.enqueueUpdatedMedia(postId, postVersion, submitterMemberId, images);
    }

    @Override
    public int retryDue() {
        logBacklogIfNeeded();
        if (!reviewEnabled()) return 0;
        transactionService.retireUnsafe(BATCH_LIMIT);
        List<Long> dueIds = outboxMapper.selectDueIds(BATCH_LIMIT);
        return dispatch(dueIds);
    }

    public boolean dispatchOne(Long taskId) {
        if (!reviewEnabled()) return false;
        return dispatchClaimed(taskId) == DispatchOutcome.SUBMITTED;
    }

    private DispatchOutcome dispatchClaimed(Long taskId) {
        ClubPostMediaReviewOutbox task;
        try {
            task = transactionService.claim(taskId);
        } catch (RuntimeException claimFailure) {
            log.error("ClubPost media review claim failed; task remains recoverable, taskId={}", taskId,
                    claimFailure);
            return DispatchOutcome.RETRYABLE_FAILURE;
        }
        if (task == null) return DispatchOutcome.SKIPPED;
        String mediaUrl;
        String openid;
        try {
            if (!validTask(task)) {
                transactionService.markPoisoned(task, "TASK_INVALID");
                return DispatchOutcome.SKIPPED;
            }
            ClubPost post = postMapper.selectClubPostById(task.getPostId());
            if (post == null || !Integer.valueOf(0).equals(post.getDelFlag())) {
                transactionService.markStale(task, "POST_NOT_ACTIVE");
                return DispatchOutcome.SKIPPED;
            }
            mediaUrl = mediaUrl(task.getMediaHash(), post.getImages());
            if (mediaUrl == null) {
                transactionService.markStale(task, "MEDIA_STALE");
                return DispatchOutcome.SKIPPED;
            }
            UmsMember member = memberMapper.selectUmsMemberById(task.getSubmitterMemberId());
            openid = member == null ? null : member.getWxOpenId();
            if (openid == null || openid.trim().isEmpty()) {
                transactionService.markPoisoned(task, "SUBMITTER_IDENTITY_UNAVAILABLE");
                return DispatchOutcome.SKIPPED;
            }
        } catch (RuntimeException beforeDispatch) {
            // No provider method has been entered on this path.
            retryTransient(task, ClubPostMediaReviewOutbox.PRE_DISPATCH_UNAVAILABLE);
            return DispatchOutcome.RETRYABLE_FAILURE;
        }

        String traceId = null;
        try {
            traceId = securityService.submitMediaCheckOne(
                    IWxContentSecurityService.BIZ_TYPE_CLUB_POST, task.getPostId(), mediaUrl, openid);
            // The legacy checker collapses not-sent, transport UNKNOWN and inbox failure to null.
            // Null or any exception after entry cannot authorize another submission.
            if (traceId == null || traceId.trim().isEmpty()
                    || !callbackInboxService.isRegistered(traceId)) {
                unknown(task, traceId);
                return DispatchOutcome.UNKNOWN;
            }
            if (!transactionService.markSubmitted(task, traceId)) {
                unknown(task, traceId);
                return DispatchOutcome.UNKNOWN;
            }
            return DispatchOutcome.SUBMITTED;
        } catch (RuntimeException afterDispatch) {
            unknown(task, traceId);
            return DispatchOutcome.UNKNOWN;
        }
    }

    private void unknown(ClubPostMediaReviewOutbox task, String traceId) {
        try {
            if (!transactionService.markUnknown(task, traceId)) {
                log.error("ClubPost media review UNKNOWN write lost ownership, taskId={}", task.getId());
            }
        } catch (RuntimeException receiptFailure) {
            // Leave PROCESSING intact; expiration only stops it, never dispatches it again.
            log.error("ClubPost media review UNKNOWN write unavailable, taskId={} type={}",
                    task.getId(), receiptFailure.getClass().getSimpleName());
        }
        alarmSafely("俱乐部动态图片送审结果待确认", "taskId=" + task.getId() + ", postId=" + task.getPostId());
    }

    private int dispatch(List<Long> ids) {
        int submitted = 0;
        int attempted = 0;
        long startedAt = nanoTime.getAsLong();
        for (Long id : ids == null ? Collections.<Long>emptyList() : ids) {
            if (attempted > 0 && nanoTime.getAsLong() - startedAt >= NEW_TASK_START_BUDGET_NANOS) {
                log.warn("ClubPost media review batch stopped before next claim after start budget; attempted={}",
                        attempted);
                break;
            }
            try {
                DispatchOutcome outcome = dispatchClaimed(id);
                attempted++;
                if (outcome == DispatchOutcome.SUBMITTED) submitted++;
                if (outcome == DispatchOutcome.RETRYABLE_FAILURE || outcome == DispatchOutcome.UNKNOWN) {
                    log.warn("ClubPost media review batch circuit opened after retryable failure; taskId={}", id);
                    break;
                }
            } catch (RuntimeException poison) {
                log.error("ClubPost media review poison isolated, taskId={}", id, poison);
                break;
            }
        }
        return submitted;
    }

    private void logBacklogIfNeeded() {
        try {
            long backlogCount = outboxMapper.countBacklog();
            if (backlogCount <= 0L) return;
            Date oldestCreatedAt = outboxMapper.selectOldestBacklogCreatedAt();
            boolean tooOld = oldestCreatedAt != null
                    && System.currentTimeMillis() - oldestCreatedAt.getTime() >= BACKLOG_ALERT_AGE_MILLIS;
            if (backlogCount >= BACKLOG_ALERT_COUNT || tooOld) {
                log.warn("ClubPost media review backlog alert: backlogCount={}, oldestCreatedAt={}",
                        backlogCount, oldestCreatedAt);
                alarmBacklog("俱乐部动态图片送审积压",
                        "backlogCount=" + backlogCount + ", oldestCreatedAt=" + oldestCreatedAt);
            }
        } catch (RuntimeException metricsFailure) {
            log.error("ClubPost media review backlog metrics read failed; dispatch continues", metricsFailure);
            alarmBacklog("俱乐部动态图片送审积压指标失败",
                    "积压数量/年龄读取失败，请检查数据库与 mapper；dispatch 仍继续");
        }
    }

    private void retryTransient(ClubPostMediaReviewOutbox task, String errorCode) {
        transactionService.retryTransient(task, errorCode);
        if (task.getAttemptCount() != null && task.getMaxAttempts() != null
                && task.getAttemptCount() >= Math.min(ClubPostMediaReviewOutbox.MAX_ATTEMPTS, task.getMaxAttempts())) {
            alarmSafely("俱乐部动态图片送审持续失败",
                    "taskId=" + task.getId() + ", postId=" + task.getPostId()
                            + ", attempts=" + task.getAttemptCount() + ", code=" + errorCode);
        }
    }

    private void alarmBacklog(String title, String detail) {
        long now = System.currentTimeMillis();
        long next = nextBacklogAlarmAtMillis.get();
        if (now < next || !nextBacklogAlarmAtMillis.compareAndSet(
                next, now + BACKLOG_ALARM_COOLDOWN_MILLIS)) {
            return;
        }
        alarmSafely(title, detail);
    }

    private void alarmSafely(String title, String detail) {
        try {
            alarmService.alarm(title, detail);
        } catch (RuntimeException alarmFailure) {
            log.error("ClubPost media review alarm delivery failed; title={}", title, alarmFailure);
        }
    }

    private boolean validTask(ClubPostMediaReviewOutbox task) {
        return task.getId() != null && task.getId() > 0
                && task.getPostId() != null && task.getPostId() > 0
                && task.getPostVersion() != null && task.getPostVersion() >= 0
                && task.getSubmitterMemberId() != null && task.getSubmitterMemberId() > 0
                && task.getMediaHash() != null && task.getMediaHash().matches("(?i)[0-9a-f]{64}")
                && task.getOwnerToken() != null && task.getOwnerToken().matches("(?i)[0-9a-f]{32}")
                && task.getFenceToken() != null && task.getFenceToken() > 0
                && task.getAttemptCount() != null && task.getAttemptCount() > 0
                && task.getMaxAttempts() != null && task.getMaxAttempts() > 0;
    }

    private String mediaUrl(String expectedHash, String images) {
        Map<String, String> media = ClubPostMediaReviewTransactionService.mediaByHash(images);
        return media.get(expectedHash.toLowerCase());
    }

    private boolean reviewEnabled() {
        try {
            return securityService.isEnabled();
        } catch (RuntimeException configFailure) {
            log.error("ClubPost media review enablement read failed; pending tasks were not claimed",
                    configFailure);
            return false;
        }
    }

    private enum DispatchOutcome {
        SUBMITTED,
        SKIPPED,
        RETRYABLE_FAILURE,
        UNKNOWN
    }
}
