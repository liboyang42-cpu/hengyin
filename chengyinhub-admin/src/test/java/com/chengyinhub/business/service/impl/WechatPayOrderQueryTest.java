package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.service.WechatOrderNotExistException;
import com.chengyinhub.common.config.WxPayConfig;
import com.wechat.pay.java.core.exception.ServiceException;
import com.wechat.pay.java.core.http.HttpMethod;
import com.wechat.pay.java.core.http.HttpRequest;
import com.wechat.pay.java.service.payments.jsapi.JsapiService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 裁决2:只有微信明确返回 ORDER_NOT_EXIST 才映射成「微信侧无单」,其它查单失败仍是普通异常(对账不动)。 */
class WechatPayOrderQueryTest {

    @Test
    void orderNotExistIsMappedToDedicatedException() {
        assertThrows(WechatOrderNotExistException.class,
                () -> serviceFailingWith(404, "{\"code\":\"ORDER_NOT_EXIST\",\"message\":\"订单不存在\"}")
                        .queryOrderStatus("T1"));
    }

    @Test
    void otherServiceErrorsStayGenericQueryFailures() {
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> serviceFailingWith(500, "{\"code\":\"SYSTEM_ERROR\",\"message\":\"系统错误\"}")
                        .queryOrderStatus("T1"));
        assertFalse(error instanceof WechatOrderNotExistException);
    }

    private WechatPayServiceImpl serviceFailingWith(int status, String body) {
        WechatPayServiceImpl service = new WechatPayServiceImpl();
        ReflectionTestUtils.setField(service, "wxPayConfig", mock(WxPayConfig.class));
        JsapiService jsapi = mock(JsapiService.class);
        HttpRequest request = new HttpRequest.Builder().httpMethod(HttpMethod.GET)
                .url("https://api.mch.weixin.qq.com/v3/pay/transactions/out-trade-no/T1").build();
        when(jsapi.queryOrderByOutTradeNo(any())).thenThrow(new ServiceException(request, status, body));
        ReflectionTestUtils.setField(service, "jsapiService", jsapi);
        return service;
    }
}
