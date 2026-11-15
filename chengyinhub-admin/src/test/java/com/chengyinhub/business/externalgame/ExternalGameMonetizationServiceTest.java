package com.chengyinhub.business.externalgame;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExternalGameMonetizationServiceTest {
    @Test
    void splitMathRespectsChannelFeeBearer() {
        // 10000 fen (100 CNY), 70% creator share (7000 bps), 0.6% channel fee (60 fen)
        var creatorBears = ExternalGameMonetizationService.computeSplit(10000L, 7000, 0);
        assertEquals(60L, creatorBears.channelFeeFen());
        assertEquals(6940L, creatorBears.creatorNetFen());
        assertEquals(3000L, creatorBears.platformShareFen());

        var platformBears = ExternalGameMonetizationService.computeSplit(10000L, 7000, 1);
        assertEquals(60L, platformBears.channelFeeFen());
        assertEquals(7000L, platformBears.creatorNetFen());
        assertEquals(2940L, platformBears.platformShareFen());
    }

    @Test
    void canonicalResultAndHmacAreDeterministic() {
        String canonical = ExternalGameMonetizationService.canonicalResultPayload(
                "EGS_0123", "rpg.one", "nonce123", true, 98, 12500L, "deadbeef");
        assertEquals("EXTGAME_RESULT|EGS_0123|rpg.one|nonce123|1|98|12500|deadbeef", canonical);
        String mac1 = ExternalGameMonetizationService.hmacSha256Hex("nonce123", canonical);
        String mac2 = ExternalGameMonetizationService.hmacSha256Hex("nonce123", canonical);
        assertEquals(64, mac1.length());
        assertEquals(mac1, mac2);
    }
}
