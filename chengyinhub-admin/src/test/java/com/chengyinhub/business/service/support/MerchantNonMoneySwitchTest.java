package com.chengyinhub.business.service.support;

import com.chengyinhub.business.service.MerchantDeskException;
import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Q1 merchant-reach: one non-money switch opens the desk; products and money stay on their own switches. */
class MerchantNonMoneySwitchTest {
    private ISysConfigService config;
    private MerchantDeskSwitch deskSwitch;

    @BeforeEach
    void setUp() {
        config = mock(ISysConfigService.class);
        deskSwitch = new MerchantDeskSwitch();
        ReflectionTestUtils.setField(deskSwitch, "configService", config);
    }

    @Test
    void defaultsClosed() {
        assertFalse(deskSwitch.isEnabled());
        assertFalse(deskSwitch.nonMoneyEnabled());
        assertThrows(MerchantDeskException.class, deskSwitch::requireEnabled);
    }

    @Test
    void nonMoneySwitchOpensDeskButNotProducts() {
        when(config.selectConfigByKey(MerchantDeskSwitch.NON_MONEY_KEY)).thenReturn("true");
        assertTrue(deskSwitch.nonMoneyEnabled());
        assertTrue(deskSwitch.isEnabled());
        assertDoesNotThrow(deskSwitch::requireEnabled);
        assertFalse(deskSwitch.productsEnabled());
        assertThrows(MerchantDeskException.class, deskSwitch::requireProductsEnabled);
    }

    @Test
    void legacyDeskKeyStillOpensDesk() {
        when(config.selectConfigByKey(MerchantDeskSwitch.CONFIG_KEY)).thenReturn("true");
        assertTrue(deskSwitch.isEnabled());
        assertFalse(deskSwitch.nonMoneyEnabled());
    }

    @Test
    void onlyStrictTrueOpens() {
        when(config.selectConfigByKey(MerchantDeskSwitch.NON_MONEY_KEY)).thenReturn("yes");
        assertFalse(deskSwitch.isEnabled());
        when(config.selectConfigByKey(MerchantDeskSwitch.NON_MONEY_KEY)).thenThrow(new RuntimeException("db down"));
        assertFalse(deskSwitch.isEnabled());
    }
}
