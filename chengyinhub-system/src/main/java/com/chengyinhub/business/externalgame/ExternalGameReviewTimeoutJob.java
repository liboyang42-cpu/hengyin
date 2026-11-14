package com.chengyinhub.business.externalgame;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Nothing stays PENDING forever: every sweep moves submissions older than
 * {@code chengyin.external-game.review-timeout-days} (default 3) to ESCALATED (actor SYSTEM, reason REVIEW_TIMEOUT).
 * Uses the existing chengyin.jobs.enabled scheduling switch; the admin queue also sweeps before it reads, so the
 * queue is correct even when background jobs are off. Guarded UPDATEs make concurrent sweeps on several nodes safe.
 */
@Component
public class ExternalGameReviewTimeoutJob {
    private static final Logger log = LoggerFactory.getLogger(ExternalGameReviewTimeoutJob.class);
    private final ExternalGameReviewService reviews;
    private final AtomicBoolean running = new AtomicBoolean();

    public ExternalGameReviewTimeoutJob(ExternalGameReviewService reviews) { this.reviews = reviews; }

    @Scheduled(fixedDelayString = "${chengyin.external-game.timeout-sweep-ms:600000}", initialDelayString = "${chengyin.external-game.timeout-sweep-ms:600000}")
    public void sweep() {
        if (!running.compareAndSet(false, true)) return;
        try {
            int moved = reviews.sweepTimeouts();
            if (moved > 0) log.info("external game review timeout: {} submission(s) PENDING -> ESCALATED", moved);
        } catch (RuntimeException e) {
            log.warn("external game review timeout sweep failed: {}", e.getClass().getSimpleName());
        } finally {
            running.set(false);
        }
    }
}
