package com.chengyinhub.web.health;

import com.chengyinhub.business.service.IWechatPayService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WechatPayHealthIndicatorTest {
    @Test
    void reportsUpWhenWechatPayIsReady() {
        IWechatPayService wechatPayService = Mockito.mock(IWechatPayService.class);
        Mockito.when(wechatPayService.isReady()).thenReturn(true);

        Health health = new WechatPayHealthIndicator(wechatPayService).health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("ready", health.getDetails().get("state"));
    }

    @Test
    void reportsDownWithoutSensitiveConfigurationDetailsWhenWechatPayIsNotReady() {
        IWechatPayService wechatPayService = Mockito.mock(IWechatPayService.class);
        Mockito.when(wechatPayService.isReady()).thenReturn(false);

        Health health = new WechatPayHealthIndicator(wechatPayService).health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("unavailable", health.getDetails().get("state"));
        assertEquals(1, health.getDetails().size());
    }
}
