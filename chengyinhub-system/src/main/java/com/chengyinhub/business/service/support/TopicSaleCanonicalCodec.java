package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.snapshot.TopicSaleCanonicalV1;
import com.fasterxml.jackson.databind.ObjectMapper;

/** canonical JSON 的单一编解码器；未知 schema 一律拒绝。 */
public final class TopicSaleCanonicalCodec {
    private static final ObjectMapper JSON = new ObjectMapper();

    private TopicSaleCanonicalCodec() {}

    /** 严格版:产品类型未知时不豁免任何一项。 */
    public static String write(TopicSaleCanonicalV1 value) {
        return write(value, null);
    }

    /** 按产品类型收口,见 {@link TopicSaleCanonicalV1#validateComplete(Integer)}。 */
    public static String write(TopicSaleCanonicalV1 value, Integer productType) {
        if (value == null) throw new IllegalStateException("SALE_SNAPSHOT_MISSING_STORE_COUNT");
        value.validateComplete(productType);
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("canonical15 序列化失败", e);
        }
    }

    public static String writeDraft(TopicSaleCanonicalV1 value) {
        if (value == null) throw new IllegalStateException("SALE_SNAPSHOT_MISSING_STORE_COUNT");
        value.validateDraft();
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("canonical15 草稿序列化失败", e);
        }
    }

    /** 严格版:产品类型未知时不豁免任何一项。 */
    public static TopicSaleCanonicalV1 read(String schemaVersion, String json) {
        return read(schemaVersion, json, null);
    }

    /** 按产品类型收口,见 {@link TopicSaleCanonicalV1#validateComplete(Integer)}。 */
    public static TopicSaleCanonicalV1 read(String schemaVersion, String json, Integer productType) {
        TopicSaleCanonicalV1 value = parse(schemaVersion, json);
        value.validateComplete(productType);
        return value;
    }

    public static TopicSaleCanonicalV1 readDraft(String schemaVersion, String json) {
        TopicSaleCanonicalV1 value = parse(schemaVersion, json);
        value.validateDraft();
        return value;
    }

    private static TopicSaleCanonicalV1 parse(String schemaVersion, String json) {
        if (!TopicSaleCanonicalV1.SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalStateException("SALE_SNAPSHOT_UNKNOWN_SCHEMA_VERSION:" + schemaVersion);
        }
        try {
            TopicSaleCanonicalV1 value = JSON.readValue(json, TopicSaleCanonicalV1.class);
            return value;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("canonical15 不可解析", e);
        }
    }

    /** 严格版:产品类型未知时不豁免任何一项。 */
    public static void assertFieldsEqual(TopicSaleCanonicalV1 expected, TopicSaleCanonicalV1 actual,
                                         String errorPrefix) {
        assertFieldsEqual(expected, actual, errorPrefix, null);
    }

    /** 按产品类型收口,见 {@link TopicSaleCanonicalV1#validateComplete(Integer)}。 */
    public static void assertFieldsEqual(TopicSaleCanonicalV1 expected, TopicSaleCanonicalV1 actual,
                                         String errorPrefix, Integer productType) {
        expected.validateComplete(productType);
        actual.validateComplete(productType);
        for (String field : TopicSaleCanonicalV1.FIELD_NAMES) {
            if (!expected.field(field).toString().equals(actual.field(field).toString())) {
                throw new IllegalStateException(errorPrefix + field);
            }
        }
    }
}
