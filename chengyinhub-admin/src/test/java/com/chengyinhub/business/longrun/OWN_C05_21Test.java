package com.chengyinhub.business.longrun;

import com.chengyinhub.business.service.ai.AiSceneRouter;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** NOT_RUN: actual shop bizType resolves to the existing provider, never cache/mock. */
class OWN_C05_21Test {
    @Test
    void shopNpcUsesItsExistingBusinessNameAndProvider() {
        AiSceneRouter router = new AiSceneRouter();
        assertEquals("deepseek", router.resolve("npc_shop_chat"));
        assertFalse(router.isCacheOnly("npc_shop_chat"));
        assertEquals("deepseek", router.resolve("merchant_npc_chat"));
        assertEquals("cache", router.resolve("npc_event"));
    }
}
