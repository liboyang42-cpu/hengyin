package com.chengyinhub.business.service.support.ops;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.chengyinhub.business.domain.AuditRecord;
import com.chengyinhub.business.domain.AuditTask;

/**
 * C11 审核结果可见性：正常提交 → 审核 / 驳回 / 修订 → 批准结果可见。
 *
 * <p>不写审核单。状态枚举复用既有 {@link AuditTask} / {@link AuditRecord}。
 * 当前内容投影已上架，不能把本 request 的驳回改写成批准可见。
 */
public final class ReviewResultVisibilityAdapter {

    public enum Audience {
        PUBLIC, SUBMITTER, SUPPORT
    }

    private ReviewResultVisibilityAdapter() {
    }

    public static Visibility visibility(ReviewRound round, Audience audience) {
        Objects.requireNonNull(round, "round");
        Objects.requireNonNull(audience, "audience");
        Integer status = round.taskStatus;
        if (status == null) {
            return Visibility.hidden("UNKNOWN");
        }
        if (AuditTask.STATUS_APPROVED.equals(status)) {
            return Visibility.visible("APPROVED", true, audience != Audience.PUBLIC);
        }
        if (AuditTask.STATUS_REJECTED.equals(status)) {
            boolean submitterOrSupport = audience != Audience.PUBLIC;
            return new Visibility(submitterOrSupport, false, true, "REJECTED", round.reasonText);
        }
        if (AuditTask.STATUS_PENDING.equals(status) || AuditTask.STATUS_ESCALATED.equals(status)) {
            boolean submitterOrSupport = audience != Audience.PUBLIC;
            return new Visibility(submitterOrSupport, false, false, statusLabel(status), null);
        }
        if (AuditTask.STATUS_CANCELLED.equals(status)) {
            return Visibility.hidden("CANCELLED");
        }
        return Visibility.hidden("UNKNOWN");
    }

    /**
     * 本 request 的可见性只看原审核记录，不看当前内容投影是否已上架。
     */
    public static Visibility visibilityForRequest(ReviewRound round, Audience audience,
                                                 boolean currentContentProjectionPublished) {
        Visibility result = visibility(round, audience);
        if (currentContentProjectionPublished && AuditTask.STATUS_REJECTED.equals(round.taskStatus)) {
            return new Visibility(result.submitterVisible, false, true, "REJECTED", round.reasonText);
        }
        if (currentContentProjectionPublished && !AuditTask.STATUS_APPROVED.equals(round.taskStatus)) {
            return new Visibility(result.submitterVisible, false, result.reviseAllowed, result.state, round.reasonText);
        }
        return result;
    }

    public static ReviewRound submitted(String requestId, Long taskId, Long submitterId) {
        return new ReviewRound(requestId, taskId, submitterId, AuditTask.STATUS_PENDING,
                AuditRecord.ACTION_SUBMIT, null);
    }

    public static ReviewRound rejected(ReviewRound submitted, String reasonText) {
        Objects.requireNonNull(submitted, "submitted");
        return new ReviewRound(submitted.requestId, submitted.taskId, submitted.submitterId,
                AuditTask.STATUS_REJECTED, AuditRecord.ACTION_REJECT, reasonText);
    }

    /** 驳回后修订 = 新提交轮次，旧驳回轮次仍可按原 request 回查。 */
    public static ReviewRound revised(ReviewRound rejected, String newRequestId, Long newTaskId) {
        Objects.requireNonNull(rejected, "rejected");
        if (!AuditTask.STATUS_REJECTED.equals(rejected.taskStatus)) {
            throw new IllegalStateException("只有驳回后才能修订");
        }
        return submitted(newRequestId, newTaskId, rejected.submitterId);
    }

    public static ReviewRound approved(ReviewRound round) {
        Objects.requireNonNull(round, "round");
        if (AuditTask.STATUS_REJECTED.equals(round.taskStatus)
                || AuditTask.STATUS_CANCELLED.equals(round.taskStatus)) {
            throw new IllegalStateException("驳回/撤销轮次不能直接标批准");
        }
        return new ReviewRound(round.requestId, round.taskId, round.submitterId,
                AuditTask.STATUS_APPROVED, AuditRecord.ACTION_APPROVE, null);
    }

    public static List<ReviewRound> flowSubmitRejectReviseApprove(
            String submitRequestId, Long submitTaskId, Long submitterId, String rejectReason,
            String reviseRequestId, Long reviseTaskId) {
        ReviewRound submit = submitted(submitRequestId, submitTaskId, submitterId);
        ReviewRound reject = rejected(submit, rejectReason);
        ReviewRound revise = revised(reject, reviseRequestId, reviseTaskId);
        ReviewRound approve = approved(revise);
        List<ReviewRound> flow = new ArrayList<ReviewRound>();
        flow.add(submit);
        flow.add(reject);
        flow.add(revise);
        flow.add(approve);
        return Collections.unmodifiableList(flow);
    }

    private static String statusLabel(Integer status) {
        if (AuditTask.STATUS_PENDING.equals(status)) {
            return "PENDING";
        }
        if (AuditTask.STATUS_ESCALATED.equals(status)) {
            return "ESCALATED";
        }
        return "UNKNOWN";
    }

    public static final class ReviewRound {
        public final String requestId;
        public final Long taskId;
        public final Long submitterId;
        public final Integer taskStatus;
        public final String recordAction;
        public final String reasonText;

        public ReviewRound(String requestId, Long taskId, Long submitterId, Integer taskStatus,
                           String recordAction, String reasonText) {
            this.requestId = SupportEventLocator.requireRequestId(requestId);
            if (taskId == null || taskId <= 0L) {
                throw new IllegalArgumentException("taskId 不合法");
            }
            this.taskId = taskId;
            this.submitterId = submitterId;
            this.taskStatus = taskStatus;
            this.recordAction = recordAction;
            this.reasonText = reasonText;
        }
    }

    public static final class Visibility {
        public final boolean submitterVisible;
        public final boolean publicVisible;
        public final boolean reviseAllowed;
        public final String state;
        public final String reasonText;

        Visibility(boolean submitterVisible, boolean publicVisible, boolean reviseAllowed,
                   String state, String reasonText) {
            this.submitterVisible = submitterVisible;
            this.publicVisible = publicVisible;
            this.reviseAllowed = reviseAllowed;
            this.state = state;
            this.reasonText = reasonText;
        }

        static Visibility visible(String state, boolean publicVisible, boolean submitterVisible) {
            return new Visibility(submitterVisible, publicVisible, false, state, null);
        }

        static Visibility hidden(String state) {
            return new Visibility(false, false, false, state, null);
        }

        public boolean approvedResultVisible() {
            return publicVisible && "APPROVED".equals(state);
        }
    }
}
