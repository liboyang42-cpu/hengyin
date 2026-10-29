package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference;
import com.chengyinhub.business.service.RefundRequestResult;
import com.chengyinhub.common.config.WxPayConfig;
import com.wechat.pay.java.service.payments.jsapi.JsapiService;
import com.wechat.pay.java.service.refund.RefundService;
import com.wechat.pay.java.service.refund.model.CreateRequest;
import com.wechat.pay.java.service.refund.model.Refund;
import com.wechat.pay.java.service.refund.model.Status;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Unit SDK boundary only: real immutable fact/hash checks, memory storage, no MySQL/provider proof. */
class WechatRefundOriginalFactSubmitTest {
    @Test void originalReferenceUsesPaidTransactionEvenAfterCurrentAliasChangesAndReplaysSameRequest() {
        Fixture f=new Fixture();
        when(f.intents.currentWechatPaymentNo("REG1","merchant")).thenReturn("W2");
        assertEquals(RefundRequestResult.ACCEPTED,f.submit(f.paid,"REG1",new BigDecimal("10.00")));
        assertEquals(RefundRequestResult.ACCEPTED,f.submit(f.paid,"REG1",new BigDecimal("10.00")));
        var requests=ArgumentCaptor.forClass(CreateRequest.class);
        verify(f.refunds,times(2)).create(requests.capture());
        for(var request:requests.getAllValues()) {
            assertEquals("TX1",request.getTransactionId());assertNull(request.getOutTradeNo());
            assertEquals("RF1",request.getOutRefundNo());
            assertEquals(200L,request.getAmount().getRefund().longValue());
            assertEquals(1000L,request.getAmount().getTotal().longValue());
            assertEquals("CNY",request.getAmount().getCurrency());assertEquals("partial",request.getReason());
        }
        verify(f.intents,never()).currentWechatPaymentNo(anyString(),anyString());
        verifyNoInteractions(f.payments);
    }
    @Test void legacySignatureRequiresTheUniqueOriginalFactAndNeverResolvesCurrentAlias() {
        Fixture f=new Fixture();
        f.facts.recordVerified(new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.REFUND,
                "merchant","wx-original","REG1","W1","RF-OTHER","WXRF-OTHER","TX1","APP","CNY",1000,200,
                Instant.parse(WechatVerifiedFactTestSupport.SUCCESS_TIME),"a".repeat(64),"SUCCESS"));
        assertEquals(RefundRequestResult.ACCEPTED,f.gateway.submitRefund("REG1","RF1",new BigDecimal("2.00"),new BigDecimal("10.00"),"partial"));
        var request=ArgumentCaptor.forClass(CreateRequest.class);verify(f.refunds).create(request.capture());
        assertEquals("TX1",request.getValue().getTransactionId());assertNull(request.getValue().getOutTradeNo());
        verify(f.intents,never()).currentWechatPaymentNo(anyString(),anyString());
    }
    @Test void legacyMissingAmbiguousOrCorruptOriginalNeverReachesSdk() {
        Fixture f=new Fixture();
        assertEquals(RefundRequestResult.UNKNOWN,f.gateway.submitRefund("MISSING","RF1",BigDecimal.ONE,BigDecimal.TEN,"partial"));
        var duplicate=new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.PAYMENT,
                "other-merchant","wx-other","REG1","W2","W2","TX2","TX2","APP","CNY",1000,1000,
                Instant.parse(WechatVerifiedFactTestSupport.SUCCESS_TIME),"b".repeat(64),"SUCCESS");
        assertEquals(1,f.rows.insertIgnore(WechatVerifiedPaymentFactService.columns(duplicate)));
        assertEquals(RefundRequestResult.UNKNOWN,f.gateway.submitRefund("REG1","RF1",BigDecimal.ONE,BigDecimal.TEN,"partial"));
        f.rows.rows.remove(2L);f.rows.rows.get(f.paid.id()).put("total_fen",999L);
        assertEquals(RefundRequestResult.UNKNOWN,f.gateway.submitRefund("REG1","RF1",BigDecimal.ONE,BigDecimal.TEN,"partial"));
        verifyNoInteractions(f.refunds,f.payments,f.intents);
    }
    @Test void bothSignaturesRejectMissingTotalRatherThanInventingFullRefund() {
        Fixture f=new Fixture();
        assertEquals(RefundRequestResult.DEFINITIVE_FAILED,f.submit(f.paid,"REG1",null));
        assertEquals(RefundRequestResult.DEFINITIVE_FAILED,f.gateway.submitRefund("REG1","RF1",new BigDecimal("2.00"),null,"partial"));
        verifyNoInteractions(f.refunds,f.payments,f.intents);
    }
    @Test void invalidRefundAmountsAndIdentifiersNeverReachSdk() {
        Fixture f=new Fixture();
        for(var amount:List.of(new BigDecimal("0"),new BigDecimal("10.01"),new BigDecimal("0.001"),new BigDecimal("21474836.48"))) {
            assertEquals(RefundRequestResult.DEFINITIVE_FAILED,
                    f.gateway.submitRefund(f.paid,"REG1","RF1",amount,new BigDecimal("10.00"),"partial"));
        }
        assertEquals(RefundRequestResult.DEFINITIVE_FAILED,
                f.gateway.submitRefund(f.paid,"REG1","",new BigDecimal("2.00"),new BigDecimal("10.00"),"partial"));
        verifyNoInteractions(f.refunds,f.payments);
    }
    @Test void wrongHashBusinessMerchantTotalOrFrozenSnapshotKeepsUnknownWithoutCallingSdk() {
        Fixture f=new Fixture();
        assertEquals(RefundRequestResult.UNKNOWN,f.submit(null,"REG1",new BigDecimal("10.00")));
        assertEquals(RefundRequestResult.UNKNOWN,f.submit(new Reference(f.paid.id(),"b".repeat(64)),"REG1",new BigDecimal("10.00")));
        assertEquals(RefundRequestResult.UNKNOWN,f.submit(f.paid,"REG2",new BigDecimal("10.00")));
        assertEquals(RefundRequestResult.UNKNOWN,f.submit(f.paid,"REG1",new BigDecimal("9.00")));
        WechatVerifiedFactTestSupport.bind(f.gateway,f.payments,f.refunds,"other-merchant");
        assertEquals(RefundRequestResult.UNKNOWN,f.submit(f.paid,"REG1",new BigDecimal("10.00")));
        WechatVerifiedFactTestSupport.bind(f.gateway,f.payments,f.refunds,"merchant");
        when(f.intents.frozenRequestHash("merchant","W1")).thenReturn("b".repeat(64));
        assertEquals(RefundRequestResult.UNKNOWN,f.submit(f.paid,"REG1",new BigDecimal("10.00")));
        verifyNoInteractions(f.refunds,f.payments);
    }
    @Test void timeoutAndProviderErrorsRemainUnknownAndNeverChangeThePaymentReference() {
        Fixture f=new Fixture();
        doThrow(new RuntimeException("timeout")).when(f.refunds).create(any());
        assertEquals(RefundRequestResult.UNKNOWN,f.submit(f.paid,"REG1",new BigDecimal("10.00")));
        var error=mock(com.wechat.pay.java.core.exception.ServiceException.class);
        when(error.getHttpStatusCode()).thenReturn(500);
        doThrow(error).when(f.refunds).create(any());
        assertEquals(RefundRequestResult.UNKNOWN,f.submit(f.paid,"REG1",new BigDecimal("10.00")));
        Refund mismatched=new Refund();mismatched.setStatus(Status.SUCCESS);mismatched.setTransactionId("TX2");
        doReturn(mismatched).when(f.refunds).create(any());
        assertEquals(RefundRequestResult.UNKNOWN,f.submit(f.paid,"REG1",new BigDecimal("10.00")));
        assertEquals("TX1",f.facts.requireVerified(f.paid).facts().transactionId());
        verify(f.intents,never()).currentWechatPaymentNo(anyString(),anyString());
    }
    @Test void activeBusinessTransactionFailsBeforeAnySdkOrFactRead() {
        Fixture f=new Fixture();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class,()->f.submit(f.paid,"REG1",new BigDecimal("10.00")));
            verifyNoInteractions(f.refunds,f.payments,f.intents);
        } finally {TransactionSynchronizationManager.setActualTransactionActive(false);}
    }
    private static final class Fixture {
        final WechatPayServiceImpl gateway=WechatVerifiedFactTestSupport.gateway();
        final WechatVerifiedPaymentFactService facts=new WechatVerifiedPaymentFactService();
        final WechatVerifiedFactTestSupport.MemoryMapper rows=new WechatVerifiedFactTestSupport.MemoryMapper();
        final JsapiService payments=mock(JsapiService.class);
        final RefundService refunds=mock(RefundService.class);
        final WechatPaymentIntentStore intents=mock(WechatPaymentIntentStore.class);
        final Reference paid;
        Fixture() {
            WechatVerifiedFactTestSupport.setField(facts,"mapper",rows);
            paid=facts.recordVerified(new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.PAYMENT,
                    "merchant","wx-original","REG1","W1","W1","TX1","TX1","APP","CNY",1000,1000,
                    Instant.parse(WechatVerifiedFactTestSupport.SUCCESS_TIME),"a".repeat(64),"SUCCESS"));
            WechatVerifiedFactTestSupport.setField(gateway,"verifiedFacts",facts);
            WechatVerifiedFactTestSupport.setField(gateway,"intents",intents);
            WechatVerifiedFactTestSupport.setField(gateway,"wxPayConfig",new WxPayConfig());
            WechatVerifiedFactTestSupport.setField(gateway,"refundService",refunds);
            WechatVerifiedFactTestSupport.bind(gateway,payments,refunds,"merchant");
            when(intents.businessPaymentOrderNo("W1","merchant")).thenReturn("REG1");
            when(intents.frozenRequestHash("merchant","W1")).thenReturn("a".repeat(64));
            when(intents.frozenFacts("merchant","W1")).thenReturn(List.of(Map.of(
                    "appid","wx-original","trade_type","APP","currency","CNY","total_fen",1000L)));
            Refund accepted=new Refund();accepted.setStatus(Status.PROCESSING);
            accepted.setTransactionId("TX1");accepted.setOutTradeNo("W1");accepted.setOutRefundNo("RF1");
            var amount=new com.wechat.pay.java.service.refund.model.Amount();
            amount.setTotal(1000L);amount.setRefund(200L);amount.setCurrency("CNY");accepted.setAmount(amount);
            when(refunds.create(any())).thenReturn(accepted);
        }
        RefundRequestResult submit(Reference reference,String business,BigDecimal total) {
            return gateway.submitRefund(reference,business,"RF1",new BigDecimal("2.00"),total,"partial");
        }
    }
}
