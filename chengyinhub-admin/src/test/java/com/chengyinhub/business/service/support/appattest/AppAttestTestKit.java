package com.chengyinhub.business.service.support.appattest;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.dataformat.cbor.CBORGenerator;
import org.bouncycastle.asn1.*;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;

/**
 * Synthetic cryptographic fixtures only. Every key/certificate is generated in memory per
 * instance. No real Apple/device fixture, key, certificate, enrollment or network access.
 */
public final class AppAttestTestKit
{
    public static final String APP_ID = "ABCDE12345.com.chengyinhub.test";
    static final BouncyCastleProvider BC = new BouncyCastleProvider();
    static final DateTimeFormatter TIMESTAMP = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    final KeyPair attestedKey = keyPair();
    final KeyPair rootKey = keyPair();
    final KeyPair intermediateKey = keyPair();
    final KeyPair receiptRootKey = keyPair();
    final KeyPair receiptIntermediateKey = keyPair();
    final KeyPair signerKey = keyPair();
    final X509Certificate root;
    final X509Certificate intermediate;
    final X509Certificate receiptRoot;
    final X509Certificate receiptIntermediate;
    final X509Certificate signer;
    final Instant initializedAt;
    final AppAttestPolicy policy = new AppAttestPolicy(APP_ID, Collections.singleton(4), Collections.singleton("1.0"));

    public AppAttestTestKit(Instant now) throws Exception
    {
        initializedAt = now;
        root = certificate("Synthetic Attestation Root", rootKey, null, rootKey, now, true, null, false);
        intermediate = certificate("Synthetic Attestation Intermediate", intermediateKey, root, rootKey, now, true, null, false);
        receiptRoot = certificate("Synthetic Receipt Root", receiptRootKey, null, receiptRootKey, now, true, null, false);
        receiptIntermediate = certificate("Synthetic Receipt Intermediate", receiptIntermediateKey, receiptRoot, receiptRootKey, now, true, null, false);
        signer = certificate("Synthetic Receipt Signer", signerKey, receiptIntermediate, receiptIntermediateKey, now, false, null, true);
    }

    public AppAttestVerifier verifier()
    {
        return new AppAttestVerifier(policy, root, receiptRoot);
    }

    public String keyId()
    {
        return Base64.getEncoder().encodeToString(AppAttestCrypto.hash(point()));
    }

    public byte[] publicKeySpki() { return attestedKey.getPublic().getEncoded().clone(); }

    public byte[] enrollment(byte[] exactServerChallenge, Instant now) throws Exception
    {
        return enrollment(exactServerChallenge, now, authData(true, 0, APP_ID, 4L, "1.0"), null, null);
    }

    byte[] enrollment(byte[] challenge, Instant now, byte[] auth, byte[] nonceOverride,
                      Map<Integer, byte[]> fieldOverrides) throws Exception
    {
        byte[] nonce = nonceOverride == null ? AppAttestCrypto.hash(auth, AppAttestCrypto.hash(challenge)) : nonceOverride;
        X509Certificate leaf = certificate("Synthetic Attested Key", attestedKey, intermediate,
                intermediateKey, now, false, nonce, false);
        return envelope(auth, leaf, receipt(leaf, challenge, now, fieldOverrides));
    }

    byte[] envelope(byte[] auth, X509Certificate leaf, byte[] receipt) throws Exception
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (CBORGenerator generator = new CBORFactory().createGenerator(out))
        {
            generator.writeStartObject();
            generator.writeStringField("fmt", "apple-appattest");
            generator.writeFieldName("attStmt");
            generator.writeStartObject();
            generator.writeFieldName("x5c");
            generator.writeStartArray();
            generator.writeBinary(leaf.getEncoded());
            generator.writeBinary(intermediate.getEncoded());
            generator.writeEndArray();
            generator.writeBinaryField("receipt", receipt);
            generator.writeEndObject();
            generator.writeBinaryField("authData", auth);
            generator.writeEndObject();
        }
        return out.toByteArray();
    }

    byte[] receipt(X509Certificate leaf, byte[] challenge, Instant now, Map<Integer, byte[]> overrides) throws Exception
    {
        Map<Integer, byte[]> fields = new TreeMap<>();
        fields.put(2, utf8(APP_ID));
        fields.put(3, leaf.getEncoded());
        fields.put(4, AppAttestCrypto.hash(challenge));
        fields.put(5, utf8("synthetic-opaque-receipt-token"));
        fields.put(6, utf8("ATTEST"));
        fields.put(7, utf8("production"));
        fields.put(12, utf8(TIMESTAMP.format(now)));
        fields.put(21, utf8(TIMESTAMP.format(now.plusSeconds(3600))));
        if (overrides != null)
        {
            for (Map.Entry<Integer, byte[]> entry : overrides.entrySet())
            {
                if (entry.getValue() == null) { fields.remove(entry.getKey()); }
                else { fields.put(entry.getKey(), entry.getValue()); }
            }
        }
        ASN1EncodableVector payload = new ASN1EncodableVector();
        for (Map.Entry<Integer, byte[]> field : fields.entrySet())
        {
            payload.add(new DERSequence(new ASN1Encodable[] {
                    new ASN1Integer(field.getKey()), new ASN1Integer(1), new DEROctetString(field.getValue()) }));
        }
        return cms(new DERSet(payload).getEncoded(ASN1Encoding.DER));
    }

    byte[] cms(byte[] payload) throws Exception
    {
        return cms(payload, signer, signerKey, Arrays.asList(signer, receiptIntermediate, receiptRoot));
    }

    byte[] cms(byte[] payload, X509Certificate signingCertificate, KeyPair signingKey,
               List<X509Certificate> certificates) throws Exception
    {
        CMSSignedDataGenerator generator = new CMSSignedDataGenerator();
        generator.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(
                new JcaDigestCalculatorProviderBuilder().setProvider(BC).build())
                .setDirectSignature(true).build(new JcaContentSignerBuilder("SHA256withECDSA")
                        .setProvider(BC).build(signingKey.getPrivate()), signingCertificate));
        generator.addCertificates(new JcaCertStore(certificates));
        // Default BC CMS serialization is BER, exercising real constructed-octet framing.
        return generator.generate(new CMSProcessableByteArray(payload), true).getEncoded();
    }

    public byte[] assertion(byte[] exactClientData, long counter) throws Exception
    {
        return assertion(exactClientData, authData(false, counter, APP_ID, 4L, "1.0"), false);
    }

    byte[] assertion(byte[] data, byte[] auth, boolean incorrectlyDoubleHashNonce) throws Exception
    {
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(attestedKey.getPrivate());
        if (incorrectlyDoubleHashNonce)
        {
            signature.update(AppAttestCrypto.hash(auth, AppAttestCrypto.hash(data)));
        }
        else
        {
            signature.update(auth);
            signature.update(AppAttestCrypto.hash(data));
        }
        return assertionEnvelope(auth, signature.sign());
    }

    static byte[] assertionEnvelope(byte[] auth, byte[] signature) throws Exception
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (CBORGenerator generator = new CBORFactory().createGenerator(out))
        {
            generator.writeStartObject();
            generator.writeBinaryField("signature", signature);
            generator.writeBinaryField("authenticatorData", auth);
            generator.writeEndObject();
        }
        return out.toByteArray();
    }

    byte[] authData(boolean enrollment, long counter, String appId, Object category, String version) throws Exception
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(AppAttestCrypto.hash(utf8(appId)));
        out.write(enrollment ? 0xc0 : 0x80);
        out.write(ByteBuffer.allocate(4).putInt((int) counter).array());
        if (enrollment)
        {
            out.write(new byte[] { 'a','p','p','a','t','t','e','s','t',0,0,0,0,0,0,0 });
            out.write(new byte[] { 0, 32 });
            out.write(AppAttestCrypto.hash(point()));
            try (CBORGenerator generator = new CBORFactory().createGenerator(out))
            {
                generator.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
                generator.writeStartObject(5);
                generator.writeFieldId(1); generator.writeNumber(2);
                generator.writeFieldId(3); generator.writeNumber(-7);
                generator.writeFieldId(-1); generator.writeNumber(1);
                generator.writeFieldId(-2); generator.writeBinary(Arrays.copyOfRange(point(), 1, 33));
                generator.writeFieldId(-3); generator.writeBinary(Arrays.copyOfRange(point(), 33, 65));
                generator.writeEndObject();
            }
        }
        try (CBORGenerator generator = new CBORFactory().createGenerator(out))
        {
            generator.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
            generator.writeStartObject();
            generator.writeFieldName(enrollment ? "apple_validation_category_01" : "validationCategory");
            if (category instanceof byte[]) { generator.writeBinary((byte[]) category); }
            else if (category instanceof String) { generator.writeString((String) category); }
            else { generator.writeNumber(((Number) category).longValue()); }
            generator.writeStringField(enrollment ? "apple_bundle_version_01" : "bundleVersion", version);
            generator.writeEndObject();
        }
        return out.toByteArray();
    }

    byte[] point() { return AppAttestCrypto.point((ECPublicKey) attestedKey.getPublic()); }
    static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    static KeyPair keyPair()
    {
        try
        {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        }
        catch (GeneralSecurityException ex) { throw new IllegalStateException(ex); }
    }

    static X509Certificate certificate(String name, KeyPair subjectKey, X509Certificate issuer,
                                       KeyPair issuerKey, Instant now, boolean ca, byte[] nonce,
                                       boolean receiptRole) throws Exception
    {
        X500Name subject = new X500Name("CN=" + name);
        X500Name issuerName = issuer == null ? subject : new X500Name(issuer.getSubjectX500Principal().getName());
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(issuerName,
                new BigInteger(120, new SecureRandom()).add(BigInteger.ONE), Date.from(now.minusSeconds(86400)),
                Date.from(now.plusSeconds(31536000)), subject, subjectKey.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
        builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(ca ? KeyUsage.keyCertSign | KeyUsage.cRLSign : KeyUsage.digitalSignature));
        if (nonce != null)
        {
            builder.addExtension(new ASN1ObjectIdentifier(AppAttestCrypto.NONCE_OID), false,
                    new DERSequence(new DERTaggedObject(true, 1, new DEROctetString(nonce))));
        }
        if (receiptRole)
        {
            builder.addExtension(new ASN1ObjectIdentifier(AppAttestCrypto.RECEIPT_SIGNER_OID), false, DERNull.INSTANCE);
        }
        X509CertificateHolder holder = builder.build(new JcaContentSignerBuilder("SHA256withECDSA")
                .setProvider(BC).build(issuerKey.getPrivate()));
        return new JcaX509CertificateConverter().setProvider(BC).getCertificate(holder);
    }
}
