package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AuditRecord;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.MerchantReview;
import com.chengyinhub.business.domain.dto.AuditRevertDTO;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.mapper.AuditRecordMapper;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.MerchantReviewMapper;
import com.chengyinhub.business.service.IMerchantReviewService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuditTaskMerchantReviewReversalLifecycleTest {

    @Test
    void originalSubmissionRejectRevertThenApproveStaysPrivateUntilSecondDecision() {
        Scenario scenario = new Scenario("auto_gate", MerchantReview.STATUS_PENDING_REVIEW);

        scenario.review(AuditTask.STATUS_REJECTED, "首轮拒绝");
        assertEquals(MerchantReview.STATUS_HIDDEN, scenario.review.getStatus());

        AuditRecord rejected = scenario.timeline.get(0);
        scenario.revert("revert-submission");
        assertEquals(AuditTask.STATUS_PENDING, scenario.taskStatus);
        assertEquals(MerchantReview.STATUS_PENDING_REVIEW, scenario.review.getStatus());
        assertEquals(rejected.getId(), scenario.timeline.get(0).getRevertedRecordId());

        scenario.review(AuditTask.STATUS_APPROVED, "重新审核通过");
        assertEquals(AuditTask.STATUS_APPROVED, scenario.taskStatus);
        assertEquals(MerchantReview.STATUS_VISIBLE, scenario.review.getStatus());
    }

    @Test
    void reportedReviewRejectRevertThenRejectAgainRestoresPublicStateAndLinksLatestRound() {
        Scenario scenario = new Scenario("report", MerchantReview.STATUS_VISIBLE);

        scenario.review(AuditTask.STATUS_REJECTED, "首轮举报成立");
        AuditRecord firstReject = scenario.timeline.get(0);
        scenario.revert("revert-report-1");
        assertEquals(MerchantReview.STATUS_VISIBLE, scenario.review.getStatus());
        assertEquals(firstReject.getId(), scenario.timeline.get(0).getRevertedRecordId());

        scenario.review(AuditTask.STATUS_REJECTED, "重新裁决举报成立");
        AuditRecord secondReject = scenario.timeline.get(0);
        assertNotNull(secondReject.getId());
        scenario.revert("revert-report-2");

        assertEquals(MerchantReview.STATUS_VISIBLE, scenario.review.getStatus());
        assertEquals(secondReject.getId(), scenario.timeline.get(0).getRevertedRecordId());
    }

    private static final class Scenario {
        private static final Long TASK_ID = 71L;
        private static final Long REVIEW_ID = 501L;
        private static final Long OPERATOR_ID = 900L;

        private final MerchantReview review = new MerchantReview();
        private final List<AuditRecord> timeline = new ArrayList<>();
        private final AuditTaskServiceImpl service = new AuditTaskServiceImpl();
        private final AtomicLong recordIds = new AtomicLong(800L);
        private final String sourceType;
        private Integer taskStatus = AuditTask.STATUS_PENDING;
        private Integer taskVersion = 0;

        private Scenario(String sourceType, String reviewStatus) {
            this.sourceType = sourceType;
            AuditTaskMapper tasks = mock(AuditTaskMapper.class);
            AuditRecordMapper records = mock(AuditRecordMapper.class);
            BusinessAuditEventMapper audits = mock(BusinessAuditEventMapper.class);
            MerchantReviewMapper reviews = mock(MerchantReviewMapper.class);

            MerchantReviewServiceImpl reviewService = new MerchantReviewServiceImpl();
            ReflectionTestUtils.setField(reviewService, "merchantReviewMapper", reviews);
            @SuppressWarnings("unchecked")
            ObjectProvider<IMerchantReviewService> provider = mock(ObjectProvider.class);
            when(provider.getObject()).thenReturn(reviewService);

            review.setId(REVIEW_ID);
            review.setStatus(reviewStatus);
            review.setVersion(0);

            when(tasks.selectAuditTaskById(TASK_ID)).thenAnswer(call -> taskSnapshot());
            when(tasks.updateAuditTaskReviewCas(any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
                Integer expectedStatus = call.getArgument(1);
                Integer targetStatus = call.getArgument(2);
                Integer expectedVersion = call.getArgument(4);
                if (!expectedStatus.equals(taskStatus) || !expectedVersion.equals(taskVersion)) return 0;
                taskStatus = targetStatus;
                taskVersion += 1;
                return 1;
            });
            when(tasks.revertAuditTaskDecisionCas(any(), any(), any(), any(), any())).thenAnswer(call -> {
                Integer expectedStatus = call.getArgument(1);
                Integer expectedVersion = call.getArgument(2);
                if (!expectedStatus.equals(taskStatus) || !expectedVersion.equals(taskVersion)) return 0;
                taskStatus = AuditTask.STATUS_PENDING;
                taskVersion += 1;
                return 1;
            });
            when(records.selectAuditRecordList(any(AuditRecord.class))).thenAnswer(call -> new ArrayList<>(timeline));
            when(records.insertAuditRecord(any(AuditRecord.class))).thenAnswer(call -> {
                AuditRecord record = call.getArgument(0);
                if (record.getId() == null) record.setId(recordIds.incrementAndGet());
                timeline.add(0, record);
                return 1;
            });
            when(audits.insertBusinessAuditEvent(any(BusinessAuditEvent.class))).thenReturn(1);
            when(reviews.selectReviewByIdForUpdate(REVIEW_ID)).thenReturn(review);
            when(reviews.moderateCas(any(), any(), any(), any(), any())).thenAnswer(call -> {
                String expectedStatus = call.getArgument(1);
                String targetStatus = call.getArgument(2);
                Integer expectedVersion = call.getArgument(4);
                if (!expectedStatus.equals(review.getStatus()) || !expectedVersion.equals(review.getVersion())) return 0;
                review.setStatus(targetStatus);
                review.setVersion(review.getVersion() + 1);
                return 1;
            });

            ReflectionTestUtils.setField(service, "auditTaskMapper", tasks);
            ReflectionTestUtils.setField(service, "auditRecordMapper", records);
            ReflectionTestUtils.setField(service, "businessAuditEventMapper", audits);
            ReflectionTestUtils.setField(service, "merchantReviewServiceProvider", provider);
            ReflectionTestUtils.setField(service, "clock", Clock.systemUTC());
        }

        private void review(Integer status, String reason) {
            AuditReviewDTO request = new AuditReviewDTO();
            request.setStatus(status);
            request.setReasonText(reason);
            request.setRequestId("review-" + taskVersion + "-" + status);
            assertEquals(1, service.review(TASK_ID, OPERATOR_ID, request));
        }

        private void revert(String key) {
            AuditRevertDTO request = new AuditRevertDTO();
            request.setExpectedVersion(taskVersion);
            request.setIdempotencyKey(key);
            request.setReason("撤销误判");
            service.revert(TASK_ID, OPERATOR_ID, true, request);
        }

        private AuditTask taskSnapshot() {
            AuditTask task = new AuditTask();
            task.setId(TASK_ID);
            task.setBizType(AuditTask.BIZ_MERCHANT_REVIEW);
            task.setBizId(REVIEW_ID);
            task.setSourceType(sourceType);
            task.setStatus(taskStatus);
            task.setVersion(taskVersion);
            task.setUpdateTime(new Date());
            return task;
        }
    }
}
