package com.chengyinhub.business.util;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R8-STORY / R11-C:B1 发布闸的剧情收集入口 —— cond 与 optionalClues 引用必须全部被收集,
 * 尾声的 relationKey(显式关系值来源)同样进闸;npcId 只是 NPC 引用,不产生键;坏 JSON fail-closed。
 */
class StateKeyRefsJourneyStoryTest {

    @Test
    void collectsEveryConditionSourceAndOptionalClues() {
        String json = "{\"schemaVersion\":1,"
                + "\"stages\":[{\"chapterId\":11,\"end\":{\"changed\":["
                + "{\"when\":{\"var\":\"clue.page\",\"op\":\"GTE\",\"value\":1},\"text\":\"a\"},"
                + "{\"when\":{\"op\":\"HAS_TAG\",\"value\":\"tag.hurried\"},\"text\":\"b\"},"
                + "{\"when\":{\"op\":\"NODE_COMPLETED\",\"nodeId\":9},\"text\":\"c\"}]}}],"
                + "\"ending\":{\"endings\":["
                + "{\"code\":\"a\",\"title\":\"A\",\"when\":[{\"var\":\"relation.arong\",\"op\":\"GTE\",\"value\":2}]},"
                + "{\"code\":\"b\",\"title\":\"B\",\"fallback\":true}],"
                + "\"exhibits\":[{\"when\":{\"var\":\"counter.coins\",\"op\":\"GT\",\"value\":1},\"text\":\"x\"}],"
                + "\"badges\":[{\"when\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.brave\"}],\"title\":\"x\"},"
                + "{\"title\":\"y\",\"fallback\":true}],"
                + "\"optionalClues\":[\"clue.voice\",\"clue.photo\"],"
                + "\"epilogues\":[{\"npcId\":7,\"relationKey\":\"relation.lin_trust\","
                + "\"ally\":\"a\",\"neutral\":\"b\",\"guarded\":\"c\"}]}}";

        Set<String> keys = StateKeyRefs.collectJourneyStory(json);

        assertTrue(keys.contains("clue.page"), keys.toString());
        assertTrue(keys.contains("tag.hurried"), keys.toString());
        assertTrue(keys.contains("relation.arong"), keys.toString());
        assertTrue(keys.contains("counter.coins"), keys.toString());
        assertTrue(keys.contains("tag.brave"), keys.toString());
        assertTrue(keys.contains("clue.voice"), keys.toString());
        assertTrue(keys.contains("clue.photo"), keys.toString());
        assertTrue(keys.contains("relation.lin_trust"), "尾声 relationKey 进闸:" + keys);
        assertEquals(8, keys.size(), "NODE_COMPLETED 与 epilogue npcId 不产生键:" + keys);
    }

    /** 发布闸类型核对单独取一份 relationKey;未配置尾声 = 空集合。 */
    @Test
    void collectJourneyStoryRelationKeysReturnsEpilogueRelationKeysOnly() {
        Set<String> relationKeys = StateKeyRefs.collectJourneyStoryRelationKeys(
                "{\"schemaVersion\":1,\"ending\":{\"epilogues\":["
                        + "{\"npcId\":7,\"relationKey\":\"relation.arong\",\"ally\":\"a\",\"neutral\":\"b\",\"guarded\":\"c\"},"
                        + "{\"npcId\":8,\"relationKey\":\"relation.lin\",\"ally\":\"a\",\"neutral\":\"b\",\"guarded\":\"c\"}]}}");

        assertEquals(2, relationKeys.size(), relationKeys.toString());
        assertTrue(relationKeys.contains("relation.arong"), relationKeys.toString());
        assertTrue(relationKeys.contains("relation.lin"), relationKeys.toString());
        assertTrue(StateKeyRefs.collectJourneyStoryRelationKeys(
                "{\"schemaVersion\":1,\"call\":{\"npcId\":7}}").isEmpty());
        assertTrue(StateKeyRefs.collectJourneyStoryRelationKeys(null).isEmpty());
    }

    @Test
    void blankOrLegacyStoryCollectsNothing() {
        assertTrue(StateKeyRefs.collectJourneyStory(null).isEmpty());
        assertTrue(StateKeyRefs.collectJourneyStory("").isEmpty());
        assertTrue(StateKeyRefs.collectJourneyStory("   ").isEmpty());
        assertTrue(StateKeyRefs.collectJourneyStory("{\"schemaVersion\":1,"
                + "\"call\":{\"npcId\":7,\"opener\":\"开场\"}}").isEmpty());
    }


    @Test
    void runtimeThoughtTagsComeOnlyFromStrictlyValidatedCurrentTopicRules() {
        String first = "{\"schemaVersion\":1,\"thoughts\":[{\"key\":\"window\",\"name\":\"窗\",\"need\":1}]}";
        String other = "{\"schemaVersion\":1,\"thoughts\":[{\"key\":\"another\",\"name\":\"门\",\"need\":1}]}";
        assertEquals(java.util.Collections.singleton("thought.window.done"), StateKeyRefs.declaredThoughtDoneTags(first));
        assertEquals(java.util.Collections.singleton("thought.another.done"), StateKeyRefs.declaredThoughtDoneTags(other));
        assertTrue(StateKeyRefs.declaredThoughtDoneTags(null).isEmpty());
        assertTrue(StateKeyRefs.declaredThoughtDoneTags("{\"schemaVersion\":1}").isEmpty());
        assertTrue(StateKeyRefs.collectJourneyStory("{\"schemaVersion\":1,\"ending\":{\"endings\":["
                + "{\"when\":[{\"op\":\"HAS_TAG\",\"value\":\"thought.window.done\"}]}]}}")
                .contains("thought.window.done"), "收集器不能全局忽略thought引用");
    }

    @Test
    void invalidThoughtDeclarationsCannotCreateRuntimeTagExceptions() {
        assertThrows(IllegalArgumentException.class, () -> StateKeyRefs.declaredThoughtDoneTags("{not json"));
        assertThrows(IllegalArgumentException.class, () -> StateKeyRefs.declaredThoughtDoneTags(
                "{\"schemaVersion\":1,\"thoughts\":[{\"key\":\"window\",\"need\":1}]}"));
        assertThrows(IllegalArgumentException.class, () -> StateKeyRefs.declaredThoughtDoneTags(
                "{\"schemaVersion\":1,\"thoughts\":[{\"key\":\"window.done\",\"name\":\"窗\",\"need\":1}]}"));
    }


    @Test
    void thoughtCompletionTagCannotBeReadAsANumericVariable() {
        assertThrows(IllegalArgumentException.class, () -> StateKeyRefs.collectJourneyStory(
                "{\"schemaVersion\":1,\"ending\":{\"endings\":[{\"when\":["
                        + "{\"op\":\"EQ\",\"var\":\"thought.window.done\",\"value\":1}]}]}}"));
        assertThrows(IllegalArgumentException.class, () -> StateKeyRefs.collectChapterBlocks(
                "[{\"type\":\"text\",\"beat\":\"outcome\",\"when\":{\"op\":\"EQ\","
                        + "\"var\":\"thought.window.done\",\"value\":1}}]"));
    }

    @Test
    void brokenJsonFailsClosedInsteadOfLookingEmpty() {
        assertThrows(IllegalArgumentException.class, () -> StateKeyRefs.collectJourneyStory("{not json"));
    }
}
