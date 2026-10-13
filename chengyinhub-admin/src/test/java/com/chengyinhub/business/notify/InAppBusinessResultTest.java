package com.chengyinhub.business.notify;

import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.service.IImService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InAppBusinessResultTest {
    @Test
    void officialResultActuallyPersistsInTheSpecifiedSystemConversation() {
        IImService im = mock(IImService.class);
        InAppNotifyChannel channel = new InAppNotifyChannel();
        ReflectionTestUtils.setField(channel, "imService", im);
        com.chengyinhub.business.service.impl.AlarmService alarm = mock(com.chengyinhub.business.service.impl.AlarmService.class);
        ReflectionTestUtils.setField(channel, "alarmService", alarm);
        when(im.sendSystem(101L, ImMessage.MSG_TEXT, "审批结果\n本期名额已满", null)).thenReturn(201L);
        NotifyMessage notice = NotifyMessage.of(NotifyEvent.OFFICIAL_BROADCAST, 101L)
                .with("title", "审批结果").with("sub", "本期名额已满");
        assertTrue(channel.send(notice));
        verify(im).sendSystem(101L, ImMessage.MSG_TEXT, "审批结果\n本期名额已满", null);
        verifyNoInteractions(alarm);
        when(im.sendSystem(101L, ImMessage.MSG_TEXT, "审批结果\n本期名额已满", null)).thenReturn(null);
        assertFalse(channel.send(notice));
        verify(alarm).alarm(eq("业务结果站内信未送达"), eq("event=OFFICIAL_BROADCAST memberId=101 type=NO_MESSAGE_ID"));
        when(im.sendSystem(101L, ImMessage.MSG_TEXT, "审批结果\n本期名额已满", null)).thenReturn(0L);
        assertFalse(channel.send(notice));
        when(im.sendSystem(101L, ImMessage.MSG_TEXT, "审批结果\n本期名额已满", null))
                .thenThrow(new IllegalStateException("must not leak message content"));
        assertFalse(channel.send(notice));
        verify(alarm).alarm(eq("业务结果站内信未送达"), eq("event=OFFICIAL_BROADCAST memberId=101 type=IllegalStateException"));
    }
}
