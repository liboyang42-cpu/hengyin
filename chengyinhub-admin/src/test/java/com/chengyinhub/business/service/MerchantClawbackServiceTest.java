package com.chengyinhub.business.service;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.chengyinhub.business.domain.MerchantClawbackApplication;
import com.chengyinhub.business.domain.MerchantPayableBatch;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UmsMemberBalanceDetail;
import com.chengyinhub.business.mapper.MerchantClawbackApplicationMapper;
import com.chengyinhub.business.mapper.MerchantPayableBatchMapper;
import com.chengyinhub.business.mapper.UmsMemberBalanceDetailMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.impl.MerchantClawbackServiceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M块商家违规倒扣:发起路径判定 + 双人复核 + 两条结算路径的 CAS/负控。
 *
 * <p>⚠️ mapper 全 mock,SQL 口径(selectNextUnpaidBatch 按 period_ym 排序对不对、
 * deductForClawback 的 status 条件对不对)一概证不了,只有真库能验。本用例只管
 * 「service 层拿到 mapper 返回值之后,该走哪条路径、闸开不开、状态怎么收口」。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MerchantClawbackServiceTest {

    private static final long MERCHANT = 500L;
    private static final long BATCH_ID = 9L;
    private static final long APP_ID = 77L;
    private static final String INITIATOR = "ops_a";
    private static final String REVIEWER = "ops_b";

    @Mock
    private MerchantClawbackApplicationMapper applicationMapper;

    @Mock
    private MerchantPayableBatchMapper batchMapper;

    @Mock
    private IUmsMemberService umsMemberService;

    @Mock
    private UmsMemberMapper umsMemberMapper;

    @Mock
    private UmsMemberBalanceDetailMapper balanceDetailMapper;

    @InjectMocks
    private MerchantClawbackServiceImpl service;

    private MerchantPayableBatch pendingBatch() {
        MerchantPayableBatch b = new MerchantPayableBatch();
        b.setId(BATCH_ID);
        b.setMerchantMemberId(MERCHANT);
        b.setPeriodYm("2026-07");
        b.setAmountTotal(new BigDecimal("100.00"));
        b.setStatus(MerchantPayableBatch.STATUS_PENDING);
        return b;
    }

    private MerchantClawbackApplication pendingApplication(String settlePath, Long targetBatchId) {
        MerchantClawbackApplication a = new MerchantClawbackApplication();
        a.setId(APP_ID);
        a.setMerchantMemberId(MERCHANT);
        a.setReason("客诉工单#123 查实虚假核销");
        a.setAmount(new BigDecimal("50.00"));
        a.setSettlePath(settlePath);
        a.setTargetBatchId(targetBatchId);
        a.setStatus(MerchantClawbackApplication.STATUS_PENDING_REVIEW);
        a.setInitiatedBy(INITIATOR);
        return a;
    }

    // ==================== 发起:路径判定 ====================

    @Test
    void initiate_choosesOffsetBatch_whenUnpaidBatchExists() {
        when(batchMapper.selectNextUnpaidBatch(MERCHANT)).thenReturn(pendingBatch());

        MerchantClawbackApplication a = service.initiate(MERCHANT, "违规原因", new BigDecimal("50.00"), INITIATOR);

        assertEquals(MerchantClawbackApplication.SETTLE_PATH_OFFSET_BATCH, a.getSettlePath());
        assertEquals(Long.valueOf(BATCH_ID), a.getTargetBatchId());
        assertEquals(MerchantClawbackApplication.STATUS_PENDING_REVIEW, a.getStatus());
        verify(applicationMapper).insertApplication(a);
    }

    @Test
    void initiate_choosesBalance_whenNoUnpaidBatch() {
        when(batchMapper.selectNextUnpaidBatch(MERCHANT)).thenReturn(null);

        MerchantClawbackApplication a = service.initiate(MERCHANT, "违规原因", new BigDecimal("50.00"), INITIATOR);

        assertEquals(MerchantClawbackApplication.SETTLE_PATH_BALANCE, a.getSettlePath());
        assertEquals(null, a.getTargetBatchId());
    }

    @Test
    void initiate_rejects_whenAmountNotPositive() {
        assertThrows(IllegalArgumentException.class,
                () -> service.initiate(MERCHANT, "原因", new BigDecimal("0.00"), INITIATOR));
        assertThrows(IllegalArgumentException.class,
                () -> service.initiate(MERCHANT, "原因", new BigDecimal("-10.00"), INITIATOR));
        verify(applicationMapper, never()).insertApplication(any());
    }

    @Test
    void initiate_rejects_whenReasonBlank() {
        assertThrows(IllegalArgumentException.class,
                () -> service.initiate(MERCHANT, "   ", new BigDecimal("50.00"), INITIATOR));
        verify(applicationMapper, never()).insertApplication(any());
    }

    @Test
    void initiate_rejects_whenOperatorMissing() {
        assertThrows(IllegalArgumentException.class,
                () -> service.initiate(MERCHANT, "原因", new BigDecimal("50.00"), " "));
    }

    // ==================== 复核:双人复核闸 ====================

    /**
     * ★ 负控核心:复核人与发起人相同必须被拒,且拒绝发生在任何扣款动作之前
     * (batchMapper 一次都不能被碰)。
     *
     * <p>⚠️ 别只 assertThrows——若 maker-checker 闸被删掉,执行会一路走到
     * {@code executeOffsetBatch} 并正常返回 {@code true}(因为其余前置条件都已满足),
     * 不会抛任何异常。用「其余闸全部放行」的搭建方式,让这条断言只对 maker-checker 一处敏感。
     */
    @Test
    void confirmExecute_rejects_whenReviewerSameAsInitiator() {
        when(applicationMapper.selectById(APP_ID))
                .thenReturn(pendingApplication(MerchantClawbackApplication.SETTLE_PATH_OFFSET_BATCH, BATCH_ID));
        when(batchMapper.deductForClawback(BATCH_ID, new BigDecimal("50.00"))).thenReturn(1);
        when(applicationMapper.markExecutedIfPending(eq(APP_ID), eq(INITIATOR), any(), any())).thenReturn(1);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.confirmExecute(APP_ID, INITIATOR));
        assertEquals("复核人不能与提交人为同一人(双人复核)", ex.getMessage());

        verify(batchMapper, never()).deductForClawback(anyLong(), any());
        verify(applicationMapper, never()).markExecutedIfPending(anyLong(), any(), any(), any());
    }

    @Test
    void confirmExecute_returnsFalse_whenAlreadyExecuted() {
        MerchantClawbackApplication a = pendingApplication(MerchantClawbackApplication.SETTLE_PATH_BALANCE, null);
        a.setStatus(MerchantClawbackApplication.STATUS_EXECUTED);
        when(applicationMapper.selectById(APP_ID)).thenReturn(a);

        assertFalse(service.confirmExecute(APP_ID, REVIEWER));

        verify(umsMemberMapper, never()).addBalance(anyLong(), any());
    }

    // ==================== 复核执行:OFFSET_BATCH 路径 ====================

    @Test
    void confirmExecute_deductsBatchAmount_onOffsetBatchPath() {
        MerchantClawbackApplication a = pendingApplication(MerchantClawbackApplication.SETTLE_PATH_OFFSET_BATCH, BATCH_ID);
        when(applicationMapper.selectById(APP_ID)).thenReturn(a);
        when(batchMapper.deductForClawback(BATCH_ID, new BigDecimal("50.00"))).thenReturn(1);
        when(applicationMapper.markExecutedIfPending(eq(APP_ID), eq(REVIEWER), any(), any())).thenReturn(1);

        assertTrue(service.confirmExecute(APP_ID, REVIEWER));

        verify(batchMapper).deductForClawback(BATCH_ID, new BigDecimal("50.00"));
        verify(umsMemberMapper, never()).addBalance(anyLong(), any());
        verify(applicationMapper).markExecutedIfPending(eq(APP_ID), eq(REVIEWER), any(), any());
    }

    /**
     * ★ 负控:发起复核之间目标批次已不可冲抵(如已被打款)—— CAS 返回 0,必须抛异常且
     * <b>不能把申请置成 EXECUTED</b>(否则就是「记录说扣了、批次金额其实没动」的假账)。
     */
    @Test
    void confirmExecute_throws_whenTargetBatchNoLongerEligible() {
        MerchantClawbackApplication a = pendingApplication(MerchantClawbackApplication.SETTLE_PATH_OFFSET_BATCH, BATCH_ID);
        when(applicationMapper.selectById(APP_ID)).thenReturn(a);
        when(batchMapper.deductForClawback(BATCH_ID, new BigDecimal("50.00"))).thenReturn(0);   // 已被打款/流转

        assertThrows(IllegalStateException.class, () -> service.confirmExecute(APP_ID, REVIEWER));

        verify(applicationMapper, never()).markExecutedIfPending(anyLong(), any(), any(), any());
    }

    // ==================== 复核执行:BALANCE 兜底路径 ====================

    @Mock
    private com.chengyinhub.business.service.support.SettlementDebtLedger settlementDebtLedger;

    private void givenBalance(String balance) {
        UmsMember merchant = new UmsMember();
        merchant.setId(MERCHANT);
        merchant.setBalance(new BigDecimal(balance));
        when(umsMemberService.selectUmsMemberById(MERCHANT)).thenReturn(merchant);
        // 加锁读的余额才是扣款依据;普通查询给一个不同的旧值,证明实现没有拿快照算账
        UmsMember stale = new UmsMember();
        stale.setId(MERCHANT);
        stale.setBalance(new BigDecimal("999.00"));
        when(umsMemberMapper.selectUmsMemberById(MERCHANT)).thenReturn(stale);
        when(umsMemberMapper.selectBalanceForUpdate(MERCHANT)).thenReturn(new BigDecimal(balance));
        when(umsMemberMapper.deductBalance(eq(MERCHANT), any())).thenReturn(1);
        when(applicationMapper.markExecutedIfPending(eq(APP_ID), eq(REVIEWER), any(), any())).thenReturn(1);
    }

    @Test
    void confirmExecute_deductsBalance_onBalancePath() {
        MerchantClawbackApplication a = pendingApplication(MerchantClawbackApplication.SETTLE_PATH_BALANCE, null);
        when(applicationMapper.selectById(APP_ID)).thenReturn(a);
        givenBalance("80.00");

        assertTrue(service.confirmExecute(APP_ID, REVIEWER));

        verify(batchMapper, never()).deductForClawback(anyLong(), any());
        verify(umsMemberMapper).deductBalance(MERCHANT, new BigDecimal("50.00"));
        verify(umsMemberMapper, never()).addBalance(anyLong(), any());
        ArgumentCaptor<UmsMemberBalanceDetail> captor = ArgumentCaptor.forClass(UmsMemberBalanceDetail.class);
        verify(balanceDetailMapper).insertUmsMemberBalanceDetail(captor.capture());
        assertEquals(new BigDecimal("30.00"), captor.getValue().getAfterBalance());
        assertEquals(new BigDecimal("-50.00"), captor.getValue().getChangeBalance());
        verify(settlementDebtLedger).record(MERCHANT,
                com.chengyinhub.business.service.support.SettlementDebtLedger.SOURCE_CLAWBACK, APP_ID, new BigDecimal("0.00"));
    }

    /**
     * ★ 2026-09-17 总控裁定:余额 20 不够扣 50 ⇒ 只扣到 0,差额 30 记欠款(30 天,逾期禁止发布)。
     * 修复前这里是扣成 -30(平台人工判定的钱既不是欠款、也不触发逾期限制)。
     */
    @Test
    void confirmExecute_insufficientBalance_deductsToZeroAndRecordsDebt() {
        MerchantClawbackApplication a = pendingApplication(MerchantClawbackApplication.SETTLE_PATH_BALANCE, null);
        when(applicationMapper.selectById(APP_ID)).thenReturn(a);
        givenBalance("20.00");

        assertTrue(service.confirmExecute(APP_ID, REVIEWER));

        verify(umsMemberMapper, never()).addBalance(anyLong(), any());
        verify(umsMemberMapper).deductBalance(MERCHANT, new BigDecimal("20.00"));
        ArgumentCaptor<UmsMemberBalanceDetail> captor = ArgumentCaptor.forClass(UmsMemberBalanceDetail.class);
        verify(balanceDetailMapper).insertUmsMemberBalanceDetail(captor.capture());
        assertEquals(0, BigDecimal.ZERO.compareTo(captor.getValue().getAfterBalance()));
        verify(settlementDebtLedger).record(MERCHANT,
                com.chengyinhub.business.service.support.SettlementDebtLedger.SOURCE_CLAWBACK, APP_ID, new BigDecimal("30.00"));
    }

    /** 余额已为 0:一分不扣、不写零额流水,全额记欠款。 */
    @Test
    void confirmExecute_zeroBalance_recordsFullDebtWithoutFlow() {
        MerchantClawbackApplication a = pendingApplication(MerchantClawbackApplication.SETTLE_PATH_BALANCE, null);
        when(applicationMapper.selectById(APP_ID)).thenReturn(a);
        givenBalance("0.00");

        assertTrue(service.confirmExecute(APP_ID, REVIEWER));

        verify(umsMemberMapper, never()).deductBalance(anyLong(), any());
        verify(balanceDetailMapper, never()).insertUmsMemberBalanceDetail(any());
        verify(settlementDebtLedger).record(MERCHANT,
                com.chengyinhub.business.service.support.SettlementDebtLedger.SOURCE_CLAWBACK, APP_ID, new BigDecimal("50.00"));
    }

    @Test
    void confirmExecute_throws_whenMerchantNotFound_onBalancePath() {
        MerchantClawbackApplication a = pendingApplication(MerchantClawbackApplication.SETTLE_PATH_BALANCE, null);
        when(applicationMapper.selectById(APP_ID)).thenReturn(a);
        when(umsMemberService.selectUmsMemberById(MERCHANT)).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> service.confirmExecute(APP_ID, REVIEWER));

        verify(umsMemberMapper, never()).addBalance(anyLong(), any());
        verify(applicationMapper, never()).markExecutedIfPending(anyLong(), any(), any(), any());
    }

    /**
     * ★ 负控:申请 CAS 在扣款之后输了(并发下别人已推进/驳回)—— 必须抛异常触发事务回滚,
     * 不能让「余额已经扣了、申请却停在待复核」的半吊子状态被外部感知为成功。
     */
    @Test
    void confirmExecute_throws_whenApplicationCasLostAfterDeduction() {
        MerchantClawbackApplication a = pendingApplication(MerchantClawbackApplication.SETTLE_PATH_BALANCE, null);
        when(applicationMapper.selectById(APP_ID)).thenReturn(a);
        UmsMember merchant = new UmsMember();
        merchant.setId(MERCHANT);
        merchant.setBalance(new BigDecimal("100.00"));
        when(umsMemberService.selectUmsMemberById(MERCHANT)).thenReturn(merchant);
        when(umsMemberMapper.selectBalanceForUpdate(MERCHANT)).thenReturn(new BigDecimal("100.00"));
        when(umsMemberMapper.deductBalance(eq(MERCHANT), any())).thenReturn(1);
        when(applicationMapper.markExecutedIfPending(eq(APP_ID), eq(REVIEWER), any(), any())).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.confirmExecute(APP_ID, REVIEWER));
    }

    // ==================== 驳回 ====================

    @Test
    void reject_marksRejected_withoutTouchingMoney() {
        when(applicationMapper.markRejectedIfPending(eq(APP_ID), eq(REVIEWER), any(), eq("查证不实"))).thenReturn(1);

        assertTrue(service.reject(APP_ID, REVIEWER, "查证不实"));

        verify(batchMapper, never()).deductForClawback(anyLong(), any());
        verify(umsMemberMapper, never()).addBalance(anyLong(), any());
    }

    /** 驳回不要求复核人 ≠ 发起人——驳回不移动资金,不是双人复核要卡的动作。 */
    @Test
    void reject_allowsSamePersonAsInitiator() {
        when(applicationMapper.markRejectedIfPending(eq(APP_ID), eq(INITIATOR), any(), any())).thenReturn(1);

        assertTrue(service.reject(APP_ID, INITIATOR, "发起人自己撤回"));
    }

    @Test
    void reject_rejects_whenReasonBlank() {
        assertThrows(IllegalArgumentException.class, () -> service.reject(APP_ID, REVIEWER, " "));
    }

    @Test
    void reject_returnsFalse_whenCasLost() {
        when(applicationMapper.markRejectedIfPending(eq(APP_ID), eq(REVIEWER), any(), any())).thenReturn(0);

        assertFalse(service.reject(APP_ID, REVIEWER, "查证不实"));
    }

    @Test
    void list_delegatesToMapperWithMerchantAndStatusFilter() {
        service.list(MERCHANT, MerchantClawbackApplication.STATUS_PENDING_REVIEW);

        ArgumentCaptor<MerchantClawbackApplication> captor = ArgumentCaptor.forClass(MerchantClawbackApplication.class);
        verify(applicationMapper).selectList(captor.capture());
        assertEquals(Long.valueOf(MERCHANT), captor.getValue().getMerchantMemberId());
        assertEquals(MerchantClawbackApplication.STATUS_PENDING_REVIEW, captor.getValue().getStatus());
    }
}
