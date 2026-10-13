package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** 来源只记录作者选用的内容副本，不赋予任务、奖励或权限。 */
public final class ConfigDraftSource {
    private ConfigDraftSource() { }

    public static void validate(JsonNode source) {
        if (source.isMissingNode()) return;
        fields(source, "schemaVersion", "route", "style", "world", "templateCopies");
        if (source.toString().length() > 60000 || !source.path("schemaVersion").isInt()
                || source.path("schemaVersion").asInt() != 1) fail();
        if (!source.has("route") && !source.has("style") && !source.has("world")) fail();
        if (source.has("route")) {
            JsonNode route = source.get("route");
            fields(route, "id", "updatedAt", "name", "city", "previewImg", "routeGeometry", "defaultCategoryIds", "nodes");
            identity(route); strings(route, "name", "city", "previewImg", "routeGeometry", "defaultCategoryIds");
            for (JsonNode node : ThemeRuntimeConfig.rows(route, "nodes", 200)) {
                fields(node, "id", "name", "address", "longitude", "latitude", "imgUrl", "nodeTime", "playTemplateId");
                positive(node.path("id")); strings(node, "name", "address", "longitude", "latitude", "imgUrl");
                if (node.has("playTemplateId")) positive(node.get("playTemplateId"));
                if (node.has("nodeTime") && (!node.get("nodeTime").isIntegralNumber() || node.path("nodeTime").asLong() < 0)) fail();
            }
        }
        if (source.has("style")) {
            JsonNode style = source.get("style");
            fields(style, "id", "updatedAt", "name", "coverImg", "toneTokens", "defaultTitle", "defaultSubtitle", "defaultDesc", "defaultCategoryIds");
            identity(style); strings(style, "name", "coverImg", "defaultTitle", "defaultSubtitle", "defaultDesc", "defaultCategoryIds");
            JsonNode tokens = style.path("toneTokens");
            fields(tokens, "--cy-color-bg-surface", "--cy-color-text-primary", "--cy-color-text-secondary", "--cy-color-border-subtle", "--cy-color-brand");
            tokens.elements().forEachRemaining(value -> { if (!value.isTextual() || !value.asText().matches("#[0-9a-fA-F]{6}")) fail(); });
        }
        if (source.has("world")) {
            JsonNode world = source.get("world");
            fields(world, "id", "updatedAt", "name", "worldCode", "subtitle", "cityCode", "coverUrl", "intro", "chapters", "quests");
            identity(world); strings(world, "name", "worldCode", "subtitle", "cityCode", "coverUrl", "intro");
            for (JsonNode chapter : ThemeRuntimeConfig.rows(world, "chapters", 100)) {
                fields(chapter, "id", "name", "cityCode", "coverUrl", "intro");
                positive(chapter.path("id")); strings(chapter, "name", "cityCode", "coverUrl", "intro");
            }
            for (JsonNode quest : ThemeRuntimeConfig.rows(world, "quests", 200)) {
                fields(quest, "id", "name", "chapterId", "questType", "targetBizType", "targetBizId", "rewardExp");
                positive(quest.path("id")); strings(quest, "name", "questType", "targetBizType");
                for (String key : Arrays.asList("chapterId", "targetBizId")) if (quest.has(key)) positive(quest.get(key));
                if (quest.has("rewardExp") && (!quest.get("rewardExp").isIntegralNumber() || quest.path("rewardExp").asLong() < 0)) fail();
            }
        }
        if (source.has("templateCopies")) {
            JsonNode copies = source.get("templateCopies");
            if (!copies.isObject() || copies.size() > 200 || !source.has("route")) fail();
            copies.fields().forEachRemaining(row -> {
                if (!row.getKey().matches("[1-9][0-9]{0,17}")) fail();
                positive(row.getValue());
            });
        }
    }

    public static void assertSourceUnchanged(String existingRaw, String incomingRaw) {
        // 缺字段沿用既有 JSON 的动态更新语义；显式提交则必须保留来源。
        if (incomingRaw == null) return;
        JsonNode existing = ThemeRuntimeConfig.parseDraft(existingRaw);
        JsonNode incoming = ThemeRuntimeConfig.parseDraft(incomingRaw);
        JsonNode before = existing == null ? null : existing.get("configSource");
        JsonNode after = incoming == null ? null : incoming.get("configSource");
        if (!java.util.Objects.equals(before, after))
            throw new IllegalArgumentException("配置来源仅可由新草稿选用，已保存内容保留原版本");
    }

    private static void identity(JsonNode node) {
        positive(node.path("id"));
        if (!node.path("updatedAt").isTextual() || node.path("updatedAt").asText().length() > 64) fail();
    }
    private static void strings(JsonNode node, String... keys) {
        for (String key : keys) if (node.has(key) && (!node.get(key).isTextual() || node.get(key).asText().length() > 10000)) fail();
    }
    private static void positive(JsonNode node) {
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.asLong() <= 0) fail();
    }
    private static void fields(JsonNode node, String... names) {
        if (!node.isObject()) fail();
        Set<String> allowed = new HashSet<>(Arrays.asList(names));
        node.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) fail(); });
    }
    private static void fail() { throw new IllegalArgumentException("配置来源字段、类型或尺寸不合法"); }
}
