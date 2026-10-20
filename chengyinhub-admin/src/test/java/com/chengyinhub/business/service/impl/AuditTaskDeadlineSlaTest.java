package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.mapper.AuditRecordMapper;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P3-1/P3-2:新建审核单写入 deadline_at(按业务类型/风险定时限),工作台列表走紧急度排队,认领条件更新。
 */
class AuditTaskDeadlineSlaTest {

    private static final long HOUR = 3600_000L;

    private AuditTaskMapper auditTaskMapper;
    private AuditTaskServiceImpl svc;

    @BeforeEach
    void setUp() {
        auditTaskMapper = mock(AuditTaskMapper.class);
        AuditRecordMapper auditRecordMapper = mock(AuditRecordMapper.class);
        svc = new AuditTaskServiceImpl();
        ReflectionTestUtils.setField(svc, "auditTaskMapper", auditTaskMapper);
        ReflectionTestUtils.setField(svc, "auditRecordMapper", auditRecordMapper);
        when(auditTaskMapper.selectAuditTaskList(any())).thenReturn(Collections.emptyList());
        when(auditTaskMapper.insertAuditTask(any())).thenAnswer(inv -> {
            ((AuditTask) inv.getArgument(0)).setId(1L);
            return 1;
        });
        when(auditRecordMapper.insertAuditRecord(any())).thenReturn(1);
    }

    private long inserted() {
        ArgumentCaptor<AuditTask> captor = ArgumentCaptor.forClass(AuditTask.class);
        verify(auditTaskMapper).insertAuditTask(captor.capture());
        AuditTask task = captor.getValue();
        assertNotNull(task.getDeadlineAt(), "新建审核单必须写 deadline_at");
        return (task.getDeadlineAt().getTime() - task.getSubmittedAt().getTime()) / HOUR;
    }

    @Test
    void highRiskSafetyHoldGetsFourHours() {
        svc.submitSafetyHold(AuditTask.BIZ_COMMENT, 1L, 2L, "HIGH", "{}");
        assertEquals(4, inserted());
    }

    @Test
    void highRiskOverridesTypeForActivity() {
        svc.submitSafetyHold(AuditTask.BIZ_ACTIVITY, 1L, 2L, "HIGH", "{}");
        assertEquals(4, inserted());
    }

    @Test
    void communityPostGetsTwentyFourHours() {
        svc.submitSafetyHold(AuditTask.BIZ_COMMUNITY_POST, 1L, 2L, "NORMAL", "{}");
        assertEquals(24, inserted());
    }

    @Test
    void squareReportGetsTwentyFourHours() {
        svc.reportContent(AuditTask.BIZ_SQUARE, 1L, 2L, "spam");
        assertEquals(24, inserted());
    }

    @Test
    void activityGetsFortyEightHours() {
        svc.submitSafetyHold(AuditTask.BIZ_ACTIVITY, 1L, 2L, "NORMAL", "{}");
        assertEquals(48, inserted());
    }

    @Test
    void topicBundleGetsFortyEightHours() {
        svc.submitOrRefreshTopicBundle(1L, 2L, "{\"v\":1}");
        assertEquals(48, inserted());
    }

    @Test
    void commercialSaleReviewGetsFortyEightHoursDespiteHighLabel() {
        svc.submitCommercialReview(AuditTask.BIZ_TOPIC_SALE_REVIEW, 1L, 2L, "{}");
        assertEquals(48, inserted());
    }

    @Test
    void commercialProjectReviewGetsFortyEightHours() {
        svc.submitCommercialReview(AuditTask.BIZ_TOPIC_PROJECT_REVIEW, 1L, 2L, "{}");
        assertEquals(48, inserted());
    }

    @Test
    void otherTypesDefaultToTwentyFourHours() {
        svc.submit(AuditTask.BIZ_IM, 1L, 2L, "auto_gate", "{}");
        assertEquals(24, inserted());
    }

    @Test
    void hoursAreConfigurable() {
        ReflectionTestUtils.setField(svc, "highRiskDeadlineHours", 2);
        svc.submitSafetyHold(AuditTask.BIZ_COMMENT, 1L, 2L, "HIGH", "{}");
        assertEquals(2, inserted());
    }

    @Test
    void workbenchListUsesUrgencyQueue() {
        AuditTask q = new AuditTask();
        svc.selectAuditTaskList(q);
        verify(auditTaskMapper).selectAuditTaskQueue(q);
    }

    @Test
    void claimSucceedsWhenConditionalUpdateHits() {
        when(auditTaskMapper.claimAuditTask(eq(7L), eq(9L), any())).thenReturn(1);
        svc.claim(7L, 9L);
        verify(auditTaskMapper).claimAuditTask(eq(7L), eq(9L), any());
    }

    @Test
    void claimRefusedWhenAlreadyClaimedOrFinished() {
        when(auditTaskMapper.claimAuditTask(anyLong(), anyLong(), any())).thenReturn(0);
        ServiceException ex = assertThrows(ServiceException.class, () -> svc.claim(7L, 9L));
        assertEquals("该任务已被认领或已处理", ex.getMessage());
    }
}
