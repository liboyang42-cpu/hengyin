package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.mapper.AuditRecordMapper;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.IOfficialEventService;
import com.chengyinhub.business.service.ISmsCouponService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 审核台 review() 对官方活动(先审后发)与优惠券抽检单(先发后审)的回写与发布者通知。 */
class AuditTaskOfficialEventCouponModerationTest {
    private static final Long PUBLISHER = 101L;

    private AuditTaskMapper taskMapper;
    private IOfficialEventService officialEventService;
    private ISmsCouponService couponService;
    private IImService imService;
    private AuditTaskServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        taskMapper = mock(AuditTaskMapper.class);
        AuditRecordMapper recordMapper = mock(AuditRecordMapper.class);
        BusinessAuditEventMapper auditMapper = mock(BusinessAuditEventMapper.class);
        officialEventService = mock(IOfficialEventService.class);
        couponService = mock(ISmsCouponService.class);
        imService = mock(IImService.class);
        ObjectProvider<IOfficialEventService> officialProvider = mock(ObjectProvider.class);
        when(officialProvider.getObject()).thenReturn(officialEventService);
        ObjectProvider<ISmsCouponService> couponProvider = mock(ObjectProvider.class);
        when(couponProvider.getObject()).thenReturn(couponService);
        ObjectProvider<IImService> imProvider = mock(ObjectProvider.class);
        when(imProvider.getObject()).thenReturn(imService);

        service = new AuditTaskServiceImpl();
        ReflectionTestUtils.setField(service, "auditTaskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "auditRecordMapper", recordMapper);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", auditMapper);
        ReflectionTestUtils.setField(service, "officialEventServiceProvider", officialProvider);
        ReflectionTestUtils.setField(service, "smsCouponServiceProvider", couponProvider);
        ReflectionTestUtils.setField(service, "imServiceProvider", imProvider);
        when(recordMapper.insertAuditRecord(any())).thenReturn(1);
        when(auditMapper.insertBusinessAuditEvent(any())).thenReturn(1);
        when(taskMapper.updateAuditTaskReviewCas(any(), any(), any(), any(), any(), any())).thenReturn(1);
    }

    @Test
    void approvingOfficialEventPublishesItAndNotifiesPublisher() {
        when(taskMapper.selectAuditTaskById(81L)).thenReturn(task(81L, AuditTask.BIZ_OFFICIAL_EVENT, 42L, "user_publish"));

        assertEquals(1, service.review(81L, 900L, decision(AuditTask.STATUS_APPROVED, null)));

        verify(officialEventService).applyAuditDecision(42L, true, null, PUBLISHER);
        ArgumentCaptor<String> card = ArgumentCaptor.forClass(String.class);
        verify(imService).sendSystem(eq(PUBLISHER), any(), eq("审核通过"), card.capture());
        assertTrue(card.getValue().contains("OFFICIAL_EVENT"));
    }

    @Test
    void rejectingOfficialEventKeepsItHiddenAndNotifiesPublisher() {
        when(taskMapper.selectAuditTaskById(82L)).thenReturn(task(82L, AuditTask.BIZ_OFFICIAL_EVENT, 42L, "user_publish"));

        assertEquals(1, service.review(82L, 900L, decision(AuditTask.STATUS_REJECTED, "标题夸大")));

        verify(officialEventService).applyAuditDecision(42L, false, "标题夸大", PUBLISHER);
        verify(imService).sendSystem(eq(PUBLISHER), any(), eq("审核未通过"), anyString());
    }

    @Test
    void officialEventWriteBackFailurePropagatesSoDecisionCannotDiverge() {
        when(taskMapper.selectAuditTaskById(83L)).thenReturn(task(83L, AuditTask.BIZ_OFFICIAL_EVENT, 42L, "user_publish"));
        doThrow(new ServiceException("发布检查未通过：时间已过期"))
                .when(officialEventService).applyAuditDecision(42L, true, null, PUBLISHER);

        ServiceException e = assertThrows(ServiceException.class,
                () -> service.review(83L, 900L, decision(AuditTask.STATUS_APPROVED, null)));
        assertEquals("发布检查未通过：时间已过期", e.getMessage());
    }

    @Test
    void rejectingCouponSpotCheckInvalidatesCouponWhileApproveLeavesIt() {
        when(taskMapper.selectAuditTaskById(91L)).thenReturn(task(91L, AuditTask.BIZ_COUPON, 4567L, "post_review_flag"));
        assertEquals(1, service.review(91L, 900L, decision(AuditTask.STATUS_REJECTED, "虚假宣传")));
        ArgumentCaptor<SmsCoupon> upd = ArgumentCaptor.forClass(SmsCoupon.class);
        verify(couponService).updateSmsCoupon(upd.capture());
        assertEquals(4567L, upd.getValue().getId());
        assertEquals(3, upd.getValue().getStatus());

        couponService = mock(ISmsCouponService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ISmsCouponService> fresh = mock(ObjectProvider.class);
        when(fresh.getObject()).thenReturn(couponService);
        ReflectionTestUtils.setField(service, "smsCouponServiceProvider", fresh);
        when(taskMapper.selectAuditTaskById(92L)).thenReturn(task(92L, AuditTask.BIZ_COUPON, 4568L, "post_review_flag"));
        assertEquals(1, service.review(92L, 900L, decision(AuditTask.STATUS_APPROVED, null)));
        verify(couponService, never()).updateSmsCoupon(any());
    }

    private AuditTask task(Long id, String bizType, Long bizId, String source) {
        AuditTask task = new AuditTask();
        task.setId(id);
        task.setBizType(bizType);
        task.setBizId(bizId);
        task.setSubmitterId(PUBLISHER);
        task.setSourceType(source);
        task.setStatus(AuditTask.STATUS_PENDING);
        task.setVersion(0);
        task.setSnapshotJson("{\"bizType\":\"" + bizType + "\",\"bizId\":" + bizId + "}");
        return task;
    }

    private AuditReviewDTO decision(Integer status, String reason) {
        AuditReviewDTO request = new AuditReviewDTO();
        request.setStatus(status);
        request.setReasonText(reason);
        return request;
    }
}
