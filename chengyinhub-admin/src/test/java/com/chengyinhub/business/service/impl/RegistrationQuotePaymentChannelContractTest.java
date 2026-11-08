package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.RegistrationQuoteResult;
import com.chengyinhub.business.domain.dto.RegistrationRequest;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.WechatPaymentOperations;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 报价的支付通道只是「现有渠道是否就绪」，不是商品政策或 IAP 审批结论；
 * 通道探测失败必须表现为不可用，而不是假装可付。
 */
class RegistrationQuotePaymentChannelContractTest {

    private RegistrationCheckoutServiceImpl service(WechatPaymentOperations pay) {
        RegistrationCheckoutServiceImpl service = new RegistrationCheckoutServiceImpl();
        ReflectionTestUtils.setField(service, "registrationService", mock(ICmsRegistrationService.class));
        ReflectionTestUtils.setField(service, "wechatPayService", pay);
        return service;
    }

    private RegistrationCheckoutServiceImpl withQuote(WechatPaymentOperations pay, BigDecimal amount) {
        RegistrationCheckoutServiceImpl service = service(pay);
        ICmsRegistrationService registrations =
                (ICmsRegistrationService) ReflectionTestUtils.getField(service, "registrationService");
        RegistrationQuoteResult quote = new RegistrationQuoteResult();
        quote.setPayAmount(amount);
        quote.setQuoteSign("sign");
        when(registrations.quoteRegistration(any(), any(), any(), any(), any(), any())).thenReturn(quote);
        return service;
    }

    private RegistrationRequest request() {
        RegistrationRequest request = new RegistrationRequest();
        request.setOwnerType(2);
        request.setOwnerId(200L);
        request.setTicketId(300L);
        return request;
    }

    @Test
    void readyChannelsAreReportedForPositiveAmount() {
        WechatPaymentOperations pay = mock(WechatPaymentOperations.class);
        when(pay.isReady()).thenReturn(true);
        when(pay.isAppReady()).thenReturn(true);

        RegistrationQuoteResult quote = withQuote(pay, new BigDecimal("100.00")).quote(88L, request());
        assertEquals(List.of("JSAPI", "APP"), quote.getPaymentChannels());
    }

    @Test
    void jsapiOnlyWhenAppChannelIsNotConfigured() {
        WechatPaymentOperations pay = mock(WechatPaymentOperations.class);
        when(pay.isReady()).thenReturn(true);
        when(pay.isAppReady()).thenReturn(false);

        RegistrationQuoteResult quote = withQuote(pay, new BigDecimal("100.00")).quote(88L, request());
        assertEquals(List.of("JSAPI"), quote.getPaymentChannels());
    }

    /** 零元单没有支付动作：不报通道，也不把「无通道」表达成支付失败。 */
    @Test
    void zeroAmountQuoteReportsNoChannel() {
        WechatPaymentOperations pay = mock(WechatPaymentOperations.class);
        when(pay.isReady()).thenReturn(true);
        when(pay.isAppReady()).thenReturn(true);

        RegistrationQuoteResult quote = withQuote(pay, BigDecimal.ZERO).quote(88L, request());
        assertTrue(quote.getPaymentChannels().isEmpty());
    }

    /** 通道探测异常按不可用处理；绝不降级成「可用」。 */
    @Test
    void channelProbeFailureNeverFakesAvailability() {
        WechatPaymentOperations pay = mock(WechatPaymentOperations.class);
        when(pay.isReady()).thenThrow(new IllegalStateException("channel config unreadable"));
        when(pay.isAppReady()).thenReturn(true);

        RegistrationQuoteResult quote = withQuote(pay, new BigDecimal("100.00")).quote(88L, request());
        assertTrue(quote.getPaymentChannels().isEmpty());
        assertTrue(new BigDecimal("100.00").compareTo(quote.getPayAmount()) == 0, "金额不受通道探测影响");
    }
}
