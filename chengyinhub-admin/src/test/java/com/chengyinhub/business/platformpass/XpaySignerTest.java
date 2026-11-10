package com.chengyinhub.business.platformpass;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 期望值由 Python hmac/hashlib 独立算出，证明拼接规则（uri + "&" + body）与十六进制小写输出。 */
class XpaySignerTest {
    private static final String SIGN_DATA = "{\"offerId\":\"OFFER\",\"buyQuantity\":1}";

    @Test
    void clientPaySigUsesFixedUriAndAppKey() {
        assertEquals("0de126970bebb876d881cca76eb7961a272d46b87e804456dd71f3b8b0bf7dd4",
                XpaySigner.paySig("test-app-key", XpaySigner.CLIENT_URI, SIGN_DATA));
    }

    @Test
    void serverPaySigUsesApiPath() {
        assertEquals("6835086a59fa96f7322bd81c4bb80c028cd246271ec6467357ed432c61dbe59a",
                XpaySigner.paySig("test-app-key", "/xpay/query_order", "{\"openid\":\"o\"}"));
    }

    @Test
    void userSignatureUsesSessionKey() {
        assertEquals("74ac961d2617440f26b9fbee13f9d98a7559b22f38d9d599952a11c5b81c26aa",
                XpaySigner.userSignature("session-key", SIGN_DATA));
    }

    @Test
    void pushSignatureIsSortedSha1() {
        assertTrue(XpaySigner.pushSignatureMatches("tok", "cb1280606383b6651d19b1fe525f375d316683d5", "1700000000", "nonce1"));
        assertFalse(XpaySigner.pushSignatureMatches("tok", "cb1280606383b6651d19b1fe525f375d316683d6", "1700000000", "nonce1"));
        assertFalse(XpaySigner.pushSignatureMatches("", "x", "1", "2"));
    }

    @Test
    void missingKeyRefusesToSign() {
        assertThrows(IllegalStateException.class, () -> XpaySigner.paySig("", "u", "b"));
    }
}
