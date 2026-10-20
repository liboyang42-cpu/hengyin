package com.chengyinhub.business.service.support.appattest;

import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.dataformat.cbor.CBORGenerator;
import org.bouncycastle.asn1.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AppAttestVerifierTest
{
    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00.123Z");
    private static final byte[] CHALLENGE = AppAttestTestKit.utf8("synthetic-server-challenge-32bytes");
    private static final byte[] CLIENT_DATA = AppAttestTestKit.utf8("{\"challenge\":\"synthetic\",\"operation\":\"read\"}");
    private static AppAttestTestKit kit;

    @BeforeAll
    static void fixtures() throws Exception { kit = new AppAttestTestKit(NOW); }

    @Test
    void sharedEnrollmentVectorHashesDecodedNonceOnly() throws Exception
    {
        com.fasterxml.jackson.databind.JsonNode vector;
        try (java.io.InputStream input = getClass().getResourceAsStream("/native-app-attest-enrollment-v1.json"))
        {
            assertNotNull(input);
            vector = new com.fasterxml.jackson.databind.ObjectMapper().readTree(input);
        }
        byte[] challenge = Base64.getDecoder().decode(vector.path("challenge").path("challengeBase64").asText());
        assertEquals(32, challenge.length);
        assertEquals(vector.path("decodedChallengeHex").asText(), AppAttestCrypto.hex(challenge));
        byte[] clientDataHash = AppAttestCrypto.hash(challenge);
        assertEquals(vector.path("clientDataHashHex").asText(), AppAttestCrypto.hex(clientDataHash));
        assertEquals(vector.path("clientDataHashBase64").asText(), Base64.getEncoder().encodeToString(clientDataHash));
        assertFalse(vector.path("challenge").path("rewardEnabled").asBoolean());
        assertTrue(vector.path("attestationObject").isNull());
        assertEquals(kit.keyId(), kit.verifier().verifyEnrollment(kit.enrollment(challenge, NOW), kit.keyId(), challenge, NOW).getKeyId());
    }

    @Test
    void syntheticBerEnrollmentAndAssertionsValidateWithoutProductionPinBypass() throws Exception
    {
        byte[] attestation = kit.enrollment(CHALLENGE, NOW);
        EnrollmentResult enrolled = kit.verifier().verifyEnrollment(attestation, kit.keyId(), CHALLENGE, NOW);
        assertArrayEquals(kit.publicKeySpki(), enrolled.getPublicKeySpki());
        assertEquals(kit.keyId(), enrolled.getKeyId());
        assertEquals(NOW, enrolled.getReceiptCreatedAt());
        assertEquals(NOW.plusSeconds(3600), enrolled.getReceiptExpiresAt());
        assertEquals("production", enrolled.getReceiptEnvironment());
        assertEquals("ATTEST", enrolled.getReceiptType());
        assertEquals("1.0", enrolled.getBundleVersion());
        assertEquals(4L, enrolled.getDistributionCategory());
        assertEquals(1L, kit.verifier().verifyAssertion(kit.assertion(CLIENT_DATA, 1), enrolled.getPublicKeySpki(), CLIENT_DATA).getCounter());
        assertEquals(0xffffffffL, kit.verifier().verifyAssertion(kit.assertion(CLIENT_DATA, 0xffffffffL), enrolled.getPublicKeySpki(), CLIENT_DATA).getCounter());
        byte[] copy = enrolled.getPublicKeySpki(); copy[0] ^= 1;
        assertArrayEquals(kit.publicKeySpki(), enrolled.getPublicKeySpki());
        byte[] receipt = enrolled.getReceipt(); byte first = receipt[0]; receipt[0] ^= 1;
        assertEquals(first, enrolled.getReceipt()[0]);
    }

    @Test
    void productionConstructorRejectsSyntheticOrMissingTrustRoots() throws Exception
    {
        denied("UNPINNED_TRUST_ROOT", () -> new AppAttestVerifier(kit.policy, kit.root.getEncoded(), kit.receiptRoot.getEncoded()));
        denied("MISSING_TRUST_ROOT", () -> new AppAttestVerifier(kit.policy, (byte[]) null, kit.receiptRoot.getEncoded()));
        assertThrows(AppAttestVerificationException.class, () -> new AppAttestPolicy("", Collections.singleton(4), Collections.singleton("1.0")));
        assertThrows(AppAttestVerificationException.class, () -> new AppAttestPolicy(AppAttestTestKit.APP_ID, Collections.singleton(3), Collections.singleton("1.0")));
        assertThrows(AppAttestVerificationException.class, () -> new AppAttestPolicy(AppAttestTestKit.APP_ID, Collections.singleton(4), Collections.emptySet()));
    }

    @Test
    void challengeAndKeyIdentityMustMatchExactBytes() throws Exception
    {
        byte[] enrolled = kit.enrollment(CHALLENGE, NOW);
        byte[] wrongChallenge = CHALLENGE.clone(); wrongChallenge[0] ^= 1;
        denied("NONCE_MISMATCH", () -> kit.verifier().verifyEnrollment(enrolled, kit.keyId(), wrongChallenge, NOW));
        String wrongKey = Base64.getEncoder().encodeToString(new byte[32]);
        denied("KEY_ID_MISMATCH", () -> kit.verifier().verifyEnrollment(enrolled, wrongKey, CHALLENGE, NOW));
        denied("INVALID_KEY_ID", () -> kit.verifier().verifyEnrollment(enrolled, kit.keyId().replace("=", ""), CHALLENGE, NOW));
        denied("CHALLENGE_SIZE_LIMIT", () -> kit.verifier().verifyEnrollment(enrolled, kit.keyId(), new byte[15], NOW));
    }

    @Test
    void documentedHashProtocolRejectsRawChallengeNonce() throws Exception
    {
        byte[] auth = kit.authData(true, 0, AppAttestTestKit.APP_ID, 4, "1.0");
        byte[] rawChallengeNonce = AppAttestCrypto.hash(auth, CHALLENGE);
        denied("NONCE_MISMATCH", () -> verify(kit.enrollment(CHALLENGE, NOW, auth, rawChallengeNonce, null)));
    }

    @Test
    void assertionUsesMessageHashOnceAndExactOriginalClientData() throws Exception
    {
        byte[] auth = kit.authData(false, 1, AppAttestTestKit.APP_ID, 4, "1.0");
        denied("ASSERTION_SIGNATURE_INVALID", () -> assertion(kit.assertion(CLIENT_DATA, auth, true), CLIENT_DATA));
        byte[] modified = Arrays.copyOf(CLIENT_DATA, CLIENT_DATA.length + 1); modified[modified.length - 1] = ' ';
        denied("ASSERTION_SIGNATURE_INVALID", () -> assertion(kit.assertion(CLIENT_DATA, 1), modified));
        KeyPair wrong = AppAttestTestKit.keyPair();
        denied("ASSERTION_SIGNATURE_INVALID", () -> kit.verifier().verifyAssertion(kit.assertion(CLIENT_DATA, 1), wrong.getPublic().getEncoded(), CLIENT_DATA));
        denied("ASSERTION_COUNTER_ZERO", () -> assertion(kit.assertion(CLIENT_DATA, 0), CLIENT_DATA));
    }

    @Test
    void rpCounterEnvironmentCredentialAndFlagsAreChecked() throws Exception
    {
        denied("RP_ID_MISMATCH", () -> verify(kit.enrollment(CHALLENGE, NOW,
                kit.authData(true, 0, "WRONG12345.com.chengyinhub.test", 4, "1.0"), null, null)));
        denied("INITIAL_COUNTER_NOT_ZERO", () -> verify(kit.enrollment(CHALLENGE, NOW,
                kit.authData(true, 1, AppAttestTestKit.APP_ID, 4, "1.0"), null, null)));
        byte[] auth = kit.authData(true, 0, AppAttestTestKit.APP_ID, 4, "1.0");
        auth[37] = 'd';
        denied("UNSUPPORTED_AAGUID", () -> verify(kit.enrollment(CHALLENGE, NOW, auth, null, null)));
        auth[37] = 'a'; auth[55] ^= 1;
        denied("CREDENTIAL_ID_MISMATCH", () -> verify(kit.enrollment(CHALLENGE, NOW, auth, null, null)));
        auth[55] ^= 1; auth[54] = 31;
        denied("CREDENTIAL_ID_LENGTH", () -> verify(kit.enrollment(CHALLENGE, NOW, auth, null, null)));
        auth[54] = 32; auth[32] = 0x40;
        denied("UNSUPPORTED_AUTHENTICATOR_FLAGS", () -> verify(kit.enrollment(CHALLENGE, NOW, auth, null, null)));
        byte[] assertion = kit.authData(false, 1, AppAttestTestKit.APP_ID, 4, "1.0");
        assertion[32] = (byte) 0xc0;
        denied("UNSUPPORTED_AUTHENTICATOR_FLAGS", () -> assertion(kit.assertion(CLIENT_DATA, assertion, false), CLIENT_DATA));
    }

    @Test
    void extensionWireTypesNamesAndExplicitAllowlistsFailClosed() throws Exception
    {
        denied("UNSUPPORTED_EXTENSION_VALUE_TYPE", () -> verify(kit.enrollment(CHALLENGE, NOW,
                kit.authData(true, 0, AppAttestTestKit.APP_ID, new byte[] {4,0,0,0}, "1.0"), null, null)));
        denied("UNSUPPORTED_EXTENSION_VALUE_TYPE", () -> verify(kit.enrollment(CHALLENGE, NOW,
                kit.authData(true, 0, AppAttestTestKit.APP_ID, "4", "1.0"), null, null)));
        denied("DISTRIBUTION_CATEGORY_NOT_ALLOWED", () -> verify(kit.enrollment(CHALLENGE, NOW,
                kit.authData(true, 0, AppAttestTestKit.APP_ID, 2, "1.0"), null, null)));
        denied("BUNDLE_VERSION_NOT_ALLOWED", () -> verify(kit.enrollment(CHALLENGE, NOW,
                kit.authData(true, 0, AppAttestTestKit.APP_ID, 4, "1"), null, null)));
        byte[] attestationAuth = kit.authData(true, 0, AppAttestTestKit.APP_ID, 4, "1.0");
        BoundedCbor.Item cose = BoundedCbor.read(attestationAuth, 87, 2048, true, false);
        byte[] wrongNames = concatenate(Arrays.copyOf(kit.authData(false, 1, AppAttestTestKit.APP_ID, 4, "1.0"), 37),
                Arrays.copyOfRange(attestationAuth, cose.end, attestationAuth.length));
        denied("UNSUPPORTED_EXTENSION_DIALECT", () -> assertion(kit.assertion(CLIENT_DATA, wrongNames, false), CLIENT_DATA));
    }

    @Test
    void certificateChainValidityNonceShapeAndReceiptRootAreIndependent() throws Exception
    {
        byte[] auth = kit.authData(true, 0, AppAttestTestKit.APP_ID, 4, "1.0");
        denied("NONCE_EXTENSION_LENGTH", () -> verify(kit.enrollment(CHALLENGE, NOW, auth, new byte[31], null)));
        byte[] enrolled = kit.enrollment(CHALLENGE, NOW);
        denied("CERTIFICATE_CHAIN_INVALID", () -> kit.verifier().verifyEnrollment(enrolled, kit.keyId(), CHALLENGE, NOW.plusSeconds(31536001)));
        AppAttestVerifier swapped = new AppAttestVerifier(kit.policy, kit.receiptRoot, kit.root);
        denied("CERTIFICATE_ISSUER_MISMATCH", () -> swapped.verifyEnrollment(enrolled, kit.keyId(), CHALLENGE, NOW));
        X509Certificate leaf = AppAttestTestKit.certificate("Synthetic Attested Key", kit.attestedKey, kit.intermediate,
                kit.intermediateKey, NOW, false, AppAttestCrypto.hash(auth, AppAttestCrypto.hash(CHALLENGE)), false);
        byte[] corrupted = leaf.getEncoded(); corrupted[corrupted.length - 1] ^= 1;
        X509Certificate invalid = AppAttestCrypto.certificate(corrupted);
        denied("CERTIFICATE_CHAIN_INVALID", () -> verify(kit.envelope(auth, invalid, kit.receipt(leaf, CHALLENGE, NOW, null))));
    }

    @Test
    void receiptIdentityEnvironmentTypeKeyAndHashAreCheckedAfterSignature() throws Exception
    {
        for (Map.Entry<Integer, byte[]> entry : new LinkedHashMap<Integer, byte[]>() {{
            put(2, AppAttestTestKit.utf8("WRONG12345.com.chengyinhub.test"));
            put(6, AppAttestTestKit.utf8("RECEIPT"));
            put(7, AppAttestTestKit.utf8("development"));
        }}.entrySet())
        {
            denied("RECEIPT_IDENTITY_OR_ENVIRONMENT_MISMATCH", () -> verify(withReceipt(entry.getKey(), entry.getValue())));
        }
        denied("RECEIPT_CLIENT_HASH_MISMATCH", () -> verify(withReceipt(4, CHALLENGE)));
        denied("RECEIPT_KEY_MISMATCH", () -> verify(withReceipt(3, kit.signer.getEncoded())));
        for (int id : Arrays.asList(2, 3, 4, 6, 7, 12, 21))
        {
            denied("MISSING_RECEIPT_FIELD", () -> verify(withReceipt(id, null)));
        }
        denied("UNSUPPORTED_OR_DUPLICATE_RECEIPT_FIELD", () -> verify(withReceipt(17, AppAttestTestKit.utf8("5"))));
    }

    @Test
    void receiptFreshnessFutureSkewAndExpirationAreBounded() throws Exception
    {
        denied("RECEIPT_NOT_FRESH", () -> verify(withReceipt(12, time(NOW.minusSeconds(301)))));
        denied("RECEIPT_NOT_FRESH", () -> verify(withReceipt(12, time(NOW.plusSeconds(31)))));
        denied("RECEIPT_EXPIRED_OR_INVALID_INTERVAL", () -> verify(withReceipt(21, time(NOW))));
        denied("RECEIPT_TIME_FORMAT", () -> verify(withReceipt(12, AppAttestTestKit.utf8("2026-10-02"))));
        denied("RECEIPT_REFRESH_INTERVAL_INVALID", () -> verify(withReceipt(19, time(NOW.minusSeconds(1)))));
        assertNotNull(verify(withReceipt(12, time(NOW.minusSeconds(300)))));
        assertNotNull(verify(withReceipt(12, time(NOW.plusSeconds(30)))));
    }

    @Test
    void receiptSignatureCannotBeSkippedAndPayloadDuplicatesCannotBeTrusted() throws Exception
    {
        byte[] auth = kit.authData(true, 0, AppAttestTestKit.APP_ID, 4, "1.0");
        X509Certificate leaf = AppAttestTestKit.certificate("Synthetic Attested Key", kit.attestedKey, kit.intermediate,
                kit.intermediateKey, NOW, false, AppAttestCrypto.hash(auth, AppAttestCrypto.hash(CHALLENGE)), false);
        byte[] receipt = kit.receipt(leaf, CHALLENGE, NOW, null);
        int appIdPosition = indexOf(receipt, AppAttestTestKit.utf8(AppAttestTestKit.APP_ID));
        assertTrue(appIdPosition > 0); receipt[appIdPosition] ^= 1;
        denied("RECEIPT_SIGNATURE_INVALID", () -> verify(kit.envelope(auth, leaf, receipt)));
        ASN1Encodable field = new DERSequence(new ASN1Encodable[] { new ASN1Integer(2), new ASN1Integer(1), new DEROctetString(AppAttestTestKit.utf8(AppAttestTestKit.APP_ID)) });
        byte[] duplicate = kit.cms(new DERSet(new ASN1Encodable[] { field, field }).getEncoded());
        denied("UNSUPPORTED_OR_DUPLICATE_RECEIPT_FIELD", () -> verify(kit.envelope(auth, leaf, duplicate)));
    }

    @Test
    void malformedSignatureAndWholeInputLimitsDeny() throws Exception
    {
        byte[] assertion = kit.assertion(CLIENT_DATA, 1);
        assertThrows(AppAttestVerificationException.class, () -> assertion(concatenate(assertion, new byte[] {0}), CLIENT_DATA));
        assertThrows(AppAttestVerificationException.class, () -> assertion(Arrays.copyOf(assertion, assertion.length - 1), CLIENT_DATA));
        denied("ASSERTION_SIZE_LIMIT", () -> assertion(new byte[4097], CLIENT_DATA));
        denied("ATTESTATION_SIZE_LIMIT", () -> verify(new byte[65537]));
        byte[] auth = kit.authData(false, 1, AppAttestTestKit.APP_ID, 4, "1.0");
        denied("ECDSA_SIGNATURE_SCALAR", () -> assertion(AppAttestTestKit.assertionEnvelope(auth,
                new DERSequence(new ASN1Encodable[] { new ASN1Integer(0), new ASN1Integer(1) }).getEncoded()), CLIENT_DATA));
        byte[] noncanonical = new byte[] {0x30, 7, 2, 2, 0, 1, 2, 1, 1};
        assertThrows(AppAttestVerificationException.class, () -> assertion(AppAttestTestKit.assertionEnvelope(auth, noncanonical), CLIENT_DATA));
    }

    private static byte[] withReceipt(int field, byte[] value) throws Exception
    {
        return kit.enrollment(CHALLENGE, NOW, kit.authData(true, 0, AppAttestTestKit.APP_ID, 4, "1.0"),
                null, Collections.singletonMap(field, value));
    }
    private static byte[] time(Instant instant) { return AppAttestTestKit.utf8(AppAttestTestKit.TIMESTAMP.format(instant)); }
    private static EnrollmentResult verify(byte[] value) { return kit.verifier().verifyEnrollment(value, kit.keyId(), CHALLENGE, NOW); }
    private static AssertionResult assertion(byte[] value, byte[] data) { return kit.verifier().verifyAssertion(value, kit.publicKeySpki(), data); }
    private static void denied(String reason, org.junit.jupiter.api.function.Executable executable)
    {
        assertEquals(reason, assertThrows(AppAttestVerificationException.class, executable).getReasonCode());
    }
    private static byte[] concatenate(byte[] one, byte[] two)
    {
        byte[] result = Arrays.copyOf(one, one.length + two.length);
        System.arraycopy(two, 0, result, one.length, two.length); return result;
    }
    private static int indexOf(byte[] haystack, byte[] needle)
    {
        outer: for (int i = 0; i <= haystack.length - needle.length; i++)
        {
            for (int j = 0; j < needle.length; j++) { if (haystack[i + j] != needle[j]) { continue outer; } }
            return i;
        }
        return -1;
    }
}
