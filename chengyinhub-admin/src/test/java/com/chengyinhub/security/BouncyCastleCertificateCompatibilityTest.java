package com.chengyinhub.security;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class BouncyCastleCertificateCompatibilityTest {
    @Test
    void approvedArtifactsProvideSingleVersionOfEachCryptoPackage() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        if (!java.nio.file.Files.isDirectory(root.resolve("chengyinhub-admin"))) root = root.getParent();
        org.w3c.dom.Document pom = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                .newDocumentBuilder().parse(root.resolve("pom.xml").toFile());
        assertEquals("1.86", pom.getElementsByTagName("bouncycastle.version").item(0).getTextContent());
        String[][] resources = {
                {"org/bouncycastle/jce/provider/BouncyCastleProvider.class", "bcprov"},
                {"org/bouncycastle/cert/X509CertificateHolder.class", "bcpkix"},
                {"org/bouncycastle/asn1/cms/ContentInfo.class", "bcutil"}
        };
        for (String[] resource : resources) {
            java.util.List<java.net.URL> origins = Collections.list(getClass().getClassLoader().getResources(resource[0]));
            assertEquals(1, origins.size(), resource[0] + " must have one provider, without old/new duplicate jars");
            assertTrue(origins.get(0).toString().contains(resource[1] + "-jdk18on-1.86.jar"), origins.toString());
            assertFalse(origins.get(0).toString().contains("jdk15on"));
        }
    }

    @Test
    void rsaSignatureRejectsTamperingAndCertificateRoundTrips() throws Exception {
        BouncyCastleProvider provider = new BouncyCastleProvider();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", provider);
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        X500Name name = new X500Name("CN=Local BC compatibility fixture");
        long now = System.currentTimeMillis();
        X509CertificateHolder holder = new JcaX509v3CertificateBuilder(name, BigInteger.ONE,
                new Date(now - 60000), new Date(now + 60000), name, pair.getPublic())
                .build(new JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(pair.getPrivate()));
        X509Certificate certificate = new JcaX509CertificateConverter().setProvider(provider).getCertificate(holder);
        certificate.checkValidity();
        certificate.verify(pair.getPublic(), provider);
        X509Certificate parsed = (X509Certificate) CertificateFactory.getInstance("X.509", provider)
                .generateCertificate(new java.io.ByteArrayInputStream(certificate.getEncoded()));
        assertArrayEquals(certificate.getEncoded(), parsed.getEncoded());
        parsed.verify(pair.getPublic(), provider);
        Signature signer = Signature.getInstance("SHA256withRSA", provider);
        signer.initSign(pair.getPrivate());
        signer.update(new byte[]{1, 2, 3});
        byte[] signature = signer.sign();
        signer.initVerify(parsed.getPublicKey());
        signer.update(new byte[]{1, 2, 3});
        assertTrue(signer.verify(signature));
        signer.initVerify(parsed.getPublicKey());
        signer.update(new byte[]{1, 2, 4});
        assertFalse(signer.verify(signature));
        KeyPair other = generator.generateKeyPair();
        assertThrows(java.security.SignatureException.class, () -> parsed.verify(other.getPublic(), provider));
    }
}
