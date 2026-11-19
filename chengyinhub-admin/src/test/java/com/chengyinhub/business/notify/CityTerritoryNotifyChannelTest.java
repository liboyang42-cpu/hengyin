package com.chengyinhub.business.notify;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.IWxSubscribeMsgService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** CITY-GAPS-R3 item 4: City Monopoly events land as in-app IM system cards only; no subscribe template, no new push. */
class CityTerritoryNotifyChannelTest {

    private static NotifyMessage message() {
        return NotifyMessage.of(NotifyEvent.CITY_TERRITORY, 99L).with("kind", "PLACE_LOST").with("territoryId", "T1")
                .with("title", "老街咖啡").with("text", "「老街咖啡」被玩家7***7抢走了。").with("points", 12);
    }

    @Test
    void inAppChannelWritesASystemCardTheClientCanRoute() {
        IImService im = mock(IImService.class);
        InAppNotifyChannel channel = new InAppNotifyChannel();
        ReflectionTestUtils.setField(channel, "imService", im);
        when(im.sendSystem(eq(99L), eq(ImMessage.MSG_CARD), anyString(), anyString())).thenReturn(5L);

        assertTrue(channel.send(message()));
        ArgumentCaptor<String> extra = ArgumentCaptor.forClass(String.class);
        verify(im).sendSystem(eq(99L), eq(ImMessage.MSG_CARD), eq("「老街咖啡」被玩家7***7抢走了。"), extra.capture());
        JSONObject card = JSON.parseObject(extra.getValue());
        assertEquals("cityTerritory", card.getString("cardType"));
        assertEquals("PLACE_LOST", card.getString("kind"));
        assertEquals("T1", card.getString("territoryId"));
        assertEquals(12, card.getIntValue("points"));
        assertEquals("POINTS", card.getString("currency"));
    }

    @Test
    void imFailureIsSwallowed() {
        IImService im = mock(IImService.class);
        InAppNotifyChannel channel = new InAppNotifyChannel();
        ReflectionTestUtils.setField(channel, "imService", im);
        when(im.sendSystem(anyLong(), anyInt(), anyString(), anyString())).thenThrow(new IllegalStateException("down"));
        assertFalse(assertDoesNotThrow(() -> channel.send(message())));
    }

    @Test
    void wechatSubscribeChannelDoesNotSendCityEvents() {
        IWxSubscribeMsgService wx = mock(IWxSubscribeMsgService.class);
        WxSubscribeChannel channel = new WxSubscribeChannel();
        ReflectionTestUtils.setField(channel, "wx", wx);
        assertFalse(channel.send(message()));
        verifyNoInteractions(wx);
    }
}
