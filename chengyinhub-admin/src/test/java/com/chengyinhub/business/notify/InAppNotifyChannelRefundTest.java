package com.chengyinhub.business.notify;

import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.service.IImService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [6-20] 退款事件的站内信兜底。
 *
 * <p>退款原先只发微信订阅消息:没订阅、订阅次数用完或拒收的玩家等于零通知,钱到了也不知道。
 * 站内信不依赖订阅授权,这里钉死退款事件真的会落到 IM 系统消息里,且异常不外抛
 * (调用方在资金事务的 afterCommit 里,抛出会污染已生效的退款)。
 */
class InAppNotifyChannelRefundTest {

    private IImService imService;
    private InAppNotifyChannel channel;

    @BeforeEach
    void setUp() {
        imService = mock(IImService.class);
        channel = new InAppNotifyChannel();
        ReflectionTestUtils.setField(channel, "imService", imService);
    }

    @Test
    void refundEventWritesAnInAppLetterWithTheAmount() {
        when(imService.sendSystem(eq(100L), eq(ImMessage.MSG_TEXT), anyString(), isNull())).thenReturn(88L);

        boolean delivered = channel.send(NotifyMessage.of(NotifyEvent.REFUND, 100L).with("amount", "12.00"));

        assertTrue(delivered);
        verify(imService).sendSystem(eq(100L), eq(ImMessage.MSG_TEXT),
                org.mockito.ArgumentMatchers.contains("12.00"), isNull());
    }

    @Test
    void refundEventWithoutAmountStillSendsATruthfulLetter() {
        when(imService.sendSystem(eq(100L), eq(ImMessage.MSG_TEXT), anyString(), isNull())).thenReturn(88L);

        assertTrue(channel.send(NotifyMessage.of(NotifyEvent.REFUND, 100L)));

        verify(imService).sendSystem(eq(100L), eq(ImMessage.MSG_TEXT),
                org.mockito.ArgumentMatchers.contains("退款"), isNull());
    }

    @Test
    void refundLetterFailureReturnsFalseInsteadOfThrowing() {
        when(imService.sendSystem(anyLong(), any(), anyString(), any())).thenThrow(new RuntimeException("im down"));

        assertFalse(channel.send(NotifyMessage.of(NotifyEvent.REFUND, 100L).with("amount", "12.00")));
    }

    @Test
    void teamStatusStillUsesTheCardLetter() {
        when(imService.sendSystem(eq(100L), eq(ImMessage.MSG_CARD), eq("已加入"), any())).thenReturn(7L);

        assertTrue(channel.send(NotifyMessage.of(NotifyEvent.TEAM_STATUS, 100L)
                .with("teamTitle", "夜骑小队").with("statusText", "已加入")));
    }
}
