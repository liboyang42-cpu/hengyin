package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.domain.ThemeStateKey;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Isolated ENTRY-only draft evaluation. This is not a Spring service or an authorization boundary.
 * Every call creates fresh in-memory state and uses the existing configuration/engine methods.
 * Declared references describe the caller's draft; they do not prove ownership or grant access.
 *
 * No route advancement, completion/story-ending selection, budget settlement, rescue, clock/step
 * simulation, durable inventory/rewards, receipts or message delivery is performed. A future
 * authorized adapter must supply the real draft/reference context; no HTTP adapter is provided.
 */
public final class ThemeRuntimeDraftPreview {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final int MAX_CONFIG_CHARS = 200000;
    public static final int MAX_RULES_BYTES = 16384;
    public static final int MAX_JSON_DEPTH = 64;
    public static final int MAX_NODE_DECLARATIONS = 200;
    public static final int MAX_STATE_DECLARATIONS = 256;
    public static final int MAX_OUTPUT_CHARS = 1000000;
    // Preview resource/representation bounds, not changes to the published rotation policy.
    public static final long MAX_EPOCH_MILLIS = 253402300799999L;
    public static final int MAX_RUN_INDEX = Integer.MAX_VALUE - 50;

    /**
     * Evaluate initial state, optionally after entering one configured chapter by its stable index.
     * The explicit epoch/run index go unchanged to ThemeRuntimeConfig.freeze; no system clock is read.
     * The chapter argument is a hypothetical entry instruction, not proof of live route eligibility.
     */
    public Result evaluate(String themeJson, String rulesJson, Set<Long> declaredNodes,
                           Set<String> declaredStateKeys, int runIndex, long epochMillis,
                           Integer chapterIndex) {
        if (themeJson == null || themeJson.length() > MAX_CONFIG_CHARS || themeJson.trim().isEmpty())
            throw new IllegalArgumentException("预览需要不超过 200000 字符的主题草稿配置");
        if (rulesJson != null && (rulesJson.length() > MAX_RULES_BYTES
                || rulesJson.getBytes(StandardCharsets.UTF_8).length > MAX_RULES_BYTES))
            throw new IllegalArgumentException("预览旅程规则不能超过 16 KiB");
        if (runIndex < 0 || runIndex > MAX_RUN_INDEX || epochMillis < 0 || epochMillis > MAX_EPOCH_MILLIS)
            throw new IllegalArgumentException("预览局序号或时间超出可表示范围");
        if (chapterIndex != null && (chapterIndex < 0 || chapterIndex > 199))
            throw new IllegalArgumentException("预览章节序号须在 0 到 199 之间");
        checkJsonDepth(themeJson);
        if (rulesJson != null && !rulesJson.trim().isEmpty()) checkJsonDepth(rulesJson);
        Set<Long> nodes = copyNodes(declaredNodes);
        Set<String> keys = copyStateKeys(declaredStateKeys);
        ObjectNode parsed = ThemeRuntimeConfig.parseDraft(themeJson);
        ThemeRuntimeConfig.assertReferences(parsed, nodes, keys);
        JourneyRules rules = JourneyRules.parse(rulesJson);
        ObjectNode frozen = ThemeRuntimeConfig.freeze(parsed, runIndex, epochMillis);
        JsonNode chapter = null;
        if (chapterIndex != null) {
            for (JsonNode row : frozen.path("chapters"))
                if (row.path("index").asInt() == chapterIndex.intValue()) chapter = row;
            if (chapter == null) throw new IllegalArgumentException("所选预览章节未配置主题运行参数");
        }

        TopicRouteEngine.State state = new TopicRouteEngine.State();
        state.setStatus("ACTIVE");
        // Same guards/initial values as the existing runtime applyJourneyInitials and team initializer.
        if (rules.hasRuntimeState()) {
            JourneyInitialState.initialize(rules, state);
            if (rules.getHp().isEnabled()) state.getVariables().put("sys.hp", rules.getHp().getInit());
            if (rules.getLuck().isEnabled()) state.getVariables().put("sys.luck", rules.getLuck().getInit());
        }
        ThemeRuntimeEngine engine = new ThemeRuntimeEngine();
        engine.initialize(frozen, state);
        if (chapter != null) engine.chapterStart(frozen, state, rules, chapter, epochMillis);
        engine.reconcileHeld(frozen, state, rules);
        engine.events(frozen, state, rules);

        ObjectNode output = JSON.createObjectNode();
        output.put("previewOnly", true);
        output.put("scope", "ENTRY_ONLY");
        output.putArray("excluded").add("ROUTE_ADVANCE").add("COMPLETION_AND_STORY_ENDINGS")
                .add("BUDGET_SETTLEMENT").add("RESCUE").add("CLOCK_AND_STEP_SIMULATION")
                .add("DURABLE_REWARDS_AND_MESSAGES");
        if (frozen.has("sceneId")) {
            output.put("sceneId", frozen.path("sceneId").asText());
            output.put("sceneName", frozen.path("sceneName").asText());
        }
        if (chapterIndex != null) output.put("chapterIndex", chapterIndex);
        ObjectNode values = output.putObject("entryValues");
        for (String key : new String[] {"sys.hp", "sys.luck", "sys.actions"})
            if (state.getVariables().containsKey(key)) values.set(key, JSON.valueToTree(state.getVariables().get(key)));
        output.set("projection", engine.view(frozen, state));
        if (output.toString().length() > MAX_OUTPUT_CHARS)
            throw new IllegalArgumentException("预览输出超过有界投影大小");
        return new Result(output);
    }

    private static Set<Long> copyNodes(Set<Long> source) {
        if (source == null || source.size() > MAX_NODE_DECLARATIONS)
            throw new IllegalArgumentException("预览节点声明须为最多 200 项的集合");
        Set<Long> copy = new HashSet<Long>();
        for (Long id : source) {
            if (id == null || id <= 0) throw new IllegalArgumentException("预览节点声明须为正整数");
            copy.add(id);
        }
        return copy;
    }

    private static Set<String> copyStateKeys(Set<String> source) {
        if (source == null || source.size() > MAX_STATE_DECLARATIONS)
            throw new IllegalArgumentException("预览状态声明须为最多 256 项的集合");
        Set<String> copy = new HashSet<String>();
        for (String key : source) {
            if (key == null || !ThemeStateKey.KEY_NAME.matcher(key).matches())
                throw new IllegalArgumentException("预览状态声明格式不正确");
            copy.add(key);
        }
        return copy;
    }

    /** Streaming depth/resource guard only; domain validation remains in the existing validators. */
    private static void checkJsonDepth(String raw) {
        try (JsonParser parser = JSON.getFactory().createParser(raw)) {
            int depth = 0, roots = 0;
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) depth--;
                else {
                    if (depth == 0 && ++roots > 1) throw new IllegalArgumentException("预览配置只能包含一个 JSON 值");
                    if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                        if (++depth > MAX_JSON_DEPTH) throw new IllegalArgumentException("预览配置嵌套超过 64 层");
                    }
                }
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("预览配置不是合法 JSON", error);
        }
    }

    /** Carries no live State/config reference and never returns the internally held JSON tree. */
    public static final class Result {
        private final ObjectNode output;
        private Result(ObjectNode output) { this.output = output.deepCopy(); }
        public ObjectNode toJson() { return output.deepCopy(); }
    }
}
