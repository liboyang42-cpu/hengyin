package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.domain.SmsCouponHistory;
import com.chengyinhub.business.domain.vo.MyCouponUsabilityVO;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.SmsCouponHistoryMapper;
import com.chengyinhub.business.mapper.SmsCouponMapper;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.ISettlementEntryService;
import com.chengyinhub.business.service.ISmsCouponHistoryService.RedemptionResult;
import com.chengyinhub.business.service.ISmsCouponService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 可用性投影必须与**核销服务端闸口**给出同一个答案。
 *
 * <p>只测投影自己是纯 helper 断言,证明不了「玩家看到可用 ⇔ 商家能核销」。这里把
 * {@link SmsCouponHistoryServiceImpl#redeemCoupon} 和 {@link CouponUsabilityService} 放在同一份
 * 领取记录 + 券模板上跑,逐条对齐:
 * AVAILABLE↔REDEEMED,USED↔ALREADY_USED,EXPIRED↔EXPIRED,DISABLED↔DISABLED,
 * NOT_STARTED↔NOT_STARTED,COUPON_MISSING↔NOT_FOUND。
 *
 * <p>特别覆盖商家停发(status=4):投影判「可用」,核销必须真的能核销 —— 这是「发券闸 ≠ 核销闸」
 * 最容易写反的一处。
 */
class CouponUsabilityMatchesRedemptionTest {

    private static final Long OWNER_MEMBER_ID = 900L;
    private static final Long ACTOR_MEMBER_ID = 901L;
    private static final Long HISTORY_ID = 1L;
    private static final Long COUPON_ID = 10L;
    private static final long HOUR = 3_600_000L;

    @Test
    void availableCouponIsProjectedUsableAndRedeems() {
        assertConsistent(history(0, past(), future()), coupon(1), MyCouponUsabilityVO.USABLE, RedemptionResult.REDEEMED);
    }

    @Test
    void stoppedIssuingCouponStaysUsableAndStillRedeems() {
        // 4=商家停发:只停新增发放。投影判可用,核销必须成功,否则就是把已领的券作废。
        assertConsistent(history(0, past(), future()), coupon(4), MyCouponUsabilityVO.USABLE, RedemptionResult.REDEEMED);
    }

    @Test
    void usedCouponIsProjectedUsedAndRedeemSaysAlreadyUsed() {
        assertConsistent(history(1, past(), future()), coupon(1), MyCouponUsabilityVO.USED, RedemptionResult.ALREADY_USED);
    }

    @Test
    void expiredByEndTimeIsProjectedExpiredAndRedeemRejects() {
        assertConsistent(history(0, past(), past()), coupon(1), MyCouponUsabilityVO.EXPIRED, RedemptionResult.EXPIRED);
    }

    @Test
    void manuallyInvalidatedCouponIsProjectedDisabledAndRedeemRejects() {
        assertConsistent(history(0, past(), future()), coupon(3), MyCouponUsabilityVO.DISABLED, RedemptionResult.DISABLED);
    }

    @Test
    void notStartedCouponIsProjectedNotStartedAndRedeemRejects() {
        assertConsistent(history(0, future(), future()), coupon(1), MyCouponUsabilityVO.NOT_STARTED, RedemptionResult.NOT_STARTED);
    }

    @Test
    void missingCouponTemplateIsProjectedMissingAndRedeemSaysNotFound() {
        assertConsistent(history(0, past(), future()), null, MyCouponUsabilityVO.COUPON_MISSING, RedemptionResult.NOT_FOUND);
    }

    // ---------- fixture ----------

    private void assertConsistent(SmsCouponHistory row, SmsCoupon coupon,
                                  String expectedReason, RedemptionResult expectedRedeem) {
        // 投影侧
        SmsCouponHistoryMapper projectionRows = mock(SmsCouponHistoryMapper.class);
        when(projectionRows.selectSmsCouponHistoryList(any())).thenReturn(new ArrayList<>(Arrays.asList(row)));
        ISmsCouponService coupons = mock(ISmsCouponService.class);
        if (coupon != null) {
            when(coupons.selectSmsCouponById(COUPON_ID)).thenReturn(coupon);
        }
        CouponUsabilityService usability = new CouponUsabilityService();
        ReflectionTestUtils.setField(usability, "smsCouponHistoryMapper", projectionRows);
        ReflectionTestUtils.setField(usability, "smsCouponService", coupons);
        List<MyCouponUsabilityVO> projected = usability.listMine(OWNER_MEMBER_ID);

        assertEquals(1, projected.size());
        assertEquals(expectedReason, projected.get(0).getReason());
        assertEquals(MyCouponUsabilityVO.USABLE.equals(expectedReason), projected.get(0).isUsable());

        // 核销侧:同一份领取记录 + 券模板
        SmsCouponHistoryMapper redeemRows = mock(SmsCouponHistoryMapper.class);
        when(redeemRows.selectSmsCouponHistoryByIdForUpdate(HISTORY_ID)).thenReturn(row);
        when(redeemRows.claimUnusedCoupon(anyLong(), any())).thenReturn(1);
        when(redeemRows.expireUnusedCoupon(anyLong(), any())).thenReturn(1);
        SmsCouponMapper couponMapper = mock(SmsCouponMapper.class);
        if (coupon != null) {
            when(couponMapper.selectSmsCouponById(COUPON_ID)).thenReturn(coupon);
        }
        when(couponMapper.incrementUseCount(COUPON_ID)).thenReturn(1);
        BusinessAuditEventMapper audit = mock(BusinessAuditEventMapper.class);
        when(audit.insertBusinessAuditEvent(any())).thenReturn(1);

        SmsCouponHistoryServiceImpl redemption = new SmsCouponHistoryServiceImpl();
        ReflectionTestUtils.setField(redemption, "smsCouponHistoryMapper", redeemRows);
        ReflectionTestUtils.setField(redemption, "smsCouponMapper", couponMapper);
        ReflectionTestUtils.setField(redemption, "settlementEntryService", mock(ISettlementEntryService.class));
        ReflectionTestUtils.setField(redemption, "businessAuditEventMapper", audit);

        RedemptionResult actual = redemption.redeemCoupon(
                HISTORY_ID, OWNER_MEMBER_ID, null, ACTOR_MEMBER_ID, IRoleService.ROLE_CLUB);
        assertEquals(expectedRedeem, actual,
                "投影说「" + expectedReason + "」,核销却给「" + actual + "」—— 两处闸口已经不一致");
    }

    private SmsCouponHistory history(int useStatus, Date start, Date end) {
        SmsCouponHistory h = new SmsCouponHistory();
        h.setId(HISTORY_ID);
        h.setCouponId(COUPON_ID);
        h.setMemberId(OWNER_MEMBER_ID);
        h.setUseStatus(useStatus);
        h.setStartTime(start);
        h.setEndTime(end);
        h.setDelFlag(0);
        return h;
    }

    private SmsCoupon coupon(int status) {
        SmsCoupon c = new SmsCoupon();
        c.setId(COUPON_ID);
        c.setName("到店券");
        c.setCouponType(1);
        c.setAmount(new BigDecimal("20.00"));
        c.setMemberId(OWNER_MEMBER_ID);
        c.setScopeType(0);
        c.setStatus(status);
        c.setDelFlag(0);
        return c;
    }

    private Date past() {
        return new Date(System.currentTimeMillis() - HOUR);
    }

    private Date future() {
        return new Date(System.currentTimeMillis() + HOUR);
    }
}
