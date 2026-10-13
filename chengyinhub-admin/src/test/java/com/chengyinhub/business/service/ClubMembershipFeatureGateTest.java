package com.chengyinhub.business.service;

import com.chengyinhub.business.service.impl.ClubMembershipFeatureGate;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClubMembershipFeatureGateTest {
    @Test
    void onlyStrictTrueEnablesAndMissingOrReadFailureFailClosed() {
        ISysConfigService config = mock(ISysConfigService.class);
        ClubMembershipFeatureGate gate = new ClubMembershipFeatureGate();
        ReflectionTestUtils.setField(gate, "configService", config);

        when(config.selectConfigByKey("feature.flag.clubMembership")).thenReturn(null, "false", " true ");
        assertFalse(gate.isEnabled());
        assertFalse(gate.isEnabled());
        assertTrue(gate.isEnabled());

        when(config.selectConfigByKey("feature.flag.clubMembership")).thenThrow(new RuntimeException("redis down"));
        assertFalse(gate.isEnabled());
        assertThrows(ServiceException.class, gate::requireEnabled);
    }
}
