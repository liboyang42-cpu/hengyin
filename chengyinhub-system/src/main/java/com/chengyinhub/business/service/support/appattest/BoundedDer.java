package com.chengyinhub.business.service.support.appattest;

import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1Primitive;
import java.io.IOException;
import java.util.Arrays;

/** Standard BC ASN.1 decoding after the isolated, non-semantic resource preflight. */
final class BoundedDer
{
    private BoundedDer() { }

    static ASN1Primitive read(byte[] bytes, int maximum)
    {
        ASN1Primitive result = readBer(bytes, maximum);
        try
        {
            if (!Arrays.equals(bytes, result.getEncoded(ASN1Encoding.DER)))
            {
                throw new AppAttestVerificationException("NON_CANONICAL_DER");
            }
            return result;
        }
        catch (IOException ex)
        {
            throw new AppAttestVerificationException("MALFORMED_DER", ex);
        }
    }

    static ASN1Primitive readBer(byte[] bytes, int maximum)
    {
        Asn1ResourceGuard.check(bytes, maximum);
        try
        {
            // BC validates tags, lengths, BER semantics and whole-input consumption.
            return ASN1Primitive.fromByteArray(bytes);
        }
        catch (IOException | RuntimeException ex)
        {
            throw new AppAttestVerificationException("MALFORMED_ASN1", ex);
        }
    }
}
