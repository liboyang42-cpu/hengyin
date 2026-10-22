package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.mapper.AuditRecordMapper;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.IOfficialEventService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #334 F10:非版本化审核类型(官方活动/优惠券/俱乐部帖等)升级(ESCALATED)后,
 * 升级前那一级迟到的决定(带着升级前的 version,或不带 version)不能裁决该单;带升级后 version 的照常裁决。
 */
class AuditEscalationLevelTest {
    private AuditTaskMapper taskMapper;
    private IOfficialEventService officialEventService;
    private AuditTaskServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        taskMapper = mock(AuditTaskMapper.class);
        AuditRecordMapper recordMapper = mock(AuditRecordMapper.class);
        BusinessAuditEventMapper auditMapper = mock(BusinessAuditEventMapper.class);
        officialEventService = mock(IOfficialEventService.class);
        ObjectProvider<IOfficialEventService> officialProvider = mock(ObjectProvider.class);
        when(officialProvider.getObject()).thenReturn(officialEventService);
        ObjectProvider<IImService> imProvider = mock(ObjectProvider.class);
        when(imProvider.getObject()).thenReturn(mock(IImService.class));
        service = new AuditTaskServiceImpl();
        ReflectionTestUtils.setField(service, "auditTaskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "auditRecordMapper", recordMapper);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", auditMapper);
        ReflectionTestUtils.setField(service, "officialEventServiceProvider", officialProvider);
        ReflectionTestUtils.setField(service, "imServiceProvider", imProvider);
        when(recordMapper.insertAuditRecord(any())).thenReturn(1);
        when(auditMapper.insertBusinessAuditEvent(any())).thenReturn(1);
        when(taskMapper.updateAuditTaskReviewCas(any(), any(), any(), any(), any(), any())).thenReturn(1);
        // 升级 CAS 把 version 0 → 1:当前是二级处理中的升级单
        when(taskMapper.selectAuditTaskById(81L)).thenReturn(escalatedTask());
    }

    @Test
    void lateDecisionFromPreEscalationLevel_isRejectedAsStaleLevel() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.review(81L, 900L, decision(AuditTask.STATUS_APPROVED, 0)));

        assertEquals(HttpStatus.CONFLICT, e.getCode());
        assertEquals(AuditTaskServiceImpl.ERROR_REVIEW_STALE_LEVEL, e.getDetailMessage());
        verify(taskMapper, never()).updateAuditTaskReviewCas(any(), any(), any(), any(), any(), any());
        verify(officialEventService, never()).applyAuditDecision(anyLong(), anyBoolean(), any(), any());
    }

    @Test
    void decisionWithoutVersion_cannotDecideEscalatedTask() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.review(81L, 900L, decision(AuditTask.STATUS_REJECTED, null)));

        assertEquals(AuditTaskServiceImpl.ERROR_REVIEW_STALE_LEVEL, e.getDetailMessage());
        verify(taskMapper, never()).updateAuditTaskReviewCas(any(), any(), any(), any(), any(), any());
    }

    @Test
    void decisionOnEscalatedVersion_isApplied() {
        assertEquals(1, service.review(81L, 900L, decision(AuditTask.STATUS_APPROVED, 1)));

        verify(taskMapper).updateAuditTaskReviewCas(any(), any(), any(), any(), any(), any());
        verify(officialEventService).applyAuditDecision(42L, true, null, 101L);
    }

    private AuditTask escalatedTask() {
        AuditTask task = new AuditTask();
        task.setId(81L);
        task.setBizType(AuditTask.BIZ_OFFICIAL_EVENT);
        task.setBizId(42L);
        task.setSubmitterId(101L);
        task.setSourceType("user_publish");
        task.setStatus(AuditTask.STATUS_ESCALATED);
        task.setVersion(1);
        task.setSnapshotJson("{\"bizType\":\"OFFICIAL_EVENT\",\"bizId\":42}");
        return task;
    }

    private AuditReviewDTO decision(Integer status, Integer expectedVersion) {
        AuditReviewDTO request = new AuditReviewDTO();
        request.setStatus(status);
        request.setExpectedVersion(expectedVersion);
        if (AuditTask.STATUS_REJECTED.equals(status)) request.setReasonText("标题夸大");
        return request;
    }
}
