package com.chengyinhub.web.controller.api;

import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommunityPostFeatureGateTest {
    private final ISysConfigService config = mock(ISysConfigService.class);
    private final CommunityPostFeatureGate gate = new CommunityPostFeatureGate(config);

    @Test
    void defaultsClosedAndRequiresPilotMembership() {
        assertFalse(gate.canRead(8L));
        assertFalse(gate.canWrite(8L));

        when(config.selectConfigByKey("feature.flag.communityPostRead")).thenReturn("true");
        when(config.selectConfigByKey("feature.flag.communityPostWrite")).thenReturn("1");
        when(config.selectConfigByKey("feature.flag.communityPostPilotMembers")).thenReturn("7, 8,9");

        assertTrue(gate.canRead(8L));
        assertTrue(gate.canWrite(8L));
        assertFalse(gate.canRead(10L));
        assertFalse(gate.canWrite(10L));
        assertFalse(gate.canRead(0L));
    }

    @Test
    void wildcardAllowsControlledGlobalCutover() {
        when(config.selectConfigByKey("feature.flag.communityPostRead")).thenReturn("true");
        when(config.selectConfigByKey("feature.flag.communityPostWrite")).thenReturn("true");
        when(config.selectConfigByKey("feature.flag.communityPostPilotMembers")).thenReturn("*");

        assertTrue(gate.canRead(8L));
        assertTrue(gate.canWrite(9L));
    }
}
