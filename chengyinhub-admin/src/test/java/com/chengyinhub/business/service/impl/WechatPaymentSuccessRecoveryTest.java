package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.service.PaymentPreorderException;
import com.wechat.pay.java.service.payments.model.*;
import com.wechat.pay.java.service.refund.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real gateway/fact algorithms, SDK and domain boundary doubles. Real domain+inbox retry also has a separate MySQL test. */
class WechatPaymentSuccessRecoveryTest {
    @Test void sameSignedPaymentSuccessRecoversAfterDomainFailureWithoutASecondProviderQuery() {
        var f=new WechatVerifiedPaymentFactGatewayTest.Fixture();Transaction notification=payment();
        when(f.parser.parse(any(),eq(Transaction.class))).thenReturn(notification);
        when(f.registration.getByRegistrationNo("REG1")).thenReturn(new com.chengyinhub.business.domain.CmsRegistration());
        when(f.registration.handleWechatPayCallback(anyMap())).thenReturn("FAIL","SUCCESS");
        assertTrue(f.gateway.handlePayCallback("signed-double",Map.of()).contains("FAIL"));
        var original=f.rows.rows.values().iterator().next();long id=((Number)original.get("id")).longValue();String hash=(String)original.get("fact_hash");
        doThrow(new RuntimeException("provider query outage")).when(f.payments).queryOrderByOutTradeNo(any());clearInvocations(f.payments);
        assertTrue(f.gateway.handlePayCallback("same-signed-double",Map.of()).contains("SUCCESS"));
        assertEquals(1,f.rows.rows.size());assertEquals(hash,f.rows.rows.get(id).get("fact_hash"));
        verify(f.registration,times(2)).handleWechatPayCallback(argThat(map->Long.toString(id).equals(map.get("verified_fact_id"))
                && hash.equals(map.get("verified_fact_hash")) && "REG1".equals(map.get("wx_out_trade_no"))));
        verifyNoInteractions(f.payments);
    }
    @Test void allFourExistingRoutesReuseOnlyTheirOriginalSuccessThroughTheRealFacadeAndRouter() {
        for (String no:List.of("CLM1","TPLTM_1","DEP1","REG1")) {
            var f=new WechatVerifiedPaymentFactGatewayTest.Fixture();
            f.original.setOutTradeNo(no);Transaction notification=payment();notification.setOutTradeNo(no);
            when(f.parser.parse(any(),eq(Transaction.class))).thenReturn(notification);
            var membership=mock(com.chengyinhub.business.service.IClubMembershipService.class);
            var commerce=mock(com.chengyinhub.business.service.IMerchantCommerceService.class);
            @SuppressWarnings("unchecked")
            var club=(org.springframework.beans.factory.ObjectProvider<com.chengyinhub.business.service.IClubMembershipService>)
                    mock(org.springframework.beans.factory.ObjectProvider.class);
            when(club.getIfAvailable()).thenReturn(membership);
            WechatVerifiedFactTestSupport.setField(f.gateway,"clubMembershipServiceProvider",club);
            WechatVerifiedFactTestSupport.setField(f.gateway,"merchantCommerceService",commerce);
            when(membership.supportsOutTradeNo("CLM1")).thenReturn(true);
            when(commerce.supportsOutTradeNo("TPLTM_1")).thenReturn(true);
            when(membership.handlePaidInvoice(eq("CLM1"),eq(true),eq(1000),eq("TX1"),
                    argThat(reference -> reference!=null && originalReference(f).equals(reference)))).thenReturn(false,true);
            when(commerce.handlePaidOrder(eq("TPLTM_1"),eq(true),eq(1000),eq("TX1"),
                    argThat(reference -> reference!=null && originalReference(f).equals(reference)))).thenReturn(false,true);
            when(f.depositOwner.markDepositPaid(eq("DEP1"),eq(1000L),eq("TX1"),any(com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference.class)))
                    .thenAnswer(call -> {
                        DepositVerifiedReferenceTestSupport.assertOriginal(f.gateway,call.getArgument(3),"DEP1",1000L,"TX1");
                        return false;
                    }).thenAnswer(call -> {
                        DepositVerifiedReferenceTestSupport.assertOriginal(f.gateway,call.getArgument(3),"DEP1",1000L,"TX1");
                        return true;
                    });
            when(f.registration.getByRegistrationNo("REG1")).thenReturn(new com.chengyinhub.business.domain.CmsRegistration());
            when(f.registration.handleWechatPayCallback(anyMap())).thenReturn("FAIL","SUCCESS");
            assertTrue(f.gateway.handlePayCallback("signed-first",Map.of()).contains("FAIL"),no);
            var row=f.rows.rows.values().iterator().next();long id=((Number)row.get("id")).longValue();String hash=(String)row.get("fact_hash");
            doThrow(new RuntimeException("controlled query outage")).when(f.payments).queryOrderByOutTradeNo(any());clearInvocations(f.payments);
            assertTrue(f.gateway.handlePayCallback("signed-redelivery",Map.of()).contains("SUCCESS"),no);
            assertEquals(1,f.rows.rows.size());assertEquals(hash,f.store.requireVerified(
                    new com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference(id,hash)).reference().hash());
            verifyNoInteractions(f.payments);
            switch(no) {
                case "CLM1" -> verify(membership,times(2)).handlePaidInvoice(eq(no),eq(true),eq(1000),eq("TX1"),eq(originalReference(f)));
                case "TPLTM_1" -> verify(commerce,times(2)).handlePaidOrder(eq(no),eq(true),eq(1000),eq("TX1"),eq(originalReference(f)));
                case "DEP1" -> verify(f.depositOwner,times(2)).markDepositPaid(eq(no),eq(1000L),eq("TX1"),
                        eq(new com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference(id,hash)));
                default -> verify(f.registration,times(2)).handleWechatPayCallback(argThat(map->Long.toString(id).equals(map.get("verified_fact_id"))
                        && hash.equals(map.get("verified_fact_hash"))));
            }
        }
    }
    @Test void durablePaymentRecoveryRejectsChangedIdentityMoneyChannelTimeAndIntentBeforeAnyDomainMutation() {
        for(String wrong:List.of("appid","mchid","amount","currency","order","transaction","channel","time","changedTime","hash","rowHash")) {
            var f=new WechatVerifiedPaymentFactGatewayTest.Fixture();f.gateway.queryOrderStatus("REG1");
            Transaction notification=payment();
            switch(wrong) {
                case "appid" -> notification.setAppid("OTHER");
                case "mchid" -> notification.setMchid("OTHER");
                case "amount" -> notification.getAmount().setTotal(999);
                case "currency" -> notification.getAmount().setCurrency("USD");
                case "order" -> notification.setOutTradeNo("REG_OTHER");
                case "changedTime" -> notification.setSuccessTime("2026-10-24T00:00:01Z");
                case "rowHash" -> f.rows.rows.values().iterator().next().put("fact_hash","b".repeat(64));
                case "transaction" -> notification.setTransactionId("TX-OTHER");
                case "channel" -> notification.setTradeType(Transaction.TradeTypeEnum.JSAPI);
                case "time" -> notification.setSuccessTime(null);
                case "hash" -> f.frozenHash="b".repeat(64);
            }
            when(f.parser.parse(any(),eq(Transaction.class))).thenReturn(notification);clearInvocations(f.payments);
            assertTrue(f.gateway.handlePayCallback("signed-conflicting-double",Map.of()).contains("FAIL"),wrong);
            verifyNoInteractions(f.registration,f.payments);assertEquals(1,f.rows.rows.size());
        }
    }
    @Test void signedRefundSuccessRecoversTheSameFactAfterOwnerFailureWithoutASecondRefundOrPaymentQuery() {
        var f=new WechatVerifiedPaymentFactGatewayTest.Fixture();RefundNotification notification=refund(f);
        when(f.parser.parse(any(),eq(RefundNotification.class))).thenReturn(notification);
        when(f.refundOwner.handleRefundNotify(anyMap())).thenReturn(false,true);
        assertTrue(f.gateway.handleRefundCallback("signed-refund-double",Map.of()).contains("FAIL"));
        String hash=(String)f.rows.rows.values().iterator().next().get("fact_hash");
        doThrow(new RuntimeException("refund query outage")).when(f.refunds).queryByOutRefundNo(any());
        doThrow(new RuntimeException("payment query outage")).when(f.payments).queryOrderByOutTradeNo(any());
        clearInvocations(f.refunds,f.payments);
        assertTrue(f.gateway.handleRefundCallback("same-signed-refund-double",Map.of()).contains("SUCCESS"));
        assertEquals(1,f.rows.rows.size());verifyNoInteractions(f.refunds,f.payments);
        verify(f.refundOwner,times(2)).handleRefundNotify(argThat(map->hash.equals(map.get("verified_fact_hash"))));
    }
    @Test void currentStatusQueriesNeverUseTheHistoricalSuccessAsAProviderStatusFallback() {
        var f=new WechatVerifiedPaymentFactGatewayTest.Fixture();f.gateway.queryOrderStatus("REG1");
        doThrow(new RuntimeException("outage")).when(f.payments).queryOrderByOutTradeNo(any());
        assertThrows(RuntimeException.class,()->f.gateway.queryOrderStatus("REG1"));
        assertNull(f.gateway.queryRefundFacts("RF1")); // original query now unavailable; no refund fact invented
        assertEquals(1,f.rows.rows.size());
    }
    @Test void noPersistedReferenceNeverRecoversFromAQueryTimeout5xxOrNotFound() {
        for (String failure:List.of("timeout","5xx","NOT_FOUND","missing_original_fields")) {
            var f=new WechatVerifiedPaymentFactGatewayTest.Fixture();
            when(f.parser.parse(any(),eq(Transaction.class))).thenReturn(payment());
            RuntimeException problem;
            if ("5xx".equals(failure) || "NOT_FOUND".equals(failure)) {
                var service=mock(com.wechat.pay.java.core.exception.ServiceException.class);
                when(service.getHttpStatusCode()).thenReturn("5xx".equals(failure)?503:404);
                when(service.getErrorCode()).thenReturn("5xx".equals(failure)?"SYSTEM_ERROR":"ORDER_NOT_EXIST");
                problem=service;
            } else problem=new RuntimeException("controlled query timeout");
            if ("missing_original_fields".equals(failure)) f.original.setSuccessTime(null);
            else doThrow(problem).when(f.payments).queryOrderByOutTradeNo(any());
            assertTrue(f.gateway.handlePayCallback("signed-double",Map.of()).contains("FAIL"),failure);
            assertEquals(0,f.rows.rows.size());verifyNoInteractions(f.registration);
            verify(f.payments).queryOrderByOutTradeNo(any());
            verify(f.payments,never()).closeOrder(any());
        }
    }
    @Test void signatureOrCertificateResolutionFailureCannotUsePersistedSuccessForPaymentOrRefund() {
        for (RuntimeException failure:List.of(mock(com.wechat.pay.java.core.exception.ValidationException.class),
                new IllegalStateException("controlled certificate resolver unavailable"))) {
            var f=new WechatVerifiedPaymentFactGatewayTest.Fixture();
            f.gateway.queryOrderStatus("REG1");f.gateway.queryRefundFacts("RF1");
            var reader=spy(f.store);WechatVerifiedFactTestSupport.setField(f.gateway,"verifiedFacts",reader);
            clearInvocations(f.payments,f.refunds,reader);
            when(f.parser.parse(any(),eq(Transaction.class))).thenThrow(failure);
            when(f.parser.parse(any(),eq(RefundNotification.class))).thenThrow(failure);
            assertTrue(f.gateway.handlePayCallback("unverifiable-double",Map.of()).contains("FAIL"));
            assertTrue(f.gateway.handleRefundCallback("unverifiable-double",Map.of()).contains("FAIL"));
            assertEquals(2,f.rows.rows.size());
            verifyNoInteractions(f.registration,f.refundOwner,f.depositOwner,f.payments,f.refunds,reader);
        }
    }
    @Test void signedRefundRecoveryRejectsAnotherPayloadSharingOnlyTheMerchantRefundNumber() {
        for(String wrong:List.of("order","refundId","transaction","amount","total","currency","time","missingTime","hash")) {
            var f=new WechatVerifiedPaymentFactGatewayTest.Fixture();f.gateway.queryRefundFacts("RF1");
            RefundNotification notification=refund(f);
            switch(wrong) {
                case "order" -> notification.setOutTradeNo("REG_OTHER");
                case "refundId" -> notification.setRefundId("WXRF_OTHER");
                case "transaction" -> notification.setTransactionId("TX_OTHER");
                case "amount" -> notification.getAmount().setRefund(501L);
                case "total" -> notification.getAmount().setTotal(1001L);
                case "currency" -> notification.getAmount().setCurrency("USD");
                case "time" -> notification.setSuccessTime("2026-10-24T00:00:01Z");
                case "missingTime" -> notification.setSuccessTime(null);
                case "hash" -> f.frozenHash="b".repeat(64);
            }
            when(f.parser.parse(any(),eq(RefundNotification.class))).thenReturn(notification);
            clearInvocations(f.payments,f.refunds);
            assertTrue(f.gateway.handleRefundCallback("signed-conflicting-refund",Map.of()).contains("FAIL"),wrong);
            assertEquals(1,f.rows.rows.size());verifyNoInteractions(f.refundOwner,f.depositOwner,f.payments,f.refunds);
        }
    }
    @Test void alreadyConfirmedPaymentBlocksBothPrepayChannelsAndCloseBeforeAnySdkActionEvenWithAnOldClosedMarker() {
        var f=new WechatVerifiedPaymentFactGatewayTest.Fixture();f.gateway.queryOrderStatus("REG1");
        when(f.config.isWechatPayEnabled()).thenReturn(true);when(f.config.getAppid()).thenReturn("wx-original");
        when(f.config.getAppAppid()).thenReturn("wx-original");when(f.config.getNotifyUrl()).thenReturn("https://example.test/notify");
        WechatVerifiedFactTestSupport.setField(f.gateway,"jsapiService",f.payments);
        var extension=mock(com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension.class);
        var app=mock(com.wechat.pay.java.service.payments.app.AppServiceExtension.class);
        WechatVerifiedFactTestSupport.setField(f.gateway,"jsapiServiceExtension",extension);
        WechatVerifiedFactTestSupport.setField(f.gateway,"appServiceExtension",app);
        var intent=WechatPaymentIntentTestSupport.install(f.gateway,Map.of("REG1",new BigDecimal("10.00")));
        var row=new HashMap<String,Object>();row.putAll(Map.of("mchid","merchant","out_trade_no","REG1","business_out_trade_no","REG1",
                "biz_type","REGISTRATION","biz_id",1L,"attempt_no",1,"appid","wx-original","trade_type","APP","currency","CNY","total_fen",1000L));
        row.put("request_hash","a".repeat(64));row.put("closed_confirmed_at","old marker");intent.intents.put("merchant:REG1",row);
        WechatVerifiedFactTestSupport.setField(f.gateway,"paymentIntentTransactions",noResources());
        var appFailure=assertThrows(PaymentPreorderException.class,()->f.gateway.createGenericAppOrder("REG1",new BigDecimal("10.00"),"description"));
        var miniFailure=assertThrows(PaymentPreorderException.class,()->f.gateway.createGenericOrder("REG1",new BigDecimal("10.00"),"description","openid"));
        assertTrue(appFailure.isResultUncertain());assertTrue(miniFailure.isResultUncertain());
        assertFalse(f.gateway.closePaymentAttempt("REG1","REG1"));
        new TransactionTemplate(noResources()).executeWithoutResult(status->{
            assertTrue(f.gateway.lockPaymentAttemptForUpdate("REG1","REG1"),"generic attempt identity remains valid");
            assertFalse(f.gateway.lockClosablePaymentAttemptForUpdate("REG1","REG1"),"known financial success blocks only the CLOSED predicate");
        });
        assertEquals(1,intent.intents.size());assertEquals("a".repeat(64),row.get("request_hash"));
        verifyNoInteractions(extension,app);verify(f.payments,never()).closeOrder(any());
    }
    private static com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference originalReference(
            WechatVerifiedPaymentFactGatewayTest.Fixture fixture) {
        Transaction original=fixture.original;
        var stored=fixture.store.findPaymentSuccess(original.getMchid(),original.getOutTradeNo(),original.getTransactionId());
        assertNotNull(stored,"redelivery must reuse the actual saved original fact, without a provider query");
        var fact=fixture.store.requireVerified(stored.reference()).facts();
        DepositVerifiedReferenceTestSupport.assertOriginal(fixture.gateway,stored.reference(),original.getOutTradeNo(),
                original.getAmount().getTotal().longValue(),original.getTransactionId());
        assertEquals(original.getOutTradeNo(),fact.wxOutTradeNo());assertEquals(original.getOutTradeNo(),fact.merchantEventNo());
        assertEquals(original.getAppid(),fact.appid());assertEquals(original.getMchid(),fact.mchid());
        assertEquals(original.getTradeType().name(),fact.tradeType());assertEquals(original.getAmount().getCurrency(),fact.currency());
        assertEquals(java.time.OffsetDateTime.parse(original.getSuccessTime()).toInstant(),fact.successAt());
        assertEquals(fixture.frozenHash,fact.intentRequestHash());
        return stored.reference();
    }

    private static Transaction payment() {
        Transaction transaction=new Transaction();transaction.setOutTradeNo("REG1");transaction.setMchid("merchant");transaction.setAppid("wx-original");
        transaction.setTransactionId("TX1");transaction.setTradeType(Transaction.TradeTypeEnum.APP);transaction.setTradeState(Transaction.TradeStateEnum.SUCCESS);
        transaction.setSuccessTime(WechatVerifiedFactTestSupport.SUCCESS_TIME);var amount=new TransactionAmount();amount.setTotal(1000);amount.setCurrency("CNY");transaction.setAmount(amount);return transaction;
    }
    private static RefundNotification refund(WechatVerifiedPaymentFactGatewayTest.Fixture f) {
        var notification=new RefundNotification();notification.setOutRefundNo("RF1");notification.setOutTradeNo("REG1");notification.setRefundId("WXRF1");
        notification.setTransactionId("TX1");notification.setRefundStatus(Status.SUCCESS);notification.setSuccessTime(WechatVerifiedFactTestSupport.SUCCESS_TIME);
        notification.setAmount(f.refund.getAmount());return notification;
    }
    private static org.springframework.transaction.PlatformTransactionManager noResources() {
        return new AbstractPlatformTransactionManager(){
            @Override protected Object doGetTransaction(){return new Object();}
            @Override protected void doBegin(Object tx,org.springframework.transaction.TransactionDefinition definition){}
            @Override protected void doCommit(DefaultTransactionStatus status){}
            @Override protected void doRollback(DefaultTransactionStatus status){}
        };
    }
}
