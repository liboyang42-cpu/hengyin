package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.domain.SmsCouponHistory;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.SmsCouponHistoryMapper;
import com.chengyinhub.business.mapper.SmsCouponMapper;
import com.chengyinhub.business.service.impl.SmsCouponHistoryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R9-40:已领取券自己的有效期为承诺 —— start 前拒核销(且无核销/结算/统计副作用),
 * start 时(含)起可核销,end 后仍拒。使用期判定不能只看 end_time。
 */
class SmsCouponRedemptionStartTimeTest {
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

    @Test
    void futureStartIsRejectedWithoutClaimSettlementCountOrAudit() {
        SmsCouponHistory history = history();
        history.setStartTime(new Date(System.currentTimeMillis() + 86_400_000L));
        history.setEndTime(new Date(System.currentTimeMillis() + 2 * 86_400_000L));
        when(histories.selectSmsCouponHistoryById(301L)).thenReturn(history);
        when(coupons.selectSmsCouponById(30L)).thenReturn(coupon());

        ISmsCouponHistoryService.RedemptionResult result = service.redeemCoupon(
                301L, 9001L, 11L, 7002L, "MERCHANT_CHECKIN");

        assertEquals(ISmsCouponHistoryService.RedemptionResult.NOT_STARTED, result);
        verify(histories, never()).claimUnusedCoupon(any(), any());
        verify(settlements, never()).recordCouponVerification(any(), any());
        verify(coupons, never()).incrementUseCount(any());
        verify(audits, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void startTimeNullOrPastAllowsRedemption() {
        SmsCouponHistory legacy = history();
        legacy.setStartTime(null);
        legacy.setEndTime(new Date(System.currentTimeMillis() + 86_400_000L));
        when(histories.selectSmsCouponHistoryById(301L)).thenReturn(legacy);
        when(coupons.selectSmsCouponById(30L)).thenReturn(coupon());
        when(histories.claimUnusedCoupon(any(), any())).thenReturn(1);
        when(coupons.incrementUseCount(30L)).thenReturn(1);
        when(audits.insertBusinessAuditEvent(any())).thenReturn(1);

        assertEquals(ISmsCouponHistoryService.RedemptionResult.REDEEMED,
                service.redeemCoupon(301L, 9001L, 11L, 7002L, "MERCHANT_CHECKIN"));

        SmsCouponHistory started = history();
        started.setStartTime(new Date(System.currentTimeMillis() - 1_000L));
        started.setEndTime(new Date(System.currentTimeMillis() + 86_400_000L));
        when(histories.selectSmsCouponHistoryById(302L)).thenReturn(started);
        when(coupons.selectSmsCouponById(30L)).thenReturn(coupon());

        assertEquals(ISmsCouponHistoryService.RedemptionResult.REDEEMED,
                service.redeemCoupon(302L, 9001L, 11L, 7002L, "MERCHANT_CHECKIN"));
    }

    @Test
    void alreadyUsedAndExpiredPrecedenceArePreserved() {
        SmsCouponHistory used = history();
        used.setUseStatus(1);
        used.setStartTime(new Date(System.currentTimeMillis() + 86_400_000L));
        when(histories.selectSmsCouponHistoryById(301L)).thenReturn(used);
        when(coupons.selectSmsCouponById(30L)).thenReturn(coupon());

        assertEquals(ISmsCouponHistoryService.RedemptionResult.ALREADY_USED,
                service.redeemCoupon(301L, 9001L, 11L, 7002L, "MERCHANT_CHECKIN"));

        SmsCouponHistory expired = history();
        expired.setUseStatus(2);
        expired.setStartTime(new Date(System.currentTimeMillis() + 86_400_000L));
        when(histories.selectSmsCouponHistoryById(303L)).thenReturn(expired);

        assertEquals(ISmsCouponHistoryService.RedemptionResult.EXPIRED,
                service.redeemCoupon(303L, 9001L, 11L, 7002L, "MERCHANT_CHECKIN"));

        verify(histories, never()).claimUnusedCoupon(any(), any());
    }

    @Test
    void claimRaceReadbackStillDetectsNotStarted() {
        SmsCouponHistory snapshot = history();
        snapshot.setEndTime(new Date(System.currentTimeMillis() + 86_400_000L));
        when(histories.selectSmsCouponHistoryById(301L)).thenReturn(snapshot);
        when(coupons.selectSmsCouponById(30L)).thenReturn(coupon());
        when(histories.claimUnusedCoupon(any(), any())).thenReturn(0);

        SmsCouponHistory current = history();
        current.setStartTime(new Date(System.currentTimeMillis() + 86_400_000L));
        when(histories.selectSmsCouponHistoryByIdForUpdate(301L)).thenReturn(current);

        assertEquals(ISmsCouponHistoryService.RedemptionResult.NOT_STARTED,
                service.redeemCoupon(301L, 9001L, 11L, 7002L, "MERCHANT_CHECKIN"));

        verify(settlements, never()).recordCouponVerification(any(), any());
        verify(audits, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void redeemAndClaimSqlCarryStartGuardContract() throws Exception {
        String service = source("chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/SmsCouponHistoryServiceImpl.java");
        String xml = source("chengyinhub-system/src/main/resources/mapper/business/SmsCouponHistoryMapper.xml");
        int method = service.indexOf("redeemCoupon(");
        int start = service.indexOf("history.getStartTime() != null && history.getStartTime().after(now)", method);
        int claim = service.indexOf("smsCouponHistoryMapper.claimUnusedCoupon", method);

        assertTrue(start > method && start < claim,
                "start 闸门必须在取得核销唯一处理权之前");
        assertTrue(xml.contains("start_time is null or start_time &lt;= #{useTime}"),
                "claimUnusedCoupon 的原子条件必须同时约束 start_time");
        assertFalse(xml.contains("start_time &lt; #{useTime}"),
                "start 边界必须包含端点,不得写成排他的 <");
    }

    private static SmsCouponHistory history() {
        SmsCouponHistory row = new SmsCouponHistory();
        row.setId(301L);
        row.setCouponId(30L);
        row.setUseStatus(0);
        return row;
    }

    private static SmsCoupon coupon() {
        SmsCoupon row = new SmsCoupon();
        row.setId(30L);
        row.setMemberId(9001L);
        row.setScopeType(1);
        row.setMerchantId(11L);
        return row;
    }

    private static String source(String relative) throws Exception {
        Path root = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        if (root.getFileName() != null && "chengyinhub-admin".equals(root.getFileName().toString())) {
            root = root.getParent();
        }
        return new String(Files.readAllBytes(root.resolve(relative)), StandardCharsets.UTF_8);
    }
}
