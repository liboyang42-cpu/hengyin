package com.chengyinhub.business.service.support;

import com.chengyinhub.business.util.AdvancedGamePublicProjection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 四类题材（都市谜案 / 怪谈追踪 / 亲子观察 / 城市寻宝）的**配置表达映射**，全部走真实生产类：
 * {@link AdvancedGameConfigValidator}（作者保存/发布形状闸）、{@link GameplaySupportMatrix}
 * （通用结算能力表）、{@link AdvancedGamePublicProjection}（玩家安全投影）、
 * {@link ThemeRuntimeConfig}（存稿 draft / 发布 strict、按局冻结、跨主题引用）。
 *
 * <p>本类不发明题目、剧情、地点或答案，也不放磁盘资源包：夹具只是把四类题材在原卡里点名的
 * 交互（CT-06/CT-07 的最终单选指认、亲子与寻宝依赖的观察排序/配对/分类、图片找物）写成最小形状，
 * 用来证明「这些题材用现有 schema 就能表达」，以及哪些用法会被原闸门拒绝。
 *
 * <p>CT-07 {@code accusation} 段与 BE-16 {@code hintLadder} 已由 G0 接入本类所调的两个生产闸门
 * （{@link AccusationTemplateSupport}、{@code AdvancedGameConfigValidator#validateHintLadder}）。
 * 本类断言的是配置表达，不是端到端发布验收。
 */
class ThemeCategoryConfigMappingTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long NODE_ID = 11L;

    /**
     * T01 都市谜案 / T02 怪谈追踪 的结案：人物或目标卡片就是原选项问答的选项，
     * 玩家只交 optionId，对错仍由 {@code qa.options[].correct} 决定。
     * 保存/发布形状闸通过 → 通用结算承认它是完成证明 → 投影里没有 correct、没有选项反馈。
     */
    @Test
    void pickThemesReuseOriginalQaAndNeverProjectTheAnswer() throws Exception {
        String raw = """
                {"schemaVersion":1,"qa":{"enabled":true,"mode":"PICK","maxTries":0,
                  "title":"核对后，哪一份记录与实际不符？",
                  "options":[
                    {"id":"p1","label":"甲的记录","correct":false,"fb":"甲的时间对得上。"},
                    {"id":"p2","label":"乙的记录","correct":true},
                    {"id":"p3","label":"丙的记录","correct":false}]}}
                """;
        String normalized = new AdvancedGameConfigValidator().validateAndNormalize(raw);
        JsonNode block = JSON.readTree(normalized);

        // 通用结算能力表把 qaPick 当完成证明，四类题材的结案题都靠它，不需要每套引擎。
        JsonNode pickRow = null;
        for (JsonNode row : GameplaySupportMatrix.view().path("types")) {
            if ("qaPick".equals(row.path("key").asText())) pickRow = row;
        }
        assertNotNull(pickRow);
        assertTrue(pickRow.path("completionProof").asBoolean());
        assertEquals("PICK", pickRow.path("mode").asText());
        GameplaySupportMatrix.validateNode(theme("PLAYER"), NODE_ID, "结案", block, 0);

        // 玩家投影：只给 id 与文案；correct / fb 一个字都不下发。
        JsonNode safe = JSON.readTree(AdvancedGamePublicProjection.safeConfigJson(normalized));
        JsonNode options = safe.path("qa").path("options");
        assertEquals(3, options.size());
        for (JsonNode option : options) {
            assertFalse(option.has("correct"), "投影不能带 correct");
            assertFalse(option.has("fb"), "投影不能带选项反馈");
            assertFalse(option.path("id").asText().isEmpty());
        }
        assertFalse(safe.path("qa").has("answerText"), "投影不能带文字答案");
    }

    /**
     * T03 亲子观察 / T05 城市寻宝 都可能先做「图中找物」，但 {@code hiddenObject} 在能力表里是
     * POI/呈现类，不是通用结算证明：只配它，存稿与发布都拦；节点必须再配一个可判定的原题型。
     * 这条守住「到达或找到不等于完成」。
     */
    @Test
    void objectFindIsNotASettlementProofForObservationThemes() throws Exception {
        JsonNode hiddenRow = null;
        for (JsonNode row : GameplaySupportMatrix.view().path("types")) {
            if ("hidden".equals(row.path("key").asText())) hiddenRow = row;
        }
        assertNotNull(hiddenRow);
        assertFalse(hiddenRow.path("completionProof").asBoolean());

        ObjectNode config = (ObjectNode) JSON.readTree("""
                {"schemaVersion":1,"hiddenObject":{"enabled":true,"title":"在图上找出三处细节",
                  "imageUrl":"https://example.test/street.png",
                  "hotspots":[{"label":"一"},{"label":"二"},{"label":"三"}]}}
                """);
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> GameplaySupportMatrix.validateNode(theme("PLAYER"), NODE_ID, "图中找物", config, 0));
        assertTrue(refusal.getMessage().contains("完成证明"), refusal.getMessage());
        // 存稿也不放宽：这不是「还没填完」，而是这类玩法本来就不产生通用结算证明。
        assertThrows(IllegalArgumentException.class,
                () -> GameplaySupportMatrix.validateNode(theme("PLAYER"), NODE_ID, "图中找物", config, 0, true));
    }

    /**
     * T03 亲子观察 与 T05 城市寻宝 的现场观察结论，可以直接用原排序/配对/分类判定，
     * 不需要图片专属判题器；三者都在通用结算能力表里是完成证明。
     */
    @Test
    void observationSortingPairingAndClassificationAreRealProofs() throws Exception {
        String[] blocks = {
                """
                {"schemaVersion":1,"sort":{"enabled":true,"prompt":"按先后顺序排列","maxAttempts":0,
                  "items":[{"id":"a","label":"一"},{"id":"b","label":"二"},{"id":"c","label":"三"}],
                  "answerOrder":["a","b","c"]}}
                """,
                """
                {"schemaVersion":1,"match":{"enabled":true,"prompt":"把现象与说法配对",
                  "left":[{"id":"l1","label":"甲"},{"id":"l2","label":"乙"}],
                  "right":[{"id":"r1","label":"一"},{"id":"r2","label":"二"}],
                  "pairs":[["l1","r1"],["l2","r2"]]}}
                """,
                """
                {"schemaVersion":1,"classify":{"enabled":true,"prompt":"把看到的分到两类",
                  "bins":[{"id":"x","label":"甲类"},{"id":"y","label":"乙类"}],
                  "items":[{"id":"a","label":"一"},{"id":"b","label":"二"}],
                  "answer":{"a":"x","b":"y"}}}
                """
        };
        for (String raw : blocks) {
            String normalized = new AdvancedGameConfigValidator().validateAndNormalize(raw);
            JsonNode block = JSON.readTree(normalized);
            GameplaySupportMatrix.validateNode(theme("PLAYER"), NODE_ID, "现场观察", block, 0);
            // 答案同样留在服务端：投影形状里没有 answerOrder / pairs / answer。
            JsonNode safe = JSON.readTree(AdvancedGamePublicProjection.safeConfigJson(normalized));
            assertFalse(safe.toString().contains("answerOrder"));
            assertFalse(safe.toString().contains("\"pairs\""));
            assertFalse(safe.path("classify").has("answer"));
        }
    }

    /**
     * 老规则：普通选项问答必须允许免费重答（maxTries=0）。四类题材的结案题在未显式升到新规则版本前
     * 不能静默改成有限次数。
     */
    @Test
    void legacyFreeRetryStaysOnForPickBeforeAnyNewRuleVersion() throws Exception {
        JsonNode block = JSON.readTree("""
                {"schemaVersion":1,"qa":{"enabled":true,"mode":"PICK","maxTries":2,
                  "title":"哪一份记录与实际不符？",
                  "options":[{"id":"p1","label":"甲","correct":true},{"id":"p2","label":"乙","correct":false}]}}
                """);
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> GameplaySupportMatrix.validateNode(theme("PLAYER"), NODE_ID, "结案", block, 0));
        assertTrue(refusal.getMessage().contains("免费重答"), refusal.getMessage());
    }

    /**
     * 存稿 vs 发布：作者只填了一半（物资没有名字）时能存草稿，发布严格校验拒绝。
     * 这是「关键绑定缺失允许存稿、拒发布」在既有闸门里的真实分界，不需要新增草稿状态。
     */
    @Test
    void incompleteThemeSavesAsDraftButPublishRefuses() {
        // 有物资条目就必须同时给容量；这里只缺物资名字，其余是作者能存下的最小合法草稿。
        String halfWritten =
                "{\"schemaVersion\":1,\"capacity\":1,\"items\":[{\"id\":\"clue_1\",\"kind\":\"ITEM\"}]}";
        ObjectNode draft = ThemeRuntimeConfig.parseDraft(halfWritten);
        assertNotNull(draft, "缺名字的物资应能存稿");
        assertEquals("ITEM", draft.path("items").path(0).path("kind").asText());

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> ThemeRuntimeConfig.parse(halfWritten));
        assertTrue(refusal.getMessage().contains("name"), refusal.getMessage());
    }

    @Test
    void anotherCitysNodesAndFallbacksCannotBeBorrowed() {
        String otherCityQuest = """
                {"schemaVersion":1,"gameplayRuntime":{"runtimeScope":"PLAYER","survival":{},
                  "acts":[{"actKey":"a1","questKeys":["q1"],"completionRule":{"type":"ALL"}}],
                  "quests":[{"questKey":"q1","actKey":"a1","nodeId":%d,"locationKey":"slot_gate",
                             "settlementSlot":"s1"%s}]}}
                """;
        ObjectNode own = ThemeRuntimeConfig.parseDraft(String.format(otherCityQuest, NODE_ID, ""));
        assertNotNull(own);
        // 本城节点集合 {11}：自身引用通过。
        ThemeRuntimeConfig.assertReferences(own, Set.of(NODE_ID), Set.of());

        ObjectNode foreignNode = ThemeRuntimeConfig.parseDraft(String.format(otherCityQuest, 99L, ""));
        IllegalArgumentException crossed = assertThrows(IllegalArgumentException.class,
                () -> ThemeRuntimeConfig.assertReferences(foreignNode, Set.of(NODE_ID), Set.of()));
        assertTrue(crossed.getMessage().contains("外来节点"), crossed.getMessage());

        ObjectNode foreignFallback = ThemeRuntimeConfig.parseDraft(
                String.format(otherCityQuest, NODE_ID, ",\"approvedFallbackNodeIds\":[12]"));
        IllegalArgumentException borrowed = assertThrows(IllegalArgumentException.class,
                () -> ThemeRuntimeConfig.assertReferences(foreignFallback, Set.of(NODE_ID), Set.of()));
        assertTrue(borrowed.getMessage().contains("备用点不属于本主题"), borrowed.getMessage());
    }

    /**
     * 冻结调用：同一份题材配置按局冻结时，取到的是该局自己的场景节点配置，原配置不被改写。
     * 两条城市实例因此各自持有独立副本，而不是共享一份可变对象。
     */
    @Test
    void frozenRunBindsOnlyItsOwnSceneNodes() throws Exception {
        String shared = """
                {"schemaVersion":1,"rotation":"ROUND_ROBIN","currentScene":"scene_a",
                 "scenes":[
                   {"id":"scene_a","name":"甲城","nodeConfigs":{"11":{"schemaVersion":1,"qa":{
                     "enabled":true,"mode":"PICK","title":"甲城现场核对","maxTries":0,
                     "options":[{"id":"p1","label":"甲说","correct":true},{"id":"p2","label":"乙说","correct":false}]}}}},
                   {"id":"scene_b","name":"乙城","nodeConfigs":{"11":{"schemaVersion":1,"qa":{
                     "enabled":true,"mode":"PICK","title":"乙城现场核对","maxTries":0,
                     "options":[{"id":"p1","label":"丙说","correct":false},{"id":"p2","label":"丁说","correct":true}]}}}}]}
                """;
        ObjectNode config = ThemeRuntimeConfig.parseDraft(shared);
        assertNotNull(config);

        ObjectNode first = ThemeRuntimeConfig.freeze(config, 0, 0L);
        ObjectNode second = ThemeRuntimeConfig.freeze(config, 1, 0L);

        assertEquals("scene_a", first.path("sceneId").asText());
        assertEquals("scene_b", second.path("sceneId").asText());
        assertEquals("甲城现场核对",
                first.path("nodeConfigs").path("11").path("qa").path("title").asText());
        assertEquals("乙城现场核对",
                second.path("nodeConfigs").path("11").path("qa").path("title").asText());
        assertNotEquals(
                first.path("nodeConfigs").path("11").path("qa").path("title").asText(),
                second.path("nodeConfigs").path("11").path("qa").path("title").asText());
        // 冻结不改原配置，下一局仍从同一份草稿取自己的场景。
        assertEquals(2, config.path("scenes").size());
        assertNull(first.path("scenes").get(0));
    }

    private static ObjectNode theme(String scope) throws Exception {
        ObjectNode theme = JSON.createObjectNode();
        ObjectNode game = theme.putObject("gameplayRuntime").put("runtimeScope", scope);
        game.putArray("quests").addObject().put("nodeId", NODE_ID);
        return theme;
    }
}
