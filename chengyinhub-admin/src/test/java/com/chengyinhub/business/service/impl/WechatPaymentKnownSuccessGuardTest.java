package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.PaymentPreorderException;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.refund.model.RefundNotification;
import com.wechat.pay.java.service.refund.model.Status;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real SDK/fact/intent/facade algorithms with controlled SDK/mapper doubles. Not a MySQL or owner-Tx proof. */
class WechatPaymentKnownSuccessGuardTest {
    @Test
    void closedNotificationsForAllFourDomainsCannotDispatchOverAnExistingVerifiedPaymentSuccess() {
        for(String no:List.of("CLM1","TPLTM_1","DEP1","REG1")) {
            var f=new WechatVerifiedPaymentFactGatewayTest.Fixture();f.original.setOutTradeNo(no);
            Map<String,String> original=f.gateway.queryOrderStatus(no);
            f.original.setTradeState(Transaction.TradeStateEnum.CLOSED);
            when(f.parser.parse(any(),eq(Transaction.class))).thenReturn(f.original);
            clearInvocations(f.payments);
            assertTrue(f.gateway.handlePayCallback("signed-closed-double",Map.of()).contains("FAIL"),no);
            verifyNoInteractions(f.registration,f.depositOwner,f.refundOwner);
            assertEquals(1,f.rows.rows.size());assertEquals(1,f.rows.rejections.size());
            assertEquals(original.get("verified_fact_hash"),f.rows.rows.values().iterator().next().get("fact_hash"));
            verify(f.payments).queryOrderByOutTradeNo(any());verify(f.payments,never()).closeOrder(any());
        }
    }

    @Test
    void aClosedObservationWithoutAnyDurableSuccessRetainsTheExistingVerifiedRouteContract() {
        var f=new WechatVerifiedPaymentFactGatewayTest.Fixture();
        f.original.setTradeState(Transaction.TradeStateEnum.CLOSED);
        when(f.parser.parse(any(),eq(Transaction.class))).thenReturn(f.original);
        when(f.registration.getByRegistrationNo("REG1")).thenReturn(new CmsRegistration());
        when(f.registration.handleWechatPayCallback(anyMap())).thenReturn("SUCCESS");
        assertTrue(f.gateway.handlePayCallback("signed-closed-double",Map.of()).contains("SUCCESS"));
        verify(f.registration).handleWechatPayCallback(argThat(facts->"CLOSED".equals(facts.get("trade_state"))));
        assertEquals(0,f.rows.rows.size());assertEquals(0,f.rows.rejections.size());
    }

    @Test
    void laterNonSuccessRefundNotificationsCannotOverwriteAnExistingVerifiedRefundSuccess() {
        for(Status later:List.of(Status.PROCESSING,Status.CLOSED,Status.ABNORMAL)) {
            var f=new WechatVerifiedPaymentFactGatewayTest.Fixture();Map<String,String> original=f.gateway.queryRefundFacts("RF1");
            RefundNotification notification=new RefundNotification();notification.setOutRefundNo("RF1");
            notification.setOutTradeNo("REG1");notification.setRefundId("WXRF1");notification.setTransactionId("TX1");
            notification.setRefundStatus(later);notification.setAmount(f.refund.getAmount());
            when(f.parser.parse(any(),eq(RefundNotification.class))).thenReturn(notification);
            clearInvocations(f.payments,f.refunds);
            assertTrue(f.gateway.handleRefundCallback("signed-conflicting-status-double",Map.of()).contains("FAIL"),later.name());
            verifyNoInteractions(f.refundOwner,f.depositOwner,f.payments,f.refunds);
            assertEquals(1,f.rows.rows.size());assertEquals(1,f.rows.rejections.size());
            assertEquals(original.get("verified_fact_hash"),f.rows.rows.values().iterator().next().get("fact_hash"));
        }
    }

    @Test
    void businessGuardRejectsUntrustedEvidenceAndNeverUsesTheCurrentMerchantToHideAnEarlierSuccess() {
        var f=new WechatVerifiedPaymentFactGatewayTest.Fixture();assertFalse(f.store.hasConfirmedExternalSuccess("REG1"));
        Map<String,String> paid=f.gateway.queryOrderStatus("REG1");assertTrue(f.store.hasConfirmedExternalSuccess("REG1"));
        assertFalse(f.store.hasConfirmedExternalSuccess("REG_OTHER"));
        assertThrows(WechatVerifiedPaymentFactService.ConflictException.class,()->f.store.hasConfirmedExternalSuccess("different-merchant","REG1"));
        f.rows.rows.values().iterator().next().put("verification_outcome","UNKNOWN");
        assertThrows(IllegalStateException.class,()->f.store.hasConfirmedExternalSuccess("REG1"));
        assertThrows(IllegalArgumentException.class,()->f.store.hasConfirmedExternalSuccess(" "));
        assertEquals(paid.get("verified_fact_hash"),f.rows.rows.values().iterator().next().get("fact_hash"));
    }

    @Test
    void everyRealPrepayEntryAndOriginalCloseRejectKnownSuccessBeforeSdkEvenAfterAnOldClosedMarker() {
        for(String no:List.of("CLM1","TPLTM_1","DEP1","REG1")) {
            var f=new WechatVerifiedPaymentFactGatewayTest.Fixture();f.original.setOutTradeNo(no);f.gateway.queryOrderStatus(no);
            when(f.config.isWechatPayEnabled()).thenReturn(true);when(f.config.getAppid()).thenReturn("wx-original");
            when(f.config.getAppAppid()).thenReturn("wx-original");when(f.config.getNotifyUrl()).thenReturn("https://example.test/notify");
            var mini=mock(com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension.class);
            var app=mock(com.wechat.pay.java.service.payments.app.AppServiceExtension.class);
            WechatVerifiedFactTestSupport.setField(f.gateway,"jsapiServiceExtension",mini);
            WechatVerifiedFactTestSupport.setField(f.gateway,"appServiceExtension",app);
            var db=WechatPaymentIntentTestSupport.install(f.gateway,Map.of(no,BigDecimal.TEN));
            String type=no.startsWith("CLM")?"CLM":no.startsWith("DEP")?"DEP":no.startsWith("TPLTM_")?"COMMERCE":"REGISTRATION";
            Map<String,Object> row=new HashMap<>(Map.of("mchid","merchant","out_trade_no",no,"business_out_trade_no",no,
                    "biz_type",type,"biz_id",1L,"attempt_no",1,"appid","wx-original","trade_type","APP","currency","CNY","total_fen",1000L));
            row.put("request_hash","a".repeat(64));row.put("closed_confirmed_at","previous-closed-marker");db.intents.put("merchant:"+no,row);
            WechatVerifiedFactTestSupport.setField(f.gateway,"paymentIntentTransactions",transactions());
            if("REG1".equals(no)) {
                CmsRegistration registration=new CmsRegistration();registration.setRegistrationNo(no);registration.setMemberId(7L);
                registration.setWechatPaymentAmount(BigDecimal.TEN);registration.setActivityTitle("original");registration.setTicketName("ticket");
                var members=mock(UmsMemberMapper.class);UmsMember member=new UmsMember();member.setWxOpenId("original-openid");
                when(members.selectUmsMemberById(7L)).thenReturn(member);WechatVerifiedFactTestSupport.setField(f.gateway,"paymentMemberMapper",members);
                assertTrue(assertThrows(PaymentPreorderException.class,()->f.gateway.createOrder(registration)).isResultUncertain());
                assertTrue(assertThrows(PaymentPreorderException.class,()->f.gateway.createAppOrder(registration)).isResultUncertain());
            } else {
                assertTrue(assertThrows(PaymentPreorderException.class,()->f.gateway.createGenericOrder(no,BigDecimal.TEN,"original","original-openid")).isResultUncertain());
                if("DEP1".equals(no)) assertTrue(assertThrows(PaymentPreorderException.class,()->f.gateway.createGenericAppOrder(no,BigDecimal.TEN,"original")).isResultUncertain());
            }
            assertFalse(f.gateway.closePaymentAttempt(no,no));
            assertEquals(1,db.intents.size());assertEquals("a".repeat(64),row.get("request_hash"));
            assertEquals(no,row.get("out_trade_no"));assertEquals("wx-original",row.get("appid"));
            verifyNoInteractions(mini,app);verify(f.payments,never()).closeOrder(any());
        }
    }

    @Test
    void originalBusinessRetryNeedsItsOwnClosedMarkerAndUnchangedMoneyBeforeTheOwnerCanWriteAnything() {
        var f=new WechatVerifiedPaymentFactGatewayTest.Fixture();
        var db=WechatPaymentIntentTestSupport.install(f.gateway,Map.of("DEP1",BigDecimal.TEN));
        var store=(WechatPaymentIntentStore)WechatVerifiedFactTestSupport.getField(f.gateway,"intents");
        Map<String,Object> row=new HashMap<>(Map.of("mchid","merchant","out_trade_no","DEP1","business_out_trade_no","DEP1",
                "biz_type","DEP","biz_id",1L,"attempt_no",1,"appid","wx-original","trade_type","APP","currency","CNY","total_fen",1000L));
        row.put("request_hash","a".repeat(64));row.put("closed_confirmed_at","old-closed-marker");db.intents.put("merchant:DEP1",row);
        assertThrows(IllegalStateException.class,()->store.lockClosedPaymentAttemptForRetry("DEP1"));
        new TransactionTemplate(transactions()).executeWithoutResult(status->{
            assertTrue(store.lockClosedPaymentAttemptForRetry("DEP1"));
            row.remove("closed_confirmed_at");assertFalse(store.lockClosedPaymentAttemptForRetry("DEP1"));
            row.put("closed_confirmed_at","old-closed-marker");row.put("total_fen",999L);
            assertFalse(store.lockClosedPaymentAttemptForRetry("DEP1"));row.put("total_fen",1000L);
            row.put("business_out_trade_no","OTHER");assertFalse(store.lockClosedPaymentAttemptForRetry("DEP1"));row.put("business_out_trade_no","DEP1");
            row.put("request_hash",null);assertFalse(store.lockClosedPaymentAttemptForRetry("DEP1"));row.put("request_hash","a".repeat(64));
        });
        f.original.setOutTradeNo("DEP1");f.gateway.queryOrderStatus("DEP1");
        new TransactionTemplate(transactions()).executeWithoutResult(status->assertFalse(store.lockClosedPaymentAttemptForRetry("DEP1")));
        assertEquals(1,db.intents.size());assertEquals("DEP1",row.get("business_out_trade_no"));
        assertEquals("a".repeat(64),row.get("request_hash"));assertEquals(1000L,row.get("total_fen"));
        verify(f.payments,never()).closeOrder(any());
    }

    private static PlatformTransactionManager transactions() {
        return new AbstractPlatformTransactionManager(){
            @Override protected Object doGetTransaction(){return new Object();}
            @Override protected void doBegin(Object value,TransactionDefinition definition){}
            @Override protected void doCommit(DefaultTransactionStatus status){}
            @Override protected void doRollback(DefaultTransactionStatus status){}
        };
    }
}
