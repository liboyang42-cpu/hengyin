package com.chengyinhub.business.service.compliance;

import com.chengyinhub.system.service.ISysConfigService;
import com.chengyinhub.web.controller.api.NpcFeatureFlags;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** DOC2 F7 开关：缺省（键不存在）全关；执行挂点要总闸 + 分闸同时开；判定口径与 NpcFeatureFlags 一致。 */
class ComplianceFeatureSwitchesTest {

    @Test
    void everySwitchIsOffWhenNoConfigRowExists() {
        ISysConfigService config = mock(ISysConfigService.class);
        when(config.selectConfigByKey(anyString())).thenReturn(null);
        ComplianceFeatureSwitches switches = new ComplianceFeatureSwitches(config);

        assertFalse(switches.userInvoiceOn());
        assertFalse(switches.minorModeOn());
        assertFalse(switches.minorOrienteeringGateOn());
    }

    @Test
    void enforcementGatesNeedTheMinorModeMasterSwitchToo() {
        ISysConfigService config = mock(ISysConfigService.class);
        when(config.selectConfigByKey(ComplianceFeatureSwitches.MINOR_ORIENTEERING_GATE)).thenReturn("true");
        ComplianceFeatureSwitches switches = new ComplianceFeatureSwitches(config);
        assertFalse(switches.minorOrienteeringGateOn());

        when(config.selectConfigByKey(ComplianceFeatureSwitches.MINOR_MODE)).thenReturn("1");
        assertTrue(switches.minorOrienteeringGateOn());
    }

    @Test
    void unreadableConfigCountsAsOff() {
        ISysConfigService config = mock(ISysConfigService.class);
        when(config.selectConfigByKey(anyString())).thenThrow(new IllegalStateException("redis down"));
        assertFalse(new ComplianceFeatureSwitches(config).userInvoiceOn());
    }

    @Test
    void flagValueSemanticsMatchNpcFeatureFlags() {
        for (String v : Arrays.asList(null, "", "true", "TRUE", "True", "1", "0", "false", "yes", "on", " true")) {
            assertEquals(NpcFeatureFlags.flagOn(v), ComplianceFeatureSwitches.flagOn(v), "值 [" + v + "] 两处口径不一致");
        }
    }
}
