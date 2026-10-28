package com.chengyinhub.business.service.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.domain.SettlementDetail;
import com.chengyinhub.business.domain.dto.RefundReviewDTO;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.RefundApplicationMapper;
import com.chengyinhub.business.mapper.SettlementDetailMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentCaptor.forClass;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefundServiceImplTest {

    @Test
    void unknownDepositRefundCannotBeAcknowledgedByRegistrationRefundFallback() {
        RefundServiceImpl service = new RefundServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "firstReviewPointsClawback",
                org.mockito.Mockito.mock(com.chengyinhub.business.service.impl.FirstReviewPointsClawbackService.class));
        ReflectionTestUtils.setField(service,"refundApplicationMapper",mock(RefundApplicationMapper.class));
        for (String state : new String[]{"SUCCESS","CLOSED","ABNORMAL"}) {
            assertFalse(service.handleRefundNotify(java.util.Map.of("outRefundNo", "RDEP1T100", "status", state)));
            assertFalse(service.handleRefundNotify(java.util.Map.of("outRefundNo", "RF1", "status", state)));
            assertTrue(service.handleRefundNotify(java.util.Map.of("outRefundNo", "UNRELATED1", "status", state)));
        }
    }

    @Test
    void refundFactsRejectMalformedCentsBeforeInboxOrTerminalWrite() {
        RefundServiceImpl service = new RefundServiceImpl();
        RefundApplicationMapper refunds = mock(RefundApplicationMapper.class);
        CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class);
        com.chengyinhub.business.service.IWxPaymentCallbackInboxService inbox =
                mock(com.chengyinhub.business.service.IWxPaymentCallbackInboxService.class);
        com.chengyinhub.business.service.INotifyService notifications =
                mock(com.chengyinhub.business.service.INotifyService.class);
        RefundApplication refund = new RefundApplication();
        refund.setId(7L);
        refund.setSourceType("registration");
        refund.setSourceId(11L);
        refund.setRefundAmount(new BigDecimal("12.50"));
        CmsRegistration registration = new CmsRegistration();
        registration.setRegistrationNo("REG11");
        registration.setWechatPaymentAmount(new BigDecimal("30.00"));
        when(refunds.selectByRefundNo("RF11")).thenReturn(refund);
        when(registrations.selectCmsRegistrationById(11L)).thenReturn(registration);
        ReflectionTestUtils.setField(service, "refundApplicationMapper", refunds);
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrations);
        ReflectionTestUtils.setField(service, "wxPaymentCallbackInboxService", inbox);
        ReflectionTestUtils.setField(service, "notifyService", notifications);
        for (String key : new String[]{"refund", "total"}) {
            for (String malformed : new String[]{"", "12.50", "not-a-number", "9223372036854775808"}) {
                java.util.Map<String, String> facts = new java.util.HashMap<>(java.util.Map.of(
                        "outRefundNo", "RF11", "outTradeNo", "REG11", "status", "SUCCESS",
                        "refund", "1250", "total", "3000", "currency", "CNY"));
                facts.put(key, malformed);
                assertFalse(service.handleRefundNotify(facts));
            }
        }
        assertFalse(service.handleRefundNotify(null));
        org.mockito.Mockito.verifyNoInteractions(inbox, notifications);
        verify(refunds, never()).updatePayoutResult(any(), any(), any());
    }

    @Test
    void acceptedRefundResultTreatsCallbackSuccessAsStrongerTerminal() {
        RefundApplicationMapper refundMapper = mock(RefundApplicationMapper.class);
        RefundServiceImpl service = service(refundMapper, mock(CmsRegistrationMapper.class),
                mock(SettlementDetailMapper.class));
        RefundApplication succeeded = new RefundApplication();
        succeeded.setId(10L);
        succeeded.setPayoutStatus(RefundApplication.PAYOUT_SUCCESS);
        // 受理成功要写 PROCESSING,但 lease CAS 失败(回调已抢先把记录推进到更强的 SUCCESS 终态)。
        when(refundMapper.updatePayoutResultWithLease(10L, "lease", RefundApplication.PAYOUT_PROCESSING,
                "accepted")).thenReturn(0);
        when(refundMapper.selectRefundApplicationById(10L)).thenReturn(succeeded);

        // SUCCESS 比 PROCESSING 更靠后 —— 幂等放行,绝不因 CAS=0 就误判成结果未持久化而抛错。
        assertDoesNotThrow(() -> service.markPayoutResult(10L, "lease", true, "accepted"));
        // CAS 恰好尝试一次,且失败后必须真的读回终态来判定(不是盲目吞掉 0 行)。
        verify(refundMapper).updatePayoutResultWithLease(10L, "lease",
                RefundApplication.PAYOUT_PROCESSING, "accepted");
        verify(refundMapper).selectRefundApplicationById(10L);
    }

    @Test
    void acceptedRefundResultThrowsWhenReadBackShowsConflictingTerminal() {
        // 负控:CAS 失败且读回一个既不是本次目标(PROCESSING)、也不是"更强 SUCCESS"的冲突态
        //      (PENDING_MANUAL:回调说失败转人工,却和"我方受理成功"矛盾)。这层读回门必须真的会红,
        //      否则上面的幂等放行就是恒真的假绿。
        RefundApplicationMapper refundMapper = mock(RefundApplicationMapper.class);
        RefundServiceImpl service = service(refundMapper, mock(CmsRegistrationMapper.class),
                mock(SettlementDetailMapper.class));
        RefundApplication conflicting = new RefundApplication();
        conflicting.setId(10L);
        conflicting.setPayoutStatus(RefundApplication.PAYOUT_PENDING_MANUAL);
        when(refundMapper.updatePayoutResultWithLease(10L, "lease", RefundApplication.PAYOUT_PROCESSING,
                "accepted")).thenReturn(0);
        when(refundMapper.selectRefundApplicationById(10L)).thenReturn(conflicting);

        assertThrows(IllegalStateException.class,
                () -> service.markPayoutResult(10L, "lease", true, "accepted"));
    }

    @Test
    void saveRegistrationRefundResolvesMerchantFromActivityWhenMissing() {
        RefundApplicationMapper refundMapper = mock(RefundApplicationMapper.class);
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        SettlementDetailMapper settlementDetailMapper = mock(SettlementDetailMapper.class);
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        RefundServiceImpl service = service(refundMapper, registrationMapper, settlementDetailMapper, activityMapper, topicMapper);
        RefundApplication refund = refund(10L, 99L, 2L);
        refund.setId(null);
        refund.setOwnerId(null);
        refund.setMemberId(null);
        refund.setMerchantId(null);
        refund.setRefundNo("CLIENT-SPOOFED-NO");
        // HF-04(A-32): 保持退款额 ≤ 实付(68)以满足新增的退款额上限校验;本用例意在验证商家/归属解析,金额为增量数据
        refund.setRefundAmount(new BigDecimal("50.00"));
        CmsRegistration registration = new CmsRegistration();
        registration.setPayableAmount(new BigDecimal("100.00"));
        registration.setId(99L);
        registration.setOwnerType(2L);
        registration.setOwnerId(20L);
        registration.setMemberId(8L);
        registration.setPaymentStatus(2); // 已支付
        registration.setPayableAmount(new BigDecimal("68.00"));
        CmsActivity activity = new CmsActivity();
        activity.setId(20L);
        activity.setMemberId(3L);
        when(registrationMapper.selectCmsRegistrationById(99L)).thenReturn(registration);
        when(activityMapper.selectCmsActivityById(20L)).thenReturn(activity);
        when(refundMapper.insertRefundApplication(any(RefundApplication.class))).thenAnswer(invocation -> {
            RefundApplication inserted = invocation.getArgument(0);
            inserted.setId(1001L);
            return 1;
        });

        int rows = service.saveRefund(refund);

        assertEquals(1, rows);
        ArgumentCaptor<RefundApplication> captor = forClass(RefundApplication.class);
        verify(refundMapper).insertRefundApplication(captor.capture());
        RefundApplication saved = captor.getValue();
        assertEquals(2L, saved.getOwnerType());
        assertEquals(20L, saved.getOwnerId());
        assertEquals(8L, saved.getMemberId());
        assertEquals(3L, saved.getMerchantId());
        assertEquals("RF99", saved.getRefundNo());
        assertEquals(0, new BigDecimal("68.00").compareTo(saved.getPaidAmount()));
        assertEquals(RefundApplication.PAYOUT_DISPATCH_PENDING, saved.getPayoutStatus(),
                "新退款单必须先持久化为待派发，不能伪装成已受理中的微信退款");
    }

    @Test
    void rejectedRefundCanBeRetriedAndApprovedAmountsCapSubsequentRefunds() {
        RefundApplicationMapper refunds = mock(RefundApplicationMapper.class);
        CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class);
        RefundServiceImpl service = service(refunds, registrations, mock(SettlementDetailMapper.class));
        CmsRegistration paid = paidRegistration99();
        when(registrations.selectCmsRegistrationById(99L)).thenReturn(paid);
        when(refunds.insertRefundApplication(any())).thenAnswer(invocation -> {
            RefundApplication inserted = invocation.getArgument(0);
            inserted.setId(1001L);
            return 1;
        });
        RefundApplication rejected = refund(10L, 99L, 2L);
        rejected.setStatus(2);
        when(refunds.selectRegistrationHistoryForUpdate(99L)).thenReturn(java.util.Collections.singletonList(rejected));
        RefundApplication retry = refund(null, 99L, 2L);
        retry.setRefundAmount(new BigDecimal("20.00"));
        assertEquals(1, service.saveRefund(retry));
        assertEquals("RF99-10", retry.getRefundNo());
        assertEquals("registration:99:10", retry.getIdempotencyKey());
        rejected.setStatus(1);
        rejected.setRefundAmount(new BigDecimal("20.00"));
        paid.setPaymentStatus(4);
        paid.setRegistrationStatus(3);
        RefundApplication remainder = refund(null, 99L, 2L);
        remainder.setRefundAmount(new BigDecimal("48.00"));
        assertEquals(1, service.saveRefund(remainder));
        RefundApplication excessive = refund(null, 99L, 2L);
        excessive.setRefundAmount(new BigDecimal("48.01"));
        assertThrows(IllegalArgumentException.class, () -> service.saveRefund(excessive));
        rejected.setStatus(0);
        RefundApplication pendingConflict = refund(null, 99L, 2L);
        pendingConflict.setRefundAmount(new BigDecimal("48.00"));
        assertThrows(IllegalStateException.class, () -> service.saveRefund(pendingConflict));
        rejected.setStatus(2);
        paid.setPaymentStatus(2);
        paid.setPayableAmount(null); paid.setTotalAmount(null); paid.setOriginalAmount(null);
        RefundApplication missingCash = refund(null, 99L, 2L);
        missingCash.setRefundAmount(new BigDecimal("20.00"));
        assertTrue(assertThrows(IllegalStateException.class, () -> service.saveRefund(missingCash)).getMessage().contains("实付快照缺失"));
    }

    @Test
    void saveRefundRejectedForUnpaidRegistration() {
        RefundApplicationMapper refundMapper = mock(RefundApplicationMapper.class);
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        SettlementDetailMapper settlementDetailMapper = mock(SettlementDetailMapper.class);
        RefundServiceImpl service = service(refundMapper, registrationMapper, settlementDetailMapper);
        RefundApplication refund = refund(null, 99L, 2L);
        CmsRegistration registration = new CmsRegistration();
        registration.setPayableAmount(new BigDecimal("100.00"));
        registration.setId(99L);
        registration.setPaymentStatus(1); // 未支付
        when(registrationMapper.selectCmsRegistrationById(99L)).thenReturn(registration);

        assertThrows(IllegalStateException.class, () -> service.saveRefund(refund));
        verify(refundMapper, never()).insertRefundApplication(any());
    }

    @Test
    void verifiedRegistrationCannotBeApproved() {
        RefundApplicationMapper refundMapper = mock(RefundApplicationMapper.class);
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        SettlementDetailMapper settlementDetailMapper = mock(SettlementDetailMapper.class);
        RefundServiceImpl service = service(refundMapper, registrationMapper, settlementDetailMapper);
        RefundApplication refund = refund(10L, 99L, 2L);
        CmsRegistration registration = new CmsRegistration();
        registration.setPayableAmount(new BigDecimal("100.00"));
        registration.setId(99L);
        registration.setVerificationStatus(1);
        when(refundMapper.selectRefundApplicationById(10L)).thenReturn(refund);
        when(registrationMapper.selectCmsRegistrationById(99L)).thenReturn(registration);

        assertThrows(IllegalStateException.class, () -> service.reviewRefund(review(10L, 1), 7L));
        verify(refundMapper, never()).updateRefundStatusIfPending(any(), any(), any(), any(), any());
    }

    @Test
    void approvedSettledRefundCreatesNegativeAdjustmentOnce() {
        RefundApplicationMapper refundMapper = mock(RefundApplicationMapper.class);
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        SettlementDetailMapper settlementDetailMapper = mock(SettlementDetailMapper.class);
        RefundServiceImpl service = service(refundMapper, registrationMapper, settlementDetailMapper);
        RefundApplication refund = refund(10L, 99L, 2L);
        CmsRegistration registration = new CmsRegistration();
        registration.setPayableAmount(new BigDecimal("100.00"));
        registration.setId(99L);
        registration.setVerificationStatus(0);
        registration.setPaymentStatus(2);
        SettlementDetail settled = new SettlementDetail();
        settled.setMerchantId(3L);
        settled.setReceiverType("merchant");
        settled.setReceiverId(3L);
        settled.setSettlementAmount(new BigDecimal("80.00"));
        settled.setStatus(1);
        when(refundMapper.selectRefundApplicationById(10L)).thenReturn(refund);
        when(registrationMapper.selectCmsRegistrationById(99L)).thenReturn(registration);
        when(refundMapper.updateRefundStatusIfPending(eq(10L), eq(1), eq(7L), any(), eq("ok"))).thenReturn(1);
        when(settlementDetailMapper.selectSettledDetails("registration", 99L))
                .thenReturn(java.util.Collections.singletonList(settled));
        when(settlementDetailMapper.insertAdjustment(any(SettlementDetail.class))).thenReturn(1);

        int rows = service.reviewRefund(review(10L, 1), 7L);

        assertEquals(1, rows);
        verify(settlementDetailMapper).insertAdjustment(org.mockito.ArgumentMatchers.argThat(detail ->
                "refund".equals(detail.getSourceType())
                        && Long.valueOf(10L).equals(detail.getSourceId())
                        && new BigDecimal("-80.00").compareTo(detail.getSettlementAmount()) == 0));
    }

    @Test
    void approvedRefundResetsRegistrationStatus() {
        // [资损] 退款批准后须同时回落 registration_status=3(已取消),否则 my-joined 误显"进行中"、
        // issueDynCode 仍发核销码、核销闸放行已退款单→topic 单二次分账。
        RefundApplicationMapper refundMapper = mock(RefundApplicationMapper.class);
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        SettlementDetailMapper settlementDetailMapper = mock(SettlementDetailMapper.class);
        RefundServiceImpl service = service(refundMapper, registrationMapper, settlementDetailMapper);
        RefundApplication refund = refund(10L, 99L, 2L);
        CmsRegistration registration = new CmsRegistration();
        registration.setPayableAmount(new BigDecimal("100.00"));
        registration.setId(99L);
        registration.setVerificationStatus(0);
        registration.setPaymentStatus(2);
        registration.setRegistrationStatus(2); // 报名成功
        when(refundMapper.selectRefundApplicationById(10L)).thenReturn(refund);
        when(registrationMapper.selectCmsRegistrationById(99L)).thenReturn(registration);
        when(refundMapper.updateRefundStatusIfPending(eq(10L), eq(1), eq(7L), any(), eq("ok"))).thenReturn(1);
        when(settlementDetailMapper.selectSettledDetails("registration", 99L)).thenReturn(null);

        service.reviewRefund(review(10L, 1), 7L);

        verify(registrationMapper).claimRegistrationRefunded(eq(99L), eq("refund-review"), any());
        // FX-10:报名置 3 之后才收回首评积分(先判「还参与过没有」要看到这张已退)
        FirstReviewPointsClawbackService clawback =
                (FirstReviewPointsClawbackService) ReflectionTestUtils.getField(service, "firstReviewPointsClawback");
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(registrationMapper, clawback);
        order.verify(registrationMapper).claimRegistrationRefunded(eq(99L), eq("refund-review"), any());
        order.verify(clawback).onRegistrationRefunded(registration);
    }

    /** F-L4-02:系统全额退款只取代待审核申请(CAS 0→2),已批准/已驳回的不动;CAS 落空即失败回滚。 */
    @Test
    void systemFullRefundSupersedesOnlyPendingApplications() {
        RefundApplicationMapper refundMapper = mock(RefundApplicationMapper.class);
        RefundServiceImpl service = service(refundMapper, mock(CmsRegistrationMapper.class), mock(SettlementDetailMapper.class));
        RefundApplication pending = refund(10L, 99L, 2L); pending.setStatus(0);
        pending.setPayoutStatus(RefundApplication.PAYOUT_DISPATCH_PENDING);
        RefundApplication approved = refund(11L, 99L, 2L); approved.setStatus(1);
        RefundApplication rejected = refund(12L, 99L, 2L); rejected.setStatus(2);
        when(refundMapper.selectRegistrationHistoryForUpdate(99L))
                .thenReturn(java.util.Arrays.asList(pending, approved, rejected));
        when(refundMapper.updateRefundStatusIfPending(eq(10L), eq(2), eq(0L), any(), anyString())).thenReturn(1);

        assertEquals(1, service.supersedePendingRegistrationRefunds(99L, "主办方取消活动,全额退款"));
        verify(refundMapper).updateRefundStatusIfPending(eq(10L), eq(2), eq(0L), any(), anyString());
        verify(refundMapper, never()).updateRefundStatusIfPending(eq(11L), any(), any(), any(), any());
        verify(refundMapper, never()).updateRefundStatusIfPending(eq(12L), any(), any(), any(), any());

        when(refundMapper.updateRefundStatusIfPending(eq(10L), eq(2), eq(0L), any(), anyString())).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> service.supersedePendingRegistrationRefunds(99L, "x"));
    }

    @Test
    void repeatedRefundApprovalDoesNotCreateAdjustment() {
        RefundApplicationMapper refundMapper = mock(RefundApplicationMapper.class);
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        SettlementDetailMapper settlementDetailMapper = mock(SettlementDetailMapper.class);
        RefundServiceImpl service = service(refundMapper, registrationMapper, settlementDetailMapper);
        RefundApplication approved = refund(10L, 99L, 2L);
        approved.setStatus(1);
        CmsRegistration terminal = paidRegistration99();
        terminal.setPayableAmount(new BigDecimal("100.00"));
        terminal.setPaymentStatus(4); terminal.setRegistrationStatus(3);
        when(refundMapper.selectRefundApplicationById(10L)).thenReturn(approved);
        when(refundMapper.selectRegistrationHistoryForUpdate(99L)).thenReturn(java.util.Collections.singletonList(approved));
        when(registrationMapper.selectCmsRegistrationById(99L)).thenReturn(terminal);

        int rows = service.reviewRefund(review(10L, 1, "again"), 7L);

        assertEquals(0, rows);
        verify(settlementDetailMapper, never()).insertAdjustment(any());
        verify((FirstReviewPointsClawbackService) ReflectionTestUtils.getField(service, "firstReviewPointsClawback"),
                never()).onRegistrationRefunded(any());
    }

    @Test
    void approvingRegistrationAlreadyRefundedByAnotherPathIsRejected() {
        // FX-03:同一报名已被别的路径(整单退/另一张退款单)退掉,claim 落空、重读是 4/3。
        // 旧实现静默 return,审批照常通过 ⇒ controller 看到 rows>0 又打一次款 = 同一张票退两遍。
        RefundApplicationMapper refundMapper = mock(RefundApplicationMapper.class);
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        SettlementDetailMapper settlementDetailMapper = mock(SettlementDetailMapper.class);
        RefundServiceImpl service = service(refundMapper, registrationMapper, settlementDetailMapper);
        CmsRegistration registration = new CmsRegistration();
        registration.setPayableAmount(new BigDecimal("100.00"));
        registration.setId(99L);
        registration.setVerificationStatus(0);
        CmsRegistration refunded = new CmsRegistration();
        refunded.setId(99L);
        refunded.setPaymentStatus(4);
        refunded.setRegistrationStatus(3);
        when(refundMapper.selectRefundApplicationById(10L)).thenReturn(refund(10L, 99L, 2L));
        when(registrationMapper.selectCmsRegistrationById(99L)).thenReturn(registration);
        when(refundMapper.updateRefundStatusIfPending(eq(10L), eq(1), eq(7L), any(), eq("ok"))).thenReturn(1);
        when(registrationMapper.claimRegistrationRefunded(eq(99L), any(), any())).thenReturn(0);
        when(registrationMapper.selectCmsRegistrationByIdForUpdate(99L)).thenReturn(refunded);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.reviewRefund(review(10L, 1), 7L));
        assertTrue(e.getMessage().contains("已退款"), e.getMessage());
        verify(settlementDetailMapper, never()).insertAdjustment(any());
        verify(settlementDetailMapper, never()).voidPendingBySource(any(), any());
    }

    @Test
    void concurrentApprovalsOfTwoRefundsForSameRegistrationPayOnce() throws Exception {
        // FX-03 并发:同一报名挂着两张退款单(如后台单 + 整单退),两名审核人同时批准。
        // claim 用有状态 CAS 模拟;只有 rows>0 的那次会让 controller 打款。
        RefundApplicationMapper refundMapper = mock(RefundApplicationMapper.class);
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        SettlementDetailMapper settlementDetailMapper = mock(SettlementDetailMapper.class);
        RefundServiceImpl service = service(refundMapper, registrationMapper, settlementDetailMapper);
        CmsRegistration registration = new CmsRegistration();
        registration.setPayableAmount(new BigDecimal("100.00"));
        registration.setId(99L);
        registration.setVerificationStatus(0);
        java.util.concurrent.atomic.AtomicInteger paymentStatus = new java.util.concurrent.atomic.AtomicInteger(2);
        when(refundMapper.selectRefundApplicationById(10L)).thenReturn(refund(10L, 99L, 2L));
        when(refundMapper.selectRefundApplicationById(11L)).thenReturn(refund(11L, 99L, 2L));
        when(registrationMapper.selectCmsRegistrationById(99L)).thenReturn(registration);
        when(refundMapper.updateRefundStatusIfPending(any(), eq(1), any(), any(), any())).thenReturn(1);
        when(registrationMapper.claimRegistrationRefunded(eq(99L), any(), any()))
                .thenAnswer(inv -> paymentStatus.compareAndSet(2, 4) ? 1 : 0);
        when(registrationMapper.selectCmsRegistrationByIdForUpdate(99L)).thenAnswer(inv -> {
            CmsRegistration current = new CmsRegistration();
            current.setPayableAmount(new BigDecimal("100.00"));
            current.setId(99L);
            current.setPaymentStatus(paymentStatus.get());
            current.setRegistrationStatus(paymentStatus.get() == 4 ? 3 : 2);
            return current;
        });

        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.atomic.AtomicInteger paid = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger rejected = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (final long id : new long[]{10L, 11L}) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    if (service.reviewRefund(review(id, 1), 7L) > 0) {
                        paid.incrementAndGet();
                    }
                } catch (IllegalStateException e) {
                    rejected.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (java.util.concurrent.Future<?> f : futures) {
            f.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(1, paid.get(), "同一报名两张退款单并发批准,只能有一次走到打款");
        assertEquals(1, rejected.get());
        verify(settlementDetailMapper).voidPendingBySource("registration", 99L);
    }

    // ===== FX-04 后台建单:多票订单首单只能退同单还没退、也没核销的部分(与小程序整单退同一口径) =====

    /** 首单 1 承载整单 300;从单 2 已退(已批准退款单 100);从单 3 仍已支付未核销。 */
    private CmsRegistration ticket(long id, int paymentStatus, int verificationStatus, String payable) {
        CmsRegistration r = new CmsRegistration();
        r.setId(id);
        r.setParentOrderNo("P1");
        r.setOwnerType(2L);
        r.setOwnerId(20L);
        r.setMemberId(8L);
        r.setPaymentStatus(paymentStatus);
        r.setRegistrationStatus(paymentStatus == 4 ? 3 : 2);
        r.setVerificationStatus(verificationStatus);
        r.setPayableAmount(new BigDecimal(payable));
        r.setWechatPaymentAmount(id == 1L ? new BigDecimal("300.00") : new BigDecimal("100.00"));
        return r;
    }

    private RefundApplication app(long registrationId, int status, String amount) {
        RefundApplication a = new RefundApplication();
        a.setSourceType("registration");
        a.setSourceId(registrationId);
        a.setStatus(status);
        a.setRefundAmount(new BigDecimal(amount));
        return a;
    }

    private void stubOrder(CmsRegistrationMapper registrationMapper, RefundApplicationMapper refundMapper,
                           CmsRegistration sub3, List<RefundApplication> sub3Apps) {
        CmsRegistration payer = ticket(1L, 2, 0, "300.00");
        when(registrationMapper.selectCmsRegistrationById(1L)).thenReturn(payer);
        when(registrationMapper.selectByParentOrderNoForUpdate("P1", 20L))
                .thenReturn(java.util.Arrays.asList(payer, ticket(2L, 4, 0, "0"), sub3));
        when(refundMapper.selectRegistrationHistoryForUpdate(2L))
                .thenReturn(java.util.Collections.singletonList(app(2L, 1, "100.00")));
        when(refundMapper.selectRegistrationHistoryForUpdate(3L))
                .thenReturn(sub3Apps);
    }

    private RefundApplication backOfficePayerRefund(String amount) {
        RefundApplication refund = new RefundApplication();
        refund.setSourceType("registration");
        refund.setSourceId(1L);
        refund.setRefundAmount(new BigDecimal(amount));
        refund.setCreatedByUserId(5L);
        return refund;
    }

    @Test
    void backOfficePayerRefundCannotExceedOrderCashNotYetRefunded() {
        RefundApplicationMapper refundMapper = mock(RefundApplicationMapper.class);
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        RefundServiceImpl service = service(refundMapper, registrationMapper, mock(SettlementDetailMapper.class));
        stubOrder(registrationMapper, refundMapper, ticket(3L, 2, 0, "0"), java.util.Collections.emptyList());
        when(refundMapper.insertRefundApplication(any(RefundApplication.class))).thenAnswer(invocation -> {
            RefundApplication inserted = invocation.getArgument(0);
            inserted.setId(1001L);
            return 1;
        });

        // 从单 2 已退 100:首单按汇总额 300 再退 = 那 100 退两遍
        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> service.saveBackOfficeRefund(backOfficePayerRefund("300.00")));
        verify(refundMapper, never()).insertRefundApplication(any());

        assertEquals(1, service.saveBackOfficeRefund(backOfficePayerRefund("200.00")));
        verify(refundMapper).insertRefundApplication(any(RefundApplication.class));
    }

    @Test
    void backOfficePayerRefundBlockedWhileSiblingRefundPending() {
        RefundApplicationMapper refundMapper = mock(RefundApplicationMapper.class);
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        RefundServiceImpl service = service(refundMapper, registrationMapper, mock(SettlementDetailMapper.class));
        stubOrder(registrationMapper, refundMapper, ticket(3L, 2, 0, "0"),
                java.util.Collections.singletonList(app(3L, 0, "100.00")));

        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> service.saveBackOfficeRefund(backOfficePayerRefund("100.00")));
        verify(refundMapper, never()).insertRefundApplication(any());
    }

    @Test
    void backOfficePayerRefundBlockedWhenSiblingVerified() {
        // 从单 3 已核销:单票实付拆不出,首单的剩余额里含着它的钱 ⇒ fail-closed,不猜金额
        RefundApplicationMapper refundMapper = mock(RefundApplicationMapper.class);
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        RefundServiceImpl service = service(refundMapper, registrationMapper, mock(SettlementDetailMapper.class));
        stubOrder(registrationMapper, refundMapper, ticket(3L, 2, 1, "0"), java.util.Collections.emptyList());

        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> service.saveBackOfficeRefund(backOfficePayerRefund("100.00")));
        verify(refundMapper, never()).insertRefundApplication(any());
    }

    @Test
    void backOfficePayerRefundApprovalRechecksOrderCash() {
        // 建单时同单无人退过(额度 300);审批前从单 2 被别的路径退了 100 ⇒ 批准时必须再核一次
        RefundApplicationMapper refundMapper = mock(RefundApplicationMapper.class);
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        SettlementDetailMapper settlementDetailMapper = mock(SettlementDetailMapper.class);
        RefundServiceImpl service = service(refundMapper, registrationMapper, settlementDetailMapper);
        stubOrder(registrationMapper, refundMapper, ticket(3L, 2, 0, "0"), java.util.Collections.emptyList());
        RefundApplication pending = backOfficePayerRefund("300.00");
        pending.setId(10L);
        pending.setStatus(0);
        when(refundMapper.selectRefundApplicationById(10L)).thenReturn(pending);

        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> service.reviewRefund(review(10L, 1), 7L));
        // A1-1 之前建的旧后台单 created_by 为空,同样要核
        pending.setCreatedByUserId(null);
        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> service.reviewRefund(review(10L, 1), 7L));
        verify(refundMapper, never()).updateRefundStatusIfPending(any(), any(), any(), any(), any());
    }

    @Test
    void systemWholeOrderApprovalIsNotRecheckedAgainstClaimedSiblings() {
        // 系统整单退(operator=0)同事务内已按 FX-01 算好金额并先 claim 了从单;再按后台口径核会误判 409
        RefundApplicationMapper refundMapper = mock(RefundApplicationMapper.class);
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        RefundServiceImpl service = service(refundMapper, registrationMapper, mock(SettlementDetailMapper.class));
        stubOrder(registrationMapper, refundMapper, ticket(3L, 4, 0, "0"), java.util.Collections.emptyList());
        RefundApplication system = backOfficePayerRefund("200.00");
        system.setCreatedByUserId(null);
        system.setId(10L);
        system.setStatus(0);
        when(refundMapper.selectRefundApplicationById(10L)).thenReturn(system);
        when(refundMapper.updateRefundStatusIfPending(eq(10L), eq(1), eq(0L), any(), any())).thenReturn(1);

        assertEquals(1, service.reviewRefund(review(10L, 1), 0L));
    }

    @Test
    void repeatedAndConcurrentBackOfficePayerRefundCreatesOneApplication() throws Exception {
        // 重复点 + 并发点:同一首单的退款单只能落一张(RF+id / registration:id 唯一键),且都受剩余额约束
        RefundApplicationMapper refundMapper = mock(RefundApplicationMapper.class);
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        RefundServiceImpl service = service(refundMapper, registrationMapper, mock(SettlementDetailMapper.class));
        stubOrder(registrationMapper, refundMapper, ticket(3L, 2, 0, "0"), java.util.Collections.emptyList());
        final java.util.Set<String> refundNos = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
        when(refundMapper.insertRefundApplication(any(RefundApplication.class))).thenAnswer(inv -> {
            RefundApplication a = inv.getArgument(0);
            if (!refundNos.add(a.getRefundNo())) {
                throw new org.springframework.dao.DuplicateKeyException("uk_refund_no");
            }
            a.setId(1001L);
            return 1;
        });

        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(3);
        java.util.concurrent.atomic.AtomicInteger saved = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (final String amount : new String[]{"200.00", "200.00", "300.00"}) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    saved.addAndGet(service.saveBackOfficeRefund(backOfficePayerRefund(amount)));
                } catch (RuntimeException ignored) {
                    // 超额(300)或撞唯一键都应被拒
                }
                return null;
            }));
        }
        start.countDown();
        for (java.util.concurrent.Future<?> f : futures) {
            f.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(1, saved.get());
        assertEquals(java.util.Collections.singleton("RF1"), refundNos);
        verify(refundMapper, never()).insertRefundApplication(org.mockito.ArgumentMatchers.argThat(a ->
                a != null && new BigDecimal("300.00").compareTo(a.getRefundAmount()) == 0));
    }

    @Test
    void zeroAffectedAdjustmentMustReadBackTheExactRefundTerminal() {
        RefundApplicationMapper refundMapper = mock(RefundApplicationMapper.class);
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        SettlementDetailMapper settlementDetailMapper = mock(SettlementDetailMapper.class);
        RefundServiceImpl service = service(refundMapper, registrationMapper, settlementDetailMapper);
        RefundApplication refund = refund(10L, 99L, 2L);
        CmsRegistration registration = new CmsRegistration();
        registration.setPayableAmount(new BigDecimal("100.00"));
        registration.setId(99L);
        registration.setPaymentStatus(2);
        registration.setVerificationStatus(0);
        SettlementDetail settled = new SettlementDetail();
        settled.setReceiverType("merchant");
        settled.setReceiverId(3L);
        settled.setMerchantId(3L);
        settled.setGrossAmount(new BigDecimal("80.00"));
        settled.setCommissionAmount(BigDecimal.ZERO);
        settled.setSettlementAmount(new BigDecimal("80.00"));
        settled.setStatus(1);
        SettlementDetail conflicting = new SettlementDetail();
        conflicting.setSourceType("refund");
        conflicting.setSourceId(10L);
        conflicting.setReceiverType("merchant");
        conflicting.setReceiverId(3L);
        conflicting.setGrossAmount(new BigDecimal("-79.99"));
        conflicting.setCommissionAmount(BigDecimal.ZERO);
        conflicting.setSettlementAmount(new BigDecimal("-79.99"));
        conflicting.setStatus(1);
        when(refundMapper.selectRefundApplicationById(10L)).thenReturn(refund);
        when(registrationMapper.selectCmsRegistrationById(99L)).thenReturn(registration);
        when(refundMapper.updateRefundStatusIfPending(eq(10L), eq(1), eq(7L), any(), any())).thenReturn(1);
        when(settlementDetailMapper.selectSettledDetails("registration", 99L))
                .thenReturn(java.util.Collections.singletonList(settled));
        when(settlementDetailMapper.insertAdjustment(any())).thenReturn(0);
        when(settlementDetailMapper.selectByIdempotencyKeyForUpdate("refund:10:merchant:3"))
                .thenReturn(conflicting);

        assertThrows(IllegalStateException.class, () -> service.reviewRefund(review(10L, 1), 7L));
    }

    // ===== A1-1(P0)maker≠checker:后台建单人不得自审自批 =====

    @Test
    void makerCannotApproveOwnBackofficeRefund() {
        RefundApplicationMapper refundMapper = mock(RefundApplicationMapper.class);
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        SettlementDetailMapper settlementDetailMapper = mock(SettlementDetailMapper.class);
        RefundServiceImpl service = service(refundMapper, registrationMapper, settlementDetailMapper);
        RefundApplication refund = refund(10L, 99L, 2L);
        refund.setCreatedByUserId(7L); // 后台账号 7 建的单
        when(refundMapper.selectRefundApplicationById(10L)).thenReturn(refund);

        // 同账号 7 批准(status=1)→ 必须被 maker≠checker 拦;且断言拦的是这道闸(不是可退闸,二者同抛 IllegalState)
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.reviewRefund(review(10L, 1), 7L));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("审批人不能与申请人"),
                "应被 maker≠checker 闸拦下,实际文案:" + ex.getMessage());
        verify(refundMapper, never()).updateRefundStatusIfPending(any(), any(), any(), any(), any());
    }

    @Test
    void makerCanRejectOwnRefundGateFiresOnlyOnApprove() {
        RefundApplicationMapper refundMapper = mock(RefundApplicationMapper.class);
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        SettlementDetailMapper settlementDetailMapper = mock(SettlementDetailMapper.class);
        RefundServiceImpl service = service(refundMapper, registrationMapper, settlementDetailMapper);
        RefundApplication refund = refund(10L, 99L, 2L);
        refund.setCreatedByUserId(7L);
        CmsRegistration registration = new CmsRegistration();
        registration.setPayableAmount(new BigDecimal("100.00"));
        registration.setId(99L);
        when(refundMapper.selectRefundApplicationById(10L)).thenReturn(refund);
        when(registrationMapper.selectCmsRegistrationById(99L)).thenReturn(registration);
        when(refundMapper.updateRefundStatusIfPending(eq(10L), eq(2), eq(7L), any(), any())).thenReturn(1);

        // 驳回(status=2)不打款 ⇒ 建单人本人可驳回;maker≠checker 只拦「批准」这一步
        int rows = service.reviewRefund(review(10L, 2), 7L);
        assertEquals(1, rows);
        verify(refundMapper).updateRefundStatusIfPending(eq(10L), eq(2), eq(7L), any(), any());
    }

    // ===== [K块补丁] uk_refund_idempotency 对非 registration 来源(如后台 RefundController.add
    //       任意 POST)裸列唯一约束不挡 NULL:enrichRegistrationRefund 只兜底 sourceType=registration,
    //       其它来源缺省 idempotencyKey 时必须在 saveRefund 里统一兜底,不能落 NULL。 =====

    @Test
    void nonRegistrationRefundWithoutIdempotencyKeyGetsAutoAssignedDefault() {
        RefundApplicationMapper refundMapper = mock(RefundApplicationMapper.class);
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        SettlementDetailMapper settlementDetailMapper = mock(SettlementDetailMapper.class);
        RefundServiceImpl service = service(refundMapper, registrationMapper, settlementDetailMapper);
        RefundApplication refund = new RefundApplication();
        refund.setSourceType("backoffice");
        refund.setSourceId(42L);
        refund.setRefundAmount(new BigDecimal("10.00"));
        when(refundMapper.insertRefundApplication(any(RefundApplication.class))).thenAnswer(invocation -> {
            RefundApplication inserted = invocation.getArgument(0);
            inserted.setId(1001L);
            return 1;
        });

        service.saveRefund(refund);

        ArgumentCaptor<RefundApplication> captor = forClass(RefundApplication.class);
        verify(refundMapper).insertRefundApplication(captor.capture());
        String key = captor.getValue().getIdempotencyKey();
        org.junit.jupiter.api.Assertions.assertNotNull(key,
                "非 registration 来源缺省 idempotencyKey 时不能落 NULL,否则 uk_refund_idempotency 唯一约束形同虚设");
        org.junit.jupiter.api.Assertions.assertTrue(key.startsWith("backoffice:42:"),
                "兜底值须可追溯来源(sourceType+sourceId 前缀),实际:" + key);
    }

    @Test
    void twoNonRegistrationRefundsWithoutIdempotencyKeyDoNotCollapseToTheSameNullValue() {
        // 负控:修复前 enrichRegistrationRefund 对非 registration 来源不做任何兜底,
        // 两次都会把 idempotencyKey 落 NULL 插入——MySQL UNIQUE KEY 语义下 NULL 与 NULL 不算重复,
        // 唯一约束完全不生效,能插入任意多条 IS NULL 的行。本用例断言修复后两次生成的兜底值
        // 都非空且互不相同,证明约束重新对这条路径起作用。
        RefundApplicationMapper refundMapper = mock(RefundApplicationMapper.class);
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        SettlementDetailMapper settlementDetailMapper = mock(SettlementDetailMapper.class);
        RefundServiceImpl service = service(refundMapper, registrationMapper, settlementDetailMapper);
        when(refundMapper.insertRefundApplication(any(RefundApplication.class))).thenAnswer(invocation -> {
            RefundApplication inserted = invocation.getArgument(0);
            inserted.setId(1001L);
            return 1;
        });

        RefundApplication first = new RefundApplication();
        first.setSourceType("backoffice");
        first.setSourceId(42L);
        first.setRefundAmount(new BigDecimal("10.00"));
        service.saveRefund(first);

        RefundApplication second = new RefundApplication();
        second.setSourceType("backoffice");
        second.setSourceId(42L);
        second.setRefundAmount(new BigDecimal("10.00"));
        service.saveRefund(second);

        ArgumentCaptor<RefundApplication> captor = forClass(RefundApplication.class);
        verify(refundMapper, org.mockito.Mockito.times(2)).insertRefundApplication(captor.capture());
        String key1 = captor.getAllValues().get(0).getIdempotencyKey();
        String key2 = captor.getAllValues().get(1).getIdempotencyKey();
        org.junit.jupiter.api.Assertions.assertNotNull(key1);
        org.junit.jupiter.api.Assertions.assertNotNull(key2);
        org.junit.jupiter.api.Assertions.assertNotEquals(key1, key2,
                "两次独立退款单的兜底 idempotencyKey 不能相同(更不能都是 NULL),否则唯一约束依旧挡不住重复落库");
    }

    @Test
    void alarmDeliveryFailureIsLoggedInsteadOfSwallowedAndKeepsPayoutResultFlowing() {
        // [P2-20] 退款待人工告警覆盖资金链路;投递失败若被静默吞掉,线上就是"没人知道有单待人工"。
        RefundApplicationMapper refundMapper = mock(RefundApplicationMapper.class);
        RefundServiceImpl service = service(refundMapper, mock(CmsRegistrationMapper.class),
                mock(SettlementDetailMapper.class));
        AlarmService alarmService = alarmServiceOf(service);
        doThrow(new IllegalStateException("webhook 5xx")).when(alarmService)
                .alarm(anyString(), anyString());
        // remark 属于退款单据正文,本类留痕不该把它二次带出去。
        // ⚠️ 断言范围仅限 RefundServiceImpl 自己的 logger:AlarmService 会无条件打
        //    [ALARM] title - detail,msg 本来就在系统日志里,这条不是全局脱敏证明。
        String remark = "REMARK_PII_9f3a";
        when(refundMapper.updatePayoutResultWithLease(10L, "lease",
                RefundApplication.PAYOUT_PENDING_MANUAL, remark)).thenReturn(1);

        List<ILoggingEvent> logs = captureRefundLogs(
                () -> assertDoesNotThrow(() -> service.markPayoutResult(10L, "lease", false, remark)));

        // 主流程照旧走完,且告警确实被尝试投递过(不是被条件绕开)。
        verify(alarmService).alarm("退款待人工", "原路退款受理失败 refundId=10 remark=" + remark);
        List<ILoggingEvent> errors = logs.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .collect(Collectors.toList());
        assertEquals(1, errors.size(), "告警投递失败必须留下且只留下一条 ERROR 事件");
        ILoggingEvent error = errors.get(0);
        String formatted = error.getFormattedMessage();
        assertTrue(formatted.contains("退款待人工"),
                "ERROR 事件必须带稳定的告警 title,否则无法按告警类型检索:" + formatted);
        assertNotNull(error.getThrowableProxy(),
                "ERROR 事件必须挂原始 throwable,否则丢掉失败原因");
        assertEquals(IllegalStateException.class.getName(),
                error.getThrowableProxy().getClassName(),
                "留痕必须是投递抛出的原始异常类型");
        assertFalse(formatted.contains(remark),
                "退款单据正文(remark)不得进入日志:" + formatted);
    }

    @Test
    void successfulAlarmDeliveryLeavesNoErrorNoise() {
        // 负控配套:告警正常投递时不能有 ERROR 噪音,否则上面的断言等于恒真。
        RefundApplicationMapper refundMapper = mock(RefundApplicationMapper.class);
        RefundServiceImpl service = service(refundMapper, mock(CmsRegistrationMapper.class),
                mock(SettlementDetailMapper.class));
        AlarmService alarmService = alarmServiceOf(service);
        when(refundMapper.updatePayoutResultWithLease(10L, "lease",
                RefundApplication.PAYOUT_PENDING_MANUAL, "ok")).thenReturn(1);

        List<ILoggingEvent> logs = captureRefundLogs(
                () -> assertDoesNotThrow(() -> service.markPayoutResult(10L, "lease", false, "ok")));

        verify(alarmService).alarm("退款待人工", "原路退款受理失败 refundId=10 remark=ok");
        for (ILoggingEvent event : logs) {
            assertNotEquals(Level.ERROR, event.getLevel(),
                    "告警投递成功时不应出现 ERROR 日志:" + event.getFormattedMessage());
        }
    }

    /** 捕获 RefundServiceImpl 自己打的日志 */
    private List<ILoggingEvent> captureRefundLogs(Runnable action) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(RefundServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Level original = logger.getLevel();
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(original);
        }
        return appender.list;
    }

    private AlarmService alarmServiceOf(RefundServiceImpl service) {
        return (AlarmService) ReflectionTestUtils.getField(service, "alarmService");
    }

    private RefundServiceImpl service(RefundApplicationMapper refundMapper,
                                      CmsRegistrationMapper registrationMapper,
                                      SettlementDetailMapper settlementDetailMapper) {
        return service(refundMapper, registrationMapper, settlementDetailMapper, mock(CmsActivityMapper.class), mock(CmsTopicMapper.class));
    }

    private RefundServiceImpl service(RefundApplicationMapper refundMapper,
                                      CmsRegistrationMapper registrationMapper,
                                      SettlementDetailMapper settlementDetailMapper,
                                      CmsActivityMapper activityMapper,
                                      CmsTopicMapper topicMapper) {
        RefundServiceImpl service = new RefundServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "firstReviewPointsClawback",
                org.mockito.Mockito.mock(com.chengyinhub.business.service.impl.FirstReviewPointsClawbackService.class));
        ReflectionTestUtils.setField(service, "refundApplicationMapper", refundMapper);
        // [P0-15] ③ 退款释放权益容量 —— 本类不测该链路,注入空 mock 即可;
        //        不注入会 NPE(手工装配不走 Spring 容器)。
        ReflectionTestUtils.setField(service, "perkLedgerService",
                org.mockito.Mockito.mock(com.chengyinhub.business.service.IPerkLedgerService.class));
        // [赏金] markRegistrationRefunded 末尾会调 onRefund;本用例不管赏金,哑 mock 即可。
        ReflectionTestUtils.setField(service, "merchantReferralBountyService",
                mock(com.chengyinhub.business.service.impl.MerchantReferralBountyService.class));
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrationMapper);
        when(registrationMapper.selectCmsRegistrationByIdForUpdate(any()))
                .thenAnswer(call -> registrationMapper.selectCmsRegistrationById(call.getArgument(0)));
        when(registrationMapper.claimRegistrationRefunded(any(), any(), any())).thenReturn(1);
        ReflectionTestUtils.setField(service, "settlementDetailMapper", settlementDetailMapper);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "umsMemberMapper", mock(com.chengyinhub.business.mapper.UmsMemberMapper.class));
        ReflectionTestUtils.setField(service, "umsMemberBalanceDetailMapper", mock(com.chengyinhub.business.mapper.UmsMemberBalanceDetailMapper.class));
        // [P2-20] 告警出口用哑 mock:不注入会 NPE(手工装配不走 Spring 容器),
        //         而 NPE 恰好被 alarm() 的 best-effort catch 接住,会让告警断言变成假绿。
        ReflectionTestUtils.setField(service, "alarmService", mock(AlarmService.class));
        return service;
    }

    private RefundReviewDTO review(Long id, Integer status) {
        return review(id, status, "ok");
    }

    private RefundReviewDTO review(Long id, Integer status, String reason) {
        RefundReviewDTO dto = new RefundReviewDTO();
        dto.setId(id);
        dto.setStatus(status);
        dto.setReviewReason(reason);
        return dto;
    }

    // ===== FX-14:POST /business/refund 带 id 能改写已有退款单 / 新增时自带「已批准」态 =====

    @Test
    void saveRefundWithIdIsRejectedAndNeverUpdatesExistingRow() {
        RefundApplicationMapper refundMapper = mock(RefundApplicationMapper.class);
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        RefundServiceImpl service = service(refundMapper, registrationMapper, mock(SettlementDetailMapper.class));
        when(registrationMapper.selectCmsRegistrationById(99L)).thenReturn(paidRegistration99());
        RefundApplication forged = refund(7L, 99L, 2L);
        forged.setRefundAmount(new BigDecimal("50.00")); // 合法金额,确保拦它的是 id 闸而不是金额闸
        forged.setStatus(1);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.saveRefund(forged));
        assertTrue(e.getMessage().contains("只能新增"), "必须是 id 闸拦下,不是别的闸:" + e.getMessage());
        verify(refundMapper, never()).insertRefundApplication(any());
    }

    @Test
    void newRefundIgnoresClientStatusPayoutAndPaidAmount() {
        RefundApplicationMapper refundMapper = mock(RefundApplicationMapper.class);
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        RefundServiceImpl service = service(refundMapper, registrationMapper, mock(SettlementDetailMapper.class));
        when(registrationMapper.selectCmsRegistrationById(99L)).thenReturn(paidRegistration99());
        when(refundMapper.insertRefundApplication(any(RefundApplication.class))).thenAnswer(invocation -> {
            RefundApplication inserted = invocation.getArgument(0);
            inserted.setId(1001L);
            return 1;
        });
        RefundApplication forged = refund(null, 99L, 2L);
        forged.setRefundAmount(new BigDecimal("50.00"));
        forged.setStatus(1);                                            // 自批
        forged.setPayoutStatus(RefundApplication.PAYOUT_SUCCESS);      // 伪造「已到账」
        forged.setPaidAmount(new BigDecimal("999.00"));

        service.saveRefund(forged);

        ArgumentCaptor<RefundApplication> captor = forClass(RefundApplication.class);
        verify(refundMapper).insertRefundApplication(captor.capture());
        RefundApplication saved = captor.getValue();
        assertEquals(0, saved.getStatus(), "新增只能是待审核,不能自带批准态绕过 reviewRefund");
        assertEquals(RefundApplication.PAYOUT_DISPATCH_PENDING, saved.getPayoutStatus());
        assertEquals(0, new BigDecimal("68.00").compareTo(saved.getPaidAmount()), "实付额只认报名单,不认前端");
    }

    @Test
    void refundAboveServerPaidAmountRejectedEvenIfClientInflatesPaidAmount() {
        RefundApplicationMapper refundMapper = mock(RefundApplicationMapper.class);
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        RefundServiceImpl service = service(refundMapper, registrationMapper, mock(SettlementDetailMapper.class));
        when(registrationMapper.selectCmsRegistrationById(99L)).thenReturn(paidRegistration99());
        RefundApplication forged = refund(null, 99L, 2L);
        forged.setPaidAmount(new BigDecimal("999.00"));
        forged.setRefundAmount(new BigDecimal("500.00"));

        assertThrows(IllegalArgumentException.class, () -> service.saveRefund(forged));
        verify(refundMapper, never()).insertRefundApplication(any());
    }

    private CmsRegistration paidRegistration99() {
        CmsRegistration registration = new CmsRegistration();
        registration.setPayableAmount(new BigDecimal("100.00"));
        registration.setId(99L);
        registration.setPaymentStatus(2);
        registration.setPayableAmount(new BigDecimal("68.00"));
        return registration;
    }

    private RefundApplication refund(Long id, Long registrationId, Long ownerType) {
        RefundApplication refund = new RefundApplication();
        refund.setId(id);
        refund.setSourceType("registration");
        refund.setSourceId(registrationId);
        refund.setOwnerType(ownerType);
        refund.setRefundAmount(new BigDecimal("80.00"));
        refund.setStatus(0);
        return refund;
    }
}
