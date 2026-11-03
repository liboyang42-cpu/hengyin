package com.chengyinhub.business.service.notify;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * C09 独占：16 类消息附件审核 → 原回执。不改公共 IM send 协议，不把可见投影当审核通过。
 */
public final class AttachmentReviewProducer {

    public static final String STATUS_PENDING = "PENDING_REVIEW";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_UNKNOWN = "UNKNOWN";

    /**
     * 16 类附件。这是独占审核生产者的类型表，不是对公共 {@code ImMessage.msgType} 的改写。
     */
    public enum AttachmentType {
        IMAGE,
        VIDEO,
        AUDIO,
        FILE,
        LOCATION,
        STICKER,
        LINK,
        CARD,
        POLL,
        CONTACT,
        VOICE_NOTE,
        LIVE,
        MUSIC,
        GIF,
        ALBUM,
        MINI_PROGRAM
    }

    private final ReviewReceiptStore store;

    public AttachmentReviewProducer(ReviewReceiptStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public static int typeCount() {
        return AttachmentType.values().length;
    }

    public ReviewReceipt submit(String requestId, AttachmentType type, String originalDigest) {
        String id = ImmutableReceiptProducer.requireRequestId(requestId);
        if (type == null) {
            throw new IllegalArgumentException("附件类型不能为空");
        }
        if (isBlank(originalDigest)) {
            throw new IllegalArgumentException("原附件摘要不能为空");
        }
        ReviewReceipt existing = store.find(id);
        if (existing != null) {
            if (existing.type != type || !originalDigest.equals(existing.originalDigest)) {
                throw new IllegalStateException("原附件回执冲突 requestId=" + id);
            }
            return existing;
        }
        ReviewReceipt receipt = new ReviewReceipt(id, type, originalDigest, STATUS_PENDING);
        store.insert(receipt);
        return receipt;
    }

    public ReviewReceipt decide(String requestId, String decision) {
        ReviewReceipt original = require(requestId);
        if (!STATUS_PENDING.equals(original.status) && !STATUS_UNKNOWN.equals(original.status)) {
            return original;
        }
        if (!STATUS_APPROVED.equals(decision) && !STATUS_REJECTED.equals(decision)
                && !STATUS_UNKNOWN.equals(decision)) {
            throw new IllegalArgumentException("非法审核结论");
        }
        ReviewReceipt next = original.withStatus(decision);
        store.replaceSameIdentity(next);
        return next;
    }

    /** 可见/已应用投影不是原审核通过证明。 */
    public ReviewReceipt recover(String requestId, String appliedProjectionStatus) {
        ReviewReceipt original = require(requestId);
        return original.withProjectionClaim(projectionClaimsVisible(appliedProjectionStatus));
    }

    public ReviewReceipt recoverOriginal(String requestId, String appliedProjectionStatus) {
        return recoverUnknown(requestId, appliedProjectionStatus);
    }

    /**
     * Missing requestId stays Java {@code null} / UNKNOWN and does not occupy the ledger.
     */
    public ReviewReceipt recoverUnknown(String requestId, String appliedProjectionStatus) {
        String id = ImmutableReceiptProducer.parseRequestId(requestId);
        if (id == null) {
            return unknownReceipt(null);
        }
        ReviewReceipt original = store.find(id);
        if (original != null) {
            return original.withProjectionClaim(projectionClaimsVisible(appliedProjectionStatus));
        }
        return unknownReceipt(id);
    }

    public boolean treatsVisibleProjectionAsApproval(String appliedProjectionStatus) {
        return false;
    }

    private static boolean projectionClaimsVisible(String appliedProjectionStatus) {
        return ImmutableReceiptProducer.PROJECTION_APPLIED.equals(appliedProjectionStatus)
                || "VISIBLE".equals(appliedProjectionStatus)
                || "APPLIED".equals(appliedProjectionStatus);
    }

    static ReviewReceipt unknownReceipt(String requestId) {
        return new ReviewReceipt(requestId, null, null, STATUS_UNKNOWN, false);
    }

    private ReviewReceipt require(String requestId) {
        String id = ImmutableReceiptProducer.requireRequestId(requestId);
        ReviewReceipt original = store.find(id);
        if (original == null) {
            throw new IllegalStateException("原附件回执不存在 requestId=" + id);
        }
        return original;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static final class ReviewReceipt {
        public final String requestId;
        public final AttachmentType type;
        public final String originalDigest;
        public final String status;
        public final boolean currentProjectionClaimsVisible;

        ReviewReceipt(String requestId, AttachmentType type, String originalDigest, String status) {
            this(requestId, type, originalDigest, status, false);
        }

        private ReviewReceipt(String requestId, AttachmentType type, String originalDigest, String status,
                              boolean currentProjectionClaimsVisible) {
            this.requestId = requestId;
            this.type = type;
            this.originalDigest = originalDigest;
            this.status = status;
            this.currentProjectionClaimsVisible = currentProjectionClaimsVisible;
        }

        ReviewReceipt withStatus(String next) {
            return new ReviewReceipt(requestId, type, originalDigest, next, currentProjectionClaimsVisible);
        }

        ReviewReceipt withProjectionClaim(boolean claimed) {
            return new ReviewReceipt(requestId, type, originalDigest, status, claimed);
        }

        public boolean originalIsNotVisibleProjection() {
            return !"VISIBLE".equals(status)
                    && !ImmutableReceiptProducer.PROJECTION_APPLIED.equals(status);
        }

        public Map<String, Object> asRequeryableView() {
            Map<String, Object> view = new LinkedHashMap<String, Object>();
            view.put("requestId", requestId);
            view.put("type", type == null ? null : type.name());
            view.put("originalDigest", originalDigest);
            view.put("status", status);
            view.put("usedVisibleProjectionAsApproval", Boolean.FALSE);
            return Collections.unmodifiableMap(view);
        }
    }

    public interface ReviewReceiptStore {
        ReviewReceipt find(String requestId);

        void insert(ReviewReceipt receipt);

        void replaceSameIdentity(ReviewReceipt receipt);
    }
}
