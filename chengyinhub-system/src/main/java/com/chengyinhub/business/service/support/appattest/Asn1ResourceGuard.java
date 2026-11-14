package com.chengyinhub.business.service.support.appattest;

/**
 * Non-semantic, iterative BER/DER framing preflight only. BC 1.70's public constructed
 * OCTET STRING stream recursively flattens children before producing a byte. This guard
 * bounds encoded nesting/objects BEFORE any BC eager load, including those children.
 * It does not decode ASN.1 values, certificates, CMS, or signatures. BC performs all of
 * those operations after this check. Review this resource guard independently before use.
 */
final class Asn1ResourceGuard
{
    private static final int MAX_DEPTH = 24;
    private static final int MAX_OBJECTS = 2048;
    private Asn1ResourceGuard() { }

    static void check(byte[] encoded, int maximum)
    {
        if (encoded == null || encoded.length == 0 || encoded.length > maximum)
        {
            deny("ASN1_SIZE_LIMIT");
        }
        int[] ends = new int[MAX_DEPTH];
        int[] limits = new int[MAX_DEPTH];
        int depth = 0;
        int offset = 0;
        int objects = 0;
        int roots = 0;
        while (offset < encoded.length || depth > 0)
        {
            while (depth > 0 && ends[depth - 1] >= 0 && offset == ends[depth - 1]) { depth--; }
            if (offset == encoded.length)
            {
                if (depth != 0) { deny("ASN1_TRUNCATED"); }
                break;
            }
            int limit = depth == 0 ? encoded.length : limits[depth - 1];
            if (offset >= limit) { deny("ASN1_CONTAINER_OVERRUN"); }
            if (++objects > MAX_OBJECTS) { deny("ASN1_OBJECT_LIMIT"); }
            int tag = encoded[offset++] & 0xff;
            if (tag == 0)
            {
                if (offset >= limit || encoded[offset++] != 0 || depth == 0 || ends[depth - 1] != -1)
                {
                    deny("ASN1_INVALID_EOC");
                }
                depth--;
                continue;
            }
            if (depth == 0 && ++roots != 1) { deny("ASN1_TRAILING_ROOT"); }
            boolean constructed = (tag & 0x20) != 0;
            if ((tag & 0x1f) == 0x1f)
            {
                long tagNumber = 0;
                int groups = 0;
                int next;
                do
                {
                    if (offset >= limit || ++groups > 5) { deny("ASN1_TAG_LIMIT"); }
                    next = encoded[offset++] & 0xff;
                    if (groups == 1 && (next & 0x7f) == 0) { deny("ASN1_INVALID_HIGH_TAG"); }
                    tagNumber = (tagNumber << 7) | (next & 0x7f);
                    if (tagNumber > Integer.MAX_VALUE) { deny("ASN1_TAG_LIMIT"); }
                }
                while ((next & 0x80) != 0);
                if (tagNumber < 31) { deny("ASN1_INVALID_HIGH_TAG"); }
            }
            if (offset >= limit) { deny("ASN1_TRUNCATED_LENGTH"); }
            int lengthByte = encoded[offset++] & 0xff;
            boolean indefinite = lengthByte == 0x80;
            long length = 0;
            if (indefinite)
            {
                if (!constructed) { deny("ASN1_PRIMITIVE_INDEFINITE"); }
            }
            else if ((lengthByte & 0x80) == 0)
            {
                length = lengthByte;
            }
            else
            {
                int count = lengthByte & 0x7f;
                if (count > 4 || count == 0 || count > limit - offset) { deny("ASN1_LENGTH_LIMIT"); }
                for (int i = 0; i < count; i++) { length = (length << 8) | (encoded[offset++] & 0xff); }
            }
            if (length > limit - offset) { deny("ASN1_CONTENT_OVERRUN"); }
            if (constructed)
            {
                if (depth == MAX_DEPTH) { deny("ASN1_DEPTH_LIMIT"); }
                int end = indefinite ? -1 : offset + (int) length;
                ends[depth] = end;
                limits[depth] = indefinite ? limit : end;
                depth++;
            }
            else
            {
                offset += (int) length;
            }
        }
        if (depth != 0 || roots != 1) { deny("ASN1_TRUNCATED"); }
    }

    private static void deny(String reason)
    {
        throw new AppAttestVerificationException(reason);
    }
}
