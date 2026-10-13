package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.domain.SmsCouponHistory;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.SmsCouponHistoryMapper;
import com.chengyinhub.business.mapper.SmsCouponMapper;
import com.chengyinhub.business.service.impl.SmsCouponHistoryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ① 券错核冲销:一次调用按序回滚四类事实(券态/核销计数/待结算分成/审计),CAS 幂等,审计失败整体回滚。
 *
 * <p>对冲销后状态回滚的断言是 RED 的锚点:占位实现(不回滚)下必然失败。
 */
class SmsCouponReverseTest {
    private SmsCouponHistoryMapper histories;
    private SmsCouponMapper coupons;
    private ISettlementEntryService settlements;
    private BusinessAuditEventMapper audits;
    private SmsCouponHistoryServiceImpl service;

    @BeforeEach
    void setUp() {
        histories = mock(SmsCouponHistoryMapper.class);
        coupons = mock(SmsCouponMapper.class);
        settlements = mock(ISettlementEntryService.class);
        audits = mock(BusinessAuditEventMapper.class);
        service = new SmsCouponHistoryServiceImpl();
        ReflectionTestUtils.setField(service, "smsCouponHistoryMapper", histories);
        ReflectionTestUtils.setField(service, "smsCouponMapper", coupons);
        ReflectionTestUtils.setField(service, "settlementEntryService", settlements);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", audits);
    }

    private SmsCouponHistory usedHistory() {
        SmsCouponHistory history = new SmsCouponHistory();
        history.setId(301L);
        history.setCouponId(30L);
        history.setMemberId(9001L);
        history.setUseStatus(1);
        history.setUseTime(new Date(1700000000000L));
        return history;
    }

    private SmsCoupon merchantCoupon() {
        SmsCoupon coupon = new SmsCoupon();
        coupon.setId(30L);
        coupon.setMemberId(9001L);
        coupon.setScopeType(1);
        coupon.setMerchantId(11L);
        return coupon;
    }

    /** RED:撤销后券态、核销计数、待结算分成、不可变审计四类事实全部按序回滚/落账。 */
    @Test
    void reverse_rollsBackUseStatusCountPendingSettlementAndWritesAudit() {
        when(histories.selectSmsCouponHistoryById(301L)).thenReturn(usedHistory());
        when(coupons.selectSmsCouponById(30L)).thenReturn(merchantCoupon());
        when(histories.reverseUsedCoupon(eq(301L), any())).thenReturn(1);
        when(coupons.decrementUseCount(30L)).thenReturn(1);
        when(settlements.deletePendingCouponVerification(301L)).thenReturn(2);
        when(audits.insertBusinessAuditEvent(any())).thenReturn(1);

        assertTrue(service.reverseRedemption(301L, "店员扫错券", 77L));

        verify(histories).reverseUsedCoupon(eq(301L), any());
        verify(coupons).decrementUseCount(30L);
        verify(settlements).deletePendingCouponVerification(301L);

        ArgumentCaptor<BusinessAuditEvent> event = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(audits).insertBusinessAuditEvent(event.capture());
        assertEquals(BusinessAuditEvent.ACTOR_TYPE_SYS_USER, event.getValue().getActorType());
        assertEquals(77L, event.getValue().getActorMemberId());
        assertEquals("SYS_USER", event.getValue().getActorRoleCode());
        assertEquals("COUPON_REDEEM_REVERSE", event.getValue().getAction());
        assertEquals("SMS_COUPON_HISTORY", event.getValue().getTargetType());
        assertEquals(301L, event.getValue().getTargetId());
        assertEquals(30L, event.getValue().getRelationId());
        assertEquals("MERCHANT", event.getValue().getDomainType());
        assertEquals(11L, event.getValue().getDomainId());
        assertEquals(9001L, event.getValue().getOwnerMemberId());
        assertEquals("{\"useStatus\":1}", event.getValue().getBeforeJson());
        assertEquals("{\"useStatus\":0}", event.getValue().getAfterJson());
        assertEquals("店员扫错券", event.getValue().getReason());
        // requestId 必须带本次核销周期(useTime),否则「撤销→重核销→再撤销」会撞
        // uk_business_audit_request 把第二次撤销整体打回。
        assertTrue(event.getValue().getRequestId().matches("coupon-redeem-reverse:301:[0-9a-f]{32}"));
        assertNotNull(event.getValue().getCreateTime());
    }

    /** RED:第二次撤销被 CAS 挡下 —— 计数/分成/审计都只有一次,不得二次回滚。 */
    @Test
    void reverse_secondCallBlockedByCas_noDoubleRollback() {
        when(histories.selectSmsCouponHistoryById(301L)).thenReturn(usedHistory());
        when(coupons.selectSmsCouponById(30L)).thenReturn(merchantCoupon());
        when(histories.reverseUsedCoupon(eq(301L), any())).thenReturn(1, 0);
        when(coupons.decrementUseCount(30L)).thenReturn(1);
        when(settlements.deletePendingCouponVerification(301L)).thenReturn(2);
        when(audits.insertBusinessAuditEvent(any())).thenReturn(1);

        assertTrue(service.reverseRedemption(301L, "店员扫错券", 77L));
        assertFalse(service.reverseRedemption(301L, "重复点击", 77L));

        verify(coupons, times(1)).decrementUseCount(30L);
        verify(settlements, times(1)).deletePendingCouponVerification(301L);
        verify(audits, times(1)).insertBusinessAuditEvent(any());
    }

    /** RED:审计写不进去,整次撤销抛错(由同事务注解回滚) —— 宁可撤不成,不许撤了查不出谁撤的。 */
    @Test
    void reverse_auditWriteFailure_rollsBack() {
        when(histories.selectSmsCouponHistoryById(301L)).thenReturn(usedHistory());
        when(coupons.selectSmsCouponById(30L)).thenReturn(merchantCoupon());
        when(histories.reverseUsedCoupon(eq(301L), any())).thenReturn(1);
        when(coupons.decrementUseCount(30L)).thenReturn(1);
        when(settlements.deletePendingCouponVerification(301L)).thenReturn(0);
        when(audits.insertBusinessAuditEvent(any())).thenReturn(0);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.reverseRedemption(301L, "店员扫错券", 77L));

        assertEquals("优惠券撤销核销审计写入失败,撤销已回滚", error.getMessage());
    }

    /** 无操作者不许撤 —— 审计的 actor_member_id NOT NULL,缺了它写不进去,必须在入口就拦。 */
    @Test
    void reverse_missingOperatorRejectedBeforeAnyWrite() {
        assertThrows(IllegalArgumentException.class,
                () -> service.reverseRedemption(301L, "店员扫错券", null));

        verifyNoInteractions(histories, coupons, settlements, audits);
    }

    /** 无原因不许撤 —— 原因就是留痕本体。 */
    @Test
    void reverse_blankReasonRejectedBeforeAnyWrite() {
        assertThrows(IllegalArgumentException.class,
                () -> service.reverseRedemption(301L, "   ", 77L));

        verifyNoInteractions(histories, coupons, settlements, audits);
    }

    /** legacy 俱乐部券沿用核销时的审计域口径(CLUB + owner member 作 surrogate),不伪造商家域。 */
    @Test
    void reverse_legacyClubCouponUsesClubAuditDomain() {
        SmsCoupon clubCoupon = merchantCoupon();
        clubCoupon.setScopeType(null);
        clubCoupon.setMerchantId(null);
        when(histories.selectSmsCouponHistoryById(301L)).thenReturn(usedHistory());
        when(coupons.selectSmsCouponById(30L)).thenReturn(clubCoupon);
        when(histories.reverseUsedCoupon(eq(301L), any())).thenReturn(1);
        when(coupons.decrementUseCount(30L)).thenReturn(1);
        when(settlements.deletePendingCouponVerification(301L)).thenReturn(0);
        when(audits.insertBusinessAuditEvent(any())).thenReturn(1);

        assertTrue(service.reverseRedemption(301L, "拿错券", 77L));

        ArgumentCaptor<BusinessAuditEvent> event = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(audits).insertBusinessAuditEvent(event.capture());
        assertEquals("CLUB", event.getValue().getDomainType());
        assertEquals(9001L, event.getValue().getDomainId());
    }

    /** 券计数与流水失配(0 行)时响亮抛错,不允许静默留一条对不上的账。 */
    @Test
    void reverse_countDecrementZeroRowsThrows() {
        when(histories.selectSmsCouponHistoryById(301L)).thenReturn(usedHistory());
        when(coupons.selectSmsCouponById(30L)).thenReturn(merchantCoupon());
        when(histories.reverseUsedCoupon(eq(301L), any())).thenReturn(1);
        when(coupons.decrementUseCount(30L)).thenReturn(0);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.reverseRedemption(301L, "店员扫错券", 77L));

        assertEquals("优惠券核销计数回退失败", error.getMessage());
        verify(audits, never()).insertBusinessAuditEvent(any());
    }
}
