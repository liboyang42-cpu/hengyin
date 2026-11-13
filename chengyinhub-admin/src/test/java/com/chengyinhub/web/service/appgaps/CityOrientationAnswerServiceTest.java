package com.chengyinhub.web.service.appgaps;

import com.chengyinhub.business.appgaps.AppGapSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CityOrientationAnswerServiceTest {
    @Test
    void payloadHashUsesUtf8ByteLengthLikeTheApp() {
        // App: sha256("session:node:expectedVersion:utf8Count:answer")
        assertEquals(AppGapSupport.sha256("7:12:3:6:外滩"), CityOrientationAnswerService.payloadHash(7, 12, 3, "外滩"));
    }
}
