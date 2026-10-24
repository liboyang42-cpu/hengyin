package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.IWxPaymentCallbackInboxService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Typed reader boundary unit tests; actual DB provenance/link/atomicity lives in RegistrationTopicFenceMySqlIT. */
class VerifiedRegistrationReceiptGuardTest {
    private CmsRegistrationServiceImpl payments;
    private RefundServiceImpl refunds;
    private CmsRegistrationMapper registrations;
    private RefundApplicationMapper applications;
    private WechatVerifiedPaymentFactService reader;
    private IWxPaymentCallbackInboxService inbox;
    private WechatVerifiedPaymentFactService.Stored payment, refund;

    @BeforeEach void fixtures() {
        payments = new CmsRegistrationServiceImpl(); refunds = new RefundServiceImpl();
        registrations = mock(CmsRegistrationMapper.class); applications = mock(RefundApplicationMapper.class);
        reader = mock(WechatVerifiedPaymentFactService.class); inbox = mock(IWxPaymentCallbackInboxService.class);
        // Unit scope: preserve A's receipt/busy boundary; this is not G cash-guard evidence.
        var paymentCashAdmission = mock(com.chengyinhub.business.service.ICoopSettlementClaimService.class);
        when(paymentCashAdmission.retainClosedPaidHalfStateSuccess(org.mockito.ArgumentMatchers.anyLong(), any())).thenReturn(true);
        ReflectionTestUtils.setField(payments, "coopSettlementClaimService", paymentCashAdmission);
        CmsRegistration registration = new CmsRegistration(); registration.setId(1L); registration.setRegistrationNo("REG1");
        registration.setOwnerType(1L); registration.setOwnerId(7L); registration.setMemberId(11L);
        registration.setWechatPaymentAmount(new BigDecimal("30.00"));
        registration.setTransactionId("TX1");
        when(registrations.selectByRegistrationNo("REG1")).thenReturn(registration);
        when(registrations.selectCmsRegistrationById(1L)).thenReturn(registration);
        when(registrations.selectCmsRegistrationByIdForUpdate(1L)).thenReturn(registration);
        CmsTopicMapper topics = mock(CmsTopicMapper.class); CmsTopic topic = new CmsTopic(); topic.setId(7L);
        when(topics.selectCmsTopicByIdForUpdate(7L)).thenReturn(topic);
        for (Object service : new Object[]{payments, refunds}) {
            ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrations);
            ReflectionTestUtils.setField(service, "verifiedPaymentFacts", reader);
            ReflectionTestUtils.setField(service, "wxPaymentCallbackInboxService", inbox);
            ReflectionTestUtils.setField(service, "alarmService", mock(AlarmService.class));
            ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
        }
        ReflectionTestUtils.setField(refunds, "refundApplicationMapper", applications);
        RefundApplication application = new RefundApplication(); application.setId(9L); application.setSourceType("registration");
        application.setSourceId(1L); application.setRefundNo("RF1"); application.setRefundAmount(new BigDecimal("10.00"));
        when(applications.selectByRefundNo("RF1")).thenReturn(application);
        payment = stored(1L, new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.PAYMENT,
                "mch-origin", "wx-origin", "REG1", "WXREG1", "WXREG1", "TX1", "TX1", "JSAPI", "CNY",
                3000, 3000, Instant.parse("2026-10-24T00:00:00Z"), null, "SUCCESS"));
        refund = stored(2L, new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.REFUND,
                "mch-origin", "wx-origin", "REG1", "WXREG1", "RF1", "WXREF1", "TX1", "JSAPI", "CNY",
                3000, 1000, Instant.parse("2026-10-24T00:00:00Z"), null, "SUCCESS"));
        when(reader.requireVerified(payment.reference())).thenReturn(payment);
        when(reader.requireVerified(refund.reference())).thenReturn(refund);
    }

    private WechatVerifiedPaymentFactService.Stored stored(long id, WechatVerifiedPaymentFact fact) {
        return new WechatVerifiedPaymentFactService.Stored(new WechatVerifiedPaymentFact.Reference(id, fact.factHash()), fact);
    }
    private Map<String, String> paymentMap() {
        Map<String, String> map = new HashMap<>(Map.of("out_trade_no", "REG1", "wx_out_trade_no", "WXREG1",
                "transaction_id", "TX1", "trade_state", "SUCCESS", "total", "3000", "mchid", "mch-origin",
                "appid", "wx-origin", "trade_type", "JSAPI", "currency", "CNY"));
        map.put("verified_fact_id", Long.toString(payment.reference().id())); map.put("verified_fact_hash", payment.reference().hash());
        return map;
    }
    private Map<String, String> refundMap() {
        Map<String, String> map = new HashMap<>(Map.of("outRefundNo", "RF1", "outTradeNo", "REG1", "status", "SUCCESS",
                "refund", "1000", "total", "3000", "currency", "CNY", "mchid", "mch-origin", "appid", "wx-origin"));
        map.put("refundId", "WXREF1"); map.put("transactionId", "TX1"); map.put("wxOutTradeNo", "WXREG1"); map.put("tradeType", "JSAPI");
        map.put("verified_fact_id", Long.toString(refund.reference().id())); map.put("verified_fact_hash", refund.reference().hash());
        return map;
    }

    @Test void paymentSuccessTextWithoutReferenceNeverReachesBusinessOrInbox() {
        Map<String, String> map = paymentMap(); map.remove("verified_fact_id");
        assertEquals("FAIL", payments.handleWechatPayCallback(map));
        verifyNoInteractions(inbox, registrations);
    }
    @Test void paymentReferenceCannotBeReusedForAnotherOriginalBusinessNumber() {
        Map<String, String> map = paymentMap(); map.put("out_trade_no", "REG-other");
        assertEquals("FAIL", payments.handleWechatPayCallback(map));
        verifyNoInteractions(inbox, registrations);
    }
    @Test void validPaymentUsesVerifiedProcessorAndDoesNotTreatBusyAsCompleted() {
        assertEquals("FAIL", payments.handleWechatPayCallback(paymentMap())); // unstubbed processor is busy/false
        verify(inbox).registerVerified("PAY:TX1:SUCCESS", 1, 1L, "SUCCESS", payment.reference());
        verify(inbox).processVerified(eq("PAY:TX1:SUCCESS"), eq(payment.reference()), any());
        verify(inbox, never()).register(anyString(), anyInt(), anyLong(), any());
        verify(registrations, never()).claimPaymentSuccess(anyLong(), anyString(), any());
        verify((com.chengyinhub.business.service.ICoopSettlementClaimService)
                ReflectionTestUtils.getField(payments, "coopSettlementClaimService"))
                .retainClosedPaidHalfStateSuccess(1L, payment.reference());
    }
    @Test void inlineConfirmedPathAlsoRequiresTheRealReferenceBeforeAnyPaymentCas() {
        Map<String, String> map = paymentMap(); map.remove("verified_fact_hash");
        assertThrows(RuntimeException.class, () -> payments.applyConfirmedWechatPaymentWithinTransaction(map));
        verify(registrations, never()).claimPaymentSuccess(anyLong(), anyString(), any()); verifyNoInteractions(inbox);
    }
    @Test void lowercaseRefundSuccessCannotBypassTheRequiredVerifiedReference() {
        Map<String, String> map = refundMap(); map.put("status", "success"); map.remove("verified_fact_id");
        assertFalse(refunds.handleRefundNotify(map));
        verifyNoInteractions(inbox); verify(applications, never()).updatePayoutResult(anyLong(), anyInt(), anyString());
    }
    @Test void verifiedRefundCannotBeReboundToADifferentProviderRefund() {
        Map<String, String> map = refundMap(); map.put("refundId", "OTHER-WXREF");
        assertFalse(refunds.handleRefundNotify(map)); verifyNoInteractions(inbox);
        verify(applications, never()).updatePayoutResult(anyLong(), anyInt(), anyString());
    }
    @Test void aPaymentReceiptCannotAuthorizeARefundTerminal() {
        Map<String, String> map = refundMap(); map.put("verified_fact_id", "1"); map.put("verified_fact_hash", payment.reference().hash());
        assertFalse(refunds.handleRefundNotify(map)); verifyNoInteractions(inbox);
    }
    @Test void validRefundUsesTheBoundProcessorAndKeepsBusyPending() {
        assertFalse(refunds.handleRefundNotify(refundMap()));
        verify(inbox).registerVerified("REFUND:RF1:SUCCESS", 2, 9L, "SUCCESS", refund.reference());
        verify(inbox).processVerified(eq("REFUND:RF1:SUCCESS"), eq(refund.reference()), any());
        verify(inbox, never()).process(anyString(), any());
        verify(applications, never()).updatePayoutResult(anyLong(), anyInt(), anyString());
    }
    @Test void unavailableFactReaderNeverDegradesToMapOnlyPaymentSuccess() {
        when(reader.requireVerified(payment.reference())).thenThrow(new IllegalStateException("fixture read unavailable"));
        assertEquals("FAIL", payments.handleWechatPayCallback(paymentMap())); verifyNoInteractions(inbox, registrations);
    }
}
