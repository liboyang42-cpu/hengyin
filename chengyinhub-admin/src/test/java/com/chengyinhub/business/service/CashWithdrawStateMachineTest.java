package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.MemberCashWithdraw;
import com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.MemberCashWithdrawMapper;
import com.chengyinhub.business.service.impl.AlarmService;
import com.chengyinhub.business.service.impl.CashWithdrawServiceImpl;
import com.chengyinhub.business.service.impl.MerchantReferralBountyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * D-02 提现资金状态机单测(经对抗审查加固)。
 * ★关键:umsMemberService.deductBalance/addBalance 失败时**抛异常**(非返回false),测试按真实语义 mock。
 * 覆盖:已受理≠成功、原子防重、CAS 先抢占杜绝重复退款、扣款抛异常不留僵尸单、退款失败转人工+告警。
 */
class CashWithdrawStateMachineTest {

    @Test
    void manualRefundKeepsClaimAndBalanceMutationInOneTransaction() throws Exception {
        var source = mock(javax.sql.DataSource.class);
        var connection = mock(java.sql.Connection.class);
        when(source.getConnection()).thenReturn(connection);
        var transactions = new org.springframework.jdbc.datasource.DataSourceTransactionManager(source);
        ReflectionTestUtils.setField(svc, "transactionManager", transactions);
        MemberCashWithdraw original = pendingRecord();
        original.setStatus(MemberCashWithdraw.STATUS_FAILED_MANUAL);
        when(mapper.selectById(1L)).thenReturn(original);
        CashWithdrawTransferIntent snapshot = transferEvidence.seed(original, true);
        var creditedBalance = new java.util.concurrent.atomic.AtomicReference<>(new BigDecimal("90.00"));
        var claimResource = new java.util.concurrent.atomic.AtomicReference<Object>();
        when(wechatPayService.queryTransferResult(any(CashWithdrawTransferIntent.class))).thenAnswer(call -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),
                    "原渠道查询必须在持有资金行锁的事务之外");
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(source));
            assertEquals(snapshot, call.getArgument(0), "查询只能携带原来源/hash/身份");
            return CashWithdrawIntentTestSupport.facts(snapshot, "FAIL");
        });
        org.mockito.Mockito.doAnswer(call -> {
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            Object resource = org.springframework.transaction.support.TransactionSynchronizationManager.getResource(source);
            assertTrue(resource != null, "退款 CAS 必须持有实际 Spring JDBC 事务资源");
            claimResource.set(resource);
            return evidence.review(call);
        }).when(mapper).updateStatusIfRefundReview(any());
        org.mockito.Mockito.doAnswer(call -> {
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            org.junit.jupiter.api.Assertions.assertSame(claimResource.get(),
                    org.springframework.transaction.support.TransactionSynchronizationManager.getResource(source),
                    "退款 CAS 与余额写必须使用同一事务资源");
            assertEquals(MemberCashWithdraw.STATUS_FAILED_REFUNDED, original.getStatus());
            assertEquals(9L, call.getArgument(0, Long.class));
            assertEquals(0, TEN.compareTo(call.getArgument(1, BigDecimal.class)));
            creditedBalance.updateAndGet(value -> value.add(call.getArgument(1, BigDecimal.class)));
            return true;
        }).when(umsMemberService).addBalance(anyLong(), any());

        assertTrue(svc.manualRefund(1L, "maker", "原失败证据"));
        assertEquals(MemberCashWithdraw.STATUS_REFUND_PENDING_REVIEW, original.getStatus());
        assertEquals(0, new BigDecimal("90.00").compareTo(creditedBalance.get()));
        verify(umsMemberService, never()).addBalance(anyLong(), any());
        assertTrue(svc.manualRefund(1L, "checker", "复核", MemberCashWithdraw.STATUS_REFUND_PENDING_REVIEW));

        assertEquals(MemberCashWithdraw.STATUS_FAILED_REFUNDED, original.getStatus());
        assertEquals(0, new BigDecimal("100.00").compareTo(creditedBalance.get()));
        var order = org.mockito.Mockito.inOrder(mapper, umsMemberService);
        order.verify(mapper).updateStatusIfRefundReview(any());
        order.verify(umsMemberService).addBalance(9L, TEN);
        verify(umsMemberService, times(1)).addBalance(9L, TEN);
        verify(wechatPayService, times(2)).queryTransferResult(snapshot);
        verify(wechatPayService, never()).transferToBalance(any(CashWithdrawTransferIntent.class));
        verify(mapper, never()).insertFundFact(any());
        assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
        assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(source));
        // Mock JDBC proves Spring resource/phase wiring; real SQL rollback remains owned by the MySQL IT.
    }

    private MemberCashWithdrawMapper mapper;
    private IUmsMemberService umsMemberService;
    private IWechatPayService wechatPayService;
    private AlarmService alarmService;
    private MerchantReferralBountyService merchantReferralBountyService;
    private CashWithdrawServiceImpl svc;
    private com.chengyinhub.business.service.impl.CashWithdrawEvidenceFixture evidence;

    private static final BigDecimal TEN = new BigDecimal("10.00");

    private com.chengyinhub.business.service.CashWithdrawIntentTestSupport transferEvidence;

    private Map<String, String> dispatchResult(Map<String, String> response, CashWithdrawTransferIntent intent) {
        Map<String, String> result = new HashMap<>(response);
        if ("false".equals(result.get("ok")) && "false".equals(result.get("ambiguous"))) {
            result.put("notSent", "true"); result.put("requestHash", intent.requestHash());
        }
        return result;
    }

    @BeforeEach
    void setUp() {
        mapper = mock(MemberCashWithdrawMapper.class);
        umsMemberService = mock(IUmsMemberService.class);
        wechatPayService = mock(IWechatPayService.class);
        alarmService = mock(AlarmService.class);
        merchantReferralBountyService = mock(MerchantReferralBountyService.class);
        svc = new CashWithdrawServiceImpl();
        transferEvidence = new com.chengyinhub.business.service.CashWithdrawIntentTestSupport();
        ReflectionTestUtils.setField(svc, "transferIntents", transferEvidence.mapper);
        org.springframework.transaction.PlatformTransactionManager transactions = mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(mock(org.springframework.transaction.TransactionStatus.class));
        ReflectionTestUtils.setField(svc, "transactionManager", transactions);
        when(mapper.updateStatusIfManual(any())).thenReturn(1);
        ReflectionTestUtils.setField(svc, "mapper", mapper);
        ReflectionTestUtils.setField(svc, "umsMemberService", umsMemberService);
        ReflectionTestUtils.setField(svc, "wechatPayService", wechatPayService);
        { com.chengyinhub.common.config.WxPayConfig __cfg = org.mockito.Mockito.mock(com.chengyinhub.common.config.WxPayConfig.class);
          org.mockito.Mockito.when(__cfg.getAppid()).thenReturn("wx-mp-appid");
          org.mockito.Mockito.when(__cfg.getMerchantId()).thenReturn("unit-merchant");
          org.mockito.Mockito.when(__cfg.getAppAppid()).thenReturn("wx-app-appid");
          // R1 起 withdraw 要按来源选同源 appid,不注入这个新依赖就是 NPE
          ReflectionTestUtils.setField(svc, "wxPayConfig", __cfg); }
        ReflectionTestUtils.setField(svc, "alarmService", alarmService);
        com.chengyinhub.business.service.IPlayerWithdrawalCooldownService cooldownStub = mock(com.chengyinhub.business.service.IPlayerWithdrawalCooldownService.class);
        when(cooldownStub.frozenAmount(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(BigDecimal.ZERO);
        ReflectionTestUtils.setField(svc, "frozenAmountResolver",
                new com.chengyinhub.business.service.support.WithdrawalFrozenAmountResolver(merchantReferralBountyService, cooldownStub));
        ReflectionTestUtils.setField(svc, "balanceAdmissionService",
                com.chengyinhub.business.longrun.OWN_C04_21Test.cashFixture(umsMemberService, mapper, (com.chengyinhub.business.service.support.WithdrawalFrozenAmountResolver) ReflectionTestUtils.getField(svc, "frozenAmountResolver"), transferEvidence.mapper));
        when(merchantReferralBountyService.frozenAmount(any())).thenReturn(BigDecimal.ZERO);
        when(mapper.selectById(1L)).thenReturn(pendingRecord());
        when(mapper.updateMemberCashWithdraw(any())).thenReturn(1);
        when(mapper.updateReceiptIfProcessing(any())).thenReturn(1);
        when(mapper.insertMemberCashWithdraw(any())).thenAnswer(inv -> {
            ((MemberCashWithdraw) inv.getArgument(0)).setId(1L);
            transferEvidence.forget(1L);
            return 1;
        });
        evidence = new com.chengyinhub.business.service.impl.CashWithdrawEvidenceFixture(mapper);
        when(umsMemberService.addBalance(anyLong(), any())).thenReturn(true);
    }

    private void stubMemberWithOpenid() {
        UmsMember m = new UmsMember();
        m.setId(9L);
        m.setWxOpenId("oABC123");
        m.setBalance(new BigDecimal("100"));
        when(umsMemberService.selectUmsMemberById(9L)).thenReturn(m);
    }

    private MemberCashWithdraw pendingRecord() {
        MemberCashWithdraw w = new MemberCashWithdraw();
        w.setId(1L);
        w.setDelFlag(0);
        w.setMemberId(9L); w.setOpenid("oABC123");
        w.setAmount(TEN);
        w.setOutBatchNo("CW1");
        w.setOutDetailNo("CD1");
        w.setStatus(MemberCashWithdraw.STATUS_PROCESSING);
        w.setCreateTime(new Date());   // 新建,isStuck=false
        w.setSubmitTime(new Date());
        return w;
    }

    private Map<String, String> qr(String result, String batchStatus) {
        Map<String, String> m = new HashMap<>();
        m.put("result", result);
        m.put("mchid", "unit-merchant"); m.put("appid", "wx-mp-appid");
        m.put("outBatchNo", "CW1"); m.put("outDetailNo", "CD1");
        m.put("transferAmount", "1000"); m.put("openid", "oABC123");
        m.put("batchId", "provider-batch"); m.put("detailId", "provider-detail");
        if ("SUCCESS".equals(result)) m.put("detailStatus", "SUCCESS");
        if (batchStatus != null) m.put("batchStatus", batchStatus);
        if ("FAIL".equals(result)) m.put("detailStatus", "FAIL");
        return m;
    }

    // ① 已受理不算成功:发起 ok=true(ACCEPTED)→ 置处理中+记录 submitTime,绝不置成功
    @Test
    void accepted_isNotSuccess() {
        when(mapper.countPendingByMember(9L)).thenReturn(0);
        stubMemberWithOpenid();
        when(umsMemberService.deductWithdrawableBalance(eq(9L), any(), any())).thenReturn(true);
        when(mapper.selectById(1L)).thenReturn(pendingRecord());
        Map<String, String> res = new HashMap<>();
        res.put("ok", "true");
        res.put("batchId", "B1");
        res.put("batchStatus", "ACCEPTED");
        when(wechatPayService.transferToBalance(any(CashWithdrawTransferIntent.class))).thenAnswer(call -> dispatchResult(res, call.getArgument(0)));

        MemberCashWithdraw current = svc.withdraw(9L, new BigDecimal("10.00"));
        assertEquals(Integer.valueOf(MemberCashWithdraw.STATUS_PROCESSING), current.getStatus());

        // 绝不置成功(不走终态守卫)
        verify(mapper, never()).updateStatusIfProcessing(any());
        verify(umsMemberService, never()).addBalance(anyLong(), any());
        // 受理只写观察字段，返回的原持久状态仍是处理中
        ArgumentCaptor<MemberCashWithdraw> cap = ArgumentCaptor.forClass(MemberCashWithdraw.class);
        verify(mapper, org.mockito.Mockito.atLeastOnce()).updateReceiptIfProcessing(cap.capture());
        boolean acceptedUpdate = cap.getAllValues().stream().anyMatch(v ->
                v.getSubmitTime() != null && v.getStatus() == null
                        && "B1".equals(v.getTransferBatchId()));
        assertTrue(acceptedUpdate, "受理仅记录批次号与提交时间，不写状态");
    }

    // ② 原子防重:insert 唯一键冲突(DuplicateKey)→ 拦截,不扣款不转账
    @Test
    void duplicateKeyOnInsert_rejected_noDeductNoTransfer() {
        when(mapper.countPendingByMember(9L)).thenReturn(0);
        stubMemberWithOpenid();
        doThrow(new DuplicateKeyException("uk_pending_member")).when(mapper).insertMemberCashWithdraw(any());

        assertThrows(IllegalStateException.class, () -> svc.withdraw(9L, new BigDecimal("10.00")));

        verify(umsMemberService, never()).deductWithdrawableBalance(anyLong(), any(), any());
        verify(wechatPayService, never()).transferToBalance(any(CashWithdrawTransferIntent.class));
    }

    // ③ 软查在途 → 直接拒绝
    @Test
    void softPending_rejected() {
        when(mapper.countPendingByMember(9L)).thenReturn(1);
        assertThrows(IllegalStateException.class, () -> svc.withdraw(9L, new BigDecimal("10.00")));
        verify(mapper, never()).insertMemberCashWithdraw(any());
    }

    // ④ 扣款抛异常(DB 未知异常,余额可能已扣)→ 置待人工(status=3)+告警,不假称已退款,不转账
    @Test
    void deductThrows_marksFailed_noZombie_noTransfer() {
        when(mapper.updateStatusIfProcessing(any())).thenAnswer(evidence::processing);
        when(mapper.countPendingByMember(9L)).thenReturn(0);
        stubMemberWithOpenid();
        doThrow(new RuntimeException("扣款状态不明")).when(umsMemberService).deductWithdrawableBalance(eq(9L), any(), any());

        assertThrows(IllegalStateException.class, () -> svc.withdraw(9L, new BigDecimal("10.00")));

        verify(wechatPayService, never()).transferToBalance(any(CashWithdrawTransferIntent.class));
        // 未知异常 → STATUS_FAILED_MANUAL=3(非 FAILED_REFUNDED),且触发告警
        ArgumentCaptor<MemberCashWithdraw> cap = ArgumentCaptor.forClass(MemberCashWithdraw.class);
        verify(mapper, org.mockito.Mockito.atLeastOnce()).updateStatusIfProcessing(cap.capture());
        boolean markedManual = cap.getAllValues().stream()
                .anyMatch(v -> Integer.valueOf(MemberCashWithdraw.STATUS_FAILED_MANUAL).equals(v.getStatus()));
        assertTrue(markedManual, "扣款异常应置待人工(3),不可假称已退款");
        verify(alarmService, times(1)).alarm(anyString(), anyString());
    }

    @Test
    void sync_success_marksSuccess() {
        when(mapper.selectPendingForQuery(anyInt())).thenReturn(Collections.singletonList(pendingRecord()));
        when(wechatPayService.queryTransferResult(org.mockito.ArgumentMatchers.argThat(i -> i != null && "CW1".equals(i.outBatchNo()) && "CD1".equals(i.outDetailNo())))).thenReturn(qr("SUCCESS", "FINISHED"));
        when(mapper.updateStatusIfProcessing(any())).thenAnswer(evidence::processing);

        evidence.seedSubmissionIntent(1L);
        svc.syncPendingWithdrawals(50);
        ArgumentCaptor<MemberCashWithdraw> cap = ArgumentCaptor.forClass(MemberCashWithdraw.class);
        verify(mapper).confirmChannelSuccess(cap.capture());
        assertEquals(MemberCashWithdraw.STATUS_SUCCESS, cap.getValue().getStatus());
        verify(umsMemberService, never()).addBalance(anyLong(), any());
    }

    @Test
    void sync_success_capturesWechatDetailId() {
        when(mapper.selectPendingForQuery(anyInt())).thenReturn(Collections.singletonList(pendingRecord()));
        Map<String, String> q = qr("SUCCESS", "FINISHED");
        q.put("detailId", "1234567890abcdef");
        when(wechatPayService.queryTransferResult(org.mockito.ArgumentMatchers.argThat(i -> i != null && "CW1".equals(i.outBatchNo()) && "CD1".equals(i.outDetailNo())))).thenReturn(q);
        when(mapper.updateStatusIfProcessing(any())).thenAnswer(evidence::processing);

        evidence.seedSubmissionIntent(1L);
        svc.syncPendingWithdrawals(50);
        ArgumentCaptor<MemberCashWithdraw> cap = ArgumentCaptor.forClass(MemberCashWithdraw.class);
        verify(mapper).confirmChannelSuccess(cap.capture());
        assertEquals("1234567890abcdef", cap.getValue().getWechatDetailId());
    }

    @Test
    void lateSuccessFromManualOrReviewUsesOriginalEvidenceWithoutRefund() {
        for (int state : new int[]{MemberCashWithdraw.STATUS_FAILED_MANUAL, MemberCashWithdraw.STATUS_REFUND_PENDING_REVIEW}) {
            MemberCashWithdraw row = pendingRecord(); row.setStatus(state);
            when(mapper.selectById(1L)).thenReturn(row);
            when(mapper.selectByIdForUpdate(1L)).thenReturn(row);
            transferEvidence.forget(1L);
            com.chengyinhub.business.longrun.OWN_C04_21Test.seedSnapshot(transferEvidence, row,
                    "unit-merchant", "wx-mp-appid", true);
            when(wechatPayService.queryTransferResult(any(CashWithdrawTransferIntent.class))).thenReturn(qr("SUCCESS", "FINISHED"));
            when(mapper.updateSuccessIfPending(any())).thenAnswer(call -> { row.setStatus(MemberCashWithdraw.STATUS_SUCCESS); return 1; });
            assertEquals(MemberCashWithdraw.STATUS_SUCCESS, svc.requery(1L).getStatus());
        }
        verify(mapper, times(2)).updateSuccessIfPending(any());
        verify(umsMemberService, never()).addBalance(anyLong(), any());
    }

    @Test
    void lostOriginalSubmissionClaimNeverInvokesTheProviderOrRefunds() {
        stubMemberWithOpenid();
        when(umsMemberService.deductWithdrawableBalance(eq(9L), any(), any())).thenReturn(true);
        when(transferEvidence.mapper.markSubmitted(anyLong(), anyString())).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> svc.withdraw(9L, TEN));
        verify(wechatPayService, never()).transferToBalance(any(CashWithdrawTransferIntent.class));
        verify(umsMemberService, never()).addBalance(anyLong(), any());
        verify(umsMemberService).deductWithdrawableBalance(9L, TEN, BigDecimal.ZERO);
    }

    // ⑥ 渠道失败退款一次:CAS 抢占(0→3)成功→退款→3→2。addBalance 恰一次,无补偿 deduct
    @Test
    void sync_fail_claimThenRefundOnce() {
        when(mapper.selectPendingForQuery(anyInt())).thenReturn(Collections.singletonList(pendingRecord()));
        when(wechatPayService.queryTransferResult(org.mockito.ArgumentMatchers.argThat(i -> i != null && "CW1".equals(i.outBatchNo()) && "CD1".equals(i.outDetailNo())))).thenReturn(qr("FAIL", "FINISHED"));
        when(mapper.updateStatusIfProcessing(any())).thenAnswer(evidence::processing); // 抢占成功
        // addBalance 成功(不抛异常)

        evidence.seedSubmissionIntent(1L);
        svc.syncPendingWithdrawals(50);

        verify(umsMemberService, times(1)).addBalance(9L, TEN);
        verify(umsMemberService, never()).deductBalance(anyLong(), any()); // ★不再有补偿撤销
        // 抢占置3,退款成功后置2
        ArgumentCaptor<MemberCashWithdraw> claim = ArgumentCaptor.forClass(MemberCashWithdraw.class);
        verify(mapper).updateStatusIfProcessing(claim.capture());
        assertEquals(MemberCashWithdraw.STATUS_FAILED_MANUAL, claim.getValue().getStatus());
        ArgumentCaptor<MemberCashWithdraw> fin = ArgumentCaptor.forClass(MemberCashWithdraw.class);
        verify(mapper).updateStatusIfManual(fin.capture());
        assertTrue(fin.getAllValues().stream()
                .anyMatch(v -> Integer.valueOf(MemberCashWithdraw.STATUS_FAILED_REFUNDED).equals(v.getStatus())));
    }

    // P1-6 L块:渠道错误码(channel_error_code)查询到后必须落库,不能丢弃
    @Test
    void sync_fail_capturesChannelErrorCode() {
        when(mapper.selectPendingForQuery(anyInt())).thenReturn(Collections.singletonList(pendingRecord()));
        Map<String, String> q = qr("FAIL", "FINISHED");
        q.put("failReasonCode", "REAL_NAME_CHECK_FAIL");
        q.put("detailId", "detail-9");
        when(wechatPayService.queryTransferResult(org.mockito.ArgumentMatchers.argThat(i -> i != null && "CW1".equals(i.outBatchNo()) && "CD1".equals(i.outDetailNo())))).thenReturn(q);
        when(mapper.updateStatusIfProcessing(any())).thenAnswer(evidence::processing); // 抢占成功

        evidence.seedSubmissionIntent(1L);
        svc.syncPendingWithdrawals(50);

        ArgumentCaptor<MemberCashWithdraw> claim = ArgumentCaptor.forClass(MemberCashWithdraw.class);
        verify(mapper).updateStatusIfProcessing(claim.capture());
        assertEquals("REAL_NAME_CHECK_FAIL", claim.getValue().getChannelErrorCode());
        assertEquals("detail-9", claim.getValue().getWechatDetailId());
    }

    // ⑦ 失败抢占败(并发已被处理)→ 直接返回,绝不退款
    @Test
    void sync_fail_lostClaim_noRefund() {
        when(mapper.selectPendingForQuery(anyInt())).thenReturn(Collections.singletonList(pendingRecord()));
        when(wechatPayService.queryTransferResult(org.mockito.ArgumentMatchers.argThat(i -> i != null && "CW1".equals(i.outBatchNo()) && "CD1".equals(i.outDetailNo())))).thenReturn(qr("FAIL", "FINISHED"));
        when(mapper.updateStatusIfProcessing(any())).thenReturn(0); // 抢占失败:别人已处理

        evidence.seedSubmissionIntent(1L);
        svc.syncPendingWithdrawals(50);

        verify(umsMemberService, never()).addBalance(anyLong(), any()); // ★杜绝重复退款
        verify(umsMemberService, never()).deductBalance(anyLong(), any());
    }

    // 已持原单行锁后 3→2 CAS 意外落空：不加余额、抛出回滚，并告警调查。
    @Test
    void sync_fail_refundClaimLost_failsClosedAndAlarms() {
        when(mapper.selectPendingForQuery(anyInt())).thenReturn(Collections.singletonList(pendingRecord()));
        when(wechatPayService.queryTransferResult(org.mockito.ArgumentMatchers.argThat(i -> i != null && "CW1".equals(i.outBatchNo()) && "CD1".equals(i.outDetailNo())))).thenReturn(qr("FAIL", "FINISHED"));
        when(mapper.updateStatusIfProcessing(any())).thenAnswer(evidence::processing); // 抢占成功
        when(mapper.updateStatusIfManual(any())).thenReturn(0);     // 回补领取输掉

        evidence.seedSubmissionIntent(1L);
        svc.syncPendingWithdrawals(50);

        verify(umsMemberService, never()).addBalance(anyLong(), any());
        verify(umsMemberService, never()).deductBalance(anyLong(), any());
        verify(alarmService).alarm(anyString(), anyString());
        verify(mapper, times(1)).updateStatusIfProcessing(any());
        ArgumentCaptor<MemberCashWithdraw> fin = ArgumentCaptor.forClass(MemberCashWithdraw.class);
        verify(mapper, times(2)).updateStatusIfManual(fin.capture());
        assertEquals(MemberCashWithdraw.STATUS_FAILED_REFUNDED, fin.getAllValues().get(0).getStatus());
    }

    // ⑧ 退款失败(addBalance 抛异常,真实语义)→ 保持待人工 + 告警
    @Test
    void sync_fail_refundThrows_manualAndAlarm() {
        when(mapper.selectPendingForQuery(anyInt())).thenReturn(Collections.singletonList(pendingRecord()));
        when(wechatPayService.queryTransferResult(org.mockito.ArgumentMatchers.argThat(i -> i != null && "CW1".equals(i.outBatchNo()) && "CD1".equals(i.outDetailNo())))).thenReturn(qr("FAIL", "FINISHED"));
        when(mapper.updateStatusIfProcessing(any())).thenAnswer(evidence::processing); // 抢占成功
        doThrow(new RuntimeException("增加余额失败")).when(umsMemberService).addBalance(eq(9L), any());

        evidence.seedSubmissionIntent(1L);
        svc.syncPendingWithdrawals(50);

        verify(alarmService, times(1)).alarm(anyString(), anyString());
        // 抢占已置3(待人工),退款失败保持3
        ArgumentCaptor<MemberCashWithdraw> claim = ArgumentCaptor.forClass(MemberCashWithdraw.class);
        verify(mapper).updateStatusIfProcessing(claim.capture());
        assertEquals(MemberCashWithdraw.STATUS_FAILED_MANUAL, claim.getValue().getStatus());
    }

    // ⑨ UNKNOWN 决不误判失败:保持处理中,不退款不置终态(未超时)
    @Test
    void sync_unknown_staysProcessing_noRefund() {
        when(mapper.selectPendingForQuery(anyInt())).thenReturn(Collections.singletonList(pendingRecord()));
        when(wechatPayService.queryTransferResult(org.mockito.ArgumentMatchers.argThat(i -> i != null && "CW1".equals(i.outBatchNo()) && "CD1".equals(i.outDetailNo())))).thenReturn(qr("UNKNOWN", null));

        evidence.seedSubmissionIntent(1L);
        svc.syncPendingWithdrawals(50);

        verify(umsMemberService, never()).addBalance(anyLong(), any());
        verify(mapper, never()).updateStatusIfProcessing(any()); // 未超时不升级
        verify(mapper, times(1)).updateReceiptIfProcessing(any());
    }

    // ⑩ 超时兜底:处理中过久(submitTime 很旧)+ 仍 UNKNOWN → CAS 升级人工 + 告警
    @Test
    void sync_stuckTooLong_escalatesToManual() {
        MemberCashWithdraw old = pendingRecord();
        old.setSubmitTime(new Date(System.currentTimeMillis() - 7L * 3600_000L)); // 7h 前
        old.setCreateTime(old.getSubmitTime());
        when(mapper.selectPendingForQuery(anyInt())).thenReturn(Collections.singletonList(old));
        when(mapper.selectById(1L)).thenReturn(old); // Recovery reloads the current persisted receipt under its lock.
        when(wechatPayService.queryTransferResult(org.mockito.ArgumentMatchers.argThat(i -> i != null && "CW1".equals(i.outBatchNo()) && "CD1".equals(i.outDetailNo())))).thenReturn(qr("UNKNOWN", null));
        when(mapper.updateStatusIfProcessing(any())).thenAnswer(evidence::processing);

        evidence.seedSubmissionIntent(1L);
        svc.syncPendingWithdrawals(50);

        ArgumentCaptor<MemberCashWithdraw> cap = ArgumentCaptor.forClass(MemberCashWithdraw.class);
        verify(mapper).updateStatusIfProcessing(cap.capture());
        assertEquals(MemberCashWithdraw.STATUS_FAILED_MANUAL, cap.getValue().getStatus());
        verify(alarmService, times(1)).alarm(anyString(), anyString());
    }

    // ⑪ 发起失败退款一次:ok=false 且退款成功 → 抛错且已退款
    @Test
    void initiateFail_refundOnce() {
        when(mapper.countPendingByMember(9L)).thenReturn(0);
        stubMemberWithOpenid();
        when(umsMemberService.deductWithdrawableBalance(eq(9L), any(), any())).thenReturn(true);
        Map<String, String> res = new HashMap<>();
        res.put("ok", "false");
        res.put("ambiguous", "false");
        res.put("error", "转账服务未就绪");
        when(wechatPayService.transferToBalance(any(CashWithdrawTransferIntent.class))).thenAnswer(call -> dispatchResult(res, call.getArgument(0)));
        when(mapper.updateStatusIfProcessing(any())).thenAnswer(evidence::processing);
        // deductBalance 成功(不抛),addBalance 成功(不抛)

        assertThrows(IllegalStateException.class, () -> svc.withdraw(9L, new BigDecimal("10.00")));

        verify(umsMemberService, times(1)).addBalance(eq(9L), any());
    }

    // ⑪b F-L4-05:回补事务提交结果未知(commit 抛异常,库里可能已是 2 且余额已加)。
    //     兜底分支绝不能无条件把状态改回 3,否则人工双人补单会再加一次余额。
    @Test
    void initiateFail_refundCommitUnknown_neverRevertsStatusToManual() {
        when(mapper.countPendingByMember(9L)).thenReturn(0);
        stubMemberWithOpenid();
        when(umsMemberService.deductWithdrawableBalance(eq(9L), any(), any())).thenReturn(true);
        Map<String, String> res = new HashMap<>();
        res.put("ok", "false");
        res.put("ambiguous", "false");
        res.put("error", "转账服务未就绪");
        when(wechatPayService.transferToBalance(any(CashWithdrawTransferIntent.class))).thenAnswer(call -> dispatchResult(res, call.getArgument(0)));
        when(mapper.updateStatusIfProcessing(any())).thenAnswer(evidence::processing);
        org.springframework.transaction.PlatformTransactionManager transactions = mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(mock(org.springframework.transaction.TransactionStatus.class));
        java.util.concurrent.atomic.AtomicInteger commits = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(inv -> {
            if (commits.incrementAndGet() == 3)
                throw new org.springframework.transaction.TransactionSystemException("commit outcome unknown");
            return null;
        }).when(transactions).commit(any());
        ReflectionTestUtils.setField(svc, "transactionManager", transactions);

        assertThrows(IllegalStateException.class, () -> svc.withdraw(9L, new BigDecimal("10.00")));

        ArgumentCaptor<MemberCashWithdraw> plain = ArgumentCaptor.forClass(MemberCashWithdraw.class);
        verify(mapper, org.mockito.Mockito.atLeast(0)).updateMemberCashWithdraw(plain.capture());
        assertTrue(plain.getAllValues().stream().noneMatch(v -> v.getStatus() != null),
                "兜底不得用无条件 update 改状态");
        ArgumentCaptor<MemberCashWithdraw> manual = ArgumentCaptor.forClass(MemberCashWithdraw.class);
        verify(mapper, times(2)).updateStatusIfManual(manual.capture());
        MemberCashWithdraw note = manual.getAllValues().get(1);
        assertEquals(null, note.getStatus());
        assertTrue(note.getManualNote().contains("待人工"));
        verify(alarmService, times(1)).alarm(eq("提现待人工"), anyString());
    }

    // ⑫ 第一人仅登记:失败待人工 → 待复核，不移动余额
    @Test
    void manualRefund_firstOperatorOnlyPreparesReview() {
        MemberCashWithdraw w = pendingRecord();
        w.setStatus(MemberCashWithdraw.STATUS_FAILED_MANUAL);
        when(mapper.selectById(1L)).thenReturn(w);
        when(mapper.updateStatusIfManual(any())).thenAnswer(evidence::manual);

        evidence.seedDebit(1L);
        boolean ok = svc.manualRefund(1L, "finance", "手工核实");

        assertTrue(ok);
        verify(umsMemberService, never()).addBalance(anyLong(), any());
        ArgumentCaptor<MemberCashWithdraw> cap = ArgumentCaptor.forClass(MemberCashWithdraw.class);
        verify(mapper).updateStatusIfManual(cap.capture());
        assertEquals(MemberCashWithdraw.STATUS_REFUND_PENDING_REVIEW, cap.getValue().getStatus());
        assertEquals("finance", cap.getValue().getManualOperator());
    }

    // ⑬ 人工补单仅限失败待人工:处理中 → 抛错,不退款
    @Test
    void manualRefund_notManual_throws() {
        when(mapper.selectById(1L)).thenReturn(pendingRecord()); // status=0
        assertThrows(IllegalStateException.class, () -> svc.manualRefund(1L, "finance", null));
        verify(umsMemberService, never()).addBalance(anyLong(), any());
    }

    // ⑭ 第一人登记抢占败(并发)→ 不退款
    @Test
    void manualRefund_lostClaim_noRefund() {
        MemberCashWithdraw w = pendingRecord();
        w.setStatus(MemberCashWithdraw.STATUS_FAILED_MANUAL);
        when(mapper.selectById(1L)).thenReturn(w);
        when(mapper.updateStatusIfManual(any())).thenReturn(0); // 抢占失败

        evidence.seedDebit(1L);
        boolean ok = svc.manualRefund(1L, "finance", null);

        assertFalse(ok);
        verify(umsMemberService, never()).addBalance(anyLong(), any());
    }

    @Test
    void manualRefund_rejectsAChangedExpectedStatusBeforeMovingMoney() {
        MemberCashWithdraw w = pendingRecord();
        w.setStatus(MemberCashWithdraw.STATUS_REFUND_PENDING_REVIEW);
        when(mapper.selectById(1L)).thenReturn(w);

        boolean ok = svc.manualRefund(1L, "finance", "手工核实", MemberCashWithdraw.STATUS_FAILED_MANUAL);

        assertFalse(ok);
        verify(mapper, never()).updateStatusIfManual(any());
        verify(mapper, never()).updateStatusIfRefundReview(any());
        verify(umsMemberService, never()).addBalance(anyLong(), any());
    }

    // ⑮ 第二个不同账号复核后才退款
    @Test
    void manualRefund_secondDistinctOperatorRefunds() {
        MemberCashWithdraw w = pendingRecord();
        w.setStatus(MemberCashWithdraw.STATUS_REFUND_PENDING_REVIEW);
        w.setManualOperator("maker");
        when(mapper.selectById(1L)).thenReturn(w);
        when(mapper.updateStatusIfRefundReview(any())).thenAnswer(evidence::review);

        evidence.seedDebit(1L);
        boolean ok = svc.manualRefund(1L, "checker", null);

        assertTrue(ok);
        verify(umsMemberService).addBalance(9L, TEN);
        ArgumentCaptor<MemberCashWithdraw> cap = ArgumentCaptor.forClass(MemberCashWithdraw.class);
        verify(mapper).updateStatusIfRefundReview(cap.capture());
        assertEquals(MemberCashWithdraw.STATUS_FAILED_REFUNDED, cap.getValue().getStatus());
        assertEquals("checker", cap.getValue().getManualVerifier());
    }

    @Test
    void manualRefund_sameOperatorCannotSelfReview() {
        MemberCashWithdraw w = pendingRecord();
        w.setStatus(MemberCashWithdraw.STATUS_REFUND_PENDING_REVIEW);
        w.setManualOperator("finance");
        when(mapper.selectById(1L)).thenReturn(w);

        evidence.seedDebit(1L);
        assertThrows(IllegalStateException.class, () -> svc.manualRefund(1L, "finance", null));

        verify(mapper, never()).updateStatusIfRefundReview(any());
        verify(umsMemberService, never()).addBalance(anyLong(), any());
    }

    @Test
    void manualRefund_refundThrows_escapesForWholeTransactionRollback() {
        MemberCashWithdraw w = pendingRecord();
        w.setStatus(MemberCashWithdraw.STATUS_REFUND_PENDING_REVIEW);
        w.setManualOperator("maker");
        when(mapper.selectById(1L)).thenReturn(w);
        when(mapper.updateStatusIfRefundReview(any())).thenAnswer(evidence::review);
        doThrow(new RuntimeException("增加余额失败")).when(umsMemberService).addBalance(eq(9L), any());

        evidence.seedDebit(1L);
        assertThrows(RuntimeException.class, () -> svc.manualRefund(1L, "checker", null));
        verify(mapper, never()).updateMemberCashWithdraw(any());
        // Real rollback is asserted by CashWithdrawFundEvidenceMySqlIT, not this mock fixture.
    }

    // ⑯ 对账:积压超阈值 → 告警(处理中积压 + 待人工;successNoBatch=0 不告警 → 恰 2 次)
    @Test
    void reconcile_alarmsOnBacklog() {
        when(mapper.countStuckProcessing(any())).thenReturn(2);
        when(mapper.countManualPending()).thenReturn(3);
        when(mapper.countSuccessWithoutBatch()).thenReturn(0);

        svc.reconcile(12);

        verify(alarmService).alarmThrottled(eq("monitor.withdraw.stuck"), anyString(), contains("提现单=2"));
        verify(alarmService).alarmThrottled(eq("monitor.withdraw.manual"), anyString(), contains("提现单=3"));
        verify(alarmService, never()).alarmThrottled(eq("monitor.withdraw.missing_batch"), anyString(), anyString());
        verifyNoMoreInteractions(alarmService);
    }

    // ⑰ 对账:全清零 → 不告警
    @Test
    void reconcile_noAlarmWhenClean() {
        when(mapper.countStuckProcessing(any())).thenReturn(0);
        when(mapper.countManualPending()).thenReturn(0);
        when(mapper.countSuccessWithoutBatch()).thenReturn(0);

        svc.reconcile(12);

        verify(alarmService, never()).alarm(anyString(), anyString());
        verify(alarmService, never()).alarmThrottled(anyString(), anyString(), anyString());
    }

    // ===== P块冻结:暂停自动化/人工处理,不动余额 =====

    @Test
    void freezeWithdraw_recordsReasonAndOperator() {
        when(mapper.selectById(1L)).thenReturn(pendingRecord());
        when(mapper.freezeWithdraw(any())).thenReturn(1);

        boolean ok = svc.freezeWithdraw(1L, "riskops", "疑似盗号");

        assertTrue(ok);
        ArgumentCaptor<MemberCashWithdraw> cap = ArgumentCaptor.forClass(MemberCashWithdraw.class);
        verify(mapper).freezeWithdraw(cap.capture());
        assertEquals(1L, cap.getValue().getId());
        assertEquals("疑似盗号", cap.getValue().getFrozenReason());
        assertEquals("riskops", cap.getValue().getFrozenBy());
        verify(umsMemberService, never()).addBalance(anyLong(), any());
        verify(umsMemberService, never()).deductBalance(anyLong(), any());
    }

    @Test
    void freezeWithdraw_missingOperator_throwsBeforeTouchingMapper() {
        assertThrows(IllegalArgumentException.class, () -> svc.freezeWithdraw(1L, "  ", "疑似盗号"));
        verify(mapper, never()).freezeWithdraw(any());
    }

    @Test
    void freezeWithdraw_notFound_throws() {
        when(mapper.selectById(1L)).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> svc.freezeWithdraw(1L, "riskops", "疑似盗号"));
        verify(mapper, never()).freezeWithdraw(any());
    }

    @Test
    void freezeWithdraw_alreadyFrozen_returnsFalse() {
        when(mapper.selectById(1L)).thenReturn(pendingRecord());
        when(mapper.freezeWithdraw(any())).thenReturn(0); // CAS 未命中=已在冻结中

        assertFalse(svc.freezeWithdraw(1L, "riskops", "重复冻结"));
    }

    @Test
    void unfreezeWithdraw_notFrozen_returnsFalse() {
        when(mapper.selectById(1L)).thenReturn(pendingRecord());
        when(mapper.unfreezeWithdraw(1L)).thenReturn(0);

        assertFalse(svc.unfreezeWithdraw(1L, "riskops"));
    }

    // 冻结中的单必须拦人工补单(非仅自动查询任务)——freeze 的意图是"暂停一切处理直到查清",
    // 若人工通道还能动钱,冻结就形同虚设。
    @Test
    void manualRefund_frozenRecord_blockedBeforeMovingMoney() {
        MemberCashWithdraw w = pendingRecord();
        w.setStatus(MemberCashWithdraw.STATUS_FAILED_MANUAL);
        w.setFrozen(1);
        w.setFrozenReason("疑似盗号");
        when(mapper.selectById(1L)).thenReturn(w);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> svc.manualRefund(1L, "finance", "手工核实"));

        assertTrue(e.getMessage().contains("冻结"));
        verify(mapper, never()).updateStatusIfManual(any());
        verify(umsMemberService, never()).addBalance(anyLong(), any());
    }

    // status=5(未实际扣款)的单,从服务侧就拒绝进入人工回补(3→4→2/addBalance)流程。
    @Test
    void manualRefund_noDebitRecord_blockedBeforeMovingMoney() {
        MemberCashWithdraw w = pendingRecord();
        w.setStatus(MemberCashWithdraw.STATUS_FAILED_NO_DEBIT);
        when(mapper.selectById(1L)).thenReturn(w);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> svc.manualRefund(1L, "finance", "手工核实"));

        assertTrue(e.getMessage().contains("未实际扣款"),
                "status=5 必须显式拒绝,不能让它走 3→4→2 流程,实际=" + e.getMessage());
        verify(mapper, never()).updateStatusIfManual(any());
        verify(umsMemberService, never()).addBalance(anyLong(), any());
    }
    @Test
    void sync_fail_refundClaimLost_noBalanceNoAlarm() {
        // Original master-only debit/dispatch proof; ledger-CAS failures remain covered separately.
        MemberCashWithdraw legacy = pendingRecord();
        when(mapper.selectById(1L)).thenReturn(legacy);
        when(mapper.selectByIdForUpdate(1L)).thenReturn(legacy);
        com.chengyinhub.business.longrun.OWN_C04_21Test.seedSnapshot(transferEvidence, legacy,
                "unit-merchant", "wx-mp-appid", true);

        when(mapper.selectPendingForQuery(anyInt())).thenReturn(Collections.singletonList(pendingRecord()));
        when(wechatPayService.queryTransferResult(any(CashWithdrawTransferIntent.class))).thenReturn(qr("FAIL", "FINISHED"));
        when(mapper.updateStatusIfProcessing(any())).thenAnswer(call -> {
            MemberCashWithdraw update = call.getArgument(0);
            if (update.getStatus() != null) mapper.selectById(update.getId()).setStatus(update.getStatus());
            return 1;
        }); // 抢占成功
        when(mapper.updateStatusIfManual(any())).thenReturn(0);     // 回补领取输掉

        svc.syncPendingWithdrawals(50);

        verify(umsMemberService, never()).addBalance(anyLong(), any());
        verify(umsMemberService, never()).deductBalance(anyLong(), any());
        verify(alarmService, never()).alarm(anyString(), anyString());
        verify(mapper, times(1)).updateStatusIfProcessing(any());
        ArgumentCaptor<MemberCashWithdraw> fin = ArgumentCaptor.forClass(MemberCashWithdraw.class);
        verify(mapper, times(1)).updateStatusIfManual(fin.capture());
        assertEquals(MemberCashWithdraw.STATUS_FAILED_REFUNDED, fin.getValue().getStatus());
    }

    @Test
    void manualRefund_refundThrows_backToPendingReview() {
        MemberCashWithdraw w = pendingRecord();
        w.setStatus(MemberCashWithdraw.STATUS_REFUND_PENDING_REVIEW);
        w.setManualOperator("maker");
        when(mapper.selectById(1L)).thenReturn(w);
        when(mapper.selectByIdForUpdate(1L)).thenReturn(w);
        com.chengyinhub.business.longrun.OWN_C04_21Test.seedSnapshot(transferEvidence, w,
                "unit-merchant", "wx-mp-appid", true);
        when(wechatPayService.queryTransferResult(any(CashWithdrawTransferIntent.class))).thenReturn(qr("FAIL", "FINISHED"));
        when(mapper.updateStatusIfRefundReview(any())).thenReturn(1);
        doThrow(new RuntimeException("增加余额失败")).when(umsMemberService).addBalance(eq(9L), any());

        assertThrows(RuntimeException.class, () -> svc.manualRefund(1L, "checker", null));
        verify(mapper).updateStatusIfRefundReview(any());
        verify(mapper, never()).updateMemberCashWithdraw(any());
        // The real transaction rollback of both UPDATEs is covered by CashWithdrawTransferIntentMySqlIT.
    }

}
