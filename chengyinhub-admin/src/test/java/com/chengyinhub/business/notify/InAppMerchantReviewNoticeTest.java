package com.chengyinhub.business.notify;

import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.service.IImService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** MERCHANT-14:入驻审核结果写进消息列表,通过时给出下一步,驳回时带原因与重新申请。 */
class InAppMerchantReviewNoticeTest {

    @Test
    void approvedTextNamesTheNextSteps() {
        assertEquals("你的商家入驻申请已通过。下一步：在「我的店铺」里设置据点、小游戏和优惠券。",
                InAppNotifyChannel.merchantReviewText(true, "ignored"));
    }

    @Test
    void rejectedTextCarriesTheReasonWhenGiven() {
        assertEquals("你的商家入驻申请未通过。原因：执照不清晰。请完善资料后重新申请。",
                InAppNotifyChannel.merchantReviewText(false, " 执照不清晰 "));
        assertEquals("你的商家入驻申请未通过。请完善资料后重新申请。", InAppNotifyChannel.merchantReviewText(false, null));
    }

    @Test
    void eventIsDeliveredAsAnInAppTextMessage() {
        IImService im = mock(IImService.class);
        InAppNotifyChannel channel = new InAppNotifyChannel();
        ReflectionTestUtils.setField(channel, "imService", im);
        when(im.sendSystem(eq(5L), eq(ImMessage.MSG_TEXT), anyString(), isNull())).thenReturn(9L);

        assertTrue(channel.send(NotifyMessage.of(NotifyEvent.MERCHANT_REVIEW, 5L).with("approved", "1").with("reason", "")));
        verify(im).sendSystem(eq(5L), eq(ImMessage.MSG_TEXT), org.mockito.ArgumentMatchers.contains("已通过"), isNull());
    }

    @Test
    void imFailureReturnsFalseWithoutThrowing() {
        IImService im = mock(IImService.class);
        InAppNotifyChannel channel = new InAppNotifyChannel();
        ReflectionTestUtils.setField(channel, "imService", im);
        when(im.sendSystem(eq(5L), eq(ImMessage.MSG_TEXT), anyString(), isNull())).thenThrow(new IllegalStateException("down"));

        assertFalse(channel.send(NotifyMessage.of(NotifyEvent.MERCHANT_REVIEW, 5L).with("approved", "0").with("reason", "x")));
    }
}
