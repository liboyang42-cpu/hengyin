package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.JourneyStory;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R8-STORY:主题级剧情配置 journeyStory 的单一校验入口契约(契约 v4-B 全部约束)。
 *
 * <p>每条约束一例非法必须被拒(中文人话);空/缺省 = 未配置返回 null;
 * 引用核对上下文(chapterId 属于本主题 / npcId 可用)给全时逐条核对。
 */
class JourneyStoryValidatorTest {

    private final JourneyStoryValidator validator = new JourneyStoryValidator();

    private static final String STORY = "{\"schemaVersion\":1,"
            + "\"call\":{\"npcId\":7,\"opener\":\"开场\",\"options\":[{\"id\":\"a\",\"line\":\"走\"},"
            + "{\"id\":\"b\",\"line\":\"留\",\"reply\":\"嗯\"}],\"brief\":\"简述\"},"
            + "\"stages\":[{\"chapterId\":11,\"question\":\"问?\",\"end\":{\"changed\":["
            + "{\"when\":null,\"text\":\"变了\"},"
            + "{\"when\":{\"var\":\"clue.x\",\"op\":\"GTE\",\"value\":1},\"text\":\"特定\"}],\"open\":\"尾\"}}],"
            + "\"ending\":{\"dispositionNodeId\":99,"
            + "\"endings\":[{\"code\":\"correct\",\"title\":\"对\",\"summary\":\"好\",\"when\":["
            + "{\"var\":\"clue.x\",\"op\":\"GTE\",\"value\":1},"
            + "{\"var\":\"sys.outcome.99\",\"op\":\"EQ\",\"value\":1}]},"
            + "{\"code\":\"default\",\"title\":\"默认\",\"fallback\":true}],"
            + "\"epilogues\":[{\"npcId\":7,\"relationKey\":\"relation.arong\",\"ally\":\"友\",\"neutral\":\"中\",\"guarded\":\"敌\"}],"
            + "\"exhibits\":[{\"when\":null,\"text\":\"展品\"},"
            + "{\"when\":{\"op\":\"HAS_TAG\",\"value\":\"tag.x\"},\"text\":\"另一个\"}],"
            + "\"badges\":[{\"when\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.x\"}],\"title\":\"徽章\"},"
            + "{\"title\":\"兜底\",\"fallback\":true}],"
            + "\"optionalClues\":[\"clue.voice\",\"clue.photo\"]}}";

    private static String story(String body) {
        return "{\"schemaVersion\":1" + body + "}";
    }

    private IllegalArgumentException rejected(String json) {
        return assertThrows(IllegalArgumentException.class, () -> validator.validate(json, null));
    }

    private IllegalArgumentException rejected(String json, JourneyStoryValidator.Context context) {
        return assertThrows(IllegalArgumentException.class, () -> validator.validate(json, context));
    }

    @Test
    void openingReferenceMustBelongToTheTopicAndFollowChapterCopies() {
        String raw = story(",\"openingChapterId\":11");
        rejected(story(",\"openingChapterId\":0"));
        rejected(raw, JourneyStoryValidator.Context.of(Collections.singleton(12L), null));
        String copy = JourneyStory.remapChapterIds(raw, Collections.singletonMap(11L, 21L));
        assertTrue(copy.contains("\"openingChapterId\":21"));
        assertThrows(IllegalArgumentException.class,
                () -> JourneyStory.remapChapterIds(raw, Collections.emptyMap()));
    }

    // ---------- 通过 / 未配置 ----------

    @Test
    void blankMeansNotConfiguredAndReturnsNull() {
        assertNull(validator.validate(null, null));
        assertNull(validator.validate("", null));
        assertNull(validator.validate("   ", null));
    }

    @Test
    void fullContractStoryPassesAndIsReturnedAsIs() {
        String returned = validator.validate(STORY, null);

        assertEquals(STORY, returned, "回显要求存什么读什么:不做字段重排");
        JourneyStory parsed = JourneyStory.parse(STORY);
        assertNotNull(parsed);
        assertEquals(Long.valueOf(11), parsed.getStages().get(0).getChapterId());
        assertEquals(2, parsed.getEnding().getEndings().size());
    }

    @Test
    void fullContractStoryPassesWithMatchingReferenceContext() {
        Set<Long> chapters = new HashSet<Long>(Collections.singletonList(11L));
        Set<Long> npcs = new HashSet<Long>(Collections.singletonList(7L));

        assertNotNull(validator.validate(STORY, JourneyStoryValidator.Context.of(chapters, npcs)));
    }

    // ---------- schemaVersion / 形状 ----------

    @Test
    void rejectsUnknownVersionAndMalformedJsonAndNonObject() {
        assertEquals("主题剧情配置版本不支持",
                rejected("{\"schemaVersion\":2}").getMessage());
        assertEquals("主题剧情配置不是合法 JSON", rejected("{not-json").getMessage());
        assertEquals("主题剧情配置必须是对象", rejected("[]").getMessage());
    }

    // ---------- call ----------

    @Test
    void rejectsCallShapeViolations() {
        assertTrue(rejected(story(",\"call\":[]")).getMessage().contains("call 必须是对象"));
        assertTrue(rejected(story(",\"call\":{\"opener\":\"x\"}")).getMessage().contains("call.npcId 必须是正整数"));
        assertTrue(rejected(story(",\"call\":{\"npcId\":0}")).getMessage().contains("call.npcId 必须是正整数"));
        // options 2–4
        assertTrue(rejected(story(",\"call\":{\"npcId\":7,\"options\":[{\"id\":\"a\",\"line\":\"x\"}]}"))
                .getMessage().contains("call.options 须为 2 至 4 项"));
        assertTrue(rejected(story(",\"call\":{\"npcId\":7,\"options\":["
                + "{\"id\":\"a\",\"line\":\"x\"},{\"id\":\"b\",\"line\":\"x\"},{\"id\":\"c\",\"line\":\"x\"},"
                + "{\"id\":\"d\",\"line\":\"x\"},{\"id\":\"e\",\"line\":\"x\"}]}"))
                .getMessage().contains("call.options 须为 2 至 4 项"));
        // id 正则 / line 必填
        assertTrue(rejected(story(",\"call\":{\"npcId\":7,\"options\":[{\"id\":\"a b\",\"line\":\"x\"},"
                + "{\"id\":\"b\",\"line\":\"x\"}]}")).getMessage().contains("options[0].id"));
        assertTrue(rejected(story(",\"call\":{\"npcId\":7,\"options\":[{\"id\":\"a\"},"
                + "{\"id\":\"b\",\"line\":\"x\"}]}")).getMessage().contains("options[0].line 不能为空"));
        // 重复 id
        assertTrue(rejected(story(",\"call\":{\"npcId\":7,\"options\":[{\"id\":\"a\",\"line\":\"x\"},"
                + "{\"id\":\"a\",\"line\":\"y\"}]}")).getMessage().contains("options[1].id 重复"));
    }

    @Test
    void callNpcMustBeAvailableWhenContextGiven() {
        Set<Long> npcs = new HashSet<Long>(Collections.singletonList(8L));

        assertTrue(rejected(STORY, JourneyStoryValidator.Context.of(null, npcs)).getMessage().contains("NPC 本主题不可用"));
    }

    // ---------- stages ----------

    @Test
    void rejectsStageShapeViolations() {
        assertTrue(rejected(story(",\"stages\":[{\"question\":\"x\"}]")).getMessage()
                .contains("stages[0].chapterId 必须是正整数"));
        assertTrue(rejected(story(",\"stages\":[{\"chapterId\":11},{\"chapterId\":11}]")).getMessage()
                .contains("stages[1].chapterId 重复"));
        assertTrue(rejected(story(",\"stages\":[{\"chapterId\":11,\"end\":{\"changed\":[{\"text\":\"\"}]}}]"))
                .getMessage().contains("changed[0].text 不能为空"));
        assertTrue(rejected(story(",\"stages\":[{\"chapterId\":11,\"end\":{\"changed\":[{\"text\":\"x\","
                + "\"when\":{\"var\":\"nope\",\"op\":\"EQ\",\"value\":1}}]}}]")).getMessage()
                .contains("changed[0].when.var"));
    }

    @Test
    void stageChapterMustBelongToTopicWhenContextGiven() {
        Set<Long> chapters = new HashSet<Long>(Collections.singletonList(10L));

        IllegalArgumentException error = rejected(STORY, JourneyStoryValidator.Context.of(chapters, null));
        assertTrue(error.getMessage().contains("章节不属于本主题"), error.getMessage());
    }

    // ---------- cond ----------

    /**
     * 阶段 1:叙事 when 必须能引用 sys.passed.{nodeId} —— 验收原文「并在下一站的
     * advantageIf / 叙事 when 里生效」。摘掉白名单里的 SYS_PASSED_VAR 分支,本条必红。
     */
    @Test
    void storyWhenCanReadSysPassed() {
        String json = story(",\"stages\":[{\"chapterId\":11,\"question\":\"问?\",\"end\":{\"changed\":["
                + "{\"when\":{\"var\":\"sys.passed.77\",\"op\":\"EQ\",\"value\":1},\"text\":\"上一站做成了\"}],"
                + "\"open\":\"尾\"}}]");

        assertEquals(json, validator.validate(json, null));
    }

    /** 配对负控:未登记的 sys.* 仍然拒 —— 防止上一条是因为白名单整个失效才过的。 */
    @Test
    void storyWhenStillRejectsUnknownSysVar() {
        String json = story(",\"stages\":[{\"chapterId\":11,\"question\":\"问?\",\"end\":{\"changed\":["
                + "{\"when\":{\"var\":\"sys.nope.77\",\"op\":\"EQ\",\"value\":1},\"text\":\"x\"}],"
                + "\"open\":\"尾\"}}]");

        assertTrue(rejected(json).getMessage().contains("sys.nope.77"));
    }

    /** 阶段 3:叙事里要能为「力竭」写一句专门的话。摘掉白名单里的 sys.exhausted,本条必红。 */
    @Test
    void storyWhenCanReadSysExhausted() {
        String json = story(",\"stages\":[{\"chapterId\":11,\"question\":\"问?\",\"end\":{\"changed\":["
                + "{\"when\":{\"var\":\"sys.exhausted\",\"op\":\"EQ\",\"value\":1},\"text\":\"你实在走不动了\"}],"
                + "\"open\":\"尾\"}}]");

        assertEquals(json, validator.validate(json, null));
    }

    @Test
    void rejectsConditionShapeViolations() {
        assertTrue(rejected(story(",\"stages\":[{\"chapterId\":11,\"end\":{\"changed\":[{\"text\":\"x\","
                + "\"when\":{\"var\":\"clue.x\",\"op\":\"NOPE\",\"value\":1}}]}}]")).getMessage()
                .contains("op 不支持"));
        assertTrue(rejected(story(",\"stages\":[{\"chapterId\":11,\"end\":{\"changed\":[{\"text\":\"x\","
                + "\"when\":{\"var\":\"tag.x\",\"op\":\"EQ\",\"value\":1}}]}}]")).getMessage()
                .contains("var 必须是 clue./relation./counter. 状态键"));
        assertTrue(rejected(story(",\"stages\":[{\"chapterId\":11,\"end\":{\"changed\":[{\"text\":\"x\","
                + "\"when\":{\"op\":\"HAS_TAG\",\"value\":\"clue.x\"}}]}}]")).getMessage()
                .contains("value 必须是 tag.* 或 thought.*.done 状态键"));
        assertTrue(rejected(story(",\"stages\":[{\"chapterId\":11,\"end\":{\"changed\":[{\"text\":\"x\","
                + "\"when\":{\"op\":\"NODE_COMPLETED\",\"nodeId\":0}}]}}]")).getMessage()
                .contains("nodeId 必须是正整数"));
        assertTrue(rejected(story(",\"stages\":[{\"chapterId\":11,\"end\":{\"changed\":[{\"text\":\"x\","
                + "\"when\":{\"var\":\"clue.x\",\"op\":\"EQ\",\"value\":1.5}}]}}]")).getMessage()
                .contains("value 必须是整数"));
        assertTrue(rejected(story(",\"stages\":[{\"chapterId\":11,\"end\":{\"changed\":[{\"text\":\"x\","
                + "\"when\":{\"var\":\"clue.x\",\"op\":\"EQ\",\"value\":100}}]}}]")).getMessage()
                .contains("value 须为 -99 至 99"));
    }

    // ---------- endings ----------

    @Test
    void rejectsEndingsWithoutExactlyOneFallback() {
        assertTrue(rejected(story(",\"ending\":{\"endings\":["
                + "{\"code\":\"a\",\"title\":\"A\",\"when\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.x\"}]}]}"))
                .getMessage().contains("必须恰好一个 fallback"));
        assertTrue(rejected(story(",\"ending\":{\"endings\":["
                + "{\"code\":\"a\",\"title\":\"A\",\"fallback\":true},"
                + "{\"code\":\"b\",\"title\":\"B\",\"fallback\":true}]}"))
                .getMessage().contains("必须恰好一个 fallback"));
        assertTrue(rejected(story(",\"ending\":{}")).getMessage().contains("endings 不能为空"));
        assertTrue(rejected(story(",\"ending\":{\"endings\":[]}")).getMessage().contains("endings 不能为空"));
    }

    @Test
    void rejectsNonFallbackEndingWithEmptyWhen() {
        assertTrue(rejected(story(",\"ending\":{\"endings\":["
                + "{\"code\":\"a\",\"title\":\"A\",\"when\":[]},{\"code\":\"b\",\"title\":\"B\",\"fallback\":true}]}"))
                .getMessage().contains("非兜底结局的 when 不能为空数组"));
        assertTrue(rejected(story(",\"ending\":{\"endings\":["
                + "{\"code\":\"a\",\"title\":\"A\"},{\"code\":\"b\",\"title\":\"B\",\"fallback\":true}]}"))
                .getMessage().contains("非兜底结局的 when 不能为空数组"));
    }

    @Test
    void rejectsEndingCodeAndTitleViolations() {
        assertTrue(rejected(story(",\"ending\":{\"endings\":["
                + "{\"code\":\"a b\",\"title\":\"A\",\"when\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.x\"}]},"
                + "{\"code\":\"b\",\"title\":\"B\",\"fallback\":true}]}")).getMessage().contains("endings[0].code"));
        assertTrue(rejected(story(",\"ending\":{\"endings\":["
                + "{\"code\":\"a\",\"when\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.x\"}]},"
                + "{\"code\":\"b\",\"title\":\"B\",\"fallback\":true}]}")).getMessage().contains("title 不能为空"));
        assertTrue(rejected(story(",\"ending\":{\"endings\":["
                + "{\"code\":\"a\",\"title\":\"A\",\"when\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.x\"}]},"
                + "{\"code\":\"a\",\"title\":\"B\",\"fallback\":true}]}")).getMessage().contains("code 重复"));
    }

    // ---------- epilogues / exhibits / badges / optionalClues ----------

    @Test
    void rejectsEpilogueViolations() {
        assertTrue(rejected(story(",\"ending\":{\"endings\":[{\"code\":\"b\",\"title\":\"B\",\"fallback\":true}],"
                + "\"epilogues\":[{\"ally\":\"友\",\"neutral\":\"中\",\"guarded\":\"敌\"}]}")).getMessage()
                .contains("epilogues[0].npcId 必须是正整数"));
        // R11-C:relationKey 必填,且必须是 relation.* 形状
        assertTrue(rejected(story(",\"ending\":{\"endings\":[{\"code\":\"b\",\"title\":\"B\",\"fallback\":true}],"
                + "\"epilogues\":[{\"npcId\":7,\"ally\":\"友\",\"neutral\":\"中\",\"guarded\":\"敌\"}]}")).getMessage()
                .contains("epilogues[0].relationKey 必须是 relation.* 状态键"));
        assertTrue(rejected(story(",\"ending\":{\"endings\":[{\"code\":\"b\",\"title\":\"B\",\"fallback\":true}],"
                + "\"epilogues\":[{\"npcId\":7,\"relationKey\":\"clue.arong\",\"ally\":\"友\",\"neutral\":\"中\",\"guarded\":\"敌\"}]}"))
                .getMessage().contains("epilogues[0].relationKey 必须是 relation.* 状态键"));
        assertTrue(rejected(story(",\"ending\":{\"endings\":[{\"code\":\"b\",\"title\":\"B\",\"fallback\":true}],"
                + "\"epilogues\":[{\"npcId\":7,\"relationKey\":123,\"ally\":\"友\",\"neutral\":\"中\",\"guarded\":\"敌\"}]}"))
                .getMessage().contains("epilogues[0].relationKey 必须是 relation.* 状态键"));
        assertTrue(rejected(story(",\"ending\":{\"endings\":[{\"code\":\"b\",\"title\":\"B\",\"fallback\":true}],"
                + "\"epilogues\":[{\"npcId\":7,\"relationKey\":\"relation.arong\",\"ally\":\"友\"}]}")).getMessage()
                .contains("epilogues[0].neutral 不能为空"));
        assertTrue(rejected(story(",\"ending\":{\"endings\":[{\"code\":\"b\",\"title\":\"B\",\"fallback\":true}],"
                + "\"epilogues\":[{\"npcId\":7,\"relationKey\":\"relation.arong\",\"ally\":\"a\",\"neutral\":\"b\",\"guarded\":\"c\"},"
                + "{\"npcId\":7,\"relationKey\":\"relation.lin\",\"ally\":\"a\",\"neutral\":\"b\",\"guarded\":\"c\"}]}")).getMessage()
                .contains("epilogues[1].npcId 重复"));
    }

    /** R17-FIX:两个尾声引用同一个 relationKey = 关系值来源歧义,必须拒并指名重复的键。 */
    @Test
    void rejectsDuplicateEpilogueRelationKey() {
        assertTrue(rejected(story(",\"ending\":{\"endings\":[{\"code\":\"b\",\"title\":\"B\",\"fallback\":true}],"
                + "\"epilogues\":["
                + "{\"npcId\":7,\"relationKey\":\"relation.arong\",\"ally\":\"a\",\"neutral\":\"b\",\"guarded\":\"c\"},"
                + "{\"npcId\":8,\"relationKey\":\"relation.arong\",\"ally\":\"a\",\"neutral\":\"b\",\"guarded\":\"c\"}]}"))
                .getMessage().contains("epilogues[1].relationKey 重复:relation.arong"));
    }

    @Test
    void epilogueNpcMustBeAvailableWhenContextGiven() {
        String json = story(",\"ending\":{\"endings\":[{\"code\":\"b\",\"title\":\"B\",\"fallback\":true}],"
                + "\"epilogues\":[{\"npcId\":7,\"relationKey\":\"relation.arong\",\"ally\":\"a\",\"neutral\":\"b\",\"guarded\":\"c\"}]}");

        assertTrue(rejected(json, JourneyStoryValidator.Context.of(null, new HashSet<Long>(Collections.singletonList(1L))))
                .getMessage().contains("NPC 本主题不可用"));
    }

    @Test
    void rejectsBadgeViolations() {
        assertTrue(rejected(story(",\"ending\":{\"endings\":[{\"code\":\"b\",\"title\":\"B\",\"fallback\":true}],"
                + "\"badges\":[{\"when\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.x\"}],\"title\":\"x\"}]}")).getMessage()
                .contains("badges 必须恰好一个 fallback"));
        assertTrue(rejected(story(",\"ending\":{\"endings\":[{\"code\":\"b\",\"title\":\"B\",\"fallback\":true}],"
                + "\"badges\":[{\"when\":[],\"title\":\"x\"},{\"title\":\"y\",\"fallback\":true}]}")).getMessage()
                .contains("非兜底徽章的 when 不能为空数组"));
        assertTrue(rejected(story(",\"ending\":{\"endings\":[{\"code\":\"b\",\"title\":\"B\",\"fallback\":true}],"
                + "\"badges\":[{\"when\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.x\"}]},"
                + "{\"title\":\"y\",\"fallback\":true}]}")).getMessage().contains("title 不能为空"));
    }

    @Test
    void rejectsOptionalClueViolations() {
        assertTrue(rejected(story(",\"ending\":{\"endings\":[{\"code\":\"b\",\"title\":\"B\",\"fallback\":true}],"
                + "\"optionalClues\":[\"tag.x\"]}")).getMessage().contains("必须是 clue.* 状态键"));
        assertTrue(rejected(story(",\"ending\":{\"endings\":[{\"code\":\"b\",\"title\":\"B\",\"fallback\":true}],"
                + "\"optionalClues\":[\"clue.a\",\"clue.a\"]}")).getMessage().contains("重复"));
    }

    @Test
    void rejectsTextOverTwoHundredChars() {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 201; i++) longText.append('长');

        assertTrue(rejected(story(",\"call\":{\"npcId\":7,\"opener\":\"" + longText + "\"}"))
                .getMessage().contains("call.opener 不能超过 200 字"));
    }

    @Test
    void rejectsArraysOverItemCap() {
        StringBuilder stages = new StringBuilder(",\"stages\":[");
        for (int i = 0; i < 17; i++) {
            if (i > 0) stages.append(',');
            stages.append("{\"chapterId\":").append(100 + i).append('}');
        }
        stages.append(']');

        assertTrue(rejected(story(stages.toString())).getMessage().contains("最多 16 条"));
    }

    @Test
    void rejectsInvalidTypesOnOptionalFields() {
        assertTrue(rejected(story(",\"call\":{\"npcId\":7,\"opener\":123}")).getMessage()
                .contains("call.opener 必须是字符串"));
        assertTrue(rejected(story(",\"ending\":{\"endings\":[{\"code\":\"b\",\"title\":\"B\",\"fallback\":\"yes\"}]}"))
                .getMessage().contains("fallback 必须是布尔值"));
    }

    @Test
    void parseOfBlankIsNullAndParseOfIllegalThrows() {
        assertNull(JourneyStory.parse(null));

        assertThrows(IllegalArgumentException.class, () -> JourneyStory.parse(STORY.replace("schemaVersion\":1",
                "schemaVersion\":2")));
    }

    @Test
    void remapChapterIdsRewritesStagesAndFailsClosedOnUnknownChapter() {
        String remapped = JourneyStory.remapChapterIds(STORY,
                Collections.singletonMap(11L, 99L));
        assertTrue(remapped.contains("\"chapterId\":99"));
        assertTrue(remapped.contains("\"chapterId\":11") == false);

        assertThrows(IllegalArgumentException.class,
                () -> JourneyStory.remapChapterIds(STORY, Collections.singletonMap(10L, 99L)));
    }

    @Test
    void remapChapterIdsLeavesStoryWithoutStagesUntouched() {
        String json = "{\"schemaVersion\":1,\"call\":{\"npcId\":7}}";

        assertEquals(json, JourneyStory.remapChapterIds(json, Collections.<Long, Long>emptyMap()));
    }

    /** Context 为 null 时不核对引用(建题场景),只校验形状。 */
    @Test
    void nullContextSkipsReferenceChecks() {
        assertNotNull(validator.validate(STORY, null));

        Set<Long> empty = new HashSet<Long>();
        assertTrue(rejected(STORY, JourneyStoryValidator.Context.of(empty, null))
                .getMessage().contains("章节不属于本主题"));
        assertTrue(rejected(STORY, JourneyStoryValidator.Context.of(null, empty))
                .getMessage().contains("NPC 本主题不可用"));
    }

    /**
     * R22-SEC S16:NODE_COMPLETED 的 nodeId 必须属于本主题(Context 给了节点集合时);
     * 写错的条件永远不成立,发布期必须报错。
     *
     * <p>负控:requireCondition 的 NODE_COMPLETED 分支去掉归属核对,本用例第二段立刻红。
     */
    @Test
    void nodeCompletedMustReferenceNodeOfThisTopicWhenContextGiven() {
        String json = "{\"schemaVersion\":1,\"ending\":{\"endings\":["
                + "{\"code\":\"a\",\"title\":\"A\",\"when\":[{\"op\":\"NODE_COMPLETED\",\"nodeId\":501}]},"
                + "{\"code\":\"b\",\"title\":\"B\",\"fallback\":true}]}}";
        Set<Long> nodes = new HashSet<Long>(Collections.singletonList(501L));

        assertNotNull(validator.validate(json, JourneyStoryValidator.Context.of(null, null, nodes, null)));

        IllegalArgumentException error = rejected(json, JourneyStoryValidator.Context.of(
                null, null, new HashSet<Long>(Collections.singletonList(502L)), null));
        assertTrue(error.getMessage().contains("不属于本主题"), error.getMessage());
    }

    /**
     * 结局条件要能引用思维阁的念头。
     *
     * <p>念头想通与否落在 tags 里({@code thought.<key>.done},由
     * TopicRouteRuntimeServiceImpl.syncThoughts 写)。在这之前 HAS_TAG 只认 tag.*,于是
     * **伏笔与结局是两个孤岛** —— 作者配得出伏笔、也配得出结局,就是没法让「某个伏笔应验了」
     * 决定走哪个结局,而那正是《预制人生》六结局的主线机制。
     *
     * <p>不走比较分支:那条路的 value 限 -99..99,而念头阈值是几千步,对不上;要表达的也不是
     * 「走了多少步」,是「想通了没有」。
     *
     * <p>负控:把 TAG 正则里的 thought 分支去掉,本用例第一段立刻红。
     */
    @Test
    void endingConditionCanReadThoughtDone() {
        String json = "{\"schemaVersion\":1,\"ending\":{\"endings\":["
                + "{\"code\":\"awake\",\"title\":\"想通了\",\"when\":["
                + "{\"op\":\"HAS_TAG\",\"value\":\"thought.afternoon.done\"}]},"
                + "{\"code\":\"default\",\"title\":\"默认\",\"fallback\":true}]}}";

        assertNotNull(validator.validate(json, null));
    }

    /** 多认一个前缀不等于放松命名:大写、缺 .done、多段、错前缀一律仍拒。 */
    @Test
    void thoughtTagStillObeysKeyNaming() {
        for (String bad : new String[] { "thought.Afternoon.done", "thought.afternoon",
                "thought.a.b.done", "thoughts.x.done", "thought..done" }) {
            String json = "{\"schemaVersion\":1,\"ending\":{\"endings\":["
                    + "{\"code\":\"a\",\"title\":\"A\",\"when\":["
                    + "{\"op\":\"HAS_TAG\",\"value\":\"" + bad + "\"}]},"
                    + "{\"code\":\"b\",\"title\":\"B\",\"fallback\":true}]}}";
            IllegalArgumentException error = rejected(json);
            assertTrue(error.getMessage().contains("状态键"), bad + " -> " + error.getMessage());
        }
    }

    /**
     * 发布页真实产出的那一份必须被收下。
     *
     * <p>这串 JSON 不是手编的:2026-09-22 在微信开发者工具里走了一遍真实流程 —— 伏笔那张卡配一条、
     * 结局面板选它、点「完成」—— 从 {@code formData.journeyStory} 原样读回来的。
     * 作者界面与服务端校验各写各的很容易漂移:UI 提示「保存成功」,提交时才被拒。
     *
     * <p>负控:把 story-ending.js 的 serialize 改成不写 fallback,这里立刻红。
     */
    @Test
    void acceptsWhatThePublishPageActuallyProduces() {
        String fromPhone = "{\"ending\":{\"endings\":["
                + "{\"code\":\"ending1\",\"title\":\"想通了\",\"summary\":\"那个下午终于讲清楚了\","
                + "\"when\":[{\"op\":\"HAS_TAG\",\"value\":\"thought.tmucvwrbh.done\"}]},"
                + "{\"code\":\"default\",\"title\":\"余波\",\"fallback\":true}]},\"schemaVersion\":1}";

        assertNotNull(validator.validate(fromPhone, JourneyStoryValidator.Context.none()));
        assertEquals(2, JourneyStory.parse(fromPhone).getEnding().getEndings().size());
    }

    @Test
    void callOptionsAreOptional() {
        String json = "{\"schemaVersion\":1,\"call\":{\"npcId\":7,\"opener\":\"只有开场\"}}";

        assertNotNull(validator.validate(json, null));
        assertEquals(1, JourneyStory.parse(json).getSchemaVersion());
    }
}
