package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsRegistrationScan;
import com.chengyinhub.business.domain.Redemption;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsRegistrationScanMapper;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import com.chengyinhub.common.constant.PurchaseKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** F-L3-06:活动票整单核销的后台撤销 —— 锁序、守卫、留痕,以及撤销后可以重新核销。 */
class WholeOrderVerificationReverseTest {
    private static final Long REG = 6001L;
    private static final Long ACTIVITY = 700L;
    private static final Long ORGANIZER = 31L;
    private static final Long OPERATOR = 1L;

    private CmsRegistrationMapper regMapper;
    private CmsActivityMapper activityMapper;
    private BusinessAuditEventMapper auditMapper;
    private IPerkLedgerService perkLedger;
    private CmsRegistrationServiceImpl service;
    private CmsActivity activity;
    private CmsRegistrationScanMapper scanMapper;
    private com.chengyinhub.business.mapper.RegistrationSeatMapper seatMapper;
    private com.chengyinhub.business.mapper.StoreVerifyGrowthDeliveryMapper deliveryMapper;
    private com.chengyinhub.business.service.impl.RedemptionRewardClawbackService clawback;

    @BeforeEach
    void setUp() {
        regMapper = mock(CmsRegistrationMapper.class);
        activityMapper = mock(CmsActivityMapper.class);
        auditMapper = mock(BusinessAuditEventMapper.class);
        perkLedger = mock(IPerkLedgerService.class);
        service = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", regMapper);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", auditMapper);
        ReflectionTestUtils.setField(service, "perkLedgerService", perkLedger);
        seatMapper = mock(com.chengyinhub.business.mapper.RegistrationSeatMapper.class);
        when(seatMapper.selectByRegistrationForUpdate(REG)).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(service, "registrationSeatMapper", seatMapper);
        scanMapper = mock(CmsRegistrationScanMapper.class);
        deliveryMapper = mock(com.chengyinhub.business.mapper.StoreVerifyGrowthDeliveryMapper.class);
        clawback = mock(com.chengyinhub.business.service.impl.RedemptionRewardClawbackService.class);
        ReflectionTestUtils.setField(service, "cmsRegistrationScanMapper", scanMapper);
        ReflectionTestUtils.setField(service, "storeVerifyGrowthDeliveryMapper", deliveryMapper);
        ReflectionTestUtils.setField(service, "redemptionRewardClawbackService", clawback);
        activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setMemberId(ORGANIZER);
        activity.setIsSettlement(0);
        when(activityMapper.selectCmsActivityByIdForUpdate(ACTIVITY)).thenReturn(activity);
        when(regMapper.selectCmsRegistrationById(REG)).thenReturn(verified());
        when(regMapper.selectCmsRegistrationByIdForUpdate(REG)).thenReturn(verified());
        when(perkLedger.listRedemptions(REG)).thenReturn(Collections.<Redemption>emptyList());
        when(regMapper.clearVerification(REG)).thenReturn(1);
        when(auditMapper.insertBusinessAuditEvent(any())).thenReturn(1);
    }

    private CmsRegistration verified() {
        CmsRegistration r = new CmsRegistration();
        r.setId(REG);
        r.setOwnerType(2L);
        r.setOwnerId(ACTIVITY);
        r.setPurchaseKind(PurchaseKind.GUIDED_TICKET);
        r.setPaymentStatus(2);
        r.setRegistrationStatus(2);
        r.setVerificationStatus(1);
        r.setVerifierId(ORGANIZER);
        return r;
    }

    @Test
    void verifiedSeatFactsCannotBeClearedByWholeOrderReversalEvenWithRewardClawback() {
        CmsRegistration current = verified(); current.setOrderNum(2L);
        when(regMapper.selectCmsRegistrationByIdForUpdate(REG)).thenReturn(current);
        var seat = new com.chengyinhub.business.domain.RegistrationSeat();
        seat.setId(81L); seat.setRegistrationId(REG); seat.setSeatNo(1); seat.setStatus(1);
        when(seatMapper.selectByRegistrationForUpdate(REG)).thenReturn(Collections.singletonList(seat));

        assertEquals("该订单有逐席位核销事实，请走席位售后核验，不能整单清除核销状态",
                message(() -> service.reverseWholeOrderVerification(REG, "核错席位", OPERATOR, true)));

        InOrder locks = inOrder(activityMapper, regMapper, seatMapper);
        locks.verify(activityMapper).selectCmsActivityByIdForUpdate(ACTIVITY);
        locks.verify(regMapper).selectCmsRegistrationByIdForUpdate(REG);
        locks.verify(seatMapper).selectByRegistrationForUpdate(REG);
        assertEquals(1, seat.getStatus()); assertEquals(1, current.getVerificationStatus());
        verify(regMapper, never()).clearVerification(anyLong());
        org.mockito.Mockito.verifyNoInteractions(auditMapper, scanMapper, deliveryMapper, clawback, perkLedger);
    }

    @Test
    void currentSeatFactWinsOverAnUnverifiedAggregateSnapshot() {
        CmsRegistration current = verified(); current.setOrderNum(2L); current.setVerificationStatus(0);
        when(regMapper.selectCmsRegistrationByIdForUpdate(REG)).thenReturn(current);
        var seat = new com.chengyinhub.business.domain.RegistrationSeat();
        seat.setId(81L); seat.setRegistrationId(REG); seat.setSeatNo(1); seat.setStatus(1);
        when(seatMapper.selectByRegistrationForUpdate(REG)).thenReturn(Collections.singletonList(seat));

        assertEquals("该订单有逐席位核销事实，请走席位售后核验，不能整单清除核销状态",
                message(() -> service.reverseWholeOrderVerification(REG, "核错席位", OPERATOR)));
        verify(regMapper, never()).clearVerification(anyLong());
        org.mockito.Mockito.verifyNoInteractions(auditMapper, scanMapper, deliveryMapper, clawback, perkLedger);
    }

    @Test
    void preparedUnverifiedSeatsPreserveTheOriginalWholeOrderReversal() {
        CmsRegistration current = verified(); current.setOrderNum(2L);
        when(regMapper.selectCmsRegistrationByIdForUpdate(REG)).thenReturn(current);
        var seat = new com.chengyinhub.business.domain.RegistrationSeat();
        seat.setId(81L); seat.setRegistrationId(REG); seat.setSeatNo(1); seat.setStatus(0);
        when(seatMapper.selectByRegistrationForUpdate(REG)).thenReturn(Collections.singletonList(seat));

        assertTrue(service.reverseWholeOrderVerification(REG, "原整单核错", OPERATOR));
        InOrder order = inOrder(regMapper, seatMapper, auditMapper);
        order.verify(regMapper).selectCmsRegistrationByIdForUpdate(REG);
        order.verify(seatMapper).selectByRegistrationForUpdate(REG);
        order.verify(regMapper).clearVerification(REG);
        order.verify(auditMapper).insertBusinessAuditEvent(any());
        assertEquals(0, seat.getStatus());
        verify(scanMapper).markWholeOrderScansReversed(REG);
        verify(deliveryMapper).deletePendingByRegistration(REG);
        verify(clawback, never()).clawbackWholeOrderVerification(any(), any(), any(), any(), any());
    }

    @Test
    void reversesLockedActivityTicketAndWritesOperatorAudit() throws Exception {
        assertTrue(service.reverseWholeOrderVerification(REG, "  店员扫错票  ", OPERATOR));

        InOrder order = inOrder(activityMapper, regMapper, auditMapper);
        order.verify(activityMapper).selectCmsActivityByIdForUpdate(ACTIVITY);
        order.verify(regMapper).selectCmsRegistrationByIdForUpdate(REG);
        order.verify(regMapper).clearVerification(REG);
        order.verify(auditMapper).insertBusinessAuditEvent(any());
        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(auditMapper).insertBusinessAuditEvent(audit.capture());
        assertEquals("REGISTRATION_VERIFY_REVERSE", audit.getValue().getAction());
        assertEquals(BusinessAuditEvent.ACTOR_TYPE_SYS_USER, audit.getValue().getActorType());
        assertEquals(OPERATOR, audit.getValue().getActorMemberId());
        assertEquals(ORGANIZER, audit.getValue().getOwnerMemberId());
        assertEquals("店员扫错票", audit.getValue().getReason());
        assertEquals("registration-verify-reverse-" + REG + "-1", audit.getValue().getRequestId());
        assertNotNull(CmsRegistrationServiceImpl.class.getMethod("reverseWholeOrderVerification",
                Long.class, String.class, Long.class).getAnnotation(Transactional.class));
    }

    @Test
    void refusesExploreTopicSettledUnverifiedAndChapterRedeemedTicketsWithoutWrites() {
        CmsRegistration explore = verified();
        explore.setPurchaseKind(PurchaseKind.EXPLORE_PASS);
        when(regMapper.selectCmsRegistrationById(REG)).thenReturn(explore);
        assertEquals("探索票请在核销记录中按章撤销", message(() -> service.reverseWholeOrderVerification(REG, "x", OPERATOR)));

        CmsRegistration topic = verified();
        topic.setOwnerType(1L);
        when(regMapper.selectCmsRegistrationById(REG)).thenReturn(topic);
        assertEquals("主题整单核销已计入执行商家结算，请走人工售后处理",
                message(() -> service.reverseWholeOrderVerification(REG, "x", OPERATOR)));

        when(regMapper.selectCmsRegistrationById(REG)).thenReturn(verified());
        activity.setIsSettlement(1);
        assertEquals("活动已结算，不能撤销核销，请走人工售后处理",
                message(() -> service.reverseWholeOrderVerification(REG, "x", OPERATOR)));
        activity.setIsSettlement(0);

        CmsRegistration unverified = verified();
        unverified.setVerificationStatus(0);
        when(regMapper.selectCmsRegistrationByIdForUpdate(REG)).thenReturn(unverified);
        assertEquals("该订单未核销或已撤销，无需重复撤销",
                message(() -> service.reverseWholeOrderVerification(REG, "x", OPERATOR)));

        when(regMapper.selectCmsRegistrationByIdForUpdate(REG)).thenReturn(verified());
        Redemption active = new Redemption();
        active.setStatus("ACTIVE");
        when(perkLedger.listRedemptions(REG)).thenReturn(Collections.singletonList(active));
        assertEquals("该票有按章核销流水，请在核销记录中逐条撤销",
                message(() -> service.reverseWholeOrderVerification(REG, "x", OPERATOR)));

        assertThrows(IllegalArgumentException.class, () -> service.reverseWholeOrderVerification(REG, "  ", OPERATOR));
        verify(regMapper, never()).clearVerification(anyLong());
        verify(auditMapper, never()).insertBusinessAuditEvent(any());
        verify(scanMapper, never()).markWholeOrderScansReversed(anyLong());
        verify(deliveryMapper, never()).deletePendingByRegistration(anyLong());
    }

    /** PR10 复审:不冲回时也要把核销台账标为已撤销,并作废尚未发放的到店成长待办;已发奖励不动。 */
    @Test
    void reversalMarksScanLedgerAndDropsPendingGrowthWithoutClawback() {
        assertTrue(service.reverseWholeOrderVerification(REG, "店员扫错票", OPERATOR));

        InOrder order = inOrder(regMapper, auditMapper, scanMapper, deliveryMapper);
        order.verify(regMapper).clearVerification(REG);
        order.verify(auditMapper).insertBusinessAuditEvent(any());
        order.verify(scanMapper).markWholeOrderScansReversed(REG);
        order.verify(deliveryMapper).deletePendingByRegistration(REG);
        verify(clawback, never()).clawbackWholeOrderVerification(any(), any(), any(), any(), any());
    }

    /** 勾选冲回:与按章撤销同一套到店积分/经验冲回机制,按撤销代数给审计幂等键。 */
    @Test
    void reversalWithClawbackReusesStoreVerifyClawback() {
        CmsRegistration owner = verified();
        owner.setMemberId(9009L);
        when(regMapper.selectCmsRegistrationById(REG)).thenReturn(owner);

        assertTrue(service.reverseWholeOrderVerification(REG, "核错项目", OPERATOR, true));

        verify(scanMapper).markWholeOrderScansReversed(REG);
        ArgumentCaptor<CmsRegistration> reg = ArgumentCaptor.forClass(CmsRegistration.class);
        verify(clawback).clawbackWholeOrderVerification(reg.capture(), org.mockito.ArgumentMatchers.eq(ORGANIZER),
                org.mockito.ArgumentMatchers.eq("核错项目"), org.mockito.ArgumentMatchers.eq(OPERATOR),
                org.mockito.ArgumentMatchers.eq("registration-verify-clawback-" + REG + "-1"));
        assertEquals(Long.valueOf(9009L), reg.getValue().getMemberId());
        verify(deliveryMapper, never()).deletePendingByRegistration(anyLong());
    }

    @Test
    void lostConditionalClearRollsBackWithoutAudit() {
        when(regMapper.clearVerification(REG)).thenReturn(0);
        assertEquals("核销状态刚发生变化，请刷新后重试",
                message(() -> service.reverseWholeOrderVerification(REG, "x", OPERATOR)));
        verify(auditMapper, never()).insertBusinessAuditEvent(any());
    }

    /** 撤销后重新核销:核销审计换一代幂等键,不能撞 uk_business_audit_request 导致整单回滚。 */
    @Test
    void verificationAfterReversalUsesNextAuditGeneration() {
        ReflectionTestUtils.setField(service, "growthService", mock(IGrowthService.class));
        ReflectionTestUtils.setField(service, "settlementEntryService", mock(ISettlementEntryService.class));
        CmsRegistrationScanMapper scanMapper = mock(CmsRegistrationScanMapper.class);
        when(scanMapper.selectCmsRegistrationScanList(any())).thenReturn(Collections.<CmsRegistrationScan>emptyList());
        ReflectionTestUtils.setField(service, "cmsRegistrationScanMapper", scanMapper);
        ReflectionTestUtils.setField(service, "merchantReferralBountyService",
                mock(com.chengyinhub.business.service.impl.MerchantReferralBountyService.class));
        BusinessAuditEvent reversed = new BusinessAuditEvent();
        reversed.setDomainType("REGISTRATION");
        reversed.setAction("REGISTRATION_VERIFY_REVERSE");
        when(auditMapper.selectByTarget("CMS_REGISTRATION", REG)).thenReturn(Collections.singletonList(reversed));
        when(regMapper.verifyIfNotVerified(REG, ORGANIZER)).thenReturn(1);
        CmsRegistration again = verified();
        again.setVerificationStatus(0);

        assertTrue(service.verifyAndSettle(again, ORGANIZER, ORGANIZER, null));

        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(auditMapper).insertBusinessAuditEvent(audit.capture());
        assertEquals("registration-redeem-" + REG + "-r1", audit.getValue().getRequestId());
    }

    private static String message(org.junit.jupiter.api.function.Executable call) {
        return assertThrows(IllegalStateException.class, call).getMessage();
    }
}
