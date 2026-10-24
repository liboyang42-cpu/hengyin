package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.service.*;
import com.wechat.pay.java.service.payments.jsapi.JsapiService;
import com.wechat.pay.java.service.payments.model.*;
import com.wechat.pay.java.service.refund.RefundService;
import com.wechat.pay.java.service.refund.model.*;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.chengyinhub.common.config.WxPayConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real gateway + real fact validation/storage algorithm, SDK doubles and memory mapper. No database proof. */
class WechatVerifiedPaymentFactGatewayTest {
    @Test void paymentQueryPersistsCompleteProviderSuccessOnceAndReturnsOnlyItsDurableReference() {
        Fixture f=new Fixture();
        Map<String,String> first=f.gateway.queryOrderStatus("REG1");
        Map<String,String> second=f.gateway.queryOrderStatus("REG1");
        assertEquals(first.get("verified_fact_id"),second.get("verified_fact_id"));
        assertEquals(first.get("verified_fact_hash"),second.get("verified_fact_hash"));
        assertEquals(1,f.rows.rows.size());
        var stored=f.store.requireVerified(reference(first)).facts();
        assertEquals("merchant",stored.mchid()); assertEquals("wx-original",stored.appid());
        assertEquals("APP",stored.tradeType()); assertEquals("CNY",stored.currency());
        assertEquals("TX1",stored.providerId()); assertEquals("REG1",stored.wxOutTradeNo());
        assertEquals(1000,stored.amountFen()); assertEquals("a".repeat(64),stored.intentRequestHash());
    }
    @Test void currentPropertiesCannotReplaceTheOriginalQueryClientMerchantAndMissingBindingFailsClosed() {
        Fixture f=new Fixture(); when(f.config.getMerchantId()).thenReturn("changed");
        assertEquals("merchant",f.gateway.queryOrderStatus("REG1").get("mchid"));
        verify(f.payments).queryOrderByOutTradeNo(argThat(r->"merchant".equals(r.getMchid())));
        WechatVerifiedFactTestSupport.setField(f.gateway,"verifiedProviderClients",null);
        assertThrows(RuntimeException.class,()->f.gateway.queryOrderStatus("REG1"));
        assertNull(f.gateway.queryRefundFacts("RF1")); assertEquals(1,f.rows.rows.size());
    }
    @Test void signedButWrongPaymentFactsAndMissingProviderSuccessTimeCannotCreateASuccessReceipt() {
        Fixture f=new Fixture();
        Transaction notification=payment(); notification.setAppid("other");
        when(f.parser.parse(any(),eq(Transaction.class))).thenReturn(notification);
        assertTrue(f.gateway.handlePayCallback("body",Map.of()).contains("FAIL"));
        assertEquals(0,f.rows.rows.size()); verifyNoInteractions(f.registration);
        f.original.setSuccessTime(null);
        assertThrows(RuntimeException.class,()->f.gateway.queryOrderStatus("REG1"));
        assertEquals(0,f.rows.rows.size());
    }
    @Test void refundSuccessIsBoundToOriginalPaymentAndExactProviderRefundIdentityAndAmount() {
        Fixture f=new Fixture(); f.original.setTradeState(Transaction.TradeStateEnum.REFUND);
        Map<String,String> facts=f.gateway.queryRefundFacts("RF1"); assertNotNull(facts);
        var refund=f.store.requireVerified(reference(facts)).facts();
        assertEquals(WechatVerifiedPaymentFact.Kind.REFUND,refund.kind());
        assertEquals("WXRF1",refund.providerId()); assertEquals("TX1",refund.transactionId());
        assertEquals("RF1",refund.merchantEventNo()); assertEquals("REG1",refund.wxOutTradeNo());
        assertEquals(500,refund.amountFen()); assertEquals(1000,refund.totalFen());
        assertEquals("REFUND",refund.originalPaymentState());
        assertEquals(1,f.rows.rows.size()); // A REFUND observation never invents a PAYMENT SUCCESS row.
    }
    @Test void missingOrMismatchedRefundFieldsAndFrozenFactsNeverBecomeSuccessfulRefunds() {
        for (String bad : new String[]{"refundId","transactionId","originalTransaction","currency","total","refund","time","frozenHash"}) {
            Fixture f=new Fixture();
            switch (bad) {
                case "refundId" -> f.refund.setRefundId(null);
                case "transactionId" -> f.refund.setTransactionId("OTHER");
                case "originalTransaction" -> f.original.setTransactionId(null);
                case "currency" -> f.refund.getAmount().setCurrency("USD");
                case "total" -> f.refund.getAmount().setTotal(1001L);
                case "refund" -> f.refund.getAmount().setRefund(1001L);
                case "time" -> f.refund.setSuccessTime(null);
                case "frozenHash" -> f.frozenHash=null;
            }
            assertNull(f.gateway.queryRefundFacts("RF1"),bad);
            assertEquals(0,f.rows.rows.size(),bad);
        }
    }
    @Test void unknownTimeoutNotFoundAndProcessingNeverCreateSuccessFacts() {
        Fixture f=new Fixture();
        doThrow(new RuntimeException("timeout")).when(f.refunds).queryByOutRefundNo(any());
        assertNull(f.gateway.queryRefundFacts("RF1"));
        var missing=mock(com.wechat.pay.java.core.exception.ServiceException.class);
        when(missing.getHttpStatusCode()).thenReturn(404); when(missing.getErrorCode()).thenReturn("RESOURCE_NOT_EXISTS");
        doThrow(missing).when(f.refunds).queryByOutRefundNo(any());
        assertEquals("NOT_FOUND",f.gateway.queryRefundFacts("RF1").get("status"));
        doReturn(f.refund).when(f.refunds).queryByOutRefundNo(any()); f.refund.setStatus(Status.PROCESSING);
        Map<String,String> pending=f.gateway.queryRefundFacts("RF1"); assertEquals("PROCESSING",pending.get("status"));
        assertFalse(pending.containsKey("verified_fact_id")); assertEquals(0,f.rows.rows.size());
    }
    @Test void conflictingVerifiedRefundNotificationIsAuditedAndCannotReachEitherDomain() {
        Fixture f=new Fixture(); RefundNotification notification=new RefundNotification();
        notification.setOutRefundNo("RF1"); notification.setOutTradeNo("REG1"); notification.setRefundId("WXRF1");
        notification.setTransactionId("TX1"); notification.setRefundStatus(Status.SUCCESS);
        notification.setSuccessTime(WechatVerifiedFactTestSupport.SUCCESS_TIME);
        Amount wrong=new Amount(); wrong.setTotal(1000L); wrong.setRefund(501L); wrong.setCurrency("CNY"); notification.setAmount(wrong);
        when(f.parser.parse(any(),eq(RefundNotification.class))).thenReturn(notification);
        assertTrue(f.gateway.handleRefundCallback("signed-double",Map.of()).contains("FAIL"));
        assertEquals(1,f.rows.rows.size()); assertEquals(1,f.rows.rejections.size());
        verifyNoInteractions(f.refundOwner,f.depositOwner);
    }
    @Test void refundCallbackReturnsSuccessOnlyAfterDurableProviderEvidenceAndOwnerCommit() {
        Fixture f=new Fixture(); RefundNotification notification=new RefundNotification();
        notification.setOutRefundNo("RF1"); notification.setOutTradeNo("REG1"); notification.setRefundId("WXRF1");
        notification.setTransactionId("TX1"); notification.setRefundStatus(Status.SUCCESS);
        notification.setSuccessTime(WechatVerifiedFactTestSupport.SUCCESS_TIME); notification.setAmount(f.refund.getAmount());
        when(f.parser.parse(any(),eq(RefundNotification.class))).thenReturn(notification);
        when(f.refundOwner.handleRefundNotify(anyMap())).thenAnswer(call->{
            Map<String,String> map=call.getArgument(0); assertEquals(500,f.store.requireVerified(reference(map)).facts().amountFen()); return false;
        });
        assertTrue(f.gateway.handleRefundCallback("signed-double",Map.of()).contains("FAIL"));
        doReturn(true).when(f.refundOwner).handleRefundNotify(anyMap());
        assertTrue(f.gateway.handleRefundCallback("signed-double",Map.of()).contains("SUCCESS"));
        assertEquals(1,f.rows.rows.size());
        verify(f.refundOwner,times(2)).handleRefundNotify(anyMap());
    }
    @Test void activeBusinessTransactionCannotReachTheOfficialQueryOrCloseSdk() {
        Fixture fixture=new Fixture();
        new org.springframework.transaction.support.TransactionTemplate(WechatVerifiedFactTestSupport.unitBusinessTransactions()).executeWithoutResult(status->{
            assertThrows(RuntimeException.class,()->fixture.gateway.queryOrderStatus("REG1"));
            assertThrows(IllegalStateException.class,()->fixture.gateway.closePaymentAttempt("REG1","REG1"));
        });
        verifyNoInteractions(fixture.payments,fixture.refunds);
        assertTrue(fixture.rows.rows.isEmpty());
    }

    private static WechatVerifiedPaymentFact.Reference reference(Map<String,String> facts) {
        return new WechatVerifiedPaymentFact.Reference(Long.parseLong(facts.get("verified_fact_id")),facts.get("verified_fact_hash"));
    }
    private static Transaction payment() {
        Transaction t=new Transaction(); t.setOutTradeNo("REG1"); t.setMchid("merchant"); t.setAppid("wx-original");
        t.setTransactionId("TX1"); t.setTradeType(Transaction.TradeTypeEnum.APP); t.setTradeState(Transaction.TradeStateEnum.SUCCESS);
        t.setSuccessTime(WechatVerifiedFactTestSupport.SUCCESS_TIME);
        TransactionAmount amount=new TransactionAmount();amount.setTotal(1000);amount.setCurrency("CNY");t.setAmount(amount);return t;
    }
    static class Fixture {
        final WechatPayServiceImpl gateway=WechatVerifiedFactTestSupport.gateway();
        final WechatVerifiedFactTestSupport.MemoryMapper rows=new WechatVerifiedFactTestSupport.MemoryMapper();
        final WechatVerifiedPaymentFactService store=new WechatVerifiedPaymentFactService();
        final JsapiService payments=mock(JsapiService.class);final RefundService refunds=mock(RefundService.class);
        final WxPayConfig config=mock(WxPayConfig.class);final NotificationParser parser=mock(NotificationParser.class);
        final ICmsRegistrationService registration=mock(ICmsRegistrationService.class);
        final IRefundService refundOwner=mock(IRefundService.class);final ICoopInviteService depositOwner=mock(ICoopInviteService.class);
        final Transaction original=payment();final Refund refund=new Refund(); String frozenHash="a".repeat(64);
        Fixture() {
            WechatVerifiedFactTestSupport.setField(store,"mapper",rows); WechatVerifiedFactTestSupport.setField(gateway,"verifiedFacts",store);
            when(config.getMerchantId()).thenReturn("merchant");WechatVerifiedFactTestSupport.setField(gateway,"wxPayConfig",config);
            when(payments.queryOrderByOutTradeNo(any())).thenReturn(original); when(refunds.queryByOutRefundNo(any())).thenReturn(refund);
            WechatVerifiedFactTestSupport.bind(gateway,payments,refunds,"merchant");
            WechatVerifiedFactTestSupport.setField(gateway,"notificationParser",parser);WechatVerifiedFactTestSupport.setField(gateway,"alarmService",mock(AlarmService.class));
            WechatVerifiedFactTestSupport.setField(gateway,"registrationService",registration);WechatVerifiedFactTestSupport.setField(gateway,"refundBizService",refundOwner);
            WechatVerifiedFactTestSupport.setField(gateway,"coopInviteService",depositOwner);
            WechatVerifiedFactTestSupport.setField(gateway,"paymentIntentJdbc",new JdbcTemplate(){
                @Override public java.util.List<Map<String,Object>> queryForList(String sql,Object... args) {
                    if (sql.startsWith("SELECT appid")) return java.util.List.of(Map.of("appid","wx-original","trade_type","APP","currency","CNY","total_fen",1000L));
                    if (sql.startsWith("SELECT business_out_trade_no")) {
                        String no=original.getOutTradeNo();String kind=no.startsWith("CLM")?"CLM":no.startsWith("DEP")?"DEP":
                                no.startsWith("TPLTM_")?"COMMERCE":"REGISTRATION";
                        return java.util.List.of(Map.of("business_out_trade_no",no,"biz_type",kind,"biz_id",1L));
                    }
                    if (sql.startsWith("SELECT request_hash")) {var row=new java.util.HashMap<String,Object>();row.put("request_hash",frozenHash);return java.util.List.of(row);}
                    return java.util.List.of();
                }
            });
            refund.setOutRefundNo("RF1");refund.setOutTradeNo("REG1");refund.setRefundId("WXRF1");refund.setTransactionId("TX1");
            refund.setStatus(Status.SUCCESS);refund.setSuccessTime(WechatVerifiedFactTestSupport.SUCCESS_TIME);
            Amount amount=new Amount();amount.setTotal(1000L);amount.setRefund(500L);amount.setCurrency("CNY");refund.setAmount(amount);
        }
    }
}
