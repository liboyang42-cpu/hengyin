package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Set;

/** Author assertions only: registry definitions are never written from journeyRules. */
public final class AuthorAttributeBinding {
    private static final ObjectMapper JSON = new ObjectMapper();
    private AuthorAttributeBinding() { }

    public static ServiceException conflict(String code, String message, int status) {
        return new ServiceException(message, status).setDetailMessage(code)
                .setData(java.util.Collections.singletonMap("errorCode", code));
    }

    public static boolean hasBinding(String raw) {
        if (raw == null || raw.isBlank()) return false;
        try { return JSON.readTree(raw).has("attributeBinding"); }
        catch (Exception invalid) { throw new ServiceException("旅程数值规则无法解析", 400); }
    }

    public static String version(JsonNode value) {
        if (value == null || !value.isTextual() || !value.textValue().matches("[1-9][0-9]{0,18}")) {
            throw conflict("ATTRIBUTE_REGISTRY_VERSION_CONFLICT", "注册版本缺失或不规范，请重新读取", 409);
        }
        try { if (Long.parseLong(value.textValue()) > 0L) return value.textValue(); }
        catch (NumberFormatException invalid) { /* rejected below */ }
        throw conflict("ATTRIBUTE_REGISTRY_VERSION_CONFLICT", "注册版本超出范围，请重新读取", 409);
    }

    public static ObjectNode normalize(JsonNode source) {
        if (source == null || !source.isObject() || !source.path("schemaVersion").isIntegralNumber()
                || !source.path("schemaVersion").canConvertToInt() || source.path("schemaVersion").intValue() != 1) {
            throw conflict("STATE_KEY_INVALID", "属性引用格式不正确", 400);
        }
        source.fieldNames().forEachRemaining(key -> {
            if (!java.util.Set.of("schemaVersion", "registryVersion", "enabledKeys").contains(key)) {
                throw conflict("STATE_KEY_INVALID", "属性引用包含未知字段：" + key, 400);
            }
        });
        ObjectNode out = JSON.createObjectNode(); out.put("schemaVersion", 1);
        out.put("registryVersion", version(source.get("registryVersion")));
        JsonNode keys = source.path("enabledKeys");
        if (!keys.isArray() || keys.size() > 32) throw conflict("STATE_KEY_INVALID", "启用属性最多32项", 400);
        Set<String> unique = new HashSet<>(); ArrayNode enabled = out.putArray("enabledKeys");
        for (JsonNode key : keys) {
            if (!key.isTextual() || !ThemeStateKey.KEY_NAME.matcher(key.textValue()).matches()
                    || !unique.add(key.textValue())) throw conflict("STATE_KEY_INVALID", "启用属性键无效或重复", 400);
            enabled.add(key.textValue());
        }
        return out;
    }

    public static ArrayNode projection(JsonNode source) {
        if (!source.isArray() || source.size() > 32) throw conflict("STATE_KEY_INVALID", "属性投影最多32项", 400);
        ArrayNode out = JSON.createArrayNode(); Set<String> keys = new HashSet<>();
        for (JsonNode row : source) {
            String key = row.path("key").asText();
            if (!row.isObject() || !ThemeStateKey.KEY_NAME.matcher(key).matches() || !keys.add(key)
                    || !row.path("label").isTextual() || row.path("label").asText().isBlank()
                    || row.path("label").asText().length() > 128 || !row.path("visible").isBoolean()) {
                throw conflict("STATE_KEY_INVALID", "属性投影不完整：" + key, 400);
            }
            for (String field : java.util.List.of("initial", "min", "max")) {
                if (!row.path(field).isIntegralNumber() || !row.path(field).canConvertToInt()) {
                    throw conflict("STATE_KEY_RANGE", "属性投影须使用整数：" + key, 400);
                }
            }
            int min = row.path("min").intValue(), max = row.path("max").intValue(), initial = row.path("initial").intValue();
            if (min < -10000 || max > 10000 || min > initial || initial > max
                    || ((key.startsWith("tag.") || key.startsWith("clue.")) && (min != 0 || max != 1))) {
                throw conflict("STATE_KEY_RANGE", "属性投影范围无效：" + key, 400);
            }
            ObjectNode target = out.addObject();
            for (String field : java.util.List.of("key", "label", "initial", "min", "max", "visible")) target.set(field, row.get(field));
        }
        return out;
    }

    public static ObjectNode validateDefinition(JsonNode source) {
        ObjectNode root = JSON.createObjectNode(); root.set(ThemeAttributeDefinition.KEY, source);
        ThemeAttributeDefinition.Snapshot snapshot = ThemeAttributeDefinition.read(root.toString());
        if (snapshot == null || !snapshot.complete) throw new IllegalArgumentException("完整属性定义不可用");
        return ((ObjectNode) source).deepCopy();
    }

    /** Caller already holds the topic row in its save/publication transaction. */
    public static String freeze(ThemeStateKeyService registry, Long topicId, Long requestConfigVersion,
                                long currentVersion, long targetVersion, String raw, boolean draft) {
        if (raw == null || raw.isBlank()) return raw;
        final ObjectNode root;
        try { root = (ObjectNode) JSON.readTree(raw); }
        catch (Exception invalid) { throw new ServiceException("旅程数值规则无法解析", 400); }
        JsonNode supplied = root.get("attributeBinding");
        if (supplied == null) {
            // Old drafts stay editable without silently importing/overwriting any registered definition.
            root.remove("attributeDefinition");
            if (!draft && root.path("attributes").size() > 0) {
                throw conflict("ATTRIBUTE_REGISTRY_VERSION_CONFLICT", "旧属性定义需作者确认注册版本后再发布", 409);
            }
            return root.toString();
        }
        ObjectNode binding = normalize(supplied);
        if (requestConfigVersion == null || requestConfigVersion != currentVersion) {
            throw conflict("ATTRIBUTE_REGISTRY_VERSION_CONFLICT", "主题配置版本已变化，请重新确认属性定义", 409);
        }
        Set<String> enabled = new HashSet<>(), asserted = new HashSet<>();
        binding.path("enabledKeys").forEach(key -> enabled.add(key.asText()));
        root.path("attributes").forEach(row -> asserted.add(row.path("key").asText()));
        if (!enabled.equals(asserted)) throw conflict("ATTRIBUTE_DEFINITION_CONFLICT", "启用键与作者确认的属性投影不一致", 400);
        if (registry == null) throw new IllegalStateException("属性注册服务不可用");
        root.remove("attributeDefinition"); // A client-provided snapshot is never authoritative.
        JourneyRules rules = JourneyRules.parse(root.toString());
        if (!enabled.isEmpty() && !rules.isStateEnabled()) {
            throw conflict("STATE_KEY_INVALID", "启用属性需要开启主题状态", 400);
        }
        ObjectNode result;
        try { result = registry.freezeDefinition(topicId, targetVersion, binding.path("registryVersion").asText(), rules); }
        catch (ServiceException error) {
            if (error.getDetailMessage() != null) error.setData(java.util.Collections.singletonMap("errorCode", error.getDetailMessage()));
            throw error;
        }
        ObjectNode definition = validateDefinition(result.has("attributeDefinition") ? result.get("attributeDefinition") : result);
        if (!Long.toString(targetVersion).equals(definition.path("configVersion").asText())
                || !binding.path("registryVersion").asText().equals(definition.path("registryVersion").asText())) {
            throw new IllegalStateException("属性冻结返回版本与保存版本不一致");
        }
        Set<String> frozenKeys = new HashSet<>(); definition.path("attributes").forEach(row -> frozenKeys.add(row.path("key").asText()));
        if (!enabled.equals(frozenKeys)) throw new IllegalStateException("属性冻结启用键不一致");
        ArrayNode projection = root.putArray("attributes");
        for (JsonNode row : definition.path("attributes")) {
            ObjectNode attribute = projection.addObject();
            for (String field : java.util.List.of("key", "label", "initial", "min", "max", "visible")) attribute.set(field, row.get(field));
        }
        root.set("attributeDefinition", definition);
        return root.toString();
    }
}
