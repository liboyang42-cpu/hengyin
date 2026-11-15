package com.chengyinhub.business.platformpass;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * A synthetic certificate chain shaped like Apple's (root → WWDR intermediate with OID 1.2.840.113635.100.6.2.1 →
 * signing leaf with OID 1.2.840.113635.100.6.11.1) and an ES256 JWS signer. Test-only: generated in memory on every
 * run, never Apple's keys, and nothing here ever talks to Apple.
 */
final class ApplePassTestChain {
    static final String INTERMEDIATE_OID = "1.2.840.113635.100.6.2.1";
    static final String LEAF_OID = "1.2.840.113635.100.6.11.1";

    final X509Certificate root;
    final X509Certificate intermediate;
    final X509Certificate leaf;
    private final ECPrivateKey leafKey;

    private ApplePassTestChain(X509Certificate root, X509Certificate intermediate, X509Certificate leaf, ECPrivateKey leafKey) {
        this.root = root;
        this.intermediate = intermediate;
        this.leaf = leaf;
        this.leafKey = leafKey;
    }

    static ApplePassTestChain create() {
        return create(true);
    }

    /** {@code appleLeaf=false} leaves the Apple signing OID off the leaf (must be refused). */
    static ApplePassTestChain create(boolean appleLeaf) {
        try {
            KeyPair rootKeys = keys(), interKeys = keys(), leafKeys = keys();
            X509Certificate root = cert("CN=Test Root CA", "CN=Test Root CA", rootKeys, rootKeys, true, null);
            X509Certificate inter = cert("CN=Test Root CA", "CN=Test WWDR", interKeys, rootKeys, true, INTERMEDIATE_OID);
            X509Certificate leaf = cert("CN=Test WWDR", "CN=Test Signing", leafKeys, interKeys, false,
                    appleLeaf ? LEAF_OID : null);
            return new ApplePassTestChain(root, inter, leaf, (ECPrivateKey) leafKeys.getPrivate());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    byte[] rootDer() {
        try {
            return root.getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Sign a JSON payload as a JWS with the x5c header Apple uses (leaf, intermediate, root). */
    String sign(String json) {
        try {
            Base64.Encoder b64 = Base64.getEncoder();
            List<String> x5c = List.of(b64.encodeToString(leaf.getEncoded()), b64.encodeToString(intermediate.getEncoded()),
                    b64.encodeToString(root.getEncoded()));
            return JWT.create().withHeader(Map.of("x5c", x5c)).withPayload(json).sign(Algorithm.ECDSA256(null, leafKey));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair keys() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    private static X509Certificate cert(String issuer, String subject, KeyPair subjectKeys, KeyPair issuerKeys,
                                        boolean ca, String appleOid) throws Exception {
        long now = System.currentTimeMillis();
        var builder = new JcaX509v3CertificateBuilder(new X500Name(issuer), BigInteger.valueOf(now + subject.hashCode() & 0x7fffffff),
                new Date(now - 86_400_000L), new Date(now + 365L * 86_400_000L), new X500Name(subject), subjectKeys.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
        builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(ca ? KeyUsage.keyCertSign | KeyUsage.cRLSign : KeyUsage.digitalSignature));
        if (appleOid != null) builder.addExtension(new ASN1ObjectIdentifier(appleOid), false, DERNull.INSTANCE);
        var signer = new JcaContentSignerBuilder("SHA256withECDSA").build(issuerKeys.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }
}
