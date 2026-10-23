package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.route.NodeOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F5 故事引擎词汇:每个新 op 正反例、组合优先级、重放幂等、未知 op 报错文案不变。 */
class TopicRouteEngineVocabularyTest {

    private final TopicRouteEngine engine = new TopicRouteEngine();
    private final ObjectMapper json = new ObjectMapper();

    private JsonNode tree(String raw) throws Exception { return json.readTree(raw); }

    private boolean pass(String conditions, TopicRouteEngine.State state) throws Exception {
        return engine.conditionsPass(tree(conditions), state);
    }

    private void apply(String effects, TopicRouteEngine.State state) throws Exception {
        engine.applyEffects(tree(effects), state);
    }

    private static TopicRouteEngine.State stateWithTag(String... tags) {
        TopicRouteEngine.State state = new TopicRouteEngine.State();
        for (String tag : tags) state.getTags().add(tag);
        return state;
    }

    // ---------- 组合 ----------

    @Test
    void andOrNotEvaluateTheirOwnChildren() throws Exception {
        TopicRouteEngine.State state = stateWithTag("tag.a");
        String a = "{\"op\":\"HAS_TAG\",\"value\":\"tag.a\"}";
        String b = "{\"op\":\"HAS_TAG\",\"value\":\"tag.b\"}";
        assertTrue(pass("[{\"op\":\"OR\",\"conditions\":[" + a + "," + b + "]}]", state));
        assertFalse(pass("[{\"op\":\"AND\",\"conditions\":[" + a + "," + b + "]}]", state));
        assertTrue(pass("[{\"op\":\"AND\",\"conditions\":[" + a + "]}]", state));
        assertTrue(pass("[{\"op\":\"NOT\",\"condition\":" + b + "}]", state));
        assertFalse(pass("[{\"op\":\"NOT\",\"condition\":" + a + "}]", state));
        assertFalse(pass("[{\"op\":\"OR\",\"conditions\":[]}]", state));
    }

    @Test
    void precedenceIsStructural() throws Exception {
        String a = "{\"op\":\"HAS_TAG\",\"value\":\"tag.a\"}";
        String b = "{\"op\":\"HAS_TAG\",\"value\":\"tag.b\"}";
        String c = "{\"op\":\"HAS_TAG\",\"value\":\"tag.c\"}";
        // NOT 只管它自己的 condition:NOT(a OR b) ≠ (NOT a) OR b
        String notOfOr = "[{\"op\":\"NOT\",\"condition\":{\"op\":\"OR\",\"conditions\":[" + a + "," + b + "]}}]";
        String orOfNot = "[{\"op\":\"OR\",\"conditions\":[{\"op\":\"NOT\",\"condition\":" + a + "}," + b + "]}]";
        TopicRouteEngine.State onlyB = stateWithTag("tag.b");
        assertFalse(pass(notOfOr, onlyB));
        assertTrue(pass(orOfNot, onlyB));
        // 顶层数组仍是 AND:[a, OR(b, c)] = a AND (b OR c)
        String topAnd = "[" + a + ",{\"op\":\"OR\",\"conditions\":[" + b + "," + c + "]}]";
        assertTrue(pass(topAnd, stateWithTag("tag.a", "tag.c")));
        assertFalse(pass(topAnd, stateWithTag("tag.c")));
        assertFalse(pass(topAnd, stateWithTag("tag.a")));
        // (a AND b) OR c
        String andInOr = "[{\"op\":\"OR\",\"conditions\":[{\"op\":\"AND\",\"conditions\":[" + a + "," + b + "]}," + c + "]}]";
        assertTrue(pass(andInOr, stateWithTag("tag.c")));
        assertTrue(pass(andInOr, stateWithTag("tag.a", "tag.b")));
        assertFalse(pass(andInOr, stateWithTag("tag.a")));
    }

    @Test
    void compositionDepthAndShapeAreBounded() throws Exception {
        TopicRouteEngine.State state = stateWithTag("tag.a");
        String leaf = "{\"op\":\"HAS_TAG\",\"value\":\"tag.a\"}";
        String three = "{\"op\":\"NOT\",\"condition\":{\"op\":\"NOT\",\"condition\":{\"op\":\"AND\",\"conditions\":[" + leaf + "]}}}";
        assertTrue(pass("[" + three + "]", state));
        String four = "{\"op\":\"NOT\",\"condition\":" + three + "}";
        assertEquals("条件嵌套过深", assertThrows(IllegalArgumentException.class, () -> pass("[" + four + "]", state)).getMessage());
        assertThrows(IllegalArgumentException.class, () -> pass("[{\"op\":\"NOT\"}]", state));
        assertThrows(IllegalArgumentException.class, () -> pass("[{\"op\":\"OR\"}]", state));
    }

    // ---------- 新条件 ----------

    private static final String LOOP = "{\"schemaVersion\":1,\"startNodeId\":1,\"terminalNodeIds\":[9]," +
            "\"variables\":{}," +
            "\"edges\":[" +
            "{\"id\":\"go\",\"fromNodeId\":1,\"toNodeId\":2,\"maxVisits\":0,\"allowLoop\":true," +
            " \"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}," +
            " \"effects\":[{\"op\":\"GRANT_XP\",\"value\":10},{\"op\":\"GRANT_BADGE\",\"value\":\"first_step\"}]}," +
            "{\"id\":\"back\",\"fromNodeId\":2,\"toNodeId\":1,\"maxVisits\":0,\"allowLoop\":true," +
            " \"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}}]}";

    @Test
    void visitCountCountsArrivalsIncludingTheStart() throws Exception {
        TopicRouteEngine.State state = engine.initial(LOOP);
        String startOnce = "[{\"op\":\"VISIT_COUNT\",\"nodeId\":1,\"cmp\":\"EQ\",\"value\":1}]";
        assertTrue(pass(startOnce, state));
        assertFalse(pass("[{\"op\":\"VISIT_COUNT\",\"nodeId\":2,\"value\":1}]", state));
        state = engine.advance(LOOP, state, new NodeOutcome(1L, "COMPLETED", "r1"), 1L).getState();
        state = engine.advance(LOOP, state, new NodeOutcome(2L, "COMPLETED", "r2"), 1L).getState();
        assertTrue(pass("[{\"op\":\"VISIT_COUNT\",\"nodeId\":1,\"cmp\":\"EQ\",\"value\":2}]", state));
        assertTrue(pass("[{\"op\":\"VISIT_COUNT\",\"nodeId\":2,\"value\":1}]", state));   // 缺省 GTE
        assertFalse(pass("[{\"op\":\"VISIT_COUNT\",\"nodeId\":2,\"cmp\":\"GT\",\"value\":1}]", state));
        // 序列化再读回(存量局走的路径)结果一致
        TopicRouteEngine.State reread = json.readValue(json.writeValueAsString(state), TopicRouteEngine.State.class);
        assertTrue(pass("[{\"op\":\"VISIT_COUNT\",\"nodeId\":1,\"cmp\":\"EQ\",\"value\":2}]", reread));
    }

    @Test
    void roleInNeedsAnInjectedRoleAndIsNeverPersisted() throws Exception {
        String cond = "[{\"op\":\"ROLE_IN\",\"values\":[\"NAVIGATOR\",\"DECODER\"]}]";
        TopicRouteEngine.State state = new TopicRouteEngine.State();
        assertFalse(pass(cond, state));
        state.setActorRoleCode("OBSERVER");
        assertFalse(pass(cond, state));
        state.setActorRoleCode("DECODER");
        assertTrue(pass(cond, state));
        assertTrue(pass(cond, state.copy()));
        String written = json.writeValueAsString(state);
        assertFalse(written.contains("DECODER"));
        assertNull(json.readValue(written, TopicRouteEngine.State.class).getActorRoleCode());
    }

    @Test
    void statusConditionsAreAddedAndRemoved() throws Exception {
        TopicRouteEngine.State state = new TopicRouteEngine.State();
        String has = "[{\"op\":\"HAS_CONDITION\",\"value\":\"poisoned\"}]";
        assertFalse(pass(has, state));
        apply("[{\"op\":\"ADD_CONDITION\",\"value\":\"poisoned\"}]", state);
        assertTrue(pass(has, state));
        TopicRouteEngine.State reread = json.readValue(json.writeValueAsString(state), TopicRouteEngine.State.class);
        assertTrue(pass(has, reread));
        apply("[{\"op\":\"REMOVE_CONDITION\",\"value\":\"poisoned\"}]", state);
        assertFalse(pass(has, state));
        apply("[{\"op\":\"REMOVE_CONDITION\",\"value\":\"poisoned\"}]", state);   // 不存在时移除是空操作
        assertFalse(pass(has, state));
    }

    @Test
    void equipRequiresHoldingAndEquippedRequiresStillHolding() throws Exception {
        TopicRouteEngine.State state = new TopicRouteEngine.State();
        String equipped = "[{\"op\":\"ITEM_EQUIPPED\",\"itemId\":\"lantern\"}]";
        apply("[{\"op\":\"EQUIP\",\"itemId\":\"lantern\"}]", state);
        assertFalse(pass(equipped, state));   // 没持有就装不上
        state.getThemeRuntime().putObject("inventory").put("lantern", 1);
        apply("[{\"op\":\"EQUIP\",\"itemId\":\"lantern\",\"slot\":\"hand\"}]", state);
        assertTrue(pass(equipped, state));
        // 同槽换装:旧的被顶掉
        state.getThemeRuntime().with("inventory").put("torch", 1);
        apply("[{\"op\":\"EQUIP\",\"itemId\":\"torch\",\"slot\":\"hand\"}]", state);
        assertFalse(pass(equipped, state));
        assertTrue(pass("[{\"op\":\"ITEM_EQUIPPED\",\"itemId\":\"torch\"}]", state));
        apply("[{\"op\":\"UNEQUIP\",\"itemId\":\"torch\"}]", state);
        assertFalse(pass("[{\"op\":\"ITEM_EQUIPPED\",\"itemId\":\"torch\"}]", state));
        // 装备后物资被用掉 / 丢掉:不再算装备中
        apply("[{\"op\":\"EQUIP\",\"itemId\":\"lantern\"}]", state);
        state.getThemeRuntime().with("inventory").remove("lantern");
        assertFalse(pass(equipped, state));
    }

    @Test
    void checkResultReadsTheSettledReceiptFact() throws Exception {
        TopicRouteEngine.State state = new TopicRouteEngine.State();
        String passed = "[{\"op\":\"CHECK_RESULT\",\"nodeId\":5,\"result\":\"PASS\"}]";
        String failed = "[{\"op\":\"CHECK_RESULT\",\"nodeId\":5,\"result\":\"FAIL\"}]";
        assertFalse(pass(passed, state));   // 未结算:两种结果都不成立
        assertFalse(pass(failed, state));
        state.getVariables().put("sys.passed.5", 1);
        assertTrue(pass(passed, state));
        assertFalse(pass(failed, state));
        state.getVariables().put("sys.passed.5", 0);
        assertFalse(pass(passed, state));
        assertTrue(pass(failed, state));
        assertFalse(pass("[{\"op\":\"CHECK_RESULT\",\"nodeId\":6,\"result\":\"FAIL\"}]", state));
        assertEquals("不支持的检定结果:CRIT", assertThrows(IllegalArgumentException.class,
                () -> pass("[{\"op\":\"CHECK_RESULT\",\"nodeId\":5,\"result\":\"CRIT\"}]", state)).getMessage());
    }

    // ---------- 新效果 ----------

    private static TopicRouteEngine.State backpackState() {
        TopicRouteEngine.State state = new TopicRouteEngine.State();
        state.getThemeRuntime().putObject("inventory"); state.getThemeRuntime().putObject("acquired");
        state.getThemeRuntime().putObject("sources"); state.getThemeRuntime().putObject("pending");
        state.setCurrentNodeId(3L);
        return state;
    }

    @Test
    void addItemQueuesAPendingClaimOncePerNode() throws Exception {
        TopicRouteEngine.State state = backpackState();
        apply("[{\"op\":\"ADD_ITEM\",\"itemId\":\"map\",\"quantity\":2}]", state);
        apply("[{\"op\":\"ADD_ITEM\",\"itemId\":\"map\",\"quantity\":2}]", state);   // 同站重放
        JsonNode pending = state.getThemeRuntime().path("pending");
        assertEquals(1, pending.size());
        assertEquals("map", pending.path("story:3:map").path("itemId").asText());
        assertEquals(2, pending.path("story:3:map").path("quantity").asInt());
        assertTrue(state.getThemeRuntime().path("sources").path("story:3:map").asBoolean());
        // 不绕过背包领取:库存不被直接改写
        assertEquals(0, state.getThemeRuntime().path("inventory").path("map").asInt());
        state.setCurrentNodeId(4L);
        apply("[{\"op\":\"ADD_ITEM\",\"itemId\":\"map\",\"quantity\":1}]", state);
        assertEquals(2, state.getThemeRuntime().path("pending").size());
    }

    @Test
    void addItemNegatives() {
        TopicRouteEngine.State noBackpack = new TopicRouteEngine.State();
        assertThrows(IllegalStateException.class,
                () -> apply("[{\"op\":\"ADD_ITEM\",\"itemId\":\"map\",\"quantity\":1}]", noBackpack));
        TopicRouteEngine.State state = backpackState();
        assertThrows(IllegalArgumentException.class,
                () -> apply("[{\"op\":\"ADD_ITEM\",\"itemId\":\"map\",\"quantity\":0}]", state));
        assertThrows(IllegalArgumentException.class,
                () -> apply("[{\"op\":\"ADD_ITEM\",\"quantity\":1}]", state));
    }

    @Test
    void sensorRewardCeilingOnlyLetsXpAndBadgesThrough() throws Exception {
        TopicRouteEngine.State state = backpackState();
        state.setRewardCeilingXpBadgeOnly(true);
        apply("[{\"op\":\"ADD_ITEM\",\"itemId\":\"map\",\"quantity\":1},"
                + "{\"op\":\"GRANT_XP\",\"value\":5},{\"op\":\"GRANT_BADGE\",\"value\":\"night_owl\"}]", state);
        assertEquals(0, state.getThemeRuntime().path("pending").size());
        assertEquals(2, state.getStoryGrants().size());
        assertFalse(json.writeValueAsString(state).contains("rewardCeiling"));
        assertTrue(state.copy().isRewardCeilingXpBadgeOnly());
    }

    @Test
    void grantsAreIdempotentPerSessionAndNodeAndCarryNoMoney() throws Exception {
        TopicRouteEngine.State state = backpackState();
        apply("[{\"op\":\"GRANT_XP\",\"value\":10},{\"op\":\"GRANT_BADGE\",\"value\":\"first_step\"}]", state);
        apply("[{\"op\":\"GRANT_XP\",\"value\":10},{\"op\":\"GRANT_BADGE\",\"value\":\"first_step\"}]", state);
        assertEquals(2, state.getStoryGrants().size());
        Map<String, Object> xp = state.getStoryGrants().get("XP:3:xp");
        assertEquals(10, ((Number) xp.get("amount")).intValue());
        assertEquals("XP", xp.get("kind"));
        assertEquals(3, xp.size());   // kind / amount / node:没有积分、券、金额字段
        assertEquals("first_step", state.getStoryGrants().get("BADGE:3:first_step").get("code"));
        // 同站两条不同 id 的 XP 各记一次
        apply("[{\"op\":\"GRANT_XP\",\"value\":3,\"id\":\"bonus\"}]", state);
        assertEquals(3, state.getStoryGrants().size());
        // 序列化读回后重放仍不二记
        TopicRouteEngine.State reread = json.readValue(json.writeValueAsString(state), TopicRouteEngine.State.class);
        apply("[{\"op\":\"GRANT_XP\",\"value\":10},{\"op\":\"GRANT_XP\",\"value\":3,\"id\":\"bonus\"}]", reread);
        assertEquals(3, reread.getStoryGrants().size());
        assertThrows(IllegalArgumentException.class, () -> apply("[{\"op\":\"GRANT_XP\",\"value\":0}]", state));
        assertThrows(IllegalArgumentException.class, () -> apply("[{\"op\":\"GRANT_XP\",\"value\":101}]", state));
        assertThrows(IllegalArgumentException.class, () -> apply("[{\"op\":\"GRANT_BADGE\"}]", state));
    }

    @Test
    void replayingTheSameNodeThroughALoopDoesNotDoubleGrant() {
        TopicRouteEngine.State state = engine.initial(LOOP);
        for (int round = 0; round < 3; round++) {
            state = engine.advance(LOOP, state, new NodeOutcome(1L, "COMPLETED", "a" + round), 1L).getState();
            state = engine.advance(LOOP, state, new NodeOutcome(2L, "COMPLETED", "b" + round), 1L).getState();
        }
        assertEquals(2, state.getStoryGrants().size());
        assertEquals(10, ((Number) state.getStoryGrants().get("XP:1:xp").get("amount")).intValue());
    }

    @Test
    void tickClockAdvancesACounterUpToItsSegments() throws Exception {
        TopicRouteEngine.State state = new TopicRouteEngine.State();
        String tick = "[{\"op\":\"TICK_CLOCK\",\"var\":\"counter.alarm\",\"value\":2,\"max\":4}]";
        apply(tick, state);
        assertEquals(2, state.getVariables().get("counter.alarm").intValue());
        apply(tick, state);
        apply(tick, state);
        assertEquals(4, state.getVariables().get("counter.alarm").intValue());
        assertTrue(pass("[{\"op\":\"GTE\",\"var\":\"counter.alarm\",\"value\":4}]", state));
        state.getVariables().put("counter.alarm", 9);   // 作者另用 SET 写过更大的值:钟不往回拨
        apply(tick, state);
        assertEquals(9, state.getVariables().get("counter.alarm").intValue());
        assertThrows(IllegalArgumentException.class,
                () -> apply("[{\"op\":\"TICK_CLOCK\",\"var\":\"clue.alarm\",\"value\":1,\"max\":4}]", state));
        assertThrows(IllegalArgumentException.class,
                () -> apply("[{\"op\":\"TICK_CLOCK\",\"var\":\"counter.alarm\",\"value\":1,\"max\":1}]", state));
    }

    // ---------- 存量契约 ----------

    @Test
    void unknownOpsStillThrowTheExistingMessages() {
        TopicRouteEngine.State state = new TopicRouteEngine.State();
        state.getVariables().put("x", 1);
        assertEquals("不支持的条件操作:FOO", assertThrows(IllegalArgumentException.class,
                () -> pass("[{\"op\":\"FOO\",\"var\":\"x\",\"value\":1}]", state)).getMessage());
        assertEquals("不支持的 effect:FOO", assertThrows(IllegalArgumentException.class,
                () -> apply("[{\"op\":\"FOO\",\"var\":\"x\",\"value\":1}]", state)).getMessage());
        assertEquals("effect 变量名不能为空", assertThrows(IllegalArgumentException.class,
                () -> apply("[{\"op\":\"FOO\"}]", state)).getMessage());
        // 内层未知 op 一样报原文案
        assertEquals("不支持的条件操作:FOO", assertThrows(IllegalArgumentException.class,
                () -> pass("[{\"op\":\"NOT\",\"condition\":{\"op\":\"FOO\",\"var\":\"x\",\"value\":1}}]", state)).getMessage());
    }

    @Test
    void earnedClueEvidenceStaysMonotonicAlongsideNewOps() throws Exception {
        TopicRouteEngine.State state = new TopicRouteEngine.State();
        apply("[{\"op\":\"SET\",\"var\":\"clue.h_map\",\"value\":1},{\"op\":\"TICK_CLOCK\",\"var\":\"counter.c\",\"value\":1,\"max\":3}]", state);
        apply("[{\"op\":\"SET\",\"var\":\"clue.h_map\",\"value\":0},{\"op\":\"INC\",\"var\":\"clue.h_map\",\"value\":-1}]", state);
        assertEquals(1, state.getVariables().get("clue.h_map").intValue());
    }

    @Test
    void untouchedStateSerializesWithoutAnyNewField() throws Exception {
        String graph = "{\"schemaVersion\":1,\"startNodeId\":1,\"terminalNodeIds\":[2],\"variables\":{\"v\":0}," +
                "\"edges\":[{\"id\":\"e\",\"fromNodeId\":1,\"toNodeId\":2," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}," +
                "\"effects\":[{\"op\":\"INC\",\"var\":\"v\",\"value\":1},{\"op\":\"ADD_TAG\",\"value\":\"t\"}]}]}";
        TopicRouteEngine.State state = engine.initial(graph);
        state.setActorRoleCode("NAVIGATOR");
        state.setRewardCeilingXpBadgeOnly(true);
        String written = json.writeValueAsString(engine.advance(graph, state,
                new NodeOutcome(1L, "COMPLETED", "r"), 1L).getState());
        for (String field : new String[] {"statusConditions", "equipped", "storyGrants", "actorRoleCode",
                "rewardCeilingXpBadgeOnly", "NAVIGATOR"}) {
            assertFalse(written.contains(field), field);
        }
    }
}
