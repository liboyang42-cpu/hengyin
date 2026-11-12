package com.chengyinhub.business.service.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.chengyinhub.common.utils.VerifyDynCodeUtil;
import static org.mockito.Mockito.verify;

/** 推广凭据签名域分离与拒绝语义。只测纯函数,不连库。 */
class ReferralTokenCodecTest {

    private static final String SECRET = "unit-test-referral-secret";
    private static final String PUBLIC_ID = "abcdef0123456789abcdef";

    @Test
    void issuedTokenVerifiesAndCarriesOnlyPublicIdentityAndExpiry() {
        long exp = 1_800_000_000L;
        String token = ReferralTokenCodec.issue(PUBLIC_ID, exp, SECRET);

        ReferralTokenCodec.Result result = ReferralTokenCodec.verify(token, exp - 1, SECRET);

        assertTrue(result.valid);
        assertEquals(PUBLIC_ID, result.publicId);
        assertEquals(exp, result.expiresAtEpochSec);
    }

    @Test
    void tamperedSignaturePublicIdOrExpiryIsRejected() {
        long exp = 1_800_000_000L;
        String token = ReferralTokenCodec.issue(PUBLIC_ID, exp, SECRET);
        String[] parts = token.split("\\.");

        String badSig = parts[0] + "." + parts[1] + "." + parts[2] + "." + "A" + parts[3].substring(1);
        String otherPublicId = parts[0] + ".zzzzzzzzzzzzzzzzzzzz." + parts[2] + "." + parts[3];
        String extendedExp = parts[0] + "." + parts[1] + "." + (exp + 3600) + "." + parts[3];

        assertFalse(ReferralTokenCodec.verify(badSig, exp - 1, SECRET).valid);
        assertFalse(ReferralTokenCodec.verify(otherPublicId, exp - 1, SECRET).valid);
        assertFalse(ReferralTokenCodec.verify(extendedExp, exp - 1, SECRET).valid);
    }

    @Test
    void expiryAndForeignSecretAreRejectedWithDistinctReasons() {
        long exp = 1_700_000_000L;
        String token = ReferralTokenCodec.issue(PUBLIC_ID, exp, SECRET);

        assertEquals("TOKEN_EXPIRED", ReferralTokenCodec.verify(token, exp + 1, SECRET).reason);
        assertEquals("INVALID_TOKEN", ReferralTokenCodec.verify(token, exp - 1, "another-secret").reason);
    }

    @Test
    void verificationCodeOfAnotherDomainIsNeverAcceptedAsReferralToken() {
        // 核销码域(v1)与推广凭据域(rf1)必须互不认可,否则一张票码就能当推广凭据。
        String verificationCode = VerifyDynCodeUtil.issue(7L, "activity",
                1_700_000_000_000L, 60_000L, 12345L, SECRET);

        assertFalse(ReferralTokenCodec.verify(verificationCode, 1_700_000_001L, SECRET).valid);
        assertNotEquals("rf1", verificationCode.split("\\.")[0]);
    }

    @Test
    void oversizedAndMalformedTokensAreRejectedBeforeAnyLookup() {
        assertFalse(ReferralTokenCodec.verify(null, 1L, SECRET).valid);
        assertFalse(ReferralTokenCodec.verify("", 1L, SECRET).valid);
        assertFalse(ReferralTokenCodec.verify("rf1." + "a".repeat(400), 1L, SECRET).valid);
        assertFalse(ReferralTokenCodec.verify("rf1.abcdef0123456789.not-a-number.x", 1L, SECRET).valid);
    }

    @Test
    void issueRejectsUnsafePublicIdAndExpiry() {
        assertThrows(IllegalArgumentException.class,
                () -> ReferralTokenCodec.issue("SHORT", 1_800_000_000L, SECRET));
        assertThrows(IllegalArgumentException.class,
                () -> ReferralTokenCodec.issue("abcdef0123456789ABCDEF", 1_800_000_000L, SECRET));
        assertThrows(IllegalArgumentException.class,
                () -> ReferralTokenCodec.issue(PUBLIC_ID, 0L, SECRET));
        assertThrows(IllegalStateException.class,
                () -> ReferralTokenCodec.issue(PUBLIC_ID, 1_800_000_000L, " "));
    }

    @Test
    void digestIsStableAndDistinct() {
        String a = ReferralTokenCodec.sha256Hex("abc");
        assertEquals(64, a.length());
        assertEquals(a, ReferralTokenCodec.sha256Hex("abc"));
        assertNotEquals(a, ReferralTokenCodec.sha256Hex("abd"));
    }
}
