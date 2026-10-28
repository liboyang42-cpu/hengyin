package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference;
import com.wechat.pay.java.service.payments.jsapi.JsapiService;
import com.wechat.pay.java.service.payments.model.*;
import com.wechat.pay.java.service.refund.RefundService;
import com.wechat.pay.java.service.refund.model.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** SDK/fact boundary unit tests; no database/provider calls or financial-domain proof. */
class WechatRefundOriginalFactQueryTest {
    @Test void exactOriginalPaymentProducesOneRefundFactAndRetainsItsOriginalAttempt() {
        Fixture f=new Fixture();Map<String,String> first=f.gateway.queryRefundFacts(f.paid,"RF1");
        Map<String,String> replay=f.gateway.queryRefundFacts(f.paid,"RF1");
        assertNotNull(first);assertEquals(first.get("verified_fact_id"),replay.get("verified_fact_id"));
        var fact=f.store.requireVerified(new Reference(Long.parseLong(first.get("verified_fact_id")),first.get("verified_fact_hash"))).facts();
        assertEquals("W1",fact.wxOutTradeNo());assertEquals("REG1",fact.businessOutTradeNo());
        assertEquals("TX1",fact.transactionId());assertEquals("wx-original",fact.appid());assertEquals(200,fact.amountFen());
        assertEquals(2,f.rows.rows.size());
    }
    @Test void originalMerchantMismatchAndMissingOrWrongReferenceNeverProbeAnotherMerchant() {
        Fixture f=new Fixture();WechatVerifiedFactTestSupport.bind(f.gateway,f.payments,f.refunds,"other-merchant");
        assertNull(f.gateway.queryRefundFacts(f.paid,"RF1"));verifyNoInteractions(f.payments,f.refunds);
        WechatVerifiedFactTestSupport.bind(f.gateway,f.payments,f.refunds,"merchant");
        assertNull(f.gateway.queryRefundFacts((Reference)null,"RF1"));
        assertNull(f.gateway.queryRefundFacts(new Reference(f.paid.id(),"b".repeat(64)),"RF1"));
        var refundReference=f.store.recordVerified(new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.REFUND,
                "merchant","wx-original","REG1","W1","RF-other","WXRF-other","TX1","APP","CNY",1000,100,
                Instant.parse(WechatVerifiedFactTestSupport.SUCCESS_TIME),"a".repeat(64),"REFUND"));
        assertNull(f.gateway.queryRefundFacts(refundReference,"RF1"));verifyNoInteractions(f.payments,f.refunds);
    }
    @Test void internallyConsistentProviderFactsForAnotherOriginalAreRejectedBeforeRefundSuccessIsWritten() {
        for(String wrong:List.of("attempt","transaction","appid","channel","total","hash","business")) {
            Fixture f=new Fixture();
            switch(wrong) {
                case "attempt" -> {f.original.setOutTradeNo("W2");f.refund.setOutTradeNo("W2");}
                case "transaction" -> {f.original.setTransactionId("TX2");f.refund.setTransactionId("TX2");}
                case "appid" -> f.original.setAppid("wx-other");
                case "channel" -> f.original.setTradeType(Transaction.TradeTypeEnum.JSAPI);
                case "total" -> {f.original.getAmount().setTotal(1001);f.refund.getAmount().setTotal(1001L);}
                case "hash" -> f.frozenHash="b".repeat(64);
                case "business" -> f.business="REG2";
            }
            assertNull(f.gateway.queryRefundFacts(f.paid,"RF1"),wrong);
            assertEquals(1,f.rows.rows.size(),wrong); // Provider verification alone must not persist the wrong expected payment's refund.
            assertEquals("W1",f.store.requireVerified(f.paid).facts().wxOutTradeNo());
        }
    }
    @Test void notFoundAndTimeoutRemainUnknownAndDoNotReleaseOrReplaceTheOriginalPaymentIdentity() {
        Fixture f=new Fixture();var missing=mock(com.wechat.pay.java.core.exception.ServiceException.class);
        when(missing.getHttpStatusCode()).thenReturn(404);when(missing.getErrorCode()).thenReturn("RESOURCE_NOT_EXISTS");
        doThrow(missing).when(f.refunds).queryByOutRefundNo(any());
        assertEquals("NOT_FOUND",f.gateway.queryRefundFacts(f.paid,"RF1").get("status"));
        doThrow(new RuntimeException("timeout")).when(f.refunds).queryByOutRefundNo(any());
        assertNull(f.gateway.queryRefundFacts(f.paid,"RF1"));assertEquals(1,f.rows.rows.size());
        assertEquals("TX1",f.store.requireVerified(f.paid).facts().transactionId());
        verifyNoInteractions(f.payments);
    }
    private static final class Fixture {
        final WechatPayServiceImpl gateway=WechatVerifiedFactTestSupport.gateway();
        final WechatVerifiedPaymentFactService store=new WechatVerifiedPaymentFactService();
        final WechatVerifiedFactTestSupport.MemoryMapper rows=new WechatVerifiedFactTestSupport.MemoryMapper();
        final JsapiService payments=mock(JsapiService.class);final RefundService refunds=mock(RefundService.class);
        final Transaction original=new Transaction();final Refund refund=new Refund();final Reference paid;
        String frozenHash="a".repeat(64);String business="REG1";
        Fixture() {
            WechatVerifiedFactTestSupport.setField(store,"mapper",rows);WechatVerifiedFactTestSupport.setField(gateway,"verifiedFacts",store);
            paid=store.recordVerified(new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.PAYMENT,
                    "merchant","wx-original","REG1","W1","W1","TX1","TX1","APP","CNY",1000,1000,
                    Instant.parse(WechatVerifiedFactTestSupport.SUCCESS_TIME),frozenHash,"SUCCESS"));
            original.setOutTradeNo("W1");original.setMchid("merchant");original.setAppid("wx-original");original.setTransactionId("TX1");
            original.setTradeType(Transaction.TradeTypeEnum.APP);original.setTradeState(Transaction.TradeStateEnum.REFUND);
            original.setSuccessTime(WechatVerifiedFactTestSupport.SUCCESS_TIME);
            var total=new TransactionAmount();total.setTotal(1000);total.setCurrency("CNY");original.setAmount(total);
            refund.setOutTradeNo("W1");refund.setOutRefundNo("RF1");refund.setRefundId("WXRF1");refund.setTransactionId("TX1");
            refund.setStatus(Status.SUCCESS);refund.setSuccessTime(WechatVerifiedFactTestSupport.SUCCESS_TIME);
            var amount=new Amount();amount.setTotal(1000L);amount.setRefund(200L);amount.setCurrency("CNY");refund.setAmount(amount);
            when(payments.queryOrderByOutTradeNo(any())).thenReturn(original);when(refunds.queryByOutRefundNo(any())).thenReturn(refund);
            WechatVerifiedFactTestSupport.bind(gateway,payments,refunds,"merchant");
            WechatVerifiedFactTestSupport.setField(gateway,"paymentIntentJdbc",new JdbcTemplate(){
                @Override public List<Map<String,Object>> queryForList(String sql,Object... params) {
                    if(sql.startsWith("SELECT appid")) return List.of(Map.of("appid",original.getAppid(),"trade_type",original.getTradeType().name(),"currency","CNY","total_fen",original.getAmount().getTotal().longValue()));
                    if(sql.startsWith("SELECT request_hash")) return List.of(Map.of("request_hash",frozenHash));
                    if(sql.startsWith("SELECT business_out_trade_no")) return List.of(Map.of(
                            "business_out_trade_no",business,"biz_type","REGISTRATION","biz_id",1L));
                    return List.of();
                }
            });
        }
    }
}
