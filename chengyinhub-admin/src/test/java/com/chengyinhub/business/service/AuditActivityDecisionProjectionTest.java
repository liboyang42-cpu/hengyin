package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.mapper.AuditRecordMapper;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.service.impl.AuditTaskServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 统一审核结论必须同步回活动列表读取的 check_status/reason 投影。 */
class AuditActivityDecisionProjectionTest {

    private AuditTaskMapper taskMapper;
    private CmsActivityMapper activityMapper;
    private AuditTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        taskMapper = mock(AuditTaskMapper.class);
        AuditRecordMapper recordMapper = mock(AuditRecordMapper.class);
        activityMapper = mock(CmsActivityMapper.class);
        service = new AuditTaskServiceImpl();
        ReflectionTestUtils.setField(service, "auditTaskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "auditRecordMapper", recordMapper);
        com.chengyinhub.business.mapper.BusinessAuditEventMapper auditMapper =
                mock(com.chengyinhub.business.mapper.BusinessAuditEventMapper.class);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", auditMapper);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        when(recordMapper.insertAuditRecord(any())).thenReturn(1);
        when(auditMapper.insertBusinessAuditEvent(any())).thenReturn(1);

        AuditTask task = new AuditTask();
        task.setId(8L);
        task.setBizType(AuditTask.BIZ_ACTIVITY);
        task.setBizId(18L);
        task.setStatus(AuditTask.STATUS_PENDING);
        task.setVersion(0);
        when(taskMapper.selectAuditTaskById(8L)).thenReturn(task);
        when(taskMapper.updateAuditTaskReviewCas(anyLong(), anyInt(), anyInt(), anyLong(), anyInt(), any())).thenReturn(1);
    }

    @Test
    void rejectionProjectsStatusAndReasonToActivity() {
        AuditReviewDTO decision = new AuditReviewDTO();
        decision.setStatus(AuditTask.STATUS_REJECTED);
        decision.setReasonText("证据不足");

        service.review(8L, 1L, decision);

        CmsActivity saved = capturedActivity();
        assertEquals(2L, saved.getStatus());
        assertEquals(2, saved.getCheckStatus());
        assertEquals(0L, saved.getPublishStatus());
        assertEquals("证据不足", saved.getReason());
    }

    @Test
    void approvalClearsOldReasonAndProjectsApprovedState() {
        AuditReviewDTO decision = new AuditReviewDTO();
        decision.setStatus(AuditTask.STATUS_APPROVED);

        service.review(8L, 1L, decision);

        CmsActivity saved = capturedActivity();
        assertEquals(1L, saved.getStatus());
        assertEquals(1, saved.getCheckStatus());
        assertEquals(1L, saved.getPublishStatus());
        assertEquals("", saved.getReason());
    }

    private CmsActivity capturedActivity() {
        ArgumentCaptor<CmsActivity> captor = ArgumentCaptor.forClass(CmsActivity.class);
        verify(activityMapper).updateCmsActivity(captor.capture());
        return captor.getValue();
    }
}
