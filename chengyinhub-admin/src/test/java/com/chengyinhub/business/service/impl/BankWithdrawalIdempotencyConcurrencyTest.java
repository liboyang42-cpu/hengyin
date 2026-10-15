package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UmsMemberWithdrawal;
import com.chengyinhub.business.domain.dto.WithdrawalDTO;
import com.chengyinhub.business.mapper.UmsMemberBalanceDetailMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.mapper.UmsMemberWithdrawalMapper;
import com.chengyinhub.business.security.WithdrawalPiiProtector;
import com.chengyinhub.business.service.IPlayerWithdrawalCooldownService;
import com.chengyinhub.business.service.support.WithdrawalFrozenAmountResolver;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.stubbing.Answer;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 银行卡提现以 memberId + requestId 领取业务意图，同 key 并发只能扣一次余额。 */
class BankWithdrawalIdempotencyConcurrencyTest {

    @Test
    void sameBusinessRequestConcurrentlyReturnsOneWithdrawalAndDeductsOnce() throws Exception {
        final long memberId = 9201L;
        final BigDecimal amount = new BigDecimal("25.00");
        AtomicReference<UmsMemberWithdrawal> stored = new AtomicReference<>();
        AtomicLong legacyIds = new AtomicLong(8100L);
        CountDownLatch bothMissedInitialReplay = new CountDownLatch(2);
        AtomicInteger replayReads = new AtomicInteger();
        Answer<Object> mapperBehavior = invocation -> {
            String method = invocation.getMethod().getName();
            if ("selectByMemberAndRequest".equals(method)) {
                if (replayReads.incrementAndGet() <= 2) {
                    bothMissedInitialReplay.countDown();
                    assertTrue(bothMissedInitialReplay.await(5, TimeUnit.SECONDS),
                            "两个提现线程必须先共同错过初始重放探测");
                    return null;
                }
                return stored.get();
            }
            if ("selectByMemberAndRequestForUpdate".equals(method)) {
                return stored.get();
            }
            if ("insertUmsMemberWithdrawal".equals(method)) {
                UmsMemberWithdrawal row = invocation.getArgument(0, UmsMemberWithdrawal.class);
                String requestId = optionalString(row, "getRequestId");
                if (requestId == null) {
                    row.setId(legacyIds.incrementAndGet());
                    return 1;
                }
                row.setId(8201L);
                if (stored.compareAndSet(null, row)) {
                    return 1;
                }
                throw new DuplicateKeyException("uk_withdrawal_member_request");
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        };
        UmsMemberWithdrawalMapper withdrawalMapper = mock(UmsMemberWithdrawalMapper.class, mapperBehavior);

        CountDownLatch bothReadBalance = new CountDownLatch(2);
        UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        UmsMember member = new UmsMember();
        member.setId(memberId);
        member.setBalance(new BigDecimal("100.00"));
        when(memberMapper.selectUmsMemberById(memberId)).thenAnswer(invocation -> {
            bothReadBalance.countDown();
            assertTrue(bothReadBalance.await(5, TimeUnit.SECONDS),
                    "两个提现线程必须共同读到同一余额快照");
            return member;
        });
        when(memberMapper.deductWithdrawableBalance(memberId, amount, BigDecimal.ZERO)).thenReturn(1);
        UmsMemberBalanceDetailMapper detailMapper = mock(UmsMemberBalanceDetailMapper.class);
        when(detailMapper.insertUmsMemberBalanceDetail(any())).thenReturn(1);

        MerchantReferralBountyService bountyService = mock(MerchantReferralBountyService.class);
        IPlayerWithdrawalCooldownService cooldownService = mock(IPlayerWithdrawalCooldownService.class);
        when(bountyService.frozenAmount(memberId)).thenReturn(BigDecimal.ZERO);
        when(cooldownService.frozenAmount(memberId)).thenReturn(BigDecimal.ZERO);
        UmsMemberWithdrawalServiceImpl service = service(
                withdrawalMapper, memberMapper, detailMapper, bountyService, cooldownService);

        WithdrawalDTO request = request(amount);
        setOptionalRequestId(request, "bank-intent-9201");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Long firstId;
        Long secondId;
        try {
            Future<Long> first = pool.submit(() -> service.withdrawalRequest(request, memberId, "member-9201"));
            Future<Long> second = pool.submit(() -> service.withdrawalRequest(request, memberId, "member-9201"));
            firstId = first.get(10, TimeUnit.SECONDS);
            secondId = second.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        verify(memberMapper, times(1)).deductWithdrawableBalance(memberId, amount, BigDecimal.ZERO);
        verify(detailMapper, times(1)).insertUmsMemberBalanceDetail(any());
        assertEquals(firstId, secondId, "同 key/同 fingerprint 必须重放同一提现单 id");
        assertNotNull(stored.get(), "唯一键 winner 必须持久化业务 claim");
        assertEquals("bank-intent-9201", optionalString(stored.get(), "getRequestId"));
        assertTrue(optionalString(stored.get(), "getRequestFingerprint").matches("[0-9a-f]{64}"),
                "winner 必须保存规范化请求指纹，供同 key 异参数冲突判定");

        WithdrawalDTO changed = request(new BigDecimal("26.00"));
        changed.setRequestId("bank-intent-9201");
        IllegalStateException conflict = assertThrows(IllegalStateException.class,
                () -> service.withdrawalRequest(changed, memberId, "member-9201"));
        assertEquals("该提现幂等键已用于不同的提现内容，请重新提交", conflict.getMessage(),
                "同 key 异金额必须由 fingerprint 冲突闸拒绝");
        verify(memberMapper, times(1)).deductWithdrawableBalance(memberId, amount, BigDecimal.ZERO);
    }

    @Test
    void differentBusinessRequestsConcurrentlyHaveOnePendingWinner() throws Exception {
        final long memberId = 9202L;
        final BigDecimal amount = new BigDecimal("25.00");
        AtomicReference<UmsMemberWithdrawal> pending = new AtomicReference<>();
        CountDownLatch bothMissedReplay = new CountDownLatch(2);
        UmsMemberWithdrawalMapper withdrawalMapper = mock(UmsMemberWithdrawalMapper.class, invocation -> {
            String method = invocation.getMethod().getName();
            if ("selectByMemberAndRequest".equals(method)) {
                bothMissedReplay.countDown();
                assertTrue(bothMissedReplay.await(5, TimeUnit.SECONDS),
                        "两个不同 key 必须共同越过重放探测后竞争单在途 claim");
                return null;
            }
            if ("selectByMemberAndRequestForUpdate".equals(method)) {
                return null;
            }
            if ("selectPendingIdByMemberForUpdate".equals(method)) {
                return pending.get() == null ? null : pending.get().getId();
            }
            if ("insertUmsMemberWithdrawal".equals(method)) {
                UmsMemberWithdrawal row = invocation.getArgument(0, UmsMemberWithdrawal.class);
                row.setId(8301L);
                if (pending.compareAndSet(null, row)) {
                    return 1;
                }
                throw new DuplicateKeyException("uk_withdrawal_pending_member");
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });

        CountDownLatch bothReadBalance = new CountDownLatch(2);
        UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        UmsMember member = new UmsMember();
        member.setId(memberId);
        member.setBalance(new BigDecimal("100.00"));
        when(memberMapper.selectUmsMemberById(memberId)).thenAnswer(invocation -> {
            bothReadBalance.countDown();
            assertTrue(bothReadBalance.await(5, TimeUnit.SECONDS),
                    "两个不同 key 必须共同读到余额后竞争单在途 claim");
            return member;
        });
        when(memberMapper.deductWithdrawableBalance(memberId, amount, BigDecimal.ZERO)).thenReturn(1);
        UmsMemberBalanceDetailMapper detailMapper = mock(UmsMemberBalanceDetailMapper.class);
        when(detailMapper.insertUmsMemberBalanceDetail(any())).thenReturn(1);
        MerchantReferralBountyService bountyService = mock(MerchantReferralBountyService.class);
        IPlayerWithdrawalCooldownService cooldownService = mock(IPlayerWithdrawalCooldownService.class);
        when(bountyService.frozenAmount(memberId)).thenReturn(BigDecimal.ZERO);
        when(cooldownService.frozenAmount(memberId)).thenReturn(BigDecimal.ZERO);
        UmsMemberWithdrawalServiceImpl service = service(
                withdrawalMapper, memberMapper, detailMapper, bountyService, cooldownService);

        WithdrawalDTO firstRequest = request(amount);
        firstRequest.setRequestId("bank-intent-9202-a");
        WithdrawalDTO secondRequest = request(amount);
        secondRequest.setRequestId("bank-intent-9202-b");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<String> outcomes;
        try {
            Future<String> first = pool.submit(() -> withdrawalOutcome(
                    service, firstRequest, memberId, "member-9202"));
            Future<String> second = pool.submit(() -> withdrawalOutcome(
                    service, secondRequest, memberId, "member-9202"));
            outcomes = Arrays.asList(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, outcomes.stream().filter(value -> value.startsWith("WITHDRAWAL:")).count(),
                "不同 key 并发只能有一个单在途 winner，实际=" + outcomes);
        assertEquals(1, outcomes.stream()
                        .filter("已有提现正在处理中，请完成后再提交"::equals).count(),
                "loser 必须由单在途唯一键对应的业务闸拒绝，实际=" + outcomes);
        verify(memberMapper, times(1)).deductWithdrawableBalance(memberId, amount, BigDecimal.ZERO);
        verify(detailMapper, times(1)).insertUmsMemberBalanceDetail(any());
    }

    private String withdrawalOutcome(UmsMemberWithdrawalServiceImpl service, WithdrawalDTO request,
                                     Long memberId, String username) {
        try {
            return "WITHDRAWAL:" + service.withdrawalRequest(request, memberId, username);
        } catch (IllegalStateException rejected) {
            return rejected.getMessage();
        }
    }

    private UmsMemberWithdrawalServiceImpl service(UmsMemberWithdrawalMapper withdrawalMapper,
                                                     UmsMemberMapper memberMapper,
                                                     UmsMemberBalanceDetailMapper detailMapper,
                                                     MerchantReferralBountyService bountyService,
                                                     IPlayerWithdrawalCooldownService cooldownService) {
        UmsMemberWithdrawalServiceImpl service = new UmsMemberWithdrawalServiceImpl();
        ReflectionTestUtils.setField(service, "umsMemberWithdrawalMapper", withdrawalMapper);
        ReflectionTestUtils.setField(service, "umsMemberMapper", memberMapper);
        ReflectionTestUtils.setField(service, "umsMemberBalanceDetailMapper", detailMapper);
        ReflectionTestUtils.setField(service, "withdrawalPiiProtector",
                new WithdrawalPiiProtector("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="));
        ReflectionTestUtils.setField(service, "frozenAmountResolver",
                new WithdrawalFrozenAmountResolver(bountyService, cooldownService));
        ReflectionTestUtils.setField(service, "balanceAdmissionService",
                com.chengyinhub.business.longrun.OWN_C04_21Test.bankFixture(memberMapper, (com.chengyinhub.business.service.support.WithdrawalFrozenAmountResolver) ReflectionTestUtils.getField(service, "frozenAmountResolver")));
        return service;
    }

    private WithdrawalDTO request(BigDecimal amount) {
        WithdrawalDTO request = new WithdrawalDTO();
        request.setRealname("张三");
        request.setBankName("测试银行");
        request.setBankAccount("6222021234567890");
        request.setMobilephone("13800138000");
        request.setWithdrawalAmount(amount);
        return request;
    }

    private void setOptionalRequestId(WithdrawalDTO request, String requestId) throws Exception {
        for (Method method : request.getClass().getMethods()) {
            if ("setRequestId".equals(method.getName())) {
                method.invoke(request, requestId);
                return;
            }
        }
    }

    private static String optionalString(Object target, String getterName) throws Exception {
        for (Method method : target.getClass().getMethods()) {
            if (getterName.equals(method.getName())) {
                return (String) method.invoke(target);
            }
        }
        return null;
    }
}
