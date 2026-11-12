package com.chengyinhub.business.service.support.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.domain.AuditRecord;
import com.chengyinhub.business.domain.AuditTask;

class ReviewResultVisibilityAdapterTest {

    @Test
    void submitRejectReviseApproveMakesApprovedResultVisible() {
        List<ReviewResultVisibilityAdapter.ReviewRound> flow =
                ReviewResultVisibilityAdapter.flowSubmitRejectReviseApprove(
                        "rev-submit", 1L, 9L, "封面不合规", "rev-revise", 2L);

        ReviewResultVisibilityAdapter.ReviewRound submit = flow.get(0);
        ReviewResultVisibilityAdapter.ReviewRound reject = flow.get(1);
        ReviewResultVisibilityAdapter.ReviewRound revise = flow.get(2);
        ReviewResultVisibilityAdapter.ReviewRound approve = flow.get(3);

        assertEquals(AuditTask.STATUS_PENDING, submit.taskStatus);
        assertEquals(AuditRecord.ACTION_SUBMIT, submit.recordAction);
        assertFalse(ReviewResultVisibilityAdapter.visibility(submit,
                ReviewResultVisibilityAdapter.Audience.PUBLIC).publicVisible);
        assertTrue(ReviewResultVisibilityAdapter.visibility(submit,
                ReviewResultVisibilityAdapter.Audience.SUBMITTER).submitterVisible);

        assertEquals(AuditTask.STATUS_REJECTED, reject.taskStatus);
        assertTrue(ReviewResultVisibilityAdapter.visibility(reject,
                ReviewResultVisibilityAdapter.Audience.SUBMITTER).reviseAllowed);
        assertFalse(ReviewResultVisibilityAdapter.visibility(reject,
                ReviewResultVisibilityAdapter.Audience.PUBLIC).publicVisible);

        assertEquals("rev-revise", revise.requestId);
        assertEquals(AuditTask.STATUS_PENDING, revise.taskStatus);

        assertEquals(AuditRecord.ACTION_APPROVE, approve.recordAction);
        assertTrue(ReviewResultVisibilityAdapter.visibility(approve,
                ReviewResultVisibilityAdapter.Audience.PUBLIC).approvedResultVisible());
        assertTrue(ReviewResultVisibilityAdapter.visibility(approve,
                ReviewResultVisibilityAdapter.Audience.SUBMITTER).approvedResultVisible());
    }

    @Test
    void currentPublishedProjectionDoesNotTurnRejectedRequestIntoApprovedVisible() {
        ReviewResultVisibilityAdapter.ReviewRound rejected = ReviewResultVisibilityAdapter.rejected(
                ReviewResultVisibilityAdapter.submitted("rev-old", 3L, 9L), "需修订");
        ReviewResultVisibilityAdapter.Visibility publicView = ReviewResultVisibilityAdapter.visibilityForRequest(
                rejected, ReviewResultVisibilityAdapter.Audience.PUBLIC, true);
        assertFalse(publicView.publicVisible);
        assertFalse(publicView.approvedResultVisible());
        assertEquals("REJECTED", publicView.state);
    }

    @Test
    void rejectedRoundCannotBeMarkedApproved() {
        ReviewResultVisibilityAdapter.ReviewRound rejected = ReviewResultVisibilityAdapter.rejected(
                ReviewResultVisibilityAdapter.submitted("rev-old", 3L, 9L), "需修订");
        assertThrows(IllegalStateException.class, () -> ReviewResultVisibilityAdapter.approved(rejected));
    }
}
