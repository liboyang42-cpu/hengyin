package com.chengyinhub.business.service.impl;

import com.chengyinhub.common.config.WxPayConfig;
import com.wechat.pay.java.service.refund.RefundService;
import com.wechat.pay.java.service.refund.model.CreateRequest;
import com.wechat.pay.java.service.refund.model.Refund;
import com.wechat.pay.java.service.refund.model.Status;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR10 复审:退款结果通知地址默认留空。留空时退款请求不得携带 notify_url(由商户平台默认回调兜底),
 * 配置后原样下发;application.yml 不得把生产回调写成所有环境的默认值。
 */
class WechatPayRefundNotifyUrlTest {

    private CreateRequest submitWith(String refundNotifyUrl) {
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        WxPayConfig config = mock(WxPayConfig.class);
        when(config.getRefundNotifyUrl()).thenReturn(refundNotifyUrl);
        WechatVerifiedFactTestSupport.setField(service, "wxPayConfig", config);
        RefundService sdk = mock(RefundService.class);
        Refund refund = new Refund();
        refund.setStatus(Status.PROCESSING);
        when(sdk.create(any())).thenReturn(refund);
        WechatVerifiedFactTestSupport.installRefundOriginal(service,sdk,"T1","T1","TX1",BigDecimal.ONE);
        completeRefund(refund,"T1","R1","TX1");

        service.submitRefund("T1", "R1", new BigDecimal("1.00"), new BigDecimal("1.00"), "退款");
        ArgumentCaptor<CreateRequest> cap = ArgumentCaptor.forClass(CreateRequest.class);
        verify(sdk).create(cap.capture());
        return cap.getValue();
    }

    private void completeRefund(Refund refund,String wxNo,String refundNo,String transactionId) {
        refund.setOutTradeNo(wxNo);refund.setOutRefundNo(refundNo);
        refund.setTransactionId(transactionId);refund.setRefundId("WX-"+refundNo);
        var amount=new com.wechat.pay.java.service.refund.model.Amount();
        amount.setTotal(100L);amount.setRefund(100L);amount.setCurrency("CNY");refund.setAmount(amount);
    }

    @Test
    void blankRefundNotifyUrl_omitsField() {
        assertNull(submitWith("").getNotifyUrl());
        assertNull(submitWith("   ").getNotifyUrl());
        assertNull(submitWith(null).getNotifyUrl());
    }

    @Test
    void configuredRefundNotifyUrl_isSent() {
        String url = "https://api.example.com/prod-api/api/registration/refund-callback";
        assertEquals(url, submitWith(url).getNotifyUrl());
    }

    @Test
    void refundUsesRetainedActualAttemptAndNormalizesCallbackBusinessIdentity() {
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        String alias = "WXP0123456789abcdef0123456789abc";
        WxPayConfig config = mock(WxPayConfig.class);
        when(config.getMerchantId()).thenReturn("merchant");
        WechatVerifiedFactTestSupport.setField(service, "wxPayConfig", config);
        RefundService sdk = mock(RefundService.class);
        Refund refund = new Refund();
        refund.setStatus(Status.PROCESSING);
        when(sdk.create(any())).thenReturn(refund);
        WechatVerifiedFactTestSupport.installRefundOriginal(service,sdk,"DEP1",alias,"TX-DEP1",BigDecimal.ONE);
        completeRefund(refund,alias,"RF1","TX-DEP1");
        assertEquals(com.chengyinhub.business.service.RefundRequestResult.ACCEPTED,
                service.submitRefund("DEP1", "RF1", BigDecimal.ONE, BigDecimal.ONE, "退款"));
        ArgumentCaptor<CreateRequest> request = ArgumentCaptor.forClass(CreateRequest.class);
        verify(sdk).create(request.capture());
        assertEquals("TX-DEP1", request.getValue().getTransactionId());
        assertNull(request.getValue().getOutTradeNo());
        refund.setOutTradeNo(alias);
        refund.setOutRefundNo("RF1");
        when(sdk.queryByOutRefundNo(any())).thenReturn(refund);
        java.util.Map<String, String> facts = service.queryRefundFacts("RF1");
        assertEquals("DEP1", facts.get("outTradeNo"));
        assertEquals(alias, facts.get("wxOutTradeNo"));
    }

    @Test
    void applicationYml_refundNotifyUrlDefaultsToEmpty() throws Exception {
        Path yml = Paths.get("src/main/resources/application.yml");
        if (!Files.exists(yml)) yml = Paths.get("chengyinhub-admin/src/main/resources/application.yml");
        String text = new String(Files.readAllBytes(yml), StandardCharsets.UTF_8);
        assertTrue(text.contains("refund-notify-url: ${CHENGYIN_WXPAY_REFUND_NOTIFY_URL:}"),
                "退款通知地址默认必须为空,生产经环境变量显式注入");
        assertFalse(text.matches("(?s).*CHENGYIN_WXPAY_REFUND_NOTIFY_URL:https?://.*"),
                "不得给退款通知地址设生产默认值");
    }
}
