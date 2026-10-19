package com.chengyinhub.business.service.impl;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.domain.SmsCouponHistory;
import com.chengyinhub.business.domain.SmsCouponRuleVersion;
import com.chengyinhub.business.domain.dto.CouponRuleChangeDTO;
import com.chengyinhub.business.mapper.SmsCouponHistoryMapper;
import com.chengyinhub.business.mapper.SmsCouponMapper;
import com.chengyinhub.business.mapper.SmsCouponRuleVersionMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F0572 平台优惠券模板治理:白名单、规则版本、停发、平台失效、旧券读旧规则。
 */
class CouponRuleGovernanceServiceImplTest {

    private SmsCouponMapper coupons;
    private SmsCouponHistoryMapper histories;
    private SmsCouponRuleVersionMapper versions;
    private CouponRuleGovernanceServiceImpl service;

    @BeforeEach
    void setUp() {
        coupons = mock(SmsCouponMapper.class);
        histories = mock(SmsCouponHistoryMapper.class);
        versions = mock(SmsCouponRuleVersionMapper.class);
        service = new CouponRuleGovernanceServiceImpl();
        ReflectionTestUtils.setField(service, "smsCouponMapper", coupons);
        ReflectionTestUtils.setField(service, "smsCouponHistoryMapper", histories);
        ReflectionTestUtils.setField(service, "ruleVersionMapper", versions);
        when(versions.insertRuleVersion(any())).thenReturn(1);
    }

    private static SmsCoupon coupon(int status, int version) {
        SmsCoupon c = new SmsCoupon();
        c.setId(30L);
        c.setName("夏日券");
        c.setStatus(status);
        c.setDelFlag(0);
        c.setRuleVersion(version);
        c.setAmount(new BigDecimal("20.00"));
        c.setMinPoint(new BigDecimal("100.00"));
        c.setStartTime(new Date(1_000_000L));
        c.setEndTime(new Date(9_000_000_000_000L));
        c.setPublishCount(100L);
        c.setReceiveCount(40L);
        return c;
    }

    private static CouponRuleChangeDTO change(int expectedVersion) {
        CouponRuleChangeDTO dto = new CouponRuleChangeDTO();
        dto.setId(30L);
        dto.setExpectedVersion(expectedVersion);
        return dto;
    }

    // ---------------------------------------------------------------- 非法输入

    @Test
    void illegalAmountsAreRejected() {
        SmsCoupon current = coupon(1, 1);
        for (String bad : Arrays.asList("0", "-5", "12.345", "100000.01")) {
            CouponRuleChangeDTO dto = change(1);
            dto.setAmount(new BigDecimal(bad));
            assertNotNull(CouponRuleGovernanceServiceImpl.validate(current, dto), "面额 " + bad + " 应被拒绝");
        }
        CouponRuleChangeDTO ok = change(1);
        ok.setAmount(new BigDecimal("15.50"));
        assertNull(CouponRuleGovernanceServiceImpl.validate(current, ok));

        CouponRuleChangeDTO negativeThreshold = change(1);
        negativeThreshold.setMinPoint(new BigDecimal("-1"));
        assertNotNull(CouponRuleGovernanceServiceImpl.validate(current, negativeThreshold));
    }

    @Test
    void illegalAmountNeverReachesTheDatabase() {
        when(coupons.selectCouponRuleStateForUpdate(30L)).thenReturn(coupon(1, 1));
        CouponRuleChangeDTO dto = change(1);
        dto.setAmount(new BigDecimal("-1"));

        ServiceException e = assertThrows(ServiceException.class, () -> service.applyChange(dto, 7L));
        assertEquals(Integer.valueOf(400), e.getCode());
        verify(coupons, never()).updateCouponRuleCas(any(), any());
        verify(versions, never()).insertRuleVersion(any());
    }

    @Test
    void publishCountNeverBelowAlreadyClaimed() {
        SmsCoupon current = coupon(1, 1); // 已领取 40
        CouponRuleChangeDTO below = change(1);
        below.setPublishCount(39L);
        assertTrue(CouponRuleGovernanceServiceImpl.validate(current, below).contains("不能低于已领取"));
        CouponRuleChangeDTO equal = change(1);
        equal.setPublishCount(40L);
        assertNull(CouponRuleGovernanceServiceImpl.validate(current, equal));
        CouponRuleChangeDTO unlimited = change(1);
        unlimited.setPublishCount(0L);
        assertNull(CouponRuleGovernanceServiceImpl.validate(current, unlimited), "0=不限量");
    }

    @Test
    void validityMustBeOrdered() {
        SmsCoupon current = coupon(1, 1);
        CouponRuleChangeDTO dto = change(1);
        dto.setEndTime(new Date(500_000L)); // 早于当前开始时间
        assertNotNull(CouponRuleGovernanceServiceImpl.validate(current, dto));
    }

    @Test
    void invalidatedCouponRulesAreFrozen() {
        CouponRuleChangeDTO dto = change(1);
        dto.setName("改名");
        assertNotNull(CouponRuleGovernanceServiceImpl.validate(coupon(3, 1), dto));
    }

    // ---------------------------------------------------------------- 版本

    @Test
    void staleVersionIsConflict() {
        when(coupons.selectCouponRuleStateForUpdate(30L)).thenReturn(coupon(1, 4));
        CouponRuleChangeDTO dto = change(3);
        dto.setAmount(new BigDecimal("30"));

        ServiceException e = assertThrows(ServiceException.class, () -> service.applyChange(dto, 7L));
        assertEquals(Integer.valueOf(409), e.getCode());
        assertEquals(4, ((Map<?, ?>) e.getData()).get("currentVersion"));
        verify(coupons, never()).updateCouponRuleCas(any(), any());
    }

    @Test
    void ruleChangeWritesBaselineAndNewVersionWithChangedFieldsOnly() {
        SmsCoupon before = coupon(1, 1);
        SmsCoupon after = coupon(1, 2);
        after.setAmount(new BigDecimal("30.00"));
        after.setPublishCount(200L);
        when(coupons.selectCouponRuleStateForUpdate(30L)).thenReturn(before, after);
        when(versions.countByCouponAndVersion(30L, 1)).thenReturn(0);
        when(coupons.updateCouponRuleCas(any(), eq(1))).thenReturn(1);
        CouponRuleChangeDTO dto = change(1);
        dto.setAmount(new BigDecimal("30.00"));
        dto.setPublishCount(200L);
        dto.setName("夏日券"); // 未变化,不计入
        dto.setReason("加量");

        SmsCouponRuleVersion row = service.applyChange(dto, 7L);

        ArgumentCaptor<SmsCoupon> update = ArgumentCaptor.forClass(SmsCoupon.class);
        verify(coupons).updateCouponRuleCas(update.capture(), eq(1));
        assertNull(update.getValue().getName());
        assertNull(update.getValue().getReceiveCount());
        assertNull(update.getValue().getStatus());
        assertEquals(new BigDecimal("30.00"), update.getValue().getAmount());
        ArgumentCaptor<SmsCouponRuleVersion> rows = ArgumentCaptor.forClass(SmsCouponRuleVersion.class);
        verify(versions, times(2)).insertRuleVersion(rows.capture());
        assertEquals(SmsCouponRuleVersion.BASELINE, rows.getAllValues().get(0).getChangeType());
        assertEquals(new BigDecimal("20.00"), rows.getAllValues().get(0).getAmount(), "基线保存改动前的旧规则");
        assertEquals(Integer.valueOf(2), row.getRuleVersion());
        assertEquals("amount,publishCount", row.getChangedFields());
        assertEquals("加量", row.getReason());
    }

    @Test
    void previewSeparatesNewClaimsFromAlreadyClaimed() {
        when(coupons.selectCouponRuleState(30L)).thenReturn(coupon(1, 2));
        java.util.Map<String, Object> unused = new java.util.HashMap<>();
        unused.put("useStatus", 0);
        unused.put("total", 25L);
        unused.put("legacyTotal", 5L);
        java.util.Map<String, Object> used = new java.util.HashMap<>();
        used.put("useStatus", 1);
        used.put("total", 15L);
        used.put("legacyTotal", 0L);
        when(histories.countClaimsByUseStatus(30L)).thenReturn(Arrays.asList(unused, used));
        CouponRuleChangeDTO dto = change(1);
        dto.setAmount(new BigDecimal("50"));

        Map<String, Object> preview = service.previewChange(dto);

        assertEquals(true, preview.get("versionConflict"));
        assertEquals(2, preview.get("currentVersion"));
        Map<?, ?> newClaims = (Map<?, ?>) preview.get("newClaims");
        assertEquals(3, newClaims.get("ruleVersion"));
        assertEquals(60L, newClaims.get("remaining"));
        Map<?, ?> claimed = (Map<?, ?>) preview.get("claimed");
        assertEquals(40L, claimed.get("total"));
        assertEquals(25L, claimed.get("unused"));
        assertEquals(5L, claimed.get("legacyWithoutSnapshot"));
        assertEquals(true, claimed.get("keepsClaimRule"));
        assertEquals(1, ((List<?>) preview.get("changes")).size());
    }

    // ---------------------------------------------------------------- 停发 / 平台失效

    @Test
    void stopIssueOnlyFromNotStartedOrActive() {
        when(coupons.selectCouponRuleStateForUpdate(30L)).thenReturn(coupon(2, 1));
        assertThrows(ServiceException.class, () -> service.stopIssue(30L, 1, null, 7L));
        verify(coupons, never()).updateCouponStatusCas(any(), any(), any(), any());
    }

    @Test
    void stopIssueBumpsVersionAndNeverTouchesClaimedCoupons() {
        SmsCoupon after = coupon(4, 2);
        when(coupons.selectCouponRuleStateForUpdate(30L)).thenReturn(coupon(1, 1), after);
        when(versions.countByCouponAndVersion(30L, 1)).thenReturn(1);
        when(coupons.updateCouponStatusCas(eq(30L), any(), eq(4), eq(1))).thenReturn(1);

        SmsCouponRuleVersion row = service.stopIssue(30L, 1, "活动提前结束", 7L);

        assertEquals(SmsCouponRuleVersion.STOP_ISSUE, row.getChangeType());
        assertEquals(Integer.valueOf(4), row.getStatus());
        verify(histories, never()).selectUnusedHoldersByCouponId(any(), any(), anyInt());
    }

    @Test
    void invalidateRequiresIndependentReason() {
        assertThrows(ServiceException.class, () -> service.invalidate(30L, 1, "  ", 7L));
        verify(coupons, never()).selectCouponRuleStateForUpdate(any());
    }

    @Test
    void invalidateRecordsReasonAndListsAffectedHolders() {
        SmsCoupon after = coupon(3, 2);
        when(coupons.selectCouponRuleStateForUpdate(30L)).thenReturn(coupon(4, 1), after);
        when(versions.countByCouponAndVersion(30L, 1)).thenReturn(1);
        when(coupons.updateCouponStatusCas(eq(30L), any(), eq(3), eq(1))).thenReturn(1);
        SmsCouponHistory holder = new SmsCouponHistory();
        holder.setId(301L);
        holder.setMemberId(5001L);
        holder.setCouponCode("COU1");
        when(histories.countUnusedHoldersByCouponId(eq(30L), any())).thenReturn(1);
        when(histories.selectUnusedHoldersByCouponId(eq(30L), any(), anyInt()))
                .thenReturn(Collections.singletonList(holder));

        Map<String, Object> result = service.invalidate(30L, 1, "发现券被批量套取", 7L);

        SmsCouponRuleVersion row = (SmsCouponRuleVersion) result.get("version");
        assertEquals(SmsCouponRuleVersion.PLATFORM_INVALIDATE, row.getChangeType());
        assertEquals("发现券被批量套取", row.getReason());
        assertEquals(Integer.valueOf(1), row.getAffectedHolderCount());
        List<?> holders = (List<?>) result.get("holders");
        assertEquals(5001L, ((Map<?, ?>) holders.get(0)).get("memberId"));
    }
}
