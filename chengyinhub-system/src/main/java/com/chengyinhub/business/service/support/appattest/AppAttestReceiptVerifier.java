package com.chengyinhub.business.service.support.appattest;

import org.bouncycastle.asn1.*;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.cms.CMSObjectIdentifiers;
import org.bouncycastle.asn1.cms.ContentInfo;
import org.bouncycastle.asn1.cms.SignedData;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Separate CMS signature and G3 path verification; never treats receipt bytes as trusted input. */
final class AppAttestReceiptVerifier
{
    private static final BouncyCastleProvider PROVIDER = new BouncyCastleProvider();
    private static final Set<Integer> ALLOWED_FIELDS = new HashSet<>(Arrays.asList(2, 3, 4, 5, 6, 7, 12, 19, 21));
    private static final Set<Integer> REQUIRED_FIELDS = new HashSet<>(Arrays.asList(2, 3, 4, 6, 7, 12, 21));

    private AppAttestReceiptVerifier() { }

    static Metadata verify(byte[] encoded, X509Certificate root, X509Certificate attestedCertificate,
                           String appId, byte[] challenge, Instant now)
    {
        try
        {
            ContentInfo contentInfo = strictContentInfo(BoundedDer.readBer(encoded, 32768));
            if (!CMSObjectIdentifiers.signedData.equals(contentInfo.getContentType()))
            {
                throw new AppAttestVerificationException("RECEIPT_NOT_SIGNED_DATA");
            }
            SignedData signedData = SignedData.getInstance(contentInfo.getContent());
            if (signedData.getVersion().intValueExact() != 1 || signedData.getDigestAlgorithms().size() != 1)
            {
                throw new AppAttestVerificationException("UNSUPPORTED_RECEIPT_CMS_SHAPE");
            }
            AlgorithmIdentifier digestAlgorithm = AlgorithmIdentifier.getInstance(signedData.getDigestAlgorithms().getObjectAt(0));
            if (!"2.16.840.1.101.3.4.2.1".equals(digestAlgorithm.getAlgorithm().getId())
                    || (digestAlgorithm.getParameters() != null && !(digestAlgorithm.getParameters() instanceof ASN1Null)))
            {
                throw new AppAttestVerificationException("UNSUPPORTED_RECEIPT_SIGNATURE");
            }
            CMSSignedData cms = new CMSSignedData(contentInfo);
            if (!CMSObjectIdentifiers.data.getId().equals(cms.getSignedContentTypeOID())
                    || cms.getSignedContent() == null || !(cms.getSignedContent().getContent() instanceof byte[])
                    || cms.getSignerInfos().size() != 1 || !cms.getCRLs().getMatches(null).isEmpty())
            {
                throw new AppAttestVerificationException("UNSUPPORTED_RECEIPT_CMS_SHAPE");
            }
            SignerInformation signer = cms.getSignerInfos().getSigners().iterator().next();
            if (signer.getVersion() != 1
                    || !"2.16.840.1.101.3.4.2.1".equals(signer.getDigestAlgOID())
                    || !"1.2.840.10045.4.3.2".equals(signer.getEncryptionAlgOID())
                    || signer.getUnsignedAttributes() != null
                    || signer.toASN1Structure().getDigestEncryptionAlgorithm().getParameters() != null
                    || (signer.toASN1Structure().getDigestAlgorithm().getParameters() != null
                        && !(signer.toASN1Structure().getDigestAlgorithm().getParameters() instanceof ASN1Null)))
            {
                throw new AppAttestVerificationException("UNSUPPORTED_RECEIPT_SIGNATURE");
            }
            checkSignedAttributes(signer.getSignedAttributes());
            Collection<X509CertificateHolder> matching = cms.getCertificates().getMatches(signer.getSID());
            Collection<X509CertificateHolder> holders = cms.getCertificates().getMatches(null);
            if (matching.size() != 1 || holders.size() < 2 || holders.size() > 4)
            {
                throw new AppAttestVerificationException("RECEIPT_CERTIFICATE_COUNT");
            }
            X509Certificate signing = AppAttestCrypto.certificate(matching.iterator().next().getEncoded());
            List<X509Certificate> remaining = new ArrayList<>();
            Set<String> fingerprints = new HashSet<>();
            for (X509CertificateHolder holder : holders)
            {
                X509Certificate cert = AppAttestCrypto.certificate(holder.getEncoded());
                if (!fingerprints.add(AppAttestCrypto.hex(AppAttestCrypto.hash(cert.getEncoded()))))
                {
                    throw new AppAttestVerificationException("RECEIPT_DUPLICATE_CERTIFICATE");
                }
                if (!cert.equals(signing) && !cert.equals(root)) { remaining.add(cert); }
            }
            List<X509Certificate> chain = new ArrayList<>();
            chain.add(signing);
            X509Certificate current = signing;
            while (!current.getIssuerX500Principal().equals(root.getSubjectX500Principal()))
            {
                List<X509Certificate> issuers = new ArrayList<>();
                for (X509Certificate candidate : remaining)
                {
                    if (current.getIssuerX500Principal().equals(candidate.getSubjectX500Principal()))
                    {
                        issuers.add(candidate);
                    }
                }
                if (issuers.size() != 1 || chain.size() >= 3)
                {
                    throw new AppAttestVerificationException("RECEIPT_CHAIN_AMBIGUOUS_OR_MISSING");
                }
                current = issuers.get(0);
                chain.add(current);
                remaining.remove(current);
            }
            if (!remaining.isEmpty()) { throw new AppAttestVerificationException("RECEIPT_UNRELATED_CERTIFICATE"); }
            AppAttestCrypto.chain(chain, root, now);
            // G3 is a broad trust root. Also require the receipt-signing role marker observed
            // in Apple's audited signer certificate; do not trust arbitrary G3-issued leaves.
            byte[] role = signing.getExtensionValue(AppAttestCrypto.RECEIPT_SIGNER_OID);
            if (role == null) { throw new AppAttestVerificationException("RECEIPT_SIGNER_ROLE_INVALID"); }
            ASN1Primitive wrappedRole = BoundedDer.read(role, 16);
            if (!(wrappedRole instanceof ASN1OctetString)
                    || !(BoundedDer.read(((ASN1OctetString) wrappedRole).getOctets(), 16) instanceof ASN1Null))
            {
                throw new AppAttestVerificationException("RECEIPT_SIGNER_ROLE_INVALID");
            }
            AppAttestCrypto.signature(signer.getSignature(), AppAttestCrypto.p256(signing.getPublicKey().getEncoded()));
            if (!signer.verify(new JcaSimpleSignerInfoVerifierBuilder().setProvider(PROVIDER).build(signing)))
            {
                throw new AppAttestVerificationException("RECEIPT_SIGNATURE_INVALID");
            }
            Map<Integer, byte[]> fields = fields((byte[]) cms.getSignedContent().getContent());
            if (!appId.equals(text(fields.get(2), 256)) || !"ATTEST".equals(text(fields.get(6), 16))
                    || !"production".equals(text(fields.get(7), 16)))
            {
                throw new AppAttestVerificationException("RECEIPT_IDENTITY_OR_ENVIRONMENT_MISMATCH");
            }
            // The audited receipt's field 3 is certificate DER, not SPKI. Support this exact
            // observed dialect, comparing the whole certificate, with no ASN.1 shape guessing.
            X509Certificate receiptKey = AppAttestCrypto.certificate(fields.get(3));
            AppAttestCrypto.equal(receiptKey.getEncoded(), attestedCertificate.getEncoded(), "RECEIPT_KEY_MISMATCH");
            AppAttestCrypto.equal(fields.get(4), AppAttestCrypto.hash(challenge), "RECEIPT_CLIENT_HASH_MISMATCH");
            Instant createdAt = timestamp(fields.get(12));
            Instant expiresAt = timestamp(fields.get(21));
            if (createdAt.isBefore(now.minusSeconds(300)) || createdAt.isAfter(now.plusSeconds(30)))
            {
                throw new AppAttestVerificationException("RECEIPT_NOT_FRESH");
            }
            if (!expiresAt.isAfter(now) || !expiresAt.isAfter(createdAt))
            {
                throw new AppAttestVerificationException("RECEIPT_EXPIRED_OR_INVALID_INTERVAL");
            }
            if (fields.containsKey(19))
            {
                Instant notBefore = timestamp(fields.get(19));
                if (notBefore.isBefore(createdAt) || !notBefore.isBefore(expiresAt))
                {
                    throw new AppAttestVerificationException("RECEIPT_REFRESH_INTERVAL_INVALID");
                }
            }
            return new Metadata(createdAt, expiresAt);
        }
        catch (AppAttestVerificationException ex)
        {
            throw ex;
        }
        catch (Exception ex)
        {
            throw new AppAttestVerificationException("RECEIPT_INVALID", ex);
        }
    }

    /** Validate original ASN.1 containers before BC's CMS model can normalize extra fields. */
    private static ContentInfo strictContentInfo(ASN1Primitive raw)
    {
        ASN1Sequence outer = sequence(raw, 2);
        schema(outer.getObjectAt(0) instanceof ASN1ObjectIdentifier);
        ASN1TaggedObject content = tagged(outer.getObjectAt(1), 0, true);
        ASN1Sequence signedData = sequence(content.getExplicitBaseObject(), 5);
        schema(signedData.getObjectAt(0) instanceof ASN1Integer
                && signedData.getObjectAt(1) instanceof ASN1Set
                && signedData.getObjectAt(4) instanceof ASN1Set);
        ASN1Set digests = (ASN1Set) signedData.getObjectAt(1);
        schema(digests.size() == 1);
        algorithmShape(digests.getObjectAt(0));
        ASN1Sequence encapsulated = sequence(signedData.getObjectAt(2), 2);
        schema(encapsulated.getObjectAt(0) instanceof ASN1ObjectIdentifier);
        ASN1TaggedObject octets = tagged(encapsulated.getObjectAt(1), 0, true);
        schema(octets.getExplicitBaseObject() instanceof ASN1OctetString);
        ASN1TaggedObject certificates = tagged(signedData.getObjectAt(3), 0, false);
        ASN1Set certificateSet = ASN1Set.getInstance(certificates, false);
        schema(certificateSet.size() >= 2 && certificateSet.size() <= 4);
        for (ASN1Encodable certificate : certificateSet) { schema(certificate instanceof ASN1Sequence); }
        ASN1Set signers = (ASN1Set) signedData.getObjectAt(4);
        schema(signers.size() == 1);
        strictSignerInfo(signers.getObjectAt(0));
        return ContentInfo.getInstance(outer);
    }

    private static void strictSignerInfo(ASN1Encodable raw)
    {
        schema(raw instanceof ASN1Sequence);
        ASN1Sequence signer = (ASN1Sequence) raw;
        schema(signer.size() == 5 || signer.size() == 6);
        schema(signer.getObjectAt(0) instanceof ASN1Integer);
        ASN1Sequence identity = sequence(signer.getObjectAt(1), 2);
        schema(identity.getObjectAt(0) instanceof ASN1Sequence && identity.getObjectAt(1) instanceof ASN1Integer);
        algorithmShape(signer.getObjectAt(2));
        int signatureIndex = 3;
        if (signer.size() == 6)
        {
            ASN1TaggedObject signedAttributes = tagged(signer.getObjectAt(3), 0, false);
            ASN1Set attributes = ASN1Set.getInstance(signedAttributes, false);
            // A signed-attribute CMS signature needs at least content-type and message-digest.
            schema(attributes.size() >= 2 && attributes.size() <= 8);
            for (ASN1Encodable value : attributes)
            {
                ASN1Sequence attribute = sequence(value, 2);
                schema(attribute.getObjectAt(0) instanceof ASN1ObjectIdentifier
                        && attribute.getObjectAt(1) instanceof ASN1Set
                        && ((ASN1Set) attribute.getObjectAt(1)).size() == 1);
            }
            signatureIndex++;
        }
        algorithmShape(signer.getObjectAt(signatureIndex));
        schema(signer.getObjectAt(signatureIndex + 1) instanceof ASN1OctetString);
    }

    private static ASN1Sequence sequence(ASN1Encodable value, int size)
    {
        schema(value instanceof ASN1Sequence && ((ASN1Sequence) value).size() == size);
        return (ASN1Sequence) value;
    }

    private static ASN1TaggedObject tagged(ASN1Encodable value, int number, boolean explicit)
    {
        schema(value instanceof ASN1TaggedObject);
        ASN1TaggedObject tag = (ASN1TaggedObject) value;
        schema(tag.hasContextTag(number) && tag.isExplicit() == explicit);
        return tag;
    }

    private static void algorithmShape(ASN1Encodable value)
    {
        schema(value instanceof ASN1Sequence);
        ASN1Sequence algorithm = (ASN1Sequence) value;
        schema((algorithm.size() == 1 || algorithm.size() == 2)
                && algorithm.getObjectAt(0) instanceof ASN1ObjectIdentifier);
    }

    private static void schema(boolean condition)
    {
        if (!condition) { throw new AppAttestVerificationException("RECEIPT_CMS_SCHEMA"); }
    }

    private static void checkSignedAttributes(AttributeTable table)
    {
        if (table == null) { return; }
        ASN1EncodableVector values = table.toASN1EncodableVector();
        if (values.size() > 8) { throw new AppAttestVerificationException("RECEIPT_ATTRIBUTE_LIMIT"); }
        Set<String> names = new HashSet<>();
        for (int i = 0; i < values.size(); i++)
        {
            Attribute attribute = Attribute.getInstance(values.get(i));
            if (!names.add(attribute.getAttrType().getId()) || attribute.getAttrValues().size() != 1)
            {
                throw new AppAttestVerificationException("RECEIPT_DUPLICATE_ATTRIBUTE");
            }
        }
    }

    private static Map<Integer, byte[]> fields(byte[] encoded)
    {
        // Receipt payload is an ASN.1 SET, not documented as canonical DER SET OF.
        // Apple's signed sample uses field order rather than DER lexical SET ordering.
        // Verify the signature over original bytes, then parse bounded BER semantics.
        ASN1Primitive payload = BoundedDer.readBer(encoded, 16384);
        if (!(payload instanceof ASN1Set) || ((ASN1Set) payload).size() > 16)
        {
            throw new AppAttestVerificationException("RECEIPT_PAYLOAD_SHAPE");
        }
        Map<Integer, byte[]> fields = new HashMap<>();
        for (ASN1Encodable entry : (ASN1Set) payload)
        {
            if (!(entry instanceof ASN1Sequence) || ((ASN1Sequence) entry).size() != 3)
            {
                throw new AppAttestVerificationException("RECEIPT_FIELD_SHAPE");
            }
            ASN1Sequence field = (ASN1Sequence) entry;
            if (!(field.getObjectAt(0) instanceof ASN1Integer) || !(field.getObjectAt(1) instanceof ASN1Integer)
                    || !(field.getObjectAt(2) instanceof ASN1OctetString))
            {
                throw new AppAttestVerificationException("RECEIPT_FIELD_TYPE");
            }
            int id = ((ASN1Integer) field.getObjectAt(0)).intValueExact();
            int version = ((ASN1Integer) field.getObjectAt(1)).intValueExact();
            byte[] content = ((ASN1OctetString) field.getObjectAt(2)).getOctets();
            if (!ALLOWED_FIELDS.contains(id) || version != 1 || content.length == 0
                    || content.length > (id == 3 ? 8192 : 1024) || fields.put(id, content) != null)
            {
                throw new AppAttestVerificationException("UNSUPPORTED_OR_DUPLICATE_RECEIPT_FIELD");
            }
        }
        if (!fields.keySet().containsAll(REQUIRED_FIELDS))
        {
            throw new AppAttestVerificationException("MISSING_RECEIPT_FIELD");
        }
        return fields;
    }

    private static String text(byte[] bytes, int maximum)
    {
        if (bytes == null || bytes.length == 0 || bytes.length > maximum)
        {
            throw new AppAttestVerificationException("RECEIPT_TEXT_SIZE");
        }
        try
        {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        }
        catch (CharacterCodingException ex)
        {
            throw new AppAttestVerificationException("RECEIPT_TEXT_INVALID", ex);
        }
    }

    private static Instant timestamp(byte[] bytes)
    {
        String text = text(bytes, 40);
        if (!text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z"))
        {
            throw new AppAttestVerificationException("RECEIPT_TIME_FORMAT");
        }
        try { return Instant.parse(text); }
        catch (DateTimeParseException ex) { throw new AppAttestVerificationException("RECEIPT_TIME_INVALID", ex); }
    }

    static final class Metadata
    {
        final Instant createdAt;
        final Instant expiresAt;
        Metadata(Instant createdAt, Instant expiresAt) { this.createdAt = createdAt; this.expiresAt = expiresAt; }
    }
}
