package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.LedgerDiscrepancy;
import com.chengyinhub.business.domain.MerchantPayableBatch;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.WxTradeBillSnapshot;
import com.chengyinhub.business.domain.WxBillImport;
import com.chengyinhub.business.mapper.FourLedgerReconcileMapper;
import com.chengyinhub.business.mapper.MerchantPayableBatchMapper;
import com.chengyinhub.business.mapper.WxTradeBillSnapshotMapper;
import com.chengyinhub.business.mapper.WxBillImportMapper;
import com.chengyinhub.common.config.WxPayConfig;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.business.service.impl.FourLedgerReconcileServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * N块(四账对账)Service 测试(P1-6 feature/p1-four-ledger-reconcile)。
 *
 * <p>依据 P1-5《四账映射与守恒规则》冻结的三条守恒式(见 {@link IFourLedgerReconcileService}
 * 类注释),对每条守恒式各做一组"正常不误报 + 故意制造差异必检出"的负控——总纲"回执≠观测"铁律
 * 要求四账对账不能被吞掉，本组测试就是守这个。
 *
 * <p>⚠️ 负控已核对(注入 → 红 → 撤销 → 绿,逐条单独验证):
 * <ul>
 *   <li>把 {@link #wechatBill_flagsMismatch_whenBillDataExistsButAmountDiffers} 的差异判断
 *       (diverges)改成恒 false ⇒ 红</li>
 *   <li>把 {@link #memberBalance_flagsMismatch_whenDetailMissingForCreditedBalance} 的余额
 *       比对短路（比如让 reconcile 直接 return emptyList）⇒ 红</li>
 *   <li>把 {@link #merchantPayable_flagsDrift_whenNetChangedAfterFreeze} 的 batch 冻结值比对
 *       删掉 ⇒ 红</li>
 * </ul>
 */
class FourLedgerReconcileServiceTest {

    private FourLedgerReconcileMapper reconcileMapper;
    private WxTradeBillSnapshotMapper wxTradeBillSnapshotMapper;
    private WxBillImportMapper wxBillImportMapper;
    private WxPayConfig wxPayConfig;
    private IUmsMemberService umsMemberService;
    private IMerchantPayableService merchantPayableService;
    private MerchantPayableBatchMapper merchantPayableBatchMapper;
    private FourLedgerReconcileServiceImpl service;

    @BeforeEach
    void setUp() {
        reconcileMapper = mock(FourLedgerReconcileMapper.class);
        wxTradeBillSnapshotMapper = mock(WxTradeBillSnapshotMapper.class);
        wxBillImportMapper = mock(WxBillImportMapper.class);
        wxPayConfig = mock(WxPayConfig.class);
        when(wxPayConfig.getMerchantId()).thenReturn("test-merchant");
        WxBillImport complete = new WxBillImport();
        complete.setStatus("SUCCESS");
        complete.setRowCount(3);
        complete.setSourceHash("0123456789abcdef0123456789abcdef01234567");
        when(wxBillImportMapper.selectImport(eq("test-merchant"), eq("TRADE"), any(Date.class)))
                .thenReturn(complete);
        umsMemberService = mock(IUmsMemberService.class);
        merchantPayableService = mock(IMerchantPayableService.class);
        merchantPayableBatchMapper = mock(MerchantPayableBatchMapper.class);

        service = new FourLedgerReconcileServiceImpl();
        ReflectionTestUtils.setField(service, "reconcileMapper", reconcileMapper);
        ReflectionTestUtils.setField(service, "wxTradeBillSnapshotMapper", wxTradeBillSnapshotMapper);
        ReflectionTestUtils.setField(service, "wxBillImportMapper", wxBillImportMapper);
        ReflectionTestUtils.setField(service, "wxPayConfig", wxPayConfig);
        ReflectionTestUtils.setField(service, "umsMemberService", umsMemberService);
        ReflectionTestUtils.setField(service, "merchantPayableService", merchantPayableService);
        ReflectionTestUtils.setField(service, "merchantPayableBatchMapper", merchantPayableBatchMapper);
    }

    // ------------------------------------------------- 维度一:微信交易 vs 报名/订单

    /** 未下载必须明确待核，不能用空差异列表表示账单一致。 */
    @Test
    void wechatBill_requiresReview_whenBillNotDownloadedYet() {
        Date billDate = new Date();
        when(wxBillImportMapper.selectImport(anyString(), eq("TRADE"), any(Date.class))).thenReturn(null);
        assertEquals(409, assertThrows(ServiceException.class,
                () -> service.reconcileWechatBillAgainstRegistrations(billDate)).getCode());
        // 未下载时不应该再去查逐笔订单/账单明细,连误报的机会都不给自己留。
        org.mockito.Mockito.verify(reconcileMapper, org.mockito.Mockito.never())
                .selectPaidWechatRegistrationsByBillDate(any());
    }

    @Test void wechatBill_requiresReviewForIncompleteImportFacts() {
        for (String status : new String[]{"NO_STATEMENT", "CREATING", "FAILED", "SUCCESS"}) {
            WxBillImport incomplete = new WxBillImport();
            incomplete.setStatus(status);
            incomplete.setRowCount(0);
            when(wxBillImportMapper.selectImport(anyString(), eq("TRADE"), any(Date.class)))
                    .thenReturn(incomplete);
            assertEquals(409, assertThrows(ServiceException.class,
                    () -> service.reconcileWechatBillAgainstRegistrations(new Date())).getCode());
        }
        org.mockito.Mockito.verify(reconcileMapper, org.mockito.Mockito.never())
                .selectPaidWechatRegistrationsByBillDate(any());
    }

    @Test void aCompleteZeroRowBillStillDetectsAnInternalPaymentMissingFromTheBill() {
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Shanghai");
        Date billDate = java.sql.Date.valueOf("2026-10-10");
        Date day = Date.from(java.time.LocalDate.of(2026, 10, 10).atStartOfDay(zone).toInstant());
        WxBillImport empty = new WxBillImport();
        empty.setStatus("SUCCESS"); empty.setRowCount(0);
        empty.setSourceHash("0123456789abcdef0123456789abcdef01234567");
        when(wxBillImportMapper.selectImport("test-merchant", "TRADE", day)).thenReturn(empty);
        CmsRegistration paid = new CmsRegistration();
        paid.setRegistrationNo("REG-MISSING-BILL"); paid.setWechatPaymentAmount(new BigDecimal("10.00"));
        when(reconcileMapper.selectPaidWechatRegistrationsByBillDate(day)).thenReturn(List.of(paid));
        List<LedgerDiscrepancy> differences = service.reconcileWechatBillAgainstRegistrations(billDate);
        assertEquals(1, differences.size());
        assertEquals("REG-MISSING-BILL", differences.get(0).getBizKey());
        assertEquals(0, BigDecimal.ZERO.compareTo(differences.get(0).getActualAmount()));
        org.mockito.Mockito.verify(wxBillImportMapper).selectImport("test-merchant", "TRADE", day);
        org.mockito.Mockito.verify(wxTradeBillSnapshotMapper, org.mockito.Mockito.never()).countByBillDate(any());
    }

    @Test void aNonMidnightInputUsesTheSameBeijingDayForTheBillAndInternalPayments() {
        Date noon = Date.from(java.time.Instant.parse("2026-10-10T04:00:00Z"));
        Date day = Date.from(java.time.Instant.parse("2026-10-09T16:00:00Z"));
        when(reconcileMapper.selectPaidWechatRegistrationsByBillDate(day)).thenReturn(List.of());
        assertTrue(service.reconcileWechatBillAgainstRegistrations(noon).isEmpty());
        org.mockito.Mockito.verify(wxBillImportMapper).selectImport("test-merchant", "TRADE", day);
        org.mockito.Mockito.verify(reconcileMapper).selectPaidWechatRegistrationsByBillDate(day);
        org.mockito.Mockito.verify(reconcileMapper, org.mockito.Mockito.never())
                .selectPaidWechatRegistrationsByBillDate(noon);
    }

    @Test void aSqlCalendarDateDoesNotMoveBackADayOnAHostAheadOfChina() {
        java.util.TimeZone original = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Auckland"));
            Date calendarDay = java.sql.Date.valueOf("2026-10-10");
            Date day = Date.from(java.time.Instant.parse("2026-10-09T16:00:00Z"));
            when(reconcileMapper.selectPaidWechatRegistrationsByBillDate(day)).thenReturn(List.of());
            assertTrue(service.reconcileWechatBillAgainstRegistrations(calendarDay).isEmpty());
            org.mockito.Mockito.verify(wxBillImportMapper).selectImport("test-merchant", "TRADE", day);
            org.mockito.Mockito.verify(reconcileMapper).selectPaidWechatRegistrationsByBillDate(day);
        } finally {
            java.util.TimeZone.setDefault(original);
        }
    }

    /**
     * 🔴 负控:账单已下载,但某笔报名的微信支付金额与账单里对应订单号的支付成功总额对不上
     * (比如漏下载了一行,或商户订单号匹配失败)⇒ 必须被检出,不能被吞掉。
     */
    @Test
    void wechatBill_flagsMismatch_whenBillDataExistsButAmountDiffers() {
        Date billDate = new Date();
        when(wxTradeBillSnapshotMapper.countByBillDate(billDate)).thenReturn(5);

        CmsRegistration r = new CmsRegistration();
        r.setId(1L);
        r.setRegistrationNo("REG20260725001");
        r.setWechatPaymentAmount(new BigDecimal("100.00"));
        when(reconcileMapper.selectPaidWechatRegistrationsByBillDate(any(Date.class))).thenReturn(Arrays.asList(r));

        // 账单里这笔订单号只找到 80 元(比如另一笔 20 元支付成功记录漏下载了)
        when(wxTradeBillSnapshotMapper.sumAmountByOutTradeNoAndBizType(
                eq("REG20260725001"), eq(WxTradeBillSnapshot.BIZ_TYPE_PAYMENT)))
                .thenReturn(new BigDecimal("80.00"));

        List<LedgerDiscrepancy> result = service.reconcileWechatBillAgainstRegistrations(billDate);

        assertEquals(1, result.size(), "对不上的一笔必须被检出,不能被吞掉");
        LedgerDiscrepancy d = result.get(0);
        assertEquals("REG20260725001", d.getBizKey());
        assertEquals(0, new BigDecimal("100.00").compareTo(d.getExpectedAmount()));
        assertEquals(0, new BigDecimal("80.00").compareTo(d.getActualAmount()));
        assertEquals(0, new BigDecimal("-20.00").compareTo(d.getDeltaAmount()), "差额方向必须保留,少了20元");
    }

    /** 账单齐全且金额一致 ⇒ 不产出差异,不能反过来误报正常订单。 */
    @Test
    void wechatBill_noDiscrepancy_whenAmountsMatch() {
        Date billDate = new Date();
        when(wxTradeBillSnapshotMapper.countByBillDate(billDate)).thenReturn(3);

        CmsRegistration r = new CmsRegistration();
        r.setRegistrationNo("REG2");
        r.setWechatPaymentAmount(new BigDecimal("50.00"));
        when(reconcileMapper.selectPaidWechatRegistrationsByBillDate(any(Date.class))).thenReturn(Arrays.asList(r));
        when(wxTradeBillSnapshotMapper.sumAmountByOutTradeNoAndBizType(eq("REG2"), anyInt()))
                .thenReturn(new BigDecimal("50.00"));

        assertTrue(service.reconcileWechatBillAgainstRegistrations(billDate).isEmpty());
    }

    // ------------------------------------------------- 维度二:内部资金流水自洽

    /**
     * 🔴 负控(对应任务书要求的"报名有支付记录但内部资金流水没有对应记录"场景):
     * 用户当前余额快照显示已经到账 100 元(比如某笔报名触发的分润 addBalance 已执行),
     * 但 ums_member_balance_detail 里一行流水都没有(该笔分润漏插了明细行,或者插入事务被静默回滚)
     * ⇒ sum(收入)-sum(支出)=0,与快照 100 不符,必须被检出。
     */
    @Test
    void memberBalance_flagsMismatch_whenDetailMissingForCreditedBalance() {
        Long memberId = 900L;
        UmsMember member = new UmsMember();
        member.setBalance(new BigDecimal("100.00"));
        when(umsMemberService.selectUmsMemberById(memberId)).thenReturn(member);
        when(reconcileMapper.sumBalanceIncomeByMember(memberId)).thenReturn(null); // 一行流水都没有
        when(reconcileMapper.sumBalanceExpenseByMember(memberId)).thenReturn(null);

        List<LedgerDiscrepancy> result = service.reconcileMemberBalanceConservation(memberId);

        assertEquals(1, result.size(), "余额已到账但流水缺失,必须被检出,不能被吞掉");
        LedgerDiscrepancy d = result.get(0);
        assertEquals(0, BigDecimal.ZERO.compareTo(d.getExpectedAmount()), "流水侧算出的净额应为0");
        assertEquals(0, new BigDecimal("100.00").compareTo(d.getActualAmount()), "快照侧仍是100");
    }

    /** 收入-支出与快照一致 ⇒ 不产出差异。 */
    @Test
    void memberBalance_noDiscrepancy_whenConserved() {
        Long memberId = 901L;
        UmsMember member = new UmsMember();
        member.setBalance(new BigDecimal("30.00"));
        when(umsMemberService.selectUmsMemberById(memberId)).thenReturn(member);
        when(reconcileMapper.sumBalanceIncomeByMember(memberId)).thenReturn(new BigDecimal("50.00"));
        when(reconcileMapper.sumBalanceExpenseByMember(memberId)).thenReturn(new BigDecimal("20.00"));

        assertTrue(service.reconcileMemberBalanceConservation(memberId).isEmpty());
    }

    /** ★ 舍入容差内的极小误差不应被判定为差异,否则每次对账都会被分单元噪音刷屏。 */
    @Test
    void memberBalance_noDiscrepancy_withinRoundingTolerance() {
        Long memberId = 902L;
        UmsMember member = new UmsMember();
        member.setBalance(new BigDecimal("10.005"));
        when(umsMemberService.selectUmsMemberById(memberId)).thenReturn(member);
        when(reconcileMapper.sumBalanceIncomeByMember(memberId)).thenReturn(new BigDecimal("10.00"));
        when(reconcileMapper.sumBalanceExpenseByMember(memberId)).thenReturn(BigDecimal.ZERO);

        assertTrue(service.reconcileMemberBalanceConservation(memberId).isEmpty());
    }

    /** 用户不存在(如已注销或ID错误)⇒ 无法比对,不产出差异也不抛异常,静默跳过这一条。 */
    @Test
    void memberBalance_returnsEmpty_whenMemberNotFound() {
        when(umsMemberService.selectUmsMemberById(903L)).thenReturn(null);

        assertTrue(service.reconcileMemberBalanceConservation(903L).isEmpty());
    }

    // ------------------------------------------------- 维度三:商家结算真源批次完整性

    /**
     * 🔴 负控:批次生成时冻结净额=100(状态已 PAID,钱已经打出去了),但生成批次之后
     * 又有一笔迟到的冲正(REVERSAL)落进同一账期,导致当前 merchant_payable 净额变成 130
     * ⇒ 账实不符,必须被检出,尤其因为批次已经 PAID(钱已经打出去了,不能悄悄改批次金额)。
     */
    @Test
    void merchantPayable_flagsDrift_whenNetChangedAfterFreeze() {
        Long merchant = 500L;
        String period = "2026-06";
        MerchantPayableBatch batch = new MerchantPayableBatch();
        batch.setMerchantMemberId(merchant);
        batch.setPeriodYm(period);
        batch.setAmountTotal(new BigDecimal("100.00"));
        batch.setStatus(MerchantPayableBatch.STATUS_PAID);
        when(merchantPayableBatchMapper.selectByMerchantAndPeriod(merchant, period)).thenReturn(batch);
        when(merchantPayableService.netPayable(merchant, period)).thenReturn(new BigDecimal("130.00"));

        List<LedgerDiscrepancy> result = service.reconcileMerchantPayableBatchIntegrity(merchant, period);

        assertEquals(1, result.size(), "批次冻结值与当前净额不一致必须被检出");
        LedgerDiscrepancy d = result.get(0);
        assertEquals(0, new BigDecimal("100.00").compareTo(d.getExpectedAmount()));
        assertEquals(0, new BigDecimal("130.00").compareTo(d.getActualAmount()));
        assertTrue(d.getReason().contains("PAID"), "批次已打款时的偏差原因必须点名批次状态,提示这是更紧急的情况");
    }

    /** 尚未生成批次 ⇒ 不视为差异(还没到结算时点)。 */
    @Test
    void merchantPayable_noDiscrepancy_whenBatchNotYetGenerated() {
        when(merchantPayableBatchMapper.selectByMerchantAndPeriod(anyLong(), anyString())).thenReturn(null);

        assertTrue(service.reconcileMerchantPayableBatchIntegrity(500L, "2026-07").isEmpty());
    }

    /** 批次冻结值与当前净额一致 ⇒ 不产出差异。 */
    @Test
    void merchantPayable_noDiscrepancy_whenFrozenMatchesCurrent() {
        Long merchant = 501L;
        String period = "2026-06";
        MerchantPayableBatch batch = new MerchantPayableBatch();
        batch.setAmountTotal(new BigDecimal("45.00"));
        batch.setStatus(MerchantPayableBatch.STATUS_CONFIRMED);
        when(merchantPayableBatchMapper.selectByMerchantAndPeriod(merchant, period)).thenReturn(batch);
        when(merchantPayableService.netPayable(merchant, period)).thenReturn(new BigDecimal("45.00"));

        assertTrue(service.reconcileMerchantPayableBatchIntegrity(merchant, period).isEmpty());
    }

    // ------------------------------------------------- 边界:null 入参不抛异常

    @Test
    void nullArguments_areNoOps() {
        assertTrue(service.reconcileWechatBillAgainstRegistrations(null).isEmpty());
        assertTrue(service.reconcileMemberBalanceConservation(null).isEmpty());
        assertTrue(service.reconcileMerchantPayableBatchIntegrity(null, "2026-07").isEmpty());
        assertTrue(service.reconcileMerchantPayableBatchIntegrity(500L, null).isEmpty());
    }
}
