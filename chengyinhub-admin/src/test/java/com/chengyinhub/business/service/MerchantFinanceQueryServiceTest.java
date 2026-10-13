package com.chengyinhub.business.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;

import com.chengyinhub.business.domain.CoopSettlement;
import com.chengyinhub.business.domain.MerchantClawbackApplication;
import com.chengyinhub.business.domain.MerchantPayable;
import com.chengyinhub.business.domain.MerchantPayableBatch;
import com.chengyinhub.business.domain.Redemption;
import com.chengyinhub.business.domain.UmsMemberBalanceDetail;
import com.chengyinhub.business.domain.vo.MerchantFinancePage;
import com.chengyinhub.business.domain.vo.MerchantRedemptionView;
import com.chengyinhub.business.domain.vo.MerchantSettlementEntry;
import com.chengyinhub.business.domain.vo.MerchantSettlementOverview;
import com.chengyinhub.business.domain.vo.PublicTransferBatch;
import com.chengyinhub.business.mapper.CoopSettlementMapper;
import com.chengyinhub.business.mapper.MerchantClawbackApplicationMapper;
import com.chengyinhub.business.mapper.MerchantPayableBatchMapper;
import com.chengyinhub.business.mapper.MerchantPayableMapper;
import com.chengyinhub.business.mapper.RedemptionMapper;
import com.chengyinhub.business.mapper.UmsMemberBalanceDetailMapper;
import com.chengyinhub.business.service.impl.MerchantFinanceQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MerchantFinanceQueryServiceTest {

    @Test
    void calculatesPersonalAccountNetWithServerDecimalArithmetic() {
        MerchantFinanceQueryService service = serviceWithBalanceRows(Arrays.asList(
                balance("0.10", 2L, new Date()), balance("0.20", 2L, new Date()), balance("-0.05", 2L, new Date())));

        MerchantSettlementOverview overview = service.overview(7L);

        assertEquals("0.30", overview.getPersonalArrivedThisMonthGross(), "两个正额余额流水必须由 BigDecimal 精确相加");
        assertEquals("-0.05", overview.getPersonalExecutedAdjustmentsThisMonth(), "已实际扣减的个人账户调整必须保持负号");
        assertEquals("0.25", overview.getPersonalArrivedThisMonthNet(), "净额必须只由同轨毛额和已执行调整守恒得出");
    }

    @Test
    void projectsNonPositivePublicBatchesWithoutPretendingTheyWerePaid() {
        MerchantPayableBatchMapper batchMapper = org.mockito.Mockito.mock(MerchantPayableBatchMapper.class);
        MerchantFinanceQueryService service = new MerchantFinanceQueryService();
        ReflectionTestUtils.setField(service, "batchMapper", batchMapper);
        when(batchMapper.selectByMerchant(7L)).thenReturn(Arrays.asList(
                batch(101L, "2026-08", "0.00", "PAID", atShanghai(2026, Calendar.AUGUST, 20, 9, 0)),
                batch(102L, "2026-09", "-12.00", "PAID", atShanghai(2026, Calendar.SEPTEMBER, 20, 9, 0))));

        MerchantFinancePage<PublicTransferBatch> page = service.publicTransferBatches(7L, 1, 50);

        assertEquals("ZERO", page.getRows().get(0).getNetDirection(), "零额批次必须保留为零方向，而非伪造正向打款");
        assertEquals("LEDGER_ERROR", page.getRows().get(0).getDisplayState(), "零额 PAID 必须返回可见账务异常");
        assertEquals("MERCHANT_OWES_PLATFORM", page.getRows().get(1).getNetDirection(), "负额批次必须明确为待处理调整");
        assertEquals("LEDGER_ERROR", page.getRows().get(1).getDisplayState(), "负额 PAID 同样必须暴露为异常付款事实");
    }

    @Test
    void keepsPaidAccrualAndLaterReversalAsSeparateSignedFacts() {
        MerchantPayableMapper payableMapper = org.mockito.Mockito.mock(MerchantPayableMapper.class);
        MerchantPayableBatchMapper batchMapper = org.mockito.Mockito.mock(MerchantPayableBatchMapper.class);
        CoopSettlementMapper coopSettlementMapper = org.mockito.Mockito.mock(CoopSettlementMapper.class);
        UmsMemberBalanceDetailMapper balanceDetailMapper = org.mockito.Mockito.mock(UmsMemberBalanceDetailMapper.class);
        MerchantClawbackApplicationMapper clawbackMapper = org.mockito.Mockito.mock(MerchantClawbackApplicationMapper.class);
        MerchantFinanceQueryService service = new MerchantFinanceQueryService();
        ReflectionTestUtils.setField(service, "payableMapper", payableMapper);
        ReflectionTestUtils.setField(service, "batchMapper", batchMapper);
        ReflectionTestUtils.setField(service, "coopSettlementMapper", coopSettlementMapper);
        ReflectionTestUtils.setField(service, "balanceDetailMapper", balanceDetailMapper);
        ReflectionTestUtils.setField(service, "clawbackMapper", clawbackMapper);

        MerchantPayable accrual = payable(11L, "ACCRUAL", "12.00", atShanghai(2026, Calendar.AUGUST, 31, 18, 0));
        MerchantPayable reversal = payable(12L, "REVERSAL", "12.00", atShanghai(2026, Calendar.SEPTEMBER, 2, 9, 0));
        MerchantPayableBatch august = batch(101L, "2026-08", "12.00", "PAID", atShanghai(2026, Calendar.SEPTEMBER, 5, 9, 0));
        MerchantPayableBatch september = batch(102L, "2026-09", "-12.00", "PENDING", null);

        when(payableMapper.selectByMerchant(7L)).thenReturn(Arrays.asList(accrual, reversal));
        when(batchMapper.selectByMerchant(7L)).thenReturn(Arrays.asList(august, september));
        when(coopSettlementMapper.selectByMerchant(7L)).thenReturn(Collections.<CoopSettlement>emptyList());
        when(balanceDetailMapper.selectUmsMemberBalanceDetailList(org.mockito.ArgumentMatchers.any(UmsMemberBalanceDetail.class)))
                .thenReturn(Collections.<UmsMemberBalanceDetail>emptyList());
        when(clawbackMapper.selectByMerchantMemberId(7L)).thenReturn(Collections.<MerchantClawbackApplication>emptyList());

        MerchantFinancePage<MerchantSettlementEntry> page = service.settlementEntries(7L, "all", 1, 50);

        assertEquals(2, page.getRows().size(), "账务事件必须保留跨月计提与冲正两条事实");
        assertEquals("-12.00", page.getRows().get(0).getSignedAmount(), "最新冲正必须在服务端归一为负额");
        assertEquals("12.00", page.getRows().get(1).getSignedAmount(), "历史计提必须保留正额两位金额");
        assertEquals("102", page.getRows().get(0).getRefId(), "冲正必须指向其后的调整批次");
        assertEquals("101", page.getRows().get(1).getRefId(), "已打款计提必须仍指向原打款批次");
        assertEquals("ADJUSTMENT_PENDING", page.getRows().get(0).getDisplayState(), "未打款的后续冲正必须显示调整中");
        assertEquals("SETTLED", page.getRows().get(1).getDisplayState(), "有完整打款事实的计提才可称已打款");
    }

    @Test
    void marksConfirmedZeroCoopSettlementAsNoCashWithServerOwnedReason() {
        MerchantFinanceQueryService service = new MerchantFinanceQueryService();
        RedemptionMapper redemptionMapper = org.mockito.Mockito.mock(RedemptionMapper.class);
        MerchantPayableMapper payableMapper = org.mockito.Mockito.mock(MerchantPayableMapper.class);
        MerchantPayableBatchMapper batchMapper = org.mockito.Mockito.mock(MerchantPayableBatchMapper.class);
        ReflectionTestUtils.setField(service, "redemptionMapper", redemptionMapper);
        ReflectionTestUtils.setField(service, "payableMapper", payableMapper);
        ReflectionTestUtils.setField(service, "batchMapper", batchMapper);

        Redemption redemption = new Redemption();
        redemption.setId(31L);
        redemption.setStatus("ACTIVE");
        redemption.setCreateTime(atShanghai(2026, Calendar.AUGUST, 20, 9, 0));
        when(redemptionMapper.selectByMerchant(7L)).thenReturn(Collections.singletonList(redemption));
        when(payableMapper.selectByMerchant(7L)).thenReturn(Collections.singletonList(
                payable(11L, "ACCRUAL", "0.00", atShanghai(2026, Calendar.AUGUST, 20, 9, 0))));
        when(batchMapper.selectByMerchant(7L)).thenReturn(Collections.<MerchantPayableBatch>emptyList());

        MerchantFinancePage<MerchantRedemptionView> page = service.redemptions(7L, "all", 1, 20);

        assertEquals("0.00", page.getRows().get(0).getSettlementAmount(), "已确认零分润仍是 0 而非金额未成立");
        assertEquals("NO_CASH_SETTLEMENT", page.getRows().get(0).getDisplayState(), "合作零分润必须统一为零现金展示态");
        assertEquals("本次合作不产生现金分润", page.getRows().get(0).getNoCashReason(), "第三条零现金原因必须由服务端给出");
    }

    private static MerchantPayable payable(Long id, String entryType, String amount, Date createTime) {
        MerchantPayable payable = new MerchantPayable();
        payable.setId(id);
        payable.setRedemptionId(31L);
        payable.setEntryType(entryType);
        payable.setAmount(new BigDecimal(amount));
        payable.setCreateTime(createTime);
        return payable;
    }

    private static MerchantFinanceQueryService serviceWithBalanceRows(java.util.List<UmsMemberBalanceDetail> rows) {
        MerchantFinanceQueryService service = new MerchantFinanceQueryService();
        UmsMemberBalanceDetailMapper balanceMapper = org.mockito.Mockito.mock(UmsMemberBalanceDetailMapper.class);
        CoopSettlementMapper coopMapper = org.mockito.Mockito.mock(CoopSettlementMapper.class);
        MerchantPayableMapper payableMapper = org.mockito.Mockito.mock(MerchantPayableMapper.class);
        MerchantPayableBatchMapper batchMapper = org.mockito.Mockito.mock(MerchantPayableBatchMapper.class);
        MerchantClawbackApplicationMapper clawbackMapper = org.mockito.Mockito.mock(MerchantClawbackApplicationMapper.class);
        ReflectionTestUtils.setField(service, "balanceDetailMapper", balanceMapper);
        ReflectionTestUtils.setField(service, "coopSettlementMapper", coopMapper);
        ReflectionTestUtils.setField(service, "payableMapper", payableMapper);
        ReflectionTestUtils.setField(service, "batchMapper", batchMapper);
        ReflectionTestUtils.setField(service, "clawbackMapper", clawbackMapper);
        when(balanceMapper.selectUmsMemberBalanceDetailList(org.mockito.ArgumentMatchers.any(UmsMemberBalanceDetail.class))).thenReturn(rows);
        when(coopMapper.selectByMerchant(7L)).thenReturn(Collections.<CoopSettlement>emptyList());
        when(payableMapper.selectByMerchant(7L)).thenReturn(Collections.<MerchantPayable>emptyList());
        when(batchMapper.selectByMerchant(7L)).thenReturn(Collections.<MerchantPayableBatch>emptyList());
        when(clawbackMapper.selectByMerchantMemberId(7L)).thenReturn(Collections.<MerchantClawbackApplication>emptyList());
        return service;
    }

    private static UmsMemberBalanceDetail balance(String amount, Long eventType, Date createTime) {
        UmsMemberBalanceDetail detail = new UmsMemberBalanceDetail();
        detail.setChangeBalance(new BigDecimal(amount));
        detail.setEventType(eventType);
        detail.setCreateTime(createTime);
        return detail;
    }

    private static MerchantPayableBatch batch(Long id, String period, String amount, String status, Date paidAt) {
        MerchantPayableBatch batch = new MerchantPayableBatch();
        batch.setId(id);
        batch.setPeriodYm(period);
        batch.setAmountTotal(new BigDecimal(amount));
        batch.setStatus(status);
        batch.setPaidAt(paidAt);
        batch.setPayVoucherNo(paidAt == null ? null : "VOUCHER-" + id);
        return batch;
    }

    private static Date atShanghai(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        calendar.clear();
        calendar.set(year, month, day, hour, minute, 0);
        return calendar.getTime();
    }
}
