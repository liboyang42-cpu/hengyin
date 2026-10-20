package com.chengyinhub.business.service.support.appattest;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.dataformat.cbor.CBORParser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Jackson streaming decoding with limits before descending into a container. */
final class BoundedCbor
{
    private static final CBORFactory FACTORY = new CBORFactory();
    private static final int MAX_DEPTH = 6;
    private static final int MAX_ITEMS = 64;
    private static final int MAX_CONTAINER_ITEMS = 16;
    static
    {
        FACTORY.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    private BoundedCbor() { }

    static Item read(byte[] input, int offset, int maximum, boolean integerKeys, boolean wholeInput)
    {
        if (input == null || offset < 0 || offset >= input.length || input.length - offset > maximum)
        {
            throw new AppAttestVerificationException("CBOR_SIZE_LIMIT");
        }
        try (CBORParser parser = FACTORY.createParser(input, offset, input.length - offset))
        {
            JsonToken first = nextToken(parser, input, offset);
            Object value = value(parser, input, offset, first, 0, new int[1], integerKeys);
            int end = Math.toIntExact(parser.getCurrentLocation().getByteOffset());
            if (end <= offset || end > input.length || (wholeInput && nextToken(parser, input, offset) != null))
            {
                throw new AppAttestVerificationException("CBOR_TRAILING_DATA");
            }
            return new Item(value, end);
        }
        catch (AppAttestVerificationException ex)
        {
            throw ex;
        }
        catch (IOException | RuntimeException ex)
        {
            throw new AppAttestVerificationException("MALFORMED_CBOR", ex);
        }
    }

    private static Object value(CBORParser parser, byte[] input, int inputOffset, JsonToken token, int depth,
                                int[] count, boolean integerKeys) throws IOException
    {
        if (token == null || depth > MAX_DEPTH || ++count[0] > MAX_ITEMS)
        {
            throw new AppAttestVerificationException("CBOR_STRUCTURE_LIMIT");
        }
        CborTokenGuard.Header header = CborTokenGuard.current(parser, input, inputOffset);
        if (header.major == 6 || parser.getCurrentTag() != -1)
        {
            throw new AppAttestVerificationException("UNSUPPORTED_CBOR_TAG");
        }
        if (token == JsonToken.START_OBJECT)
        {
            Map<String, Object> map = new LinkedHashMap<>();
            while (nextToken(parser, input, inputOffset) != JsonToken.END_OBJECT)
            {
                if (parser.currentToken() != JsonToken.FIELD_NAME || map.size() >= MAX_CONTAINER_ITEMS)
                {
                    throw new AppAttestVerificationException("CBOR_MAP_LIMIT");
                }
                // Jackson exposes CBOR numeric map labels as strings. Inspect only the native
                // token major type at its Jackson-provided boundary, never decode CBOR ourselves.
                CborTokenGuard.Header key = CborTokenGuard.current(parser, input, inputOffset);
                if (parser.getCurrentTag() != -1 || (integerKeys ? key.major > 1 : key.major != 3))
                {
                    throw new AppAttestVerificationException("CBOR_MAP_KEY_TYPE");
                }
                String name = parser.getCurrentName();
                if (integerKeys)
                {
                    long label = key.major == 0 ? key.argument : -1L - key.argument;
                    if ((label != 1 && label != 3 && label != -1 && label != -2 && label != -3)
                            || !Long.toString(label).equals(name))
                    {
                        throw new AppAttestVerificationException("UNSUPPORTED_COSE_LABEL");
                    }
                }
                else { key.textEquals(name); }
                if (name.length() > 64 || map.containsKey(name))
                {
                    throw new AppAttestVerificationException("CBOR_DUPLICATE_OR_LONG_KEY");
                }
                map.put(name, value(parser, input, inputOffset, nextToken(parser, input, inputOffset), depth + 1, count, false));
            }
            return map;
        }
        if (token == JsonToken.START_ARRAY)
        {
            List<Object> array = new ArrayList<>();
            while (nextToken(parser, input, inputOffset) != JsonToken.END_ARRAY)
            {
                if (array.size() >= MAX_CONTAINER_ITEMS)
                {
                    throw new AppAttestVerificationException("CBOR_ARRAY_LIMIT");
                }
                array.add(value(parser, input, inputOffset, parser.currentToken(), depth + 1, count, false));
            }
            return array;
        }
        if (token == JsonToken.VALUE_STRING)
        {
            String text = parser.getText();
            header.textEquals(text);
            if (text.length() > 256)
            {
                throw new AppAttestVerificationException("CBOR_TEXT_LIMIT");
            }
            return text;
        }
        if (token == JsonToken.VALUE_EMBEDDED_OBJECT)
        {
            byte[] bytes = parser.getBinaryValue();
            if (bytes == null || bytes.length > 32768)
            {
                throw new AppAttestVerificationException("CBOR_BINARY_LIMIT");
            }
            return bytes;
        }
        if (token == JsonToken.VALUE_NUMBER_INT)
        {
            long number = parser.getLongValue();
            header.integerEquals(number);
            return number;
        }
        throw new AppAttestVerificationException("UNSUPPORTED_CBOR_VALUE_TYPE");
    }

    private static JsonToken nextToken(CBORParser parser, byte[] input, int inputOffset) throws IOException
    {
        CborTokenGuard.beforeNext(parser, input, inputOffset);
        return parser.nextToken();
    }

    static final class Item
    {
        final Object value;
        final int end;
        Item(Object value, int end) { this.value = value; this.end = end; }
    }
}
