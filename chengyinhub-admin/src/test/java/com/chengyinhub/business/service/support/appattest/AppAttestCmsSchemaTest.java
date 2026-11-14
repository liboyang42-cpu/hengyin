package com.chengyinhub.business.service.support.appattest;

import org.bouncycastle.asn1.*;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class AppAttestCmsSchemaTest
{
    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00.123Z");
    private static final byte[] CHALLENGE = AppAttestTestKit.utf8("synthetic-server-challenge-32bytes");
    private static AppAttestTestKit kit;
    private static X509Certificate leaf;
    private static byte[] receipt;
    private static ASN1Sequence contentInfo;
    private static ASN1Sequence signedData;
    private static byte[] signedAttributeReceipt;

    @BeforeAll
    static void setup() throws Exception
    {
        kit = new AppAttestTestKit(NOW);
        leaf = AppAttestTestKit.certificate("Synthetic Key", kit.attestedKey, kit.intermediate,
                kit.intermediateKey, NOW, false, new byte[32], false);
        receipt = kit.receipt(leaf, CHALLENGE, NOW, null);
        contentInfo = ASN1Sequence.getInstance(BoundedDer.readBer(receipt, 32768));
        signedData = ASN1Sequence.getInstance(((ASN1TaggedObject) contentInfo.getObjectAt(1)).getExplicitBaseObject());
        ASN1Sequence encapsulated = ASN1Sequence.getInstance(signedData.getObjectAt(2));
        byte[] payload = ((ASN1OctetString) ((ASN1TaggedObject) encapsulated.getObjectAt(1)).getExplicitBaseObject()).getOctets();
        CMSSignedDataGenerator generator = new CMSSignedDataGenerator();
        generator.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(new JcaDigestCalculatorProviderBuilder()
                .setProvider(AppAttestTestKit.BC).build()).build(new JcaContentSignerBuilder("SHA256withECDSA")
                .setProvider(AppAttestTestKit.BC).build(kit.signerKey.getPrivate()), kit.signer));
        generator.addCertificates(new JcaCertStore(Arrays.asList(kit.signer, kit.receiptIntermediate, kit.receiptRoot)));
        signedAttributeReceipt = generator.generate(new CMSProcessableByteArray(payload), true).getEncoded();
    }

    @Test
    void legitimateBerCmsWithAndWithoutSignedAttributesValidates()
    {
        assertNotNull(verify(receipt));
        assertNotNull(verify(signedAttributeReceipt));
    }

    @Test
    void duplicatedCertificateFieldsAndSignerInfoSetsAreRejectedBeforeNormalization() throws Exception
    {
        ASN1Encodable[] original = elements(signedData);
        ASN1Encodable[] duplicateSigners = Arrays.copyOf(original, 6);
        duplicateSigners[5] = original[4];
        rejected(encode(duplicateSigners));
        rejected(encode(new ASN1Encodable[] { original[0], original[1], original[2],
                new BERTaggedObject(false, 0, new BERSet()), original[3], original[4] }));
    }

    @Test
    void signedDataRejectsExtraMissingReorderedAndWrongNativeTypes() throws Exception
    {
        ASN1Encodable[] original = elements(signedData);
        rejected(encode(Arrays.copyOf(original, 4)));
        ASN1Encodable[] extra = Arrays.copyOf(original, 6); extra[5] = DERNull.INSTANCE; rejected(encode(extra));
        ASN1Encodable[] reordered = original.clone(); reordered[3] = original[4]; reordered[4] = original[3]; rejected(encode(reordered));
        ASN1Encodable[] wrongType = original.clone(); wrongType[1] = new DERSequence(); rejected(encode(wrongType));
        wrongType = original.clone(); wrongType[0] = new DEROctetString(new byte[] {1}); rejected(encode(wrongType));
    }

    @Test
    void certificateContainerRequiresImplicitContextZero() throws Exception
    {
        ASN1Set certificates = ASN1Set.getInstance((ASN1TaggedObject) signedData.getObjectAt(3), false);
        for (ASN1Encodable wrongTag : new ASN1Encodable[] {
                new BERTaggedObject(false, 1, certificates), new BERTaggedObject(true, 0, certificates),
                new DERTaggedObject(false, BERTags.APPLICATION, 0, certificates) })
        {
            ASN1Encodable[] altered = elements(signedData); altered[3] = wrongTag; rejected(encode(altered));
        }
    }

    @Test
    void contentAndEncapsulationRejectTrailingFieldsWrongTagAndImplicitWrapper() throws Exception
    {
        rejected(new BERSequence(new ASN1Encodable[] {contentInfo.getObjectAt(0), contentInfo.getObjectAt(1), DERNull.INSTANCE}).getEncoded());
        rejected(new BERSequence(new ASN1Encodable[] {contentInfo.getObjectAt(0), new BERTaggedObject(true, 1, signedData)}).getEncoded());
        rejected(new BERSequence(new ASN1Encodable[] {contentInfo.getObjectAt(0), new BERTaggedObject(false, 0, signedData)}).getEncoded());
        ASN1Sequence encapsulated = ASN1Sequence.getInstance(signedData.getObjectAt(2));
        ASN1Encodable[] altered = elements(signedData);
        altered[2] = new BERSequence(new ASN1Encodable[] {encapsulated.getObjectAt(0), encapsulated.getObjectAt(1), DERNull.INSTANCE});
        rejected(encode(altered));
    }

    @Test
    void signerInfoRejectsExtraMissingDuplicateAndMisorderedFields() throws Exception
    {
        ASN1Sequence signer = ASN1Sequence.getInstance(((ASN1Set) signedData.getObjectAt(4)).getObjectAt(0));
        ASN1Encodable[] fields = elements(signer);
        rejectedSigner(Arrays.copyOf(fields, 4));
        ASN1Encodable[] extra = Arrays.copyOf(fields, 6); extra[5] = fields[4]; rejectedSigner(extra);
        ASN1Encodable[] reordered = fields.clone(); reordered[3] = fields[4]; reordered[4] = fields[3]; rejectedSigner(reordered);
        ASN1Encodable[] badIdentity = fields.clone(); badIdentity[1] = new DERTaggedObject(false, 0, new DEROctetString(new byte[20])); rejectedSigner(badIdentity);
        ASN1Encodable[] extraAlgorithmFields = fields.clone();
        ASN1Sequence signatureAlgorithm = ASN1Sequence.getInstance(fields[3]);
        extraAlgorithmFields[3] = new DERSequence(new ASN1Encodable[] {signatureAlgorithm.getObjectAt(0), DERNull.INSTANCE, DERNull.INSTANCE});
        rejectedSigner(extraAlgorithmFields);
    }

    @Test
    void signedAttributeContainerRequiresImplicitContextZeroAndNoExtraFields() throws Exception
    {
        ASN1Sequence outer = ASN1Sequence.getInstance(BoundedDer.readBer(signedAttributeReceipt, 32768));
        ASN1Sequence data = ASN1Sequence.getInstance(((ASN1TaggedObject) outer.getObjectAt(1)).getExplicitBaseObject());
        ASN1Sequence signer = ASN1Sequence.getInstance(((ASN1Set) data.getObjectAt(4)).getObjectAt(0));
        assertEquals(6, signer.size());
        ASN1Set attributes = ASN1Set.getInstance((ASN1TaggedObject) signer.getObjectAt(3), false);
        for (ASN1Encodable wrong : new ASN1Encodable[] {
                new BERTaggedObject(false, 1, attributes), new BERTaggedObject(true, 0, attributes),
                new DERTaggedObject(false, BERTags.APPLICATION, 0, attributes) })
        {
            ASN1Encodable[] fields = elements(signer); fields[3] = wrong;
            ASN1Encodable[] dataFields = elements(data); dataFields[4] = new BERSet(new DERSequence(fields));
            rejected(encode(dataFields));
        }
        ASN1Encodable[] extra = Arrays.copyOf(elements(signer), 7); extra[6] = signer.getObjectAt(3);
        ASN1Encodable[] dataFields = elements(data); dataFields[4] = new BERSet(new DERSequence(extra));
        rejected(encode(dataFields));
    }

    private static void rejectedSigner(ASN1Encodable[] signer) throws Exception
    {
        ASN1Encodable[] fields = elements(signedData); fields[4] = new BERSet(new DERSequence(signer)); rejected(encode(fields));
    }
    private static ASN1Encodable[] elements(ASN1Sequence sequence)
    {
        ASN1Encodable[] result = new ASN1Encodable[sequence.size()];
        for (int i = 0; i < result.length; i++) { result[i] = sequence.getObjectAt(i); }
        return result;
    }
    private static byte[] encode(ASN1Encodable[] fields) throws Exception
    {
        return new BERSequence(new ASN1Encodable[] {contentInfo.getObjectAt(0),
                new BERTaggedObject(true, 0, new BERSequence(fields))}).getEncoded();
    }
    private static AppAttestReceiptVerifier.Metadata verify(byte[] bytes)
    {
        return AppAttestReceiptVerifier.verify(bytes, kit.receiptRoot, leaf, AppAttestTestKit.APP_ID, CHALLENGE, NOW);
    }
    private static void rejected(byte[] bytes)
    {
        assertEquals("RECEIPT_CMS_SCHEMA", assertThrows(AppAttestVerificationException.class, () -> verify(bytes)).getReasonCode());
    }
}
