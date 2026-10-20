package com.chengyinhub.business.service.support.appattest;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.time.Instant;
import java.util.*;

/**
 * Offline, bounded App Attest verification. No enrollment, persistence, network, or rewards.
 *
 * Compatibility is deliberately narrow: production AAGUID, documented integer/text
 * extension names and bounded BER/CMS with typed ASN.1 receipt fields. These paths are covered by generated synthetic
 * fixtures only. Apple's currently published full sample contradicts its nonce/extension
 * instructions and is not an accepted positive vector. Real-device enrollment AND assertion
 * interoperability, the framing guard, and revocation policy require review before
 * enablement. App Attest alone is not evidence of walking or an iPhone hardware model.
 */
public final class AppAttestVerifier
{
    public static final String APP_ATTESTATION_ROOT_SHA256 =
            "1cb9823ba28ba6ad2d33a006941de2ae4f513ef1d4e831b9f7e0fa7b6242c932";
    public static final String RECEIPT_ROOT_G3_SHA256 =
            "63343abfb89a6a03ebb57e9b3f5fa7be7c4f5c756f3017b3a8c488c3653e9179";
    private static final byte[] PRODUCTION_AAGUID = new byte[] {
            'a', 'p', 'p', 'a', 't', 't', 'e', 's', 't', 0, 0, 0, 0, 0, 0, 0 };
    private final AppAttestPolicy policy;
    private final X509Certificate attestationRoot;
    private final X509Certificate receiptRoot;

    /** Only externally supplied, exact official DER root certificates can enter production. */
    public AppAttestVerifier(AppAttestPolicy policy, byte[] appAttestationRootDer, byte[] receiptRootG3Der)
    {
        this(policy, AppAttestCrypto.root(appAttestationRootDer, APP_ATTESTATION_ROOT_SHA256),
                AppAttestCrypto.root(receiptRootG3Der, RECEIPT_ROOT_G3_SHA256));
    }

    // Package-private seam for ephemeral synthetic unit tests; no public configurable pin bypass.
    AppAttestVerifier(AppAttestPolicy policy, X509Certificate attestationRoot, X509Certificate receiptRoot)
    {
        if (policy == null)
        {
            throw new AppAttestVerificationException("MISSING_POLICY");
        }
        AppAttestCrypto.checkRoot(attestationRoot);
        AppAttestCrypto.checkRoot(receiptRoot);
        this.policy = policy;
        this.attestationRoot = attestationRoot;
        this.receiptRoot = receiptRoot;
    }

    public EnrollmentResult verifyEnrollment(byte[] attestation, String keyId,
                                              byte[] exactServerChallengeBytes, Instant now)
    {
        requireLength(attestation, 1, 65536, "ATTESTATION_SIZE_LIMIT");
        requireLength(exactServerChallengeBytes, 16, 128, "CHALLENGE_SIZE_LIMIT");
        attestation = attestation.clone();
        exactServerChallengeBytes = exactServerChallengeBytes.clone();
        if (now == null) { throw new AppAttestVerificationException("MISSING_VERIFICATION_TIME"); }
        byte[] decodedKeyId = keyId(keyId);
        Map<String, Object> outer = map(BoundedCbor.read(attestation, 0, 65536, false, true).value);
        keys(outer, "fmt", "attStmt", "authData");
        if (!"apple-appattest".equals(outer.get("fmt")))
        {
            throw new AppAttestVerificationException("ATTESTATION_FORMAT");
        }
        Map<String, Object> statement = map(outer.get("attStmt"));
        keys(statement, "x5c", "receipt");
        Object chainValue = statement.get("x5c");
        if (!(chainValue instanceof List) || ((List<?>) chainValue).size() < 2
                || ((List<?>) chainValue).size() > 3)
        {
            throw new AppAttestVerificationException("ATTESTATION_CERTIFICATE_COUNT");
        }
        List<X509Certificate> chain = new ArrayList<>();
        for (Object item : (List<?>) chainValue)
        {
            chain.add(AppAttestCrypto.certificate(bytes(item)));
        }
        AppAttestCrypto.chain(chain, attestationRoot, now);
        X509Certificate leaf = chain.get(0);
        ECPublicKey publicKey = AppAttestCrypto.p256(leaf.getPublicKey().getEncoded());
        byte[] point = AppAttestCrypto.point(publicKey);
        AppAttestCrypto.equal(AppAttestCrypto.hash(point), decodedKeyId, "KEY_ID_MISMATCH");
        byte[] authData = bytes(outer.get("authData"));
        Authenticator auth = authenticator(authData, true);
        if (auth.counter != 0) { throw new AppAttestVerificationException("INITIAL_COUNTER_NOT_ZERO"); }
        AppAttestCrypto.equal(Arrays.copyOfRange(authData, 37, 53), PRODUCTION_AAGUID, "UNSUPPORTED_AAGUID");
        int credentialLength = ((authData[53] & 0xff) << 8) | (authData[54] & 0xff);
        if (credentialLength != 32) { throw new AppAttestVerificationException("CREDENTIAL_ID_LENGTH"); }
        AppAttestCrypto.equal(Arrays.copyOfRange(authData, 55, 87), decodedKeyId, "CREDENTIAL_ID_MISMATCH");
        Map<String, Object> cose = auth.cose;
        keys(cose, "1", "3", "-1", "-2", "-3");
        if (number(cose.get("1")) != 2 || number(cose.get("3")) != -7 || number(cose.get("-1")) != 1)
        {
            throw new AppAttestVerificationException("UNSUPPORTED_COSE_KEY");
        }
        byte[] x = bytes(cose.get("-2"));
        byte[] y = bytes(cose.get("-3"));
        requireLength(x, 32, 32, "COSE_COORDINATE_LENGTH");
        requireLength(y, 32, 32, "COSE_COORDINATE_LENGTH");
        AppAttestCrypto.equal(x, Arrays.copyOfRange(point, 1, 33), "COSE_CERTIFICATE_KEY_MISMATCH");
        AppAttestCrypto.equal(y, Arrays.copyOfRange(point, 33, 65), "COSE_CERTIFICATE_KEY_MISMATCH");
        AppAttestCrypto.equal(AppAttestCrypto.nonce(leaf),
                AppAttestCrypto.hash(authData, AppAttestCrypto.hash(exactServerChallengeBytes)), "NONCE_MISMATCH");
        byte[] receipt = bytes(statement.get("receipt"));
        AppAttestReceiptVerifier.Metadata metadata = AppAttestReceiptVerifier.verify(
                receipt, receiptRoot, leaf, policy.getAppId(), exactServerChallengeBytes, now);
        return new EnrollmentResult(publicKey.getEncoded(), keyId, receipt, metadata.createdAt,
                metadata.expiresAt, auth.bundleVersion, auth.category);
    }

    public AssertionResult verifyAssertion(byte[] assertion, byte[] storedPublicKeySpki,
                                            byte[] exactCanonicalClientDataBytes)
    {
        requireLength(assertion, 1, 4096, "ASSERTION_SIZE_LIMIT");
        requireLength(exactCanonicalClientDataBytes, 1, 16384, "CLIENT_DATA_SIZE_LIMIT");
        requireLength(storedPublicKeySpki, 1, 256, "PUBLIC_KEY_SIZE_LIMIT");
        assertion = assertion.clone();
        exactCanonicalClientDataBytes = exactCanonicalClientDataBytes.clone();
        storedPublicKeySpki = storedPublicKeySpki.clone();
        ECPublicKey key = AppAttestCrypto.p256(storedPublicKeySpki);
        Map<String, Object> outer = map(BoundedCbor.read(assertion, 0, 4096, false, true).value);
        keys(outer, "signature", "authenticatorData");
        byte[] signature = bytes(outer.get("signature"));
        byte[] authenticatorData = bytes(outer.get("authenticatorData"));
        Authenticator auth = authenticator(authenticatorData, false);
        if (auth.counter == 0) { throw new AppAttestVerificationException("ASSERTION_COUNTER_ZERO"); }
        AppAttestCrypto.signature(signature, key);
        try
        {
            // SHA256withECDSA hashes these MESSAGE bytes once. Passing the already hashed
            // nonce here would incorrectly introduce a second hash of the nonce.
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(key);
            verifier.update(authenticatorData);
            verifier.update(AppAttestCrypto.hash(exactCanonicalClientDataBytes));
            if (!verifier.verify(signature))
            {
                throw new AppAttestVerificationException("ASSERTION_SIGNATURE_INVALID");
            }
        }
        catch (GeneralSecurityException ex)
        {
            throw new AppAttestVerificationException("ASSERTION_SIGNATURE_INVALID", ex);
        }
        return new AssertionResult(auth.counter, auth.bundleVersion, auth.category);
    }

    private Authenticator authenticator(byte[] data, boolean enrollment)
    {
        requireLength(data, enrollment ? 88 : 38, 2048, "AUTHENTICATOR_DATA_SIZE");
        AppAttestCrypto.equal(Arrays.copyOfRange(data, 0, 32),
                AppAttestCrypto.hash(policy.getAppId().getBytes(StandardCharsets.UTF_8)), "RP_ID_MISMATCH");
        // AT/ED presence is exact; UP/UV, reserved, backup and other unsupported flags deny.
        if ((data[32] & 0xff) != (enrollment ? 0xc0 : 0x80))
        {
            throw new AppAttestVerificationException("UNSUPPORTED_AUTHENTICATOR_FLAGS");
        }
        long counter = ByteBuffer.wrap(data, 33, 4).getInt() & 0xffffffffL;
        int offset = enrollment ? 87 : 37;
        Map<String, Object> cose = null;
        if (enrollment)
        {
            BoundedCbor.Item encodedKey = BoundedCbor.read(data, offset, 2048, true, false);
            cose = map(encodedKey.value);
            offset = encodedKey.end;
        }
        Map<String, Object> extensions = map(BoundedCbor.read(data, offset, 1024, false, true).value);
        String categoryName = enrollment ? "apple_validation_category_01" : "validationCategory";
        String versionName = enrollment ? "apple_bundle_version_01" : "bundleVersion";
        if (!extensions.keySet().equals(new HashSet<>(Arrays.asList(categoryName, versionName))))
        {
            throw new AppAttestVerificationException("UNSUPPORTED_EXTENSION_DIALECT");
        }
        if (!(extensions.get(categoryName) instanceof Long) || !(extensions.get(versionName) instanceof String))
        {
            throw new AppAttestVerificationException("UNSUPPORTED_EXTENSION_VALUE_TYPE");
        }
        long category = number(extensions.get(categoryName));
        String version = (String) extensions.get(versionName);
        policy.checkExtensions(category, version);
        return new Authenticator(counter, category, version, cose);
    }

    private static byte[] keyId(String value)
    {
        if (value == null || value.length() != 44)
        {
            throw new AppAttestVerificationException("INVALID_KEY_ID");
        }
        try
        {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length != 32 || !value.equals(Base64.getEncoder().encodeToString(decoded)))
            {
                throw new AppAttestVerificationException("INVALID_KEY_ID");
            }
            return decoded;
        }
        catch (IllegalArgumentException ex)
        {
            throw new AppAttestVerificationException("INVALID_KEY_ID", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value)
    {
        if (!(value instanceof Map)) { throw new AppAttestVerificationException("CBOR_MAP_REQUIRED"); }
        return (Map<String, Object>) value;
    }

    private static byte[] bytes(Object value)
    {
        if (!(value instanceof byte[])) { throw new AppAttestVerificationException("CBOR_BYTES_REQUIRED"); }
        return (byte[]) value;
    }

    private static long number(Object value)
    {
        if (!(value instanceof Long)) { throw new AppAttestVerificationException("CBOR_INTEGER_REQUIRED"); }
        return (Long) value;
    }

    private static void keys(Map<String, Object> map, String... expected)
    {
        if (!map.keySet().equals(new HashSet<>(Arrays.asList(expected))))
        {
            throw new AppAttestVerificationException("CBOR_SCHEMA_MISMATCH");
        }
    }

    private static void requireLength(byte[] value, int minimum, int maximum, String reason)
    {
        if (value == null || value.length < minimum || value.length > maximum)
        {
            throw new AppAttestVerificationException(reason);
        }
    }

    private static final class Authenticator
    {
        final long counter;
        final long category;
        final String bundleVersion;
        final Map<String, Object> cose;
        Authenticator(long counter, long category, String bundleVersion, Map<String, Object> cose)
        {
            this.counter = counter; this.category = category; this.bundleVersion = bundleVersion; this.cose = cose;
        }
    }
}
