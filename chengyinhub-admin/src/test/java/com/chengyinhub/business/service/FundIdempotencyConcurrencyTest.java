package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.MemberCashWithdraw;
import com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.MemberCashWithdrawMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.impl.AlarmService;
import com.chengyinhub.business.service.impl.CashWithdrawServiceImpl;
import com.chengyinhub.business.service.impl.MerchantReferralBountyService;
import com.chengyinhub.business.service.impl.TeamGroupServiceImpl;
import com.chengyinhub.business.service.support.WithdrawalFrozenAmountResolver;
import com.chengyinhub.common.config.WxPayConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 资金回补必须先通过业务状态 CAS；两个线程打同一业务单时只能有一个 winner。 */
class FundIdempotencyConcurrencyTest {

    @Test
    void concurrentPointsOnlyRefundClaimsRegistrationBeforeRestoringFundsAndInventory() throws Exception {
        final long registrationId = 7001L;
        final long memberId = 9001L;
        final long ticketId = 8001L;
        CmsRegistration registration = new CmsRegistration();
        registration.setId(registrationId);
        registration.setMemberId(memberId);
        registration.setOwnerType(1L);
        registration.setOwnerId(6001L);
        registration.setTicketId(ticketId);
        registration.setPaymentStatus(2);
        registration.setRegistrationStatus(2);
        registration.setVerificationStatus(0);
        registration.setWechatPaymentAmount(BigDecimal.ZERO);
        registration.setPointUsed(120L);
        registration.setPointsReturned(0);
        registration.setOrderNum(1L);

        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        CountDownLatch bothReadPaidSnapshot = new CountDownLatch(2);
        when(registrationMapper.selectCmsRegistrationById(registrationId)).thenAnswer(invocation -> {
            bothReadPaidSnapshot.countDown();
            assertTrue(bothReadPaidSnapshot.await(5, TimeUnit.SECONDS),
                    "两个退款线程必须先共同读到同一笔已支付快照");
            return registration;
        });
        when(registrationMapper.selectCmsRegistrationByIdForUpdate(registrationId)).thenAnswer(invocation -> {
            bothReadPaidSnapshot.countDown();
            assertTrue(bothReadPaidSnapshot.await(5, TimeUnit.SECONDS),
                    "两个退款线程必须先共同读到同一笔已支付快照");
            return registration;
        });
        AtomicBoolean registrationClaimed = new AtomicBoolean();
        when(registrationMapper.claimRegistrationRefunded(eq(registrationId), any(), any()))
                .thenAnswer(invocation -> registrationClaimed.compareAndSet(false, true) ? 1 : 0);
        AtomicBoolean pointsClaimed = new AtomicBoolean();
        when(registrationMapper.claimPointsReturn(eq(registrationId), any(), any()))
                .thenAnswer(invocation -> pointsClaimed.compareAndSet(false, true) ? 1 : 0);

        CmsTopic topic = new CmsTopic();
        topic.setId(6001L);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        when(topicMapper.selectCmsTopicById(6001L)).thenReturn(topic);
        com.chengyinhub.business.service.impl.RegistrationScopeFixture
                .currentReads(registrationMapper, null, topicMapper);
        OmsTicket ticket = new OmsTicket();
        ticket.setId(ticketId);
        ticket.setName("积分票");
        ticket.setStartTime(new Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(3)));
        ticket.setEndTime(new Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(4)));
        OmsTicketMapper ticketMapper = mock(OmsTicketMapper.class);
        when(ticketMapper.selectOmsTicketById(ticketId)).thenReturn(ticket);
        when(ticketMapper.restoreInventory(ticketId, 1L)).thenReturn(1);
        UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        when(memberMapper.addPoints(memberId, 120)).thenReturn(1);

        TeamGroupServiceImpl service = new TeamGroupServiceImpl();

        org.springframework.test.util.ReflectionTestUtils.setField(service, "firstReviewPointsClawback",

                org.mockito.Mockito.mock(com.chengyinhub.business.service.impl.FirstReviewPointsClawbackService.class));
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrationMapper);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);
        ReflectionTestUtils.setField(service, "umsMemberMapper", memberMapper);
        ReflectionTestUtils.setField(service, "perkLedgerService", mock(IPerkLedgerService.class));
        ReflectionTestUtils.setField(service, "refundService", mock(IRefundService.class));
        ReflectionTestUtils.setField(service, "refundPayoutService", mock(IRefundPayoutService.class));
        ReflectionTestUtils.setField(service, "notifyService", mock(INotifyService.class));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(() -> service.cancelPaidRegistration(registrationId, memberId));
            Future<Integer> second = pool.submit(() -> service.cancelPaidRegistration(registrationId, memberId));
            assertEquals(1, first.get(10, TimeUnit.SECONDS));
            assertEquals(1, second.get(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        verify(ticketMapper, times(1)).restoreInventory(ticketId, 1L);
        verify(memberMapper, times(1)).addPoints(memberId, 120);
        ArgumentCaptor<String> registrationClaimActor = ArgumentCaptor.forClass(String.class);
        verify(registrationMapper, times(2)).claimRegistrationRefunded(
                eq(registrationId), registrationClaimActor.capture(), any());
        assertTrue(registrationClaimActor.getAllValues().stream().allMatch("auto-refund"::equals),
                "退款主状态 CAS 必须指向同一业务报名且使用退款操作人");
        verify(registrationMapper, times(1)).claimPointsReturn(eq(registrationId), eq("auto-refund"), any());
    }

    @Test
    void deterministicTransferFailureAndReconcileCompeteForSameClaimBeforeBalanceRefund() throws Exception {
        final long withdrawalId = 7101L;
        final long memberId = 9101L;
        final BigDecimal amount = new BigDecimal("30.00");
        MemberCashWithdraw pending = new MemberCashWithdraw();
        pending.setId(withdrawalId);
        pending.setDelFlag(0);
        pending.setMemberId(memberId); pending.setOpenid("openid-9101");
        pending.setAmount(amount);
        pending.setOutBatchNo("CW" + withdrawalId);
        pending.setOutDetailNo("CD" + withdrawalId);
        pending.setStatus(MemberCashWithdraw.STATUS_PROCESSING);
        pending.setCreateTime(new Date());
        pending.setSubmitTime(new Date());

        MemberCashWithdrawMapper mapper = mock(MemberCashWithdrawMapper.class);
        when(mapper.countPendingByMember(memberId)).thenReturn(0);
        when(mapper.insertMemberCashWithdraw(any())).thenAnswer(invocation -> {
            invocation.getArgument(0, MemberCashWithdraw.class).setId(withdrawalId);
            return 1;
        });
        when(mapper.selectById(withdrawalId)).thenReturn(pending);
        when(mapper.updateMemberCashWithdraw(any())).thenReturn(1);
        CashWithdrawIntentTestSupport evidence = new CashWithdrawIntentTestSupport();
        when(mapper.selectPendingForQuery(1)).thenReturn(Collections.singletonList(pending));
        AtomicBoolean statusClaimed = new AtomicBoolean();
        when(mapper.updateStatusIfProcessing(any())).thenAnswer(invocation -> {
            if (!statusClaimed.compareAndSet(false, true)) return 0;
            pending.setStatus(invocation.getArgument(0, MemberCashWithdraw.class).getStatus());
            return 1;
        });

        IUmsMemberService memberService = mock(IUmsMemberService.class);
        UmsMember member = new UmsMember();
        member.setId(memberId);
        member.setWxOpenId("openid-9101");
        member.setBalance(new BigDecimal("100.00"));
        when(memberService.selectUmsMemberById(memberId)).thenReturn(member);
        when(memberService.deductWithdrawableBalance(memberId, amount, BigDecimal.ZERO)).thenReturn(true);
        when(memberService.addBalance(memberId, amount)).thenReturn(true);

        CountDownLatch transferEntered = new CountDownLatch(1);
        CountDownLatch allowDeterministicFailure = new CountDownLatch(1);
        IWechatPayService wechatPayService = mock(IWechatPayService.class);
        when(wechatPayService.transferToBalance(org.mockito.ArgumentMatchers.argThat(i -> i != null
                && ("CW" + withdrawalId).equals(i.outBatchNo()) && ("CD" + withdrawalId).equals(i.outDetailNo())
                && amount.equals(i.amountYuan()) && "openid-9101".equals(i.openid())))).thenAnswer(invocation -> {
            transferEntered.countDown();
            assertTrue(allowDeterministicFailure.await(5, TimeUnit.SECONDS),
                    "发起线程应等待对账线程先领取同一提现单");
            Map<String, String> result = new HashMap<>();
            result.put("ok", "false");
            result.put("ambiguous", "false");
            result.put("error", "微信明确拒绝");
            result.put("notSent", "true");
            result.put("requestHash", invocation.getArgument(0, CashWithdrawTransferIntent.class).requestHash());
            return result;
        });
        Map<String, String> queryFailure = new HashMap<>();
        queryFailure.put("result", "FAIL");
        queryFailure.put("detailStatus", "FAIL");
        queryFailure.put("batchStatus", "FINISHED");
        queryFailure.put("mchid", "unit-merchant"); queryFailure.put("appid", "wx-appid");
        queryFailure.put("outBatchNo", "CW" + withdrawalId); queryFailure.put("outDetailNo", "CD" + withdrawalId);
        queryFailure.put("transferAmount", "3000"); queryFailure.put("openid", "openid-9101");
        queryFailure.put("batchId", "provider-batch"); queryFailure.put("detailId", "provider-detail");
        when(wechatPayService.queryTransferResult(org.mockito.ArgumentMatchers.argThat(i -> i != null && ("CW" + withdrawalId).equals(i.outBatchNo()) && ("CD" + withdrawalId).equals(i.outDetailNo()))))
                .thenReturn(queryFailure);

        CashWithdrawServiceImpl service = new CashWithdrawServiceImpl();
        org.springframework.transaction.PlatformTransactionManager transactions = mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(mock(org.springframework.transaction.TransactionStatus.class));
        ReflectionTestUtils.setField(service, "transactionManager", transactions);
        when(mapper.updateStatusIfManual(any())).thenAnswer(invocation -> {
            pending.setStatus(invocation.getArgument(0, MemberCashWithdraw.class).getStatus());
            return 1;
        });
        ReflectionTestUtils.setField(service, "transferIntents", evidence.mapper);
        ReflectionTestUtils.setField(service, "mapper", mapper);
        ReflectionTestUtils.setField(service, "umsMemberService", memberService);
        ReflectionTestUtils.setField(service, "wechatPayService", wechatPayService);
        ReflectionTestUtils.setField(service, "alarmService", mock(AlarmService.class));
        WxPayConfig payConfig = mock(WxPayConfig.class);
        when(payConfig.getAppid()).thenReturn("wx-appid");
        when(payConfig.getMerchantId()).thenReturn("unit-merchant");
        ReflectionTestUtils.setField(service, "wxPayConfig", payConfig);
        MerchantReferralBountyService bountyService = mock(MerchantReferralBountyService.class);
        IPlayerWithdrawalCooldownService cooldownService = mock(IPlayerWithdrawalCooldownService.class);
        when(bountyService.frozenAmount(memberId)).thenReturn(BigDecimal.ZERO);
        when(cooldownService.frozenAmount(memberId)).thenReturn(BigDecimal.ZERO);
        ReflectionTestUtils.setField(service, "frozenAmountResolver",
                new WithdrawalFrozenAmountResolver(bountyService, cooldownService));
        ReflectionTestUtils.setField(service, "balanceAdmissionService",
                com.chengyinhub.business.longrun.OWN_C04_21Test.cashFixture(memberService, mapper, (com.chengyinhub.business.service.support.WithdrawalFrozenAmountResolver) ReflectionTestUtils.getField(service, "frozenAmountResolver"), evidence.mapper));
        // Keep the original single-winner assertion, and persist that winner's lifecycle for later fact checks.
        org.mockito.Mockito.doAnswer(invocation -> {
            if (!statusClaimed.compareAndSet(false, true)) return 0;
            pending.setStatus(invocation.getArgument(0, MemberCashWithdraw.class).getStatus()); return 1;
        }).when(mapper).updateStatusIfProcessing(any());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<MemberCashWithdraw> initiating = pool.submit(() -> service.withdraw(memberId, amount));
            assertTrue(transferEntered.await(5, TimeUnit.SECONDS), "发起线程必须进入微信转账调用");
            Future<Integer> reconciling = pool.submit(() -> service.syncPendingWithdrawals(1));
            assertEquals(1, reconciling.get(10, TimeUnit.SECONDS));
            allowDeterministicFailure.countDown();
            ExecutionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                    ExecutionException.class, () -> initiating.get(10, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IllegalStateException,
                    "发起线程应以提现失败异常结束，实际=" + failure.getCause());
            IllegalStateException cause = (IllegalStateException) failure.getCause();
            assertTrue(cause.getMessage().contains("待核查"),
                    "原单已由对账终结，迟到本地拒绝不能重新领取回补或覆盖原回执");
        } finally {
            allowDeterministicFailure.countDown();
            pool.shutdownNow();
        }

        verify(memberService, times(1)).addBalance(memberId, amount);
        verify(evidence.mapper).markSubmitted(eq(withdrawalId), eq(evidence.get(withdrawalId).requestHash()));
        ArgumentCaptor<MemberCashWithdraw> claims = ArgumentCaptor.forClass(MemberCashWithdraw.class);
        verify(mapper, times(1)).updateStatusIfProcessing(claims.capture());
        List<MemberCashWithdraw> actualClaims = claims.getAllValues();
        assertTrue(actualClaims.stream().allMatch(claim ->
                        Long.valueOf(withdrawalId).equals(claim.getId())
                                && Integer.valueOf(MemberCashWithdraw.STATUS_FAILED_MANUAL).equals(claim.getStatus())),
                "同步失败与对账任务必须用同一 id、同一 expected-status CAS 领取回补权");
    }
}
