package com.chengyinhub.business.service.support.appattest;

import org.bouncycastle.asn1.*;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AppAttestCryptoSchemaTest
{
    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00.123Z");
    private static AppAttestTestKit kit;
    @BeforeAll
    static void setup() throws Exception { kit = new AppAttestTestKit(NOW); }

    @Test
    void exactAppIdFitsPersisted255CharacterBoundary()
    {
        String prefix = "ABCDE12345.";
        String length255 = prefix + new String(new char[244]).replace('\0', 'a');
        assertEquals(255, length255.length());
        assertEquals(length255, new AppAttestPolicy(length255, Collections.singleton(4), Collections.singleton("1.0")).getAppId());
        assertThrows(AppAttestVerificationException.class,
                () -> new AppAttestPolicy(length255 + "a", Collections.singleton(4), Collections.singleton("1.0")));
    }

    @Test
    void nonceRequiresOneExplicitContextOneOctetStringOnly() throws Exception
    {
        X509Certificate certificate = mock(X509Certificate.class);
        ASN1Encodable[] malformed = {
            new DERSequence(new DEROctetString(new byte[32])),
            new DERSequence(new DERTaggedObject(true, 2, new DEROctetString(new byte[32]))),
            new DERSequence(new DERTaggedObject(false, 1, new DEROctetString(new byte[32]))),
            new DERSequence(new DERTaggedObject(true, 1, new DERSequence(new DEROctetString(new byte[32])))),
            new DERSequence(new ASN1Encodable[] {new DERTaggedObject(true, 1, new DEROctetString(new byte[32])), DERNull.INSTANCE})
        };
        for (ASN1Encodable inner : malformed)
        {
            when(certificate.getExtensionValue(AppAttestCrypto.NONCE_OID)).thenReturn(new DEROctetString(inner.toASN1Primitive().getEncoded()).getEncoded());
            assertThrows(AppAttestVerificationException.class, () -> AppAttestCrypto.nonce(certificate));
        }
        when(certificate.getExtensionValue(AppAttestCrypto.NONCE_OID)).thenReturn(null);
        assertEquals("NONCE_EXTENSION_MISSING", assertThrows(AppAttestVerificationException.class, () -> AppAttestCrypto.nonce(certificate)).getReasonCode());
    }

    @Test
    void publicKeysRequireNamedP256UncompressedValidPoint() throws Exception
    {
        byte[] invalidPoint = new byte[65]; invalidPoint[0] = 4;
        byte[] invalid = new SubjectPublicKeyInfo(new AlgorithmIdentifier(X9ObjectIdentifiers.id_ecPublicKey,
                X9ObjectIdentifiers.prime256v1), invalidPoint).getEncoded();
        assertThrows(AppAttestVerificationException.class, () -> AppAttestCrypto.p256(invalid));
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        byte[] wrongCurve = generator.generateKeyPair().getPublic().getEncoded();
        assertThrows(AppAttestVerificationException.class, () -> AppAttestCrypto.p256(wrongCurve));
        byte[] compressed = Arrays.copyOf(kit.point(), 33); compressed[0] = 2;
        byte[] spki = new SubjectPublicKeyInfo(new AlgorithmIdentifier(X9ObjectIdentifiers.id_ecPublicKey,
                X9ObjectIdentifiers.prime256v1), compressed).getEncoded();
        assertThrows(AppAttestVerificationException.class, () -> AppAttestCrypto.p256(spki));
    }

    @Test
    void certificateRejectsExplicitUnknownAndNonEcParametersBeforeProviderParsing() throws Exception
    {
        ASN1Sequence original = ASN1Sequence.getInstance(kit.root.getEncoded());
        ASN1Sequence tbs = ASN1Sequence.getInstance(original.getObjectAt(0));
        SubjectPublicKeyInfo key = SubjectPublicKeyInfo.getInstance(tbs.getObjectAt(6));
        AlgorithmIdentifier[] unsupported = {
            new AlgorithmIdentifier(X9ObjectIdentifiers.id_ecPublicKey, DERNull.INSTANCE),
            new AlgorithmIdentifier(X9ObjectIdentifiers.id_ecPublicKey, new DERSequence()),
            new AlgorithmIdentifier(X9ObjectIdentifiers.id_ecPublicKey, new ASN1ObjectIdentifier("1.3.132.0.35")),
            new AlgorithmIdentifier(new ASN1ObjectIdentifier("1.2.840.113549.1.1.1"), DERNull.INSTANCE)
        };
        for (AlgorithmIdentifier algorithm : unsupported)
        {
            ASN1Encodable[] fields = tbs.toArray();
            fields[6] = new SubjectPublicKeyInfo(algorithm, key.getPublicKeyData().getBytes());
            byte[] malformed = new DERSequence(new ASN1Encodable[] {
                new DERSequence(fields), original.getObjectAt(1), original.getObjectAt(2)
            }).getEncoded();
            assertEquals("UNSUPPORTED_CERTIFICATE_PUBLIC_KEY", assertThrows(AppAttestVerificationException.class,
                    () -> AppAttestCrypto.certificate(malformed)).getReasonCode());
        }
        assertArrayEquals(kit.root.getEncoded(), AppAttestCrypto.certificate(kit.root.getEncoded()).getEncoded());
    }

    @Test
    void leafCaMissingKeyUsageAndNonCaIssuerCannotValidate() throws Exception
    {
        X509Certificate caLeaf = AppAttestTestKit.certificate("CA Leaf", kit.attestedKey, kit.intermediate,
                kit.intermediateKey, NOW, true, new byte[32], false);
        assertEquals("LEAF_IS_CA", assertThrows(AppAttestVerificationException.class,
                () -> AppAttestCrypto.chain(Arrays.asList(caLeaf, kit.intermediate), kit.root, NOW)).getReasonCode());
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                new X500Name(kit.intermediate.getSubjectX500Principal().getName()), BigInteger.TEN,
                Date.from(NOW.minusSeconds(60)), Date.from(NOW.plusSeconds(60)),
                new X500Name("CN=No Usage Leaf"), kit.attestedKey.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        X509Certificate noUsage = new JcaX509CertificateConverter().setProvider(AppAttestTestKit.BC).getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withECDSA").setProvider(AppAttestTestKit.BC).build(kit.intermediateKey.getPrivate())));
        assertEquals("CERTIFICATE_KEY_USAGE_INVALID", assertThrows(AppAttestVerificationException.class,
                () -> AppAttestCrypto.chain(Arrays.asList(noUsage, kit.intermediate), kit.root, NOW)).getReasonCode());
        X509Certificate notCa = AppAttestTestKit.certificate("Not CA", kit.intermediateKey, kit.root, kit.rootKey, NOW, false, null, false);
        X509Certificate child = AppAttestTestKit.certificate("Child", kit.attestedKey, notCa, kit.intermediateKey, NOW, false, new byte[32], false);
        assertEquals("ISSUER_IS_NOT_CA", assertThrows(AppAttestVerificationException.class,
                () -> AppAttestCrypto.chain(Arrays.asList(child, notCa), kit.root, NOW)).getReasonCode());
    }

    @Test
    void coseCoordinatesMustBeIdenticalToCertificatePublicKey() throws Exception
    {
        byte[] challenge = AppAttestTestKit.utf8("synthetic-server-challenge-32bytes");
        byte[] auth = kit.authData(true, 0, AppAttestTestKit.APP_ID, 4, "1.0");
        byte[] x = Arrays.copyOfRange(kit.point(), 1, 33);
        int position = -1;
        outer: for (int i = 87; i <= auth.length - x.length; i++)
        {
            for (int j = 0; j < x.length; j++) { if (auth[i + j] != x[j]) { continue outer; } }
            position = i; break;
        }
        assertTrue(position > 87); auth[position] ^= 1;
        byte[] attestation = kit.enrollment(challenge, NOW, auth, null, null);
        assertEquals("COSE_CERTIFICATE_KEY_MISMATCH", assertThrows(AppAttestVerificationException.class,
                () -> kit.verifier().verifyEnrollment(attestation, kit.keyId(), challenge, NOW)).getReasonCode());
    }

    @Test
    void arbitraryLeafUnderReceiptRootCannotSignReceipts() throws Exception
    {
        X509Certificate noRole = AppAttestTestKit.certificate("Not Receipt Signer", kit.signerKey,
                kit.receiptIntermediate, kit.receiptIntermediateKey, NOW, false, null, false);
        byte[] receipt = kit.cms(new DERSet().getEncoded(), noRole, kit.signerKey,
                Arrays.asList(noRole, kit.receiptIntermediate, kit.receiptRoot));
        assertEquals("RECEIPT_SIGNER_ROLE_INVALID", assertThrows(AppAttestVerificationException.class,
                () -> AppAttestReceiptVerifier.verify(receipt, kit.receiptRoot, kit.signer,
                        AppAttestTestKit.APP_ID, new byte[32], NOW)).getReasonCode());
    }
}
