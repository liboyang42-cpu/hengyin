package com.chengyinhub.business.platformpass;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TEST ONLY. A synthetic root → intermediate → leaf chain shaped like Apple's (the intermediate carries
 * 1.2.840.113635.100.6.2.1, the leaf 1.2.840.113635.100.6.11.1), so Apple's own SDK verifies JWS signed here exactly
 * as it verifies App Store data. Nothing here talks to Apple. Also exposes the verifier's package-private
 * test-roots constructor to tests in other packages.
 */
public final class SyntheticAppleChain {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicLong SERIAL = new AtomicLong(1000);
    private final X509Certificate root;
    private final X509Certificate intermediate;
    private final X509Certificate leaf;
    private final PrivateKey leafKey;

    public SyntheticAppleChain() {
        try {
            KeyPair rootKeys = keys(), interKeys = keys(), leafKeys = keys();
            root = cert(new X500Name("CN=Synthetic Test Root"), rootKeys, new X500Name("CN=Synthetic Test Root"), rootKeys.getPrivate(), true, null);
            intermediate = cert(new X500Name("CN=Synthetic Test WWDR"), interKeys, new X500Name("CN=Synthetic Test Root"), rootKeys.getPrivate(),
                    true, "1.2.840.113635.100.6.2.1");
            leaf = cert(new X500Name("CN=Synthetic Test Signer"), leafKeys, new X500Name("CN=Synthetic Test WWDR"), interKeys.getPrivate(),
                    false, "1.2.840.113635.100.6.11.1");
            leafKey = leafKeys.getPrivate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public byte[] rootDer() {
        try { return root.getEncoded(); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** The verifier exactly as production builds it, but trusting only this synthetic root. */
    public static PlatformPassAppleVerifier verifier(PlatformPassAppleProperties config, byte[]... roots) {
        return new PlatformPassAppleVerifier(config, List.of(roots));
    }

    /** ES256 compact JWS with an x5c header, like App Store signed data. */
    public String sign(Map<String, Object> payload) {
        try {
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("alg", "ES256");
            header.put("x5c", List.of(b64(leaf.getEncoded()), b64(intermediate.getEncoded()), b64(root.getEncoded())));
            String input = url(JSON.writeValueAsBytes(header)) + "." + url(JSON.writeValueAsBytes(payload));
            Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
            signer.initSign(leafKey);
            signer.update(input.getBytes(StandardCharsets.US_ASCII));
            return input + "." + url(signer.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static X509Certificate cert(X500Name subject, KeyPair subjectKeys, X500Name issuer, PrivateKey issuerKey,
                                        boolean ca, String appleOid) throws Exception {
        Date from = new Date(System.currentTimeMillis() - 86_400_000L);
        Date to = new Date(System.currentTimeMillis() + 365L * 86_400_000L);
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(issuer, BigInteger.valueOf(SERIAL.incrementAndGet()),
                from, to, subject, subjectKeys.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
        if (appleOid != null) builder.addExtension(new ASN1ObjectIdentifier(appleOid), false, DERNull.INSTANCE);
        return new JcaX509CertificateConverter().getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(issuerKey)));
    }

    private static String b64(byte[] v) { return Base64.getEncoder().encodeToString(v); }
    private static String url(byte[] v) { return Base64.getUrlEncoder().withoutPadding().encodeToString(v); }
}
