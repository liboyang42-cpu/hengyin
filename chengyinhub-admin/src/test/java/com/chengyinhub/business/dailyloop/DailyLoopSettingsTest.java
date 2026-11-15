package com.chengyinhub.business.dailyloop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.service.support.DailyLoopSettings;
import com.chengyinhub.system.service.ISysConfigService;

class DailyLoopSettingsTest {

    @Test
    void defaultsWhenConfigMissingOrBroken() {
        ISysConfigService cfg = mock(ISysConfigService.class);
        when(cfg.selectConfigByKey(anyString())).thenReturn("");
        DailyLoopSettings s = new DailyLoopSettings(cfg);
        assertEquals(500, s.walkMinMeters());
        assertEquals(10, s.walkMinCells());
        assertEquals(7, s.freezeEveryDays());
        assertEquals(2, s.freezeMax());

        when(cfg.selectConfigByKey(DailyLoopSettings.KEY_FREEZE_MAX)).thenReturn("abc");
        assertEquals(2, s.freezeMax());
        when(cfg.selectConfigByKey(DailyLoopSettings.KEY_FREEZE_MAX)).thenReturn("99");
        assertEquals(2, s.freezeMax(), "越过编译期上限 5 时回落默认值");
        when(cfg.selectConfigByKey(DailyLoopSettings.KEY_FREEZE_MAX)).thenThrow(new RuntimeException("redis down"));
        assertEquals(2, s.freezeMax());
        assertEquals(500, new DailyLoopSettings(null).walkMinMeters());
    }

    @Test
    void walkQualifiesOnDistanceOrCells() {
        ISysConfigService cfg = mock(ISysConfigService.class);
        when(cfg.selectConfigByKey(DailyLoopSettings.KEY_WALK_MIN_METERS)).thenReturn("500");
        when(cfg.selectConfigByKey(DailyLoopSettings.KEY_WALK_MIN_CELLS)).thenReturn("10");
        DailyLoopSettings s = new DailyLoopSettings(cfg);
        assertTrue(s.walkQualifies(500, 0));
        assertTrue(s.walkQualifies(0, 10));
        assertFalse(s.walkQualifies(499, 9));
        assertFalse(s.walkQualifies(null, null));

        when(cfg.selectConfigByKey(DailyLoopSettings.KEY_WALK_MIN_METERS)).thenReturn("0");
        assertFalse(s.walkQualifies(5000, 9), "里程门槛为 0 = 只看格子");
        when(cfg.selectConfigByKey(DailyLoopSettings.KEY_WALK_MIN_CELLS)).thenReturn("0");
        assertTrue(s.walkQualifies(0, 0), "两项都为 0 = 任何结算的漫游都算");
    }
}
