package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.service.WechatOrderNotExistException;
import com.chengyinhub.common.config.WxPayConfig;
import com.wechat.pay.java.core.exception.ServiceException;
import com.wechat.pay.java.core.http.HttpMethod;
import com.wechat.pay.java.core.http.HttpRequest;
import com.wechat.pay.java.service.payments.jsapi.JsapiService;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.payments.model.TransactionAmount;
import org.junit.jupiter.api.Test;
import com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 裁决2:只有微信明确返回 ORDER_NOT_EXIST 才映射成「微信侧无单」,其它查单失败仍是普通异常(对账不动)。 */
class WechatPayOrderQueryTest {

    @Test
    void paidQueryReturnsCompleteOriginalChannelFacts() {
        var facts = serviceReturning(original()).queryOrderStatus("T1");
        assertEquals("wx-origin", facts.get("appid"));
        assertEquals("merchant", facts.get("mchid"));
        assertEquals("APP", facts.get("trade_type"));
        assertEquals("TX1", facts.get("transaction_id"));
        assertEquals("100", facts.get("total"));
        assertEquals("CNY", facts.get("currency"));
    }

    @Test
    void directConfirmationsCannotConsumeWrongOrIncompleteOriginalFacts() {
        java.util.List<java.util.function.Consumer<Transaction>> invalid = java.util.List.of(
                t -> t.setOutTradeNo("OTHER"), t -> t.setMchid("OTHER"), t -> t.setAppid(null),
                t -> t.setAmount(null), t -> t.getAmount().setCurrency("USD"),
                t -> t.getAmount().setTotal(null), t -> t.getAmount().setTotal(0),
                t -> t.setTradeState(null), t -> t.setTransactionId(null), t -> t.setTradeType(null));
        for (var change : invalid) {
            Transaction t = original();
            change.accept(t);
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> serviceReturning(t).queryOrderStatus("T1"));
            assertFalse(failure instanceof WechatOrderNotExistException);
        }
    }

    @Test
    void unpaidQueryPreservesKnownStateWhenOptionalTransactionFieldsAreAbsent() {
        Transaction t = original();
        t.setTradeState(Transaction.TradeStateEnum.NOTPAY);
        t.setTradeType(null);
        t.setTransactionId(null);
        t.setSuccessTime("2026-10-24T00:00:00Z");
        var facts = serviceReturning(t).queryOrderStatus("T1");
        assertEquals("NOTPAY", facts.get("trade_state"));
        assertNull(facts.get("transaction_id"));
        assertNull(facts.get("trade_type"));
    }

    @Test
    void orderNotExistIsMappedToDedicatedException() {
        WechatPayServiceImpl service = serviceFailingWith(404,
                "{\"code\":\"ORDER_NOT_EXIST\",\"message\":\"订单不存在\"}");
        assertThrows(WechatOrderNotExistException.class, () -> service.queryOrderStatus("T1"));
        verifyOriginalQueryWasCalled(service);
    }

    @Test
    void otherServiceErrorsStayGenericQueryFailures() {
        for (String[] failure : new String[][]{{"500", "SYSTEM_ERROR"}, {"500", "ORDER_NOT_EXIST"},
                {"429", "ORDER_NOT_EXIST"}}) {
            WechatPayServiceImpl service = serviceFailingWith(Integer.parseInt(failure[0]),
                    "{\"code\":\"" + failure[1] + "\",\"message\":\"query unavailable\"}");
            RuntimeException error = assertThrows(RuntimeException.class, () -> service.queryOrderStatus("T1"));
            assertFalse(error instanceof WechatOrderNotExistException);
            verifyOriginalQueryWasCalled(service);
        }
    }

    private WechatPayServiceImpl serviceFailingWith(int status, String body) {
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        WechatPaymentIntentTestSupport.install(service, java.util.Map.of());
        WxPayConfig config = mock(WxPayConfig.class);
        when(config.getMerchantId()).thenReturn("merchant");
        WechatVerifiedFactTestSupport.setField(service, "wxPayConfig", config);
        JsapiService jsapi = mock(JsapiService.class);
        HttpRequest request = new HttpRequest.Builder().httpMethod(HttpMethod.GET)
                .url("https://api.mch.weixin.qq.com/v3/pay/transactions/out-trade-no/T1").build();
        when(jsapi.queryOrderByOutTradeNo(any())).thenThrow(new ServiceException(request, status, body));
        WechatVerifiedFactTestSupport.setField(service, "jsapiService", jsapi);
        return service;
    }

    private void verifyOriginalQueryWasCalled(WechatPayServiceImpl service) {
        JsapiService sdk = (JsapiService) WechatVerifiedFactTestSupport.getField(service, "jsapiService");
        org.mockito.Mockito.verify(sdk).queryOrderByOutTradeNo(org.mockito.ArgumentMatchers.argThat(
                request -> "merchant".equals(request.getMchid()) && "T1".equals(request.getOutTradeNo())));
    }

    private Transaction original() {
        Transaction t = new Transaction();
        t.setOutTradeNo("T1");
        t.setMchid("merchant");
        t.setAppid("wx-origin");
        t.setTransactionId("TX1");
        t.setSuccessTime("2026-10-24T00:00:00Z");
        t.setTradeState(Transaction.TradeStateEnum.SUCCESS);
        t.setTradeType(Transaction.TradeTypeEnum.APP);
        TransactionAmount amount = new TransactionAmount();
        amount.setTotal(100);
        amount.setCurrency("CNY");
        t.setAmount(amount);
        return t;
    }

    private WechatPayServiceImpl serviceReturning(Transaction t) {
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        WechatPaymentIntentTestSupport.install(service, java.util.Map.of());
        WxPayConfig config = mock(WxPayConfig.class);
        when(config.getMerchantId()).thenReturn("merchant");
        WechatVerifiedFactTestSupport.setField(service, "wxPayConfig", config);
        JsapiService sdk = mock(JsapiService.class);
        when(sdk.queryOrderByOutTradeNo(any())).thenReturn(t);
        WechatVerifiedFactTestSupport.setField(service, "jsapiService", sdk);
        return service;
    }
}
