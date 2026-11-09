package com.chengyinhub.business.service.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 渠道码：域分离、明文不落库、摘要常量时间比对。 */
class ChannelVoucherCodecTest {

    @Test
    void issuedCodeCarriesPublicIdAndHashesToSixtyFourHex() {
        String publicId = ChannelVoucherCodec.newPublicId();
        String code = ChannelVoucherCodec.issue(publicId);
        String hash = ChannelVoucherCodec.sha256Hex(code);

        assertEquals(publicId, ChannelVoucherCodec.publicIdOf(code));
        assertTrue(code.startsWith("cv1."));
        assertEquals(64, hash.length());
        assertNotEquals(code, hash);
        assertTrue(ChannelVoucherCodec.matches(code, hash));
    }

    @Test
    void differentIssuesNeverShareSecrets() {
        String publicId = ChannelVoucherCodec.newPublicId();
        assertNotEquals(ChannelVoucherCodec.issue(publicId), ChannelVoucherCodec.issue(publicId));
    }

    @Test
    void otherCredentialDomainsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ChannelVoucherCodec.publicIdOf("rf1.abcdef0123456789abcdef.1800000000.sig"));
        assertThrows(IllegalArgumentException.class,
                () -> ChannelVoucherCodec.publicIdOf("v1.not-a-channel-code"));
        assertThrows(IllegalArgumentException.class,
                () -> ChannelVoucherCodec.publicIdOf("cv1.short.secret"));
    }


    @Test
    void malformedSecretLengthsAndExtraSegmentsAreRejected() {
        String publicId = ChannelVoucherCodec.newPublicId();
        assertThrows(IllegalArgumentException.class,
                () -> ChannelVoucherCodec.publicIdOf("cv1." + publicId + ".short"));
        assertThrows(IllegalArgumentException.class,
                () -> ChannelVoucherCodec.publicIdOf(ChannelVoucherCodec.issue(publicId) + ".extra"));
        assertThrows(IllegalArgumentException.class,
                () -> ChannelVoucherCodec.publicIdOf("cv1." + publicId + "." + "x".repeat(10000)));
    }

    @Test
    void tamperedCodeDoesNotMatchStoredHash() {
        String publicId = ChannelVoucherCodec.newPublicId();
        String code = ChannelVoucherCodec.issue(publicId);
        String hash = ChannelVoucherCodec.sha256Hex(code);
        String tampered = code.substring(0, code.length() - 1)
                + (code.endsWith("a") ? "b" : "a");
        assertFalse(ChannelVoucherCodec.matches(tampered, hash));
    }
}
