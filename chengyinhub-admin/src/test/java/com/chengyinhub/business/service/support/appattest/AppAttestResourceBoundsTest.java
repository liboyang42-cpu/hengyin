package com.chengyinhub.business.service.support.appattest;

import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.dataformat.cbor.CBORGenerator;
import org.bouncycastle.asn1.*;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AppAttestResourceBoundsTest
{
    @Test
    void cborRejectsDuplicatesTagsCoercionsUnsupportedTypesAndTrailingValues()
    {
        byte[][] malformed = {
            {(byte)0xa2,0x61,0x61,1,0x61,0x61,2}, // duplicate map key
            {(byte)0xc0,0x01}, // tagged number
            {(byte)0xd9,(byte)0xd9,(byte)0xf7,(byte)0xa0}, // self-described CBOR tag
            {(byte)0xa1,1,2}, // non-text outer key
            {(byte)0xa1,0x41,0x61,1}, // bytes key coerced by Jackson to text
            {(byte)0xf4}, {(byte)0xf6}, {(byte)0xf7}, // bool, null, undefined
            {(byte)0xf9,0,0}, // float
            {(byte)0xa0,0}, // trailing root
            {(byte)0xa1,0x61,0x61}, // truncated value
            {0x5a,0x7f,(byte)0xff,(byte)0xff,(byte)0xff}, // huge binary declaration
            {0x7a,0x7f,(byte)0xff,(byte)0xff,(byte)0xff} // huge text declaration
        };
        for (byte[] bytes : malformed)
        {
            assertThrows(AppAttestVerificationException.class, () -> BoundedCbor.read(bytes, 0, 65536, false, true), Arrays.toString(bytes));
        }
        assertThrows(AppAttestVerificationException.class,
                () -> BoundedCbor.read(new byte[] {(byte)0xa1,0x61,0x31,2}, 0, 64, true, true));
    }

    @Test
    void cborRejectsLargeTagSentinelCollisionsAtRootNestedAndSliceOffsets()
    {
        byte[][] tagHeaders = {
            {(byte)0xda,(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff},
            {(byte)0xdb,(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff}
        };
        for (byte[] tag : tagHeaders)
        {
            byte[] root = concatenate(tag, new byte[] {(byte)0xa0});
            byte[] nestedMap = concatenate(new byte[] {(byte)0xa1,0x61,0x61}, concatenate(tag, new byte[] {1}));
            byte[] nestedArray = concatenate(new byte[] {(byte)0x81}, concatenate(tag, new byte[] {(byte)0xa0}));
            for (byte[] encoded : new byte[][] {root, nestedMap, nestedArray})
            {
                assertEquals("UNSUPPORTED_CBOR_TAG", assertThrows(AppAttestVerificationException.class,
                        () -> BoundedCbor.read(encoded, 0, 65536, false, true)).getReasonCode());
                byte[] sliced = concatenate(new byte[] {1,2,3}, encoded);
                assertEquals("UNSUPPORTED_CBOR_TAG", assertThrows(AppAttestVerificationException.class,
                        () -> BoundedCbor.read(sliced, 3, 65536, false, true)).getReasonCode());
            }
        }
    }

    @Test
    void originalHeadersRejectJacksonIntegerUtf8AndLengthCoercionsAtEverySliceOffset()
    {
        String[] poisons = {
            "a11bfffffffffffffffe01", "a13bfffffffffffffffe01", // UInt64/negative labels wrap in Jackson
            "a162c1a101", "a162c16101", "62c1a1", // malformed UTF8 coerced to ASCII
            "9affffffffff", "7affffffffff", "5affffffffff", // uint32 length becomes -1/indefinite
            "a17affffffff6161ff01" // bogus string length inside a map key
        };
        for (String poison : poisons)
        {
            byte[] original = hex(poison);
            byte[] sliced = concatenate(new byte[] {1,2,3}, original);
            for (boolean integerKeys : new boolean[] {false,true})
            {
                assertThrows(AppAttestVerificationException.class,
                        () -> BoundedCbor.read(original, 0, 65536, integerKeys, true), poison);
                assertThrows(AppAttestVerificationException.class,
                        () -> BoundedCbor.read(sliced, 3, 65536, integerKeys, true), poison);
            }
        }
    }

    @Test
    void genuineIndefiniteContainersAndStrictUtf8RemainSupported()
    {
        assertNotNull(BoundedCbor.read(hex("bf616101ff"), 0, 64, false, true).value);
        assertNotNull(BoundedCbor.read(hex("9f01ff"), 0, 64, false, true).value);
        assertEquals("é", BoundedCbor.read(hex("62c3a9"), 0, 64, false, true).value);
        assertThrows(AppAttestVerificationException.class,
                () -> BoundedCbor.read(hex("7f6161ff"), 0, 64, false, true));
        assertThrows(AppAttestVerificationException.class,
                () -> BoundedCbor.read(hex("5f4101ff"), 0, 64, false, true));
    }

    private static byte[] hex(String value)
    {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) { result[i] = (byte) Integer.parseInt(value.substring(2 * i, 2 * i + 2), 16); }
        return result;
    }

    @Test
    void cborTokenBoundariesAreAbsoluteWithinNonzeroOffsetSlices()
    {
        // A tagged prefix must not be mistaken for a token belonging to the parser slice.
        byte[] textMap = {(byte)0xda,(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xa1,0x61,0x61,1};
        BoundedCbor.Item text = BoundedCbor.read(textMap, 5, 64, false, true);
        assertEquals(1L, ((Map<?, ?>) text.value).get("a"));
        assertEquals(textMap.length, text.end);
        byte[] integerMap = {(byte)0xc0,(byte)0xa1,1,2};
        BoundedCbor.Item integer = BoundedCbor.read(integerMap, 1, 64, true, true);
        assertEquals(2L, ((Map<?, ?>) integer.value).get("1"));
        assertEquals(integerMap.length, integer.end);
    }

    private static byte[] concatenate(byte[] one, byte[] two)
    {
        byte[] result = Arrays.copyOf(one, one.length + two.length);
        System.arraycopy(two, 0, result, one.length, two.length);
        return result;
    }

    @Test
    void cborLimitsDepthArraysMapsAndTextBeforeRecursiveDescent() throws Exception
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (CBORGenerator g = new CBORFactory().createGenerator(out))
        {
            for (int i = 0; i < 20; i++) { g.writeStartArray(); }
            g.writeNumber(1);
            for (int i = 0; i < 20; i++) { g.writeEndArray(); }
        }
        rejectedCbor(out.toByteArray()); out.reset();
        try (CBORGenerator g = new CBORFactory().createGenerator(out))
        {
            g.writeStartArray(); for (int i = 0; i < 17; i++) { g.writeNumber(i); } g.writeEndArray();
        }
        rejectedCbor(out.toByteArray()); out.reset();
        try (CBORGenerator g = new CBORFactory().createGenerator(out))
        {
            g.writeStartObject(); for (int i = 0; i < 17; i++) { g.writeNumberField("k" + i, i); } g.writeEndObject();
        }
        rejectedCbor(out.toByteArray()); out.reset();
        try (CBORGenerator g = new CBORFactory().createGenerator(out)) { g.writeString(new String(new char[257]).replace('\0', 'a')); }
        rejectedCbor(out.toByteArray());
    }

    @Test
    void berSupportsConstructedOctetsAndDerStillRequiresCanonicalEncoding() throws Exception
    {
        byte[] ber = new BERSequence(new BEROctetString(new byte[2048])).getEncoded(ASN1Encoding.BER);
        assertNotNull(BoundedDer.readBer(ber, 32768));
        assertThrows(AppAttestVerificationException.class, () -> BoundedDer.read(ber, 32768));
        byte[] der = new DERSequence(new DEROctetString(new byte[2048])).getEncoded(ASN1Encoding.DER);
        assertNotNull(BoundedDer.read(der, 32768));
    }

    @Test
    void resourceGuardRejectsMalformedLengthsTagsEndMarkersAndTrailingRoots()
    {
        byte[][] malformed = {
            {0,0}, // standalone EOC
            {0x30,2,0,0}, // EOC under definite parent
            {0x30,(byte)0x80,0,1,0}, // nonzero EOC length
            {0x04,(byte)0x80,0,0}, // primitive indefinite
            {0x30,(byte)0x80}, // missing EOC
            {0x30,3,2,1}, // truncated definite content
            {0x30,2,2,2,0,0}, // child overrun
            {2,1,1,2,1,2}, // two roots
            {0x1f,(byte)0x80,1,0}, // illegal high tag
            {0x1f,(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff,0x7f,0}, // tag overflow
            {4,(byte)0x85,0,0,0,0,1,0}, // length-width bound
            {4,(byte)0x84,(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff}, // long overflow/overrun
            {4,(byte)0x82,1}, // truncated length
            {0x30,2,0x24,(byte)0x80} // indefinite child overruns definite boundary
        };
        for (byte[] bytes : malformed)
        {
            assertThrows(AppAttestVerificationException.class, () -> Asn1ResourceGuard.check(bytes, 32768), Arrays.toString(bytes));
        }
        byte[] validHighTag = {(byte)0xbf,0x20,2,5,0};
        assertDoesNotThrow(() -> Asn1ResourceGuard.check(validHighTag, 32768));
        assertNotNull(BoundedDer.read(validHighTag, 32768));
    }

    @Test
    void deeplyNestedDefiniteIndefiniteAndConstructedOctetsFailBeforeBcLoading() throws Exception
    {
        ASN1Encodable value = new DEROctetString(new byte[] {1});
        for (int i = 0; i < 30; i++) { value = new DERSequence(value); }
        final byte[] definite = value.toASN1Primitive().getEncoded();
        assertEquals("ASN1_DEPTH_LIMIT", assertThrows(AppAttestVerificationException.class,
                () -> Asn1ResourceGuard.check(definite, 32768)).getReasonCode());
        for (byte constructed : new byte[] {0x30,0x24})
        {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            for (int i = 0; i < 500; i++) { bytes.write(constructed); bytes.write(0x80); }
            bytes.write(new byte[] {4,1,1});
            for (int i = 0; i < 500; i++) { bytes.write(0); bytes.write(0); }
            assertEquals("ASN1_DEPTH_LIMIT", assertThrows(AppAttestVerificationException.class,
                    () -> Asn1ResourceGuard.check(bytes.toByteArray(), 32768)).getReasonCode());
        }
        ASN1Encodable[] objects = new ASN1Encodable[2050]; Arrays.fill(objects, DERNull.INSTANCE);
        byte[] many = new DERSequence(objects).getEncoded();
        assertEquals("ASN1_OBJECT_LIMIT", assertThrows(AppAttestVerificationException.class,
                () -> Asn1ResourceGuard.check(many, 32768)).getReasonCode());
    }

    private static void rejectedCbor(byte[] bytes)
    {
        assertThrows(AppAttestVerificationException.class, () -> BoundedCbor.read(bytes, 0, 65536, false, true));
    }
}
