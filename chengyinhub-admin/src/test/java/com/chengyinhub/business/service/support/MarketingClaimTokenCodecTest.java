package com.chengyinhub.business.service.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 领取码：域分离、签名、过期与哈希。 */
class MarketingClaimTokenCodecTest {

    private static final String SECRET = "unit-test-claim-secret";
    private static final String ID = "abcdefghijklmnopqrstuvwx";

    @Test
    void giftAndSeatTokensAreNotInterchangeable() {
        String gift = MarketingClaimTokenCodec.issue(MarketingClaimTokenCodec.GIFT_PREFIX, ID, 0L, SECRET);
        String seat = MarketingClaimTokenCodec.issue(MarketingClaimTokenCodec.SEAT_PREFIX, ID, 0L, SECRET);

        assertTrue(MarketingClaimTokenCodec.verify(
                MarketingClaimTokenCodec.GIFT_PREFIX, gift, 1L, SECRET).valid);
        assertFalse(MarketingClaimTokenCodec.verify(
                MarketingClaimTokenCodec.SEAT_PREFIX, gift, 1L, SECRET).valid);
        assertFalse(MarketingClaimTokenCodec.verify(
                MarketingClaimTokenCodec.GIFT_PREFIX, seat, 1L, SECRET).valid);
        assertNotEquals(gift, seat);
    }

    @Test
    void tamperAndExpiryAreRejected() {
        String token = MarketingClaimTokenCodec.issue(
                MarketingClaimTokenCodec.GIFT_PREFIX, ID, 1000L, SECRET);
        String tampered = token.substring(0, token.length() - 1) + (token.endsWith("A") ? "B" : "A");

        assertEquals("INVALID_TOKEN", MarketingClaimTokenCodec.verify(
                MarketingClaimTokenCodec.GIFT_PREFIX, tampered, 1L, SECRET).reason);
        assertEquals("TOKEN_EXPIRED", MarketingClaimTokenCodec.verify(
                MarketingClaimTokenCodec.GIFT_PREFIX, token, 1001L, SECRET).reason);
        assertTrue(MarketingClaimTokenCodec.verify(
                MarketingClaimTokenCodec.GIFT_PREFIX, token, 1000L, SECRET).valid);
    }

    @Test
    void zeroExpiryMeansNoCodeLevelExpiryAndHashIsStable() {
        String token = MarketingClaimTokenCodec.issue(
                MarketingClaimTokenCodec.SEAT_PREFIX, ID, 0L, SECRET);

        assertTrue(MarketingClaimTokenCodec.verify(
                MarketingClaimTokenCodec.SEAT_PREFIX, token, Long.MAX_VALUE / 2000L, SECRET).valid);
        assertEquals(MarketingClaimTokenCodec.sha256Hex(token),
                MarketingClaimTokenCodec.sha256Hex(token));
    }
}
