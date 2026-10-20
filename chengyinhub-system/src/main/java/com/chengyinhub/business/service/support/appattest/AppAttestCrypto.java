package com.chengyinhub.business.service.support.appattest;

import org.bouncycastle.asn1.*;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.*;

final class AppAttestCrypto
{
    static final String NONCE_OID = "1.2.840.113635.100.8.2";
    static final String RECEIPT_SIGNER_OID = "1.2.840.113635.100.12.15";
    private static final Set<String> CERTIFICATE_SIGNATURE_OIDS = new HashSet<>(Arrays.asList(
            "1.2.840.10045.4.3.2", "1.2.840.10045.4.3.3", "1.2.840.10045.4.3.4"));

    private AppAttestCrypto() { }

    static byte[] hash(byte[]... inputs)
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] input : inputs) { digest.update(input); }
            return digest.digest();
        }
        catch (GeneralSecurityException ex)
        {
            throw new AppAttestVerificationException("CRYPTO_UNAVAILABLE", ex);
        }
    }

    static void equal(byte[] actual, byte[] expected, String reason)
    {
        if (actual == null || expected == null || !MessageDigest.isEqual(actual, expected))
        {
            throw new AppAttestVerificationException(reason);
        }
    }

    static X509Certificate certificate(byte[] der)
    {
        try
        {
            // Inspect ASN.1 only, before a JCA provider can construct an arbitrary EC curve.
            // Apple chains use named P-256/P-384; explicit parameters are unsupported.
            SubjectPublicKeyInfo spki = org.bouncycastle.asn1.x509.Certificate
                    .getInstance(BoundedDer.read(der, 8192)).getSubjectPublicKeyInfo();
            ASN1Encodable parameters = spki.getAlgorithm().getParameters();
            if (!X9ObjectIdentifiers.id_ecPublicKey.equals(spki.getAlgorithm().getAlgorithm())
                    || !(parameters instanceof ASN1ObjectIdentifier)
                    || !(X9ObjectIdentifiers.prime256v1.equals(parameters)
                    || new ASN1ObjectIdentifier("1.3.132.0.34").equals(parameters)))
            {
                throw new AppAttestVerificationException("UNSUPPORTED_CERTIFICATE_PUBLIC_KEY");
            }
            ByteArrayInputStream stream = new ByteArrayInputStream(der);
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(stream);
            if (stream.available() != 0 || !Arrays.equals(der, cert.getEncoded()))
            {
                throw new AppAttestVerificationException("NON_CANONICAL_CERTIFICATE");
            }
            return cert;
        }
        catch (CertificateException | RuntimeException ex)
        {
            if (ex instanceof AppAttestVerificationException) { throw (AppAttestVerificationException) ex; }
            throw new AppAttestVerificationException("INVALID_CERTIFICATE", ex);
        }
    }

    static X509Certificate root(byte[] der, String expectedFingerprint)
    {
        if (der == null)
        {
            throw new AppAttestVerificationException("MISSING_TRUST_ROOT");
        }
        if (!hex(hash(der)).equals(expectedFingerprint))
        {
            throw new AppAttestVerificationException("UNPINNED_TRUST_ROOT");
        }
        X509Certificate root = certificate(der);
        checkRoot(root);
        return root;
    }

    static void checkRoot(X509Certificate root)
    {
        try
        {
            if (root == null || root.getBasicConstraints() < 0
                    || !root.getSubjectX500Principal().equals(root.getIssuerX500Principal()))
            {
                throw new AppAttestVerificationException("INVALID_TRUST_ROOT");
            }
            requireKeyUsage(root, 5);
            root.verify(root.getPublicKey());
        }
        catch (GeneralSecurityException ex)
        {
            throw new AppAttestVerificationException("INVALID_TRUST_ROOT", ex);
        }
    }

    static void chain(List<X509Certificate> leafFirst, X509Certificate root, Instant now)
    {
        if (leafFirst == null || leafFirst.isEmpty() || leafFirst.size() > 3 || now == null)
        {
            throw new AppAttestVerificationException("CERTIFICATE_CHAIN_LIMIT");
        }
        try
        {
            Date date = Date.from(now);
            root.checkValidity(date);
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < leafFirst.size(); i++)
            {
                X509Certificate cert = leafFirst.get(i);
                cert.checkValidity(date);
                if (!seen.add(hex(hash(cert.getEncoded()))) || cert.equals(root)
                        || !CERTIFICATE_SIGNATURE_OIDS.contains(cert.getSigAlgOID()))
                {
                    throw new AppAttestVerificationException("UNSUPPORTED_CERTIFICATE_CHAIN");
                }
                if (i == 0)
                {
                    if (cert.getBasicConstraints() >= 0)
                    {
                        throw new AppAttestVerificationException("LEAF_IS_CA");
                    }
                    requireKeyUsage(cert, 0);
                }
                else
                {
                    if (cert.getBasicConstraints() < 0)
                    {
                        throw new AppAttestVerificationException("ISSUER_IS_NOT_CA");
                    }
                    requireKeyUsage(cert, 5);
                }
                X509Certificate issuer = i + 1 < leafFirst.size() ? leafFirst.get(i + 1) : root;
                if (!cert.getIssuerX500Principal().equals(issuer.getSubjectX500Principal()))
                {
                    throw new AppAttestVerificationException("CERTIFICATE_ISSUER_MISMATCH");
                }
                cert.verify(issuer.getPublicKey());
            }
            CertPath path = CertificateFactory.getInstance("X.509").generateCertPath(leafFirst);
            PKIXParameters parameters = new PKIXParameters(Collections.singleton(new TrustAnchor(root, null)));
            parameters.setDate(date);
            // Intentionally offline. No AIA/OCSP/CRL fetching or claim of revocation checking.
            parameters.setRevocationEnabled(false);
            CertPathValidator.getInstance("PKIX").validate(path, parameters);
        }
        catch (GeneralSecurityException | IllegalArgumentException ex)
        {
            if (ex instanceof AppAttestVerificationException) { throw (AppAttestVerificationException) ex; }
            throw new AppAttestVerificationException("CERTIFICATE_CHAIN_INVALID", ex);
        }
    }

    private static void requireKeyUsage(X509Certificate cert, int bit)
    {
        boolean[] usage = cert.getKeyUsage();
        if (usage == null || usage.length <= bit || !usage[bit])
        {
            throw new AppAttestVerificationException("CERTIFICATE_KEY_USAGE_INVALID");
        }
    }

    static ECPublicKey p256(byte[] spki)
    {
        try
        {
            SubjectPublicKeyInfo info = SubjectPublicKeyInfo.getInstance(BoundedDer.read(spki, 256));
            if (!X9ObjectIdentifiers.id_ecPublicKey.equals(info.getAlgorithm().getAlgorithm())
                    || !(info.getAlgorithm().getParameters() instanceof ASN1ObjectIdentifier)
                    || !X9ObjectIdentifiers.prime256v1.equals(info.getAlgorithm().getParameters())
                    || info.getPublicKeyData().getPadBits() != 0
                    || info.getPublicKeyData().getBytes().length != 65
                    || info.getPublicKeyData().getBytes()[0] != 4)
            {
                throw new AppAttestVerificationException("PUBLIC_KEY_NOT_P256_UNCOMPRESSED");
            }
            org.bouncycastle.math.ec.ECPoint point = CustomNamedCurves.getByName("secp256r1")
                    .getCurve().decodePoint(info.getPublicKeyData().getBytes());
            if (point.isInfinity() || !point.isValid())
            {
                throw new AppAttestVerificationException("PUBLIC_KEY_POINT_INVALID");
            }
            PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(spki));
            if (!(key instanceof ECPublicKey))
            {
                throw new AppAttestVerificationException("PUBLIC_KEY_NOT_EC");
            }
            return (ECPublicKey) key;
        }
        catch (GeneralSecurityException | RuntimeException ex)
        {
            if (ex instanceof AppAttestVerificationException) { throw (AppAttestVerificationException) ex; }
            throw new AppAttestVerificationException("PUBLIC_KEY_INVALID", ex);
        }
    }

    static byte[] point(ECPublicKey key)
    {
        return SubjectPublicKeyInfo.getInstance(BoundedDer.read(key.getEncoded(), 256)).getPublicKeyData().getBytes();
    }

    static byte[] nonce(X509Certificate certificate)
    {
        byte[] extension = certificate.getExtensionValue(NONCE_OID);
        if (extension == null) { throw new AppAttestVerificationException("NONCE_EXTENSION_MISSING"); }
        ASN1Primitive outer = BoundedDer.read(extension, 128);
        if (!(outer instanceof ASN1OctetString))
        {
            throw new AppAttestVerificationException("NONCE_EXTENSION_SHAPE");
        }
        ASN1Primitive inner = BoundedDer.read(((ASN1OctetString) outer).getOctets(), 128);
        if (!(inner instanceof ASN1Sequence) || ((ASN1Sequence) inner).size() != 1)
        {
            throw new AppAttestVerificationException("NONCE_EXTENSION_SHAPE");
        }
        ASN1Encodable value = ((ASN1Sequence) inner).getObjectAt(0);
        if (!(value instanceof ASN1TaggedObject))
        {
            throw new AppAttestVerificationException("NONCE_EXTENSION_SHAPE");
        }
        ASN1TaggedObject tag = (ASN1TaggedObject) value;
        if (!tag.hasContextTag(1) || !tag.isExplicit()
                || !(tag.getExplicitBaseObject() instanceof ASN1OctetString))
        {
            throw new AppAttestVerificationException("NONCE_EXTENSION_SHAPE");
        }
        byte[] nonce = ((ASN1OctetString) tag.getExplicitBaseObject()).getOctets();
        if (nonce.length != 32)
        {
            throw new AppAttestVerificationException("NONCE_EXTENSION_LENGTH");
        }
        return nonce;
    }

    static void signature(byte[] signature, ECPublicKey key)
    {
        ASN1Primitive asn1 = BoundedDer.read(signature, 72);
        if (!(asn1 instanceof ASN1Sequence) || ((ASN1Sequence) asn1).size() != 2)
        {
            throw new AppAttestVerificationException("ECDSA_SIGNATURE_SHAPE");
        }
        for (ASN1Encodable scalar : (ASN1Sequence) asn1)
        {
            if (!(scalar instanceof ASN1Integer))
            {
                throw new AppAttestVerificationException("ECDSA_SIGNATURE_SHAPE");
            }
            BigInteger value = ((ASN1Integer) scalar).getValue();
            if (value.signum() <= 0 || value.compareTo(key.getParams().getOrder()) >= 0)
            {
                throw new AppAttestVerificationException("ECDSA_SIGNATURE_SCALAR");
            }
        }
    }

    static String hex(byte[] bytes)
    {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) { result.append(String.format(Locale.ROOT, "%02x", b & 0xff)); }
        return result.toString();
    }
}
