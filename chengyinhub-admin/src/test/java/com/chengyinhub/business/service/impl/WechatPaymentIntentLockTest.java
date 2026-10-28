package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.service.PaymentPreorderException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WechatPaymentIntentLockTest {
    @Test
    void allFourBusinessesFreezeTheFirstChannelAndUnknownRetryCannotChangeIt() {
        for (String no : new String[]{"CLM1", "TPLTM_1", "DEP1", "REG1"}) {
            WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
            var db = WechatPaymentIntentTestSupport.install(service, Map.of(no, BigDecimal.TEN));
            freeze(service, no, "wx-mini", "JSAPI", "openid", "description");
            assertEquals(1, db.intents.size());
            String hash = (String) db.intents.get("merchant:" + no).get("request_hash");
            assertEquals(64, hash.length());
            freeze(service, no, "wx-mini", "JSAPI", "openid", "description");
            assertEquals(hash, db.intents.get("merchant:" + no).get("request_hash"));
            PaymentPreorderException conflict = assertThrows(PaymentPreorderException.class,
                    () -> freeze(service, no, "wx-app", "APP", "", "description"));
            assertTrue(conflict.isResultUncertain());
            assertEquals("wx-mini", db.intents.get("merchant:" + no).get("appid"));
            assertEquals(hash, db.intents.get("merchant:" + no).get("request_hash"));
        }
    }

    @Test
    void unknownOriginalBlocksASecondNumberForTheSameBusiness() {
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        var db = WechatPaymentIntentTestSupport.install(service, Map.of("REG1", BigDecimal.TEN, "REG2", BigDecimal.TEN));
        freeze(service, "REG1", "wx-mini", "JSAPI", "openid", "description");
        assertThrows(PaymentPreorderException.class,
                () -> freeze(service, "REG2", "wx-app", "APP", "", "description"));
        assertEquals(1, db.intents.size());
        assertTrue(db.intents.containsKey("merchant:REG1"));
    }

    @Test
    void closedProofPermitsFreshNumberButNeverOverwritesOrReusesOriginal() {
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        var db = WechatPaymentIntentTestSupport.install(service, Map.of("REG1", BigDecimal.TEN, "REG2", BigDecimal.TEN));
        freeze(service, "REG1", "wx-mini", "JSAPI", "openid", "description");
        db.intents.get("merchant:REG1").put("closed_confirmed_at", new java.sql.Timestamp(1));
        String second = freeze(service, "REG1", "wx-app", "APP", "", "description");
        assertNotEquals("REG1", second);
        assertTrue(second.matches("WXP[A-Fa-f0-9]{29}"));
        assertEquals(2, db.intents.size());
        assertEquals("wx-mini", db.intents.get("merchant:REG1").get("appid"));
        assertEquals("wx-app", db.intents.get("merchant:" + second).get("appid"));
        assertEquals("REG1", db.intents.get("merchant:" + second).get("business_out_trade_no"));
    }

    @Test
    void changedOriginalParametersOrUncommittedBusinessCannotSubmit() {
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        var db = WechatPaymentIntentTestSupport.install(service, Map.of("REG1", BigDecimal.TEN));
        freeze(service, "REG1", "wx-mini", "JSAPI", "openid", "description");
        assertThrows(PaymentPreorderException.class,
                () -> freeze(service, "REG1", "wx-mini", "JSAPI", "OTHER", "description"));
        assertThrows(PaymentPreorderException.class,
                () -> freeze(service, "REG1", "wx-mini", "JSAPI", "openid", "CHANGED"));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(PaymentPreorderException.class,
                    () -> freeze(service, "REG1", "wx-mini", "JSAPI", "openid", "description"));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        assertEquals(1, db.intents.size());
    }

    @Test
    void sdkTimeoutHappensAfterIntentCommitAndChangingAppidCannotCallSdkAgain() {
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        var db = WechatPaymentIntentTestSupport.install(service, Map.of("DEP1", BigDecimal.TEN));
        var sdk = org.mockito.Mockito.mock(com.wechat.pay.java.service.payments.app.AppServiceExtension.class);
        WechatVerifiedFactTestSupport.setField(service, "appServiceExtension", sdk);
        org.mockito.Mockito.when(sdk.prepayWithRequestPayment(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            assertEquals(1, db.commits);
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            throw new RuntimeException("timeout");
        });
        var request = new com.wechat.pay.java.service.payments.app.model.PrepayRequest();
        request.setOutTradeNo("DEP1");
        request.setMchid("merchant");
        request.setAppid("wx-app");
        request.setDescription("description");
        request.setNotifyUrl("https://example.test/notify");
        var amount = new com.wechat.pay.java.service.payments.app.model.Amount();
        amount.setTotal(1000);
        amount.setCurrency("CNY");
        request.setAmount(amount);
        assertThrows(PaymentPreorderException.class,
                () -> WechatVerifiedFactTestSupport.invokeMethod(service, "prepayApp", request));
        request.setAppid("OTHER");
        assertThrows(PaymentPreorderException.class,
                () -> WechatVerifiedFactTestSupport.invokeMethod(service, "prepayApp", request));
        org.mockito.Mockito.verify(sdk, org.mockito.Mockito.times(1)).prepayWithRequestPayment(org.mockito.ArgumentMatchers.any());
        assertEquals("wx-app", db.intents.get("merchant:DEP1").get("appid"));
    }

    @Test
    void historicalUnfrozenProviderOrderCannotBeOverwrittenByCurrentChannel() {
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        var db = WechatPaymentIntentTestSupport.install(service, Map.of("REG1", BigDecimal.TEN));
        var sdk = org.mockito.Mockito.mock(com.wechat.pay.java.service.payments.jsapi.JsapiService.class);
        var historical = new com.wechat.pay.java.service.payments.model.Transaction();
        historical.setAppid("original-mini");
        org.mockito.Mockito.when(sdk.queryOrderByOutTradeNo(org.mockito.ArgumentMatchers.any())).thenReturn(historical);
        WechatVerifiedFactTestSupport.setField(service, "jsapiService", sdk);
        assertTrue(assertThrows(PaymentPreorderException.class,
                () -> freeze(service, "REG1", "new-app", "APP", "", "description")).isResultUncertain());
        assertTrue(db.intents.isEmpty());
        assertEquals(0, db.commits);
    }

    private String freeze(WechatPayServiceImpl service, String no, String appid, String type, String openid, String description) {
        return service.freezePaymentIntent(no, "merchant", appid, type, "CNY", 1000, openid, description, "https://example.test/notify");
    }
}
