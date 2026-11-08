package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.MarketingExtraSeatQuote;
import com.chengyinhub.business.domain.dto.MarketingExtraSeatQuoteRequest;
import com.chengyinhub.business.exception.MarketingProductException;
import com.chengyinhub.business.service.IMarketingExtraSeatService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 加座 HTTP 面只暴露报价与"我的加座"两个读口。
 *
 * <p>三条必须成立的合同：
 * 1. 会员身份只来自登录态 —— 请求体里塞别人的 memberId 不会改变服务端判定的买家；
 * 2. 拒绝原因以 reasonCode 透出，客户端不解析中文文案；
 * 3. 成交不在这里（成交走商品域唯一的积分/微信路径），因此这里没有收款/建单入口。
 */
class ApiMarketingExtraSeatControllerTest {

    private static final long CALLER = 7L;
    private static final long VICTIM = 11L;

    private IMarketingExtraSeatService service;
    private ApiMarketingExtraSeatController controller;

    @BeforeEach
    void setUp() {
        service = mock(IMarketingExtraSeatService.class);
        controller = spy(new ApiMarketingExtraSeatController(service));
        doReturn(CALLER).when(controller).getAppUserId();
    }

    @Test
    void quoteUsesLoginIdentityAndKeepsRequestedRecipient() {
        when(service.quote(anyLong(), any())).thenReturn(quote());

        MarketingExtraSeatQuoteRequest request = new MarketingExtraSeatQuoteRequest();
        request.setProductId(20L);
        request.setOriginalOrderNo("MKO-OTHER-MEMBER");
        request.setRecipientMemberId(VICTIM);

        AjaxResult result = controller.quote(request);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(service).quote(eq(CALLER), eq(request));
        assertEquals(VICTIM, ((MarketingExtraSeatQuote) result.get(AjaxResult.DATA_TAG))
                .getRecipientMemberId().longValue());
    }

    @Test
    void rejectionKeepsMachineReadableReasonCode() {
        when(service.quote(anyLong(), any())).thenThrow(
                MarketingProductException.forbidden("ORIGINAL_ORDER_FORBIDDEN", "原订单不属于你"));

        MarketingProductException rejection = assertThrows(MarketingProductException.class,
                () -> controller.quote(new MarketingExtraSeatQuoteRequest()));
        assertEquals(403, rejection.getHttpStatus());
        assertEquals("ORIGINAL_ORDER_FORBIDDEN", rejection.getReasonCode());
    }

    @Test
    void myOrdersDistrustsClientLimit() {
        when(service.myExtraSeats(anyLong(), anyInt())).thenReturn(Collections.emptyList());

        AjaxResult result = controller.myExtraSeats(1000);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        // 上限由服务层夹紧（MAX_READ_LIMIT），控制器只负责把登录身份传下去。
        verify(service).myExtraSeats(CALLER, 1000);
    }

    @Test
    void anonymousCallerIsRefusedBeforeAnyRead() {
        doReturn(null).when(controller).getAppUserId();

        ServiceException rejection = assertThrows(ServiceException.class,
                () -> controller.myExtraSeats(20));

        assertEquals(HttpStatus.UNAUTHORIZED, rejection.getCode());
        verify(service, never()).myExtraSeats(anyLong(), anyInt());
    }

    private MarketingExtraSeatQuote quote() {
        MarketingExtraSeatQuote quote = new MarketingExtraSeatQuote();
        quote.setProductId(20L);
        quote.setFulfillmentKind(3);
        quote.setQuantity(1);
        quote.setRecipientMemberId(VICTIM);
        quote.setPartyBinding("NOT_APPLICABLE");
        quote.setPartyBindingReason("SELF_PLAY_HAS_NO_TEAM_ROSTER");
        quote.setUnitPointsPrice(300);
        quote.setTotalPointsAmount(300);
        quote.setTotalCashAmount(new BigDecimal("6.00"));
        return quote;
    }
}
