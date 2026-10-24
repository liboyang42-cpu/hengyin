package com.chengyinhub.business.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.WxPaymentCallbackInbox;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.service.impl.AlarmService;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;

/**
 * W3(2026-10-22,#11 R1):报名支付回调必须与退款等回调同走 inbox fence ——
 * 先登记(同 hash 换业务即拒收),再 process 领取处理权,业务只在 fence 内推进。
 */
class PaymentCallbackFenceEntryTest
{
    private static final String NOTIFY_ID = "PAY:TX-ORIGINAL:CLOSED";

    private CmsRegistrationServiceImpl service;
    private CmsRegistrationMapper registrations;
    private IWxPaymentCallbackInboxService inbox;
    private AlarmService alarm;
    private CmsRegistration registration;
    private Map<String, String> payload;

    @BeforeEach
    void setUp()
    {
        service = new CmsRegistrationServiceImpl();
        registrations = mock(CmsRegistrationMapper.class);
        inbox = mock(IWxPaymentCallbackInboxService.class);
        alarm = mock(AlarmService.class);
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrations);
        ReflectionTestUtils.setField(service, "wxPaymentCallbackInboxService", inbox);
        ReflectionTestUtils.setField(service, "alarmService", alarm);
        var gateway = mock(com.chengyinhub.business.service.IWechatPayService.class);
        when(gateway.lockPaymentAttemptForUpdate("A-ORIGINAL", "A-ORIGINAL")).thenReturn(true);
        var attempts = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(attempts.getObject()).thenReturn(gateway);
        ReflectionTestUtils.setField(service, "paymentAttemptGateway", attempts);
        registration = new CmsRegistration();
        registration.setId(11L);
        registration.setRegistrationNo("A-ORIGINAL");
        registration.setWechatPaymentAmount(new BigDecimal("1.00"));
        when(registrations.selectByRegistrationNo("A-ORIGINAL")).thenReturn(registration);
        payload = new HashMap<>();
        payload.put("out_trade_no", "A-ORIGINAL");
        payload.put("wx_out_trade_no", "A-ORIGINAL");
        payload.put("transaction_id", "TX-ORIGINAL");
        payload.put("trade_state", "CLOSED");
        when(inbox.register(anyString(), anyInt(), anyLong(), any())).thenReturn(true);
    }

    @Test
    void registrationCallbackRegistersThenAcquiresFenceBeforeMutatingOrder()
    {
        CmsRegistration closedAlready = new CmsRegistration();
        closedAlready.setPaymentStatus(3);
        closedAlready.setRegistrationStatus(1);
        when(registrations.selectCmsRegistrationByIdForUpdate(11L)).thenReturn(closedAlready);
        when(inbox.process(anyString(), any())).thenAnswer(call -> runMutation(call.getArgument(1), row(11L, "CLOSED")));

        assertEquals("SUCCESS", service.handleWechatPayCallback(payload));

        InOrder order = inOrder(inbox, registrations);
        order.verify(inbox).register(NOTIFY_ID, WxPaymentCallbackInbox.BIZ_TYPE_PAYMENT, 11L, "CLOSED");
        order.verify(inbox).process(eq(NOTIFY_ID), any());
        order.verify(registrations).claimPaymentFailure(eq(11L), any(Date.class));
    }

    @Test
    void replayOfAlreadyDoneNotifyIsIdempotentSuccessWithoutTouchingOrder()
    {
        // 真实 inbox 对 DONE 行直接返回 true、不再跑 mutation。
        when(inbox.register(anyString(), anyInt(), anyLong(), any())).thenReturn(false);
        when(inbox.process(eq(NOTIFY_ID), any())).thenReturn(true);

        assertEquals("SUCCESS", service.handleWechatPayCallback(payload));
        assertEquals("SUCCESS", service.handleWechatPayCallback(payload));

        verify(registrations, never()).claimPaymentFailure(anyLong(), any());
        verify(registrations, never()).selectCmsRegistrationByIdForUpdate(anyLong());
        verifyNoInteractions(alarm);
    }

    @Test
    void sameHashRegisteredForAnotherBusinessIsRejectedNotTreatedAsDuplicateSuccess()
    {
        doThrow(new IWxPaymentCallbackInboxService.IdentityConflictException("conflict"))
                .when(inbox).register(anyString(), anyInt(), anyLong(), any());

        assertEquals("FAIL", service.handleWechatPayCallback(payload));

        verify(inbox, never()).process(anyString(), any());
        verify(registrations, never()).claimPaymentFailure(anyLong(), any());
        verify(alarm).alarmThrottled(eq("payment.callback.registration"), anyString(),
                contains("reason=inbox_identity_conflict"));
    }

    @Test
    void fencedRowOfAnotherBusinessNeverMutatesThisOrder()
    {
        when(inbox.process(anyString(), any())).thenAnswer(call -> runMutation(call.getArgument(1), row(99L, "CLOSED")));

        assertEquals("FAIL", service.handleWechatPayCallback(payload));

        verify(registrations, never()).claimPaymentFailure(anyLong(), any());
        verify(registrations, never()).selectCmsRegistrationByIdForUpdate(anyLong());
    }

    @Test
    void closedCallbackMissingObservedAttemptCannotMutateOrder()
    {
        payload.remove("wx_out_trade_no");
        when(inbox.process(anyString(), any())).thenAnswer(call -> runMutation(call.getArgument(1), row(11L, "CLOSED")));
        assertEquals("FAIL", service.handleWechatPayCallback(payload));
        verify(registrations, never()).claimPaymentFailure(anyLong(), any());
        verify(alarm).alarmThrottled(eq("payment.callback.registration"), anyString(),
                contains("reason=payment_attempt_conflict"));
    }

    @Test
    void busyFenceKeepsOrderUnchangedAndAsksWechatToRetry()
    {
        when(inbox.process(anyString(), any())).thenReturn(false);

        assertEquals("FAIL", service.handleWechatPayCallback(payload));

        verify(registrations, never()).claimPaymentFailure(anyLong(), any());
        verify(alarm).alarmThrottled(eq("payment.callback.registration"), anyString(),
                contains("reason=callback_not_completed"));
    }

    @Test
    void businessFailureInsideFenceReportsItsOwnReasonOnce()
    {
        when(registrations.selectCmsRegistrationByIdForUpdate(11L)).thenReturn(null);
        when(inbox.process(anyString(), any())).thenAnswer(call -> runMutation(call.getArgument(1), row(11L, "CLOSED")));

        assertEquals("FAIL", service.handleWechatPayCallback(payload));

        verify(alarm).alarmThrottled(eq("payment.callback.registration"), anyString(),
                contains("reason=order_disappeared"));
        verify(alarm, never()).alarmThrottled(anyString(), anyString(), contains("callback_not_completed"));
    }

    @Test
    void successWithDifferentAmountIsRejectedBeforeEnteringInbox()
    {
        payload.put("trade_state", "SUCCESS");
        payload.put("total", "99");

        assertEquals("FAIL", service.handleWechatPayCallback(payload));

        verifyNoInteractions(inbox);
        verify(alarm).alarmThrottled(eq("payment.callback.registration"), anyString(),
                contains("reason=amount_mismatch"));
    }

    @Test
    void callbackInsideUncommittedTransactionFailsClosedWithoutClaiming()
    {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try
        {
            assertEquals("FAIL", service.handleWechatPayCallback(payload));
        }
        finally
        {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verifyNoInteractions(inbox);
        verify(alarm).alarmThrottled(eq("payment.callback.registration"), anyString(),
                contains("reason=callback_inside_transaction"));
    }

    @Test
    void inTransactionConfirmedPaymentRequiresCallerTransactionAndBypassesInbox() throws Exception
    {
        Transactional tx = CmsRegistrationServiceImpl.class
                .getMethod("applyConfirmedWechatPaymentWithinTransaction", Map.class)
                .getAnnotation(Transactional.class);
        assertEquals(Propagation.MANDATORY, tx.propagation());

        payload.put("trade_state", "SUCCESS");
        payload.put("total", "99");
        assertEquals("FAIL", service.applyConfirmedWechatPaymentWithinTransaction(payload));
        verifyNoInteractions(inbox);
    }

    private static WxPaymentCallbackInbox row(long bizId, String eventType)
    {
        WxPaymentCallbackInbox row = new WxPaymentCallbackInbox();
        row.setBizType(WxPaymentCallbackInbox.BIZ_TYPE_PAYMENT);
        row.setBizId(bizId);
        row.setEventType(eventType);
        return row;
    }

    /** 与 WxPaymentCallbackMutationService 同语义:isCurrent/apply 抛错或 apply=false 即不完成。 */
    private static boolean runMutation(IWxPaymentCallbackInboxService.Mutation mutation, WxPaymentCallbackInbox row)
    {
        try
        {
            return !mutation.isCurrent(row) || mutation.apply(row);
        }
        catch (RuntimeException failed)
        {
            return false;
        }
    }
}
