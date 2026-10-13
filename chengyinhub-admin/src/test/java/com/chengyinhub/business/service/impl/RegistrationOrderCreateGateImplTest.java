package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.dto.RegistrationQuoteResult;
import com.chengyinhub.business.domain.dto.RegistrationRequest;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IMerchantBusinessStatusService;
import com.chengyinhub.business.service.IWechatPayService;
import com.chengyinhub.business.service.RegistrationCheckoutException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 新建报名实时门禁的直接红绿覆盖。
 *
 * 门禁的生产 seam:①主题自玩单按实时 selfPlayPrice + 支付可用性判是否放行;②普通单必须带报价签名、
 * 且服务端重算报价的签名要与客户端一致(失败读回 = 挡住已过期的价格);③需付款的单才校验支付可用性,
 * 零元单即使支付不可用也放行。全部走 Mockito 打桩三个协作者,不碰数据库,可本机稳定重放。
 */
class RegistrationOrderCreateGateImplTest {

    private final ICmsRegistrationService registrationService = mock(ICmsRegistrationService.class);
    private final ICmsTopicService topicService = mock(ICmsTopicService.class);
    private final IWechatPayService wechatPayService = mock(IWechatPayService.class);
    private final IMerchantBusinessStatusService merchantBusinessStatusService =
            mock(IMerchantBusinessStatusService.class);

    private RegistrationOrderCreateGateImpl gate() {
        RegistrationOrderCreateGateImpl gate = new RegistrationOrderCreateGateImpl();
        ReflectionTestUtils.setField(gate, "registrationService", registrationService);
        ReflectionTestUtils.setField(gate, "topicService", topicService);
        ReflectionTestUtils.setField(gate, "wechatPayService", wechatPayService);
        ReflectionTestUtils.setField(gate, "merchantBusinessStatusService", merchantBusinessStatusService);
        return gate;
    }

    private RegistrationRequest topicRequest(long topicId) {
        RegistrationRequest request = new RegistrationRequest();
        request.setOwnerType(1);
        request.setOwnerId(topicId);
        return request;
    }

    private RegistrationRequest activityRequest(String quoteSign) {
        RegistrationRequest request = new RegistrationRequest();
        request.setOwnerType(2);
        request.setOwnerId(500L);
        request.setTicketId(9L);
        request.setIsUsePoint(0);
        request.setQuoteSign(quoteSign);
        return request;
    }

    private CmsTopic topicWithPrice(BigDecimal price) {
        CmsTopic topic = new CmsTopic();
        topic.setSelfPlayPrice(price);
        return topic;
    }

    private RegistrationQuoteResult quote(String sign, BigDecimal payAmount) {
        RegistrationQuoteResult result = new RegistrationQuoteResult();
        result.setQuoteSign(sign);
        result.setPayAmount(payAmount);
        return result;
    }

    private void stubQuote(RegistrationQuoteResult result) {
        when(registrationService.quoteRegistration(eq(7L), eq(2), eq(500L), eq(9L), any(), eq(0)))
                .thenReturn(result);
    }

    // ===== 主题自玩单(ownerType=1) =====

    @Test
    void paidTopicBlockedWhenPaymentUnavailable() {
        when(topicService.selectCmsTopicById(100L)).thenReturn(topicWithPrice(new BigDecimal("19.90")));
        when(wechatPayService.isReady()).thenReturn(false);

        RegistrationCheckoutException ex = assertThrows(RegistrationCheckoutException.class,
                () -> gate().validate(7L, topicRequest(100L)));
        assertEquals(503, ex.getCode());
    }

    @Test
    void paidTopicPassesWhenPaymentReady() {
        when(topicService.selectCmsTopicById(100L)).thenReturn(topicWithPrice(new BigDecimal("19.90")));
        when(wechatPayService.isReady()).thenReturn(true);

        assertDoesNotThrow(() -> gate().validate(7L, topicRequest(100L)));
    }

    @Test
    void freeTopicPassesEvenWhenPaymentUnavailable() {
        when(topicService.selectCmsTopicById(100L)).thenReturn(topicWithPrice(BigDecimal.ZERO));
        when(wechatPayService.isReady()).thenReturn(false);

        assertDoesNotThrow(() -> gate().validate(7L, topicRequest(100L)));
        // 零元单根本不该去问支付可用性。
        verify(wechatPayService, never()).isReady();
    }

    @Test
    void missingTopicPassesWithoutTouchingPayment() {
        when(topicService.selectCmsTopicById(100L)).thenReturn(null);

        assertDoesNotThrow(() -> gate().validate(7L, topicRequest(100L)));
        verify(wechatPayService, never()).isReady();
    }

    // ===== 营业状态门禁(拍板 2026-09-16 #10) =====

    @Test
    void closedMerchantBlocksTopicOrderWithExplicitMessage() {
        when(merchantBusinessStatusService.isTargetMerchantClosed(1, 100L)).thenReturn(true);

        RegistrationCheckoutException ex = assertThrows(RegistrationCheckoutException.class,
                () -> gate().validate(7L, topicRequest(100L)));
        assertEquals(423, ex.getCode());
        assertEquals("商家暂停营业，暂不可报名", ex.getMessage());
        // 闭店拒单发生在任何报价/支付判断之前 —— 不得到下游再做无谓查询。
        verify(topicService, never()).selectCmsTopicById(any());
        verify(wechatPayService, never()).isReady();
    }

    @Test
    void closedMerchantBlocksActivityOrderBeforeQuote() {
        when(merchantBusinessStatusService.isTargetMerchantClosed(2, 500L)).thenReturn(true);

        RegistrationCheckoutException ex = assertThrows(RegistrationCheckoutException.class,
                () -> gate().validate(7L, activityRequest("MATCH")));
        assertEquals(423, ex.getCode());
        verify(registrationService, never()).quoteRegistration(any(), any(), any(), any(), any(), any());
    }

    @Test
    void openMerchantOrderStillFollowsPriceAndPaymentGates() {
        when(merchantBusinessStatusService.isTargetMerchantClosed(1, 100L)).thenReturn(false);
        when(topicService.selectCmsTopicById(100L)).thenReturn(topicWithPrice(new BigDecimal("19.90")));
        when(wechatPayService.isReady()).thenReturn(true);

        assertDoesNotThrow(() -> gate().validate(7L, topicRequest(100L)));
        verify(merchantBusinessStatusService).isTargetMerchantClosed(1, 100L);
    }

    // ===== 普通报名单(ownerType≠1) =====

    @Test
    void activityRejectedWhenQuoteSignMissing() {
        RegistrationCheckoutException ex = assertThrows(RegistrationCheckoutException.class,
                () -> gate().validate(7L, activityRequest("   ")));
        assertEquals(400, ex.getCode());
        // 缺签名时不应先去重算报价。
        verify(registrationService, never()).quoteRegistration(any(), any(), any(), any(), any(), any());
    }

    @Test
    void activityRejectedWhenServerRequoteSignDiffers() {
        // 失败读回:客户端签名与服务端重算签名不一致 = 价格已过期,必须挡 409。
        stubQuote(quote("SERVER-FRESH", new BigDecimal("30.00")));
        // 显式打开下游支付可用性闸:mock 默认 isReady()=false,签名闸若被改坏,
        // 请求会先被下游 503 接住,失败信号无法指认是 409 签名闸出了问题。
        // 置为 true 后,负控 mutation 才会精确表现为「预期的 409 未抛出」。
        when(wechatPayService.isReady()).thenReturn(true);

        RegistrationCheckoutException ex = assertThrows(RegistrationCheckoutException.class,
                () -> gate().validate(7L, activityRequest("CLIENT-STALE")));
        assertEquals(409, ex.getCode());
        verify(wechatPayService, never()).isReady();
    }

    @Test
    void activityBlockedWhenPaymentUnavailableForPayableQuote() {
        stubQuote(quote("MATCH", new BigDecimal("30.00")));
        when(wechatPayService.isReady()).thenReturn(false);

        RegistrationCheckoutException ex = assertThrows(RegistrationCheckoutException.class,
                () -> gate().validate(7L, activityRequest("MATCH")));
        assertEquals(503, ex.getCode());
    }

    @Test
    void activityPassesWhenSignMatchesAndPaymentReady() {
        stubQuote(quote("MATCH", new BigDecimal("30.00")));
        when(wechatPayService.isReady()).thenReturn(true);

        assertDoesNotThrow(() -> gate().validate(7L, activityRequest("MATCH")));
    }

    @Test
    void activityZeroAmountPassesEvenWhenPaymentUnavailable() {
        stubQuote(quote("MATCH", BigDecimal.ZERO));

        assertDoesNotThrow(() -> gate().validate(7L, activityRequest("MATCH")));
        verify(wechatPayService, never()).isReady();
    }
}
