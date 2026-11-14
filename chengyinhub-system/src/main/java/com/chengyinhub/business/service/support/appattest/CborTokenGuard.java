package com.chengyinhub.business.service.support.appattest;

import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.dataformat.cbor.CBORParser;
import com.fasterxml.jackson.dataformat.cbor.CBORReadContext;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Header-only conformance checks around Jackson 2.12.7, not a CBOR decoder. Jackson owns
 * traversal, container boundaries and semantic values. This guard prevents its UInt64,
 * large-length, tag-sentinel and malformed-UTF8 coercions in this deliberately small schema.
 * Text/byte strings must be definite; genuine indefinite maps/arrays remain supported.
 */
final class CborTokenGuard
{
    private CborTokenGuard() { }

    static void beforeNext(CBORParser parser, byte[] input, int inputOffset)
    {
        CBORReadContext context = parser.getParsingContext();
        boolean pendingObjectValue = context.inObject() && parser.currentToken() == JsonToken.FIELD_NAME;
        // Jackson synthesizes END_OBJECT/END_ARRAY for a completed definite container;
        // there is no corresponding original byte to inspect in that case.
        if (!context.inRoot() && context.hasExpectedLength() && !pendingObjectValue
                && context.getEntryCount() >= context.getExpectedLength())
        {
            return;
        }
        long position = parser.getCurrentLocation().getByteOffset();
        if (position == input.length) { return; } // Jackson decides whether this is legal EOF.
        int start = position(position, input, inputOffset);
        if ((input[start] & 0xff) == 0xff && context.acceptsBreakMarker() && !pendingObjectValue)
        {
            return; // Jackson validates and consumes the actual indefinite-container break.
        }
        header(input, start);
    }

    static Header current(CBORParser parser, byte[] input, int inputOffset)
    {
        return header(input, position(parser.getTokenLocation().getByteOffset(), input, inputOffset));
    }

    private static int position(long position, byte[] input, int inputOffset)
    {
        if (position < inputOffset || position >= input.length)
        {
            throw new AppAttestVerificationException("CBOR_TOKEN_BOUNDARY");
        }
        return (int) position;
    }

    private static Header header(byte[] input, int start)
    {
        int initial = input[start] & 0xff;
        int major = initial >>> 5;
        int additional = initial & 31;
        if (major == 6) { throw new AppAttestVerificationException("UNSUPPORTED_CBOR_TAG"); }
        if (major == 7) { throw new AppAttestVerificationException("UNSUPPORTED_CBOR_VALUE_TYPE"); }
        if (additional == 31)
        {
            if (major != 4 && major != 5)
            {
                throw new AppAttestVerificationException("UNSUPPORTED_CBOR_INDEFINITE_STRING");
            }
            return new Header(major, -1, null);
        }
        // This schema needs at most UInt32, 32KiB bytes, 256-byte text and 16 entries.
        // In particular, width 8 cannot be silently narrowed by Jackson to a signed long/int.
        if (additional > 26) { throw new AppAttestVerificationException("CBOR_HEADER_WIDTH_LIMIT"); }
        int width = additional < 24 ? 0 : 1 << (additional - 24);
        if (width > input.length - start - 1) { throw new AppAttestVerificationException("CBOR_TRUNCATED_HEADER"); }
        long argument = additional < 24 ? additional : 0;
        for (int i = 0; i < width; i++) { argument = (argument << 8) | (input[start + 1 + i] & 0xff); }
        if ((major == 4 || major == 5) && argument > 16)
        {
            throw new AppAttestVerificationException("CBOR_CONTAINER_LIMIT");
        }
        String text = null;
        if (major == 2 || major == 3)
        {
            int maximum = major == 2 ? 32768 : 256;
            int payload = start + 1 + width;
            if (argument > maximum || argument > input.length - payload)
            {
                throw new AppAttestVerificationException("CBOR_STRING_LENGTH_LIMIT");
            }
            if (major == 3)
            {
                try
                {
                    text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(input, payload, (int) argument)).toString();
                }
                catch (CharacterCodingException ex)
                {
                    throw new AppAttestVerificationException("CBOR_INVALID_UTF8", ex);
                }
            }
        }
        return new Header(major, argument, text);
    }

    static final class Header
    {
        final int major;
        final long argument;
        final String text;
        Header(int major, long argument, String text) { this.major = major; this.argument = argument; this.text = text; }

        void textEquals(String decoded)
        {
            if (major != 3 || text == null || !text.equals(decoded))
            {
                throw new AppAttestVerificationException("CBOR_TEXT_COERCION");
            }
        }

        void integerEquals(long decoded)
        {
            if ((major != 0 && major != 1) || decoded != (major == 0 ? argument : -1L - argument))
            {
                throw new AppAttestVerificationException("CBOR_INTEGER_COERCION");
            }
        }
    }
}
