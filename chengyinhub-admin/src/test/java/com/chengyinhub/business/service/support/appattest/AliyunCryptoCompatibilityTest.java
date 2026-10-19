package com.chengyinhub.business.service.support.appattest;

import com.aliyun.openapiutil.Client;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.Provider;
import java.security.Security;
import static org.junit.jupiter.api.Assertions.*;

/** Offline SDK compatibility vectors, independently checked with OpenSSL; no client or network request. */
class AliyunCryptoCompatibilityTest
{
    private static final byte[] MESSAGE = "abc".getBytes(StandardCharsets.UTF_8);
    private static final String SYNTHETIC_SECRET = "synthetic-test-secret";

    @Test
    void sdkSm3DigestPreservesKnownVectorWithoutGlobalProviderRegistration() throws Exception
    {
        Provider before = Security.getProvider("BC");
        assertEquals("66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0",
                AppAttestCrypto.hex(Client.hash(MESSAGE, Client.HMAC_SM3)));
        assertSame(before, Security.getProvider("BC"));
    }

    @Test
    void sdkSm3HmacPreservesIndependentVector() throws Exception
    {
        assertEquals("8aac62004c7e0359c9f0b1fcfb40ca21aede444eb2c8222f82a8f7d1822e13db",
                AppAttestCrypto.hex(Client.SignatureMethod("abc", SYNTHETIC_SECRET, Client.HMAC_SM3)));
    }

    @Test
    void sdkSha256DigestAndHmacPreserveIndependentVectors() throws Exception
    {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                AppAttestCrypto.hex(Client.hash(MESSAGE, Client.HMAC_SHA256)));
        assertEquals("708e9dbfff7bb2c0f963fea47a5ba0d85011e517315d622c071353024500e771",
                AppAttestCrypto.hex(Client.SignatureMethod("abc", SYNTHETIC_SECRET, Client.HMAC_SHA256)));
    }
}
