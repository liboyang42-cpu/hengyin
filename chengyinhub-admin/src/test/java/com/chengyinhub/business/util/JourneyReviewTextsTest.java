package com.chengyinhub.business.util;

import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.domain.dto.ChapterBlockDTO;
import com.chengyinhub.business.domain.dto.ChapterDTO;
import com.chengyinhub.business.service.IWxContentSecurityService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R12:旅程新叙事文本收集器 —— 契约 v4-A/B 列出的每一类字段都必须收到,
 * 漏收等于审核员看不见 / 内容安全不送检。
 *
 * <p>R14-PLAY 第 5 项:新玩法文本送审收集器 —— 已并入 {@link JourneyReviewTexts#collectBlocks}
 * 的题面段(R14 的独立收集器已删除,这里改测并入口径)。
 *
 * <p>正控:sort.prompt / items[].label、match.prompt / left[].label / right[].label、
 * classify.prompt / bins[].label / items[].label、compare.prompt / 左右 label / items[].time / text
 * 一条不少地按出现顺序收出来。
 * 负控:收集器漏掉 classify.items 的 label,{@link #collectsClassifyItemLabels()} 立刻红;
 * 漏掉 compare 条目的 time / text,{@link #collectsCompareTimelineTexts()} 立刻红。
 */
class JourneyReviewTextsTest {

    @Test
    void collectsEveryJourneyStoryTextField() {
        String json = "{\"schemaVersion\":1,"
                + "\"call\":{\"npcId\":1,\"opener\":\"开场白\",\"brief\":\"委托简报\","
                + "\"options\":[{\"id\":\"a\",\"line\":\"选项台词\",\"reply\":\"回应台词\"}]},"
                + "\"stages\":[{\"chapterId\":11,\"question\":\"阶段问题\","
                + "\"end\":{\"changed\":[{\"text\":\"变化台词\"}],\"open\":\"阶段余韵\"}}],"
                + "\"ending\":{\"endings\":[{\"code\":\"correct\",\"title\":\"结局标题\",\"summary\":\"结局小结\"}],"
                + "\"epilogues\":[{\"npcId\":1,\"ally\":\"盟友尾声\",\"neutral\":\"中立尾声\",\"guarded\":\"戒备尾声\"}],"
                + "\"exhibits\":[{\"text\":\"展品文本\"}],"
                + "\"badges\":[{\"title\":\"徽章标题\"}]}}";

        List<JourneyReviewTexts.Entry> entries = JourneyReviewTexts.collectJourneyStory(json);

        assertFields(entries, "开场白",
                "journeyStory.call.opener", "journeyStory.call.brief",
                "journeyStory.call.options[].line", "journeyStory.call.options[].reply",
                "journeyStory.stages[].question", "journeyStory.stages[].end.changed[].text",
                "journeyStory.stages[].end.open",
                "journeyStory.ending.endings[].title", "journeyStory.ending.endings[].summary",
                "journeyStory.ending.epilogues[].ally", "journeyStory.ending.epilogues[].neutral",
                "journeyStory.ending.epilogues[].guarded",
                "journeyStory.ending.exhibits[].text", "journeyStory.ending.badges[].title");
        assertEquals(14, entries.size(), fields(entries).toString());
    }

    @Test
    void collectsEveryBlockNarrativeTextField() {
        String json = "{\"schemaVersion\":1,"
                + "\"enter\":{\"npcId\":1,\"opener\":\"到店开场\",\"aside\":\"到店旁白\","
                + "\"questions\":[{\"id\":\"night\",\"q\":\"问句\",\"a\":\"答话\"}]},"
                + "\"outcome\":{\"title\":\"结果标题\",\"line\":\"成功台词\",\"lineIfFail\":\"失败台词\","
                + "\"open\":\"结果余韵\",\"facts\":[{\"text\":\"事实文本\"}]},"
                + "\"deliver\":{\"reward\":\"奖励名\",\"line\":\"交付台词\"},"
                + "\"unlock\":{\"hook\":\"解锁引子\"},"
                + "\"revisit\":{\"changes\":[{\"text\":\"重访变化\"}]},"
                + "\"brief\":{\"question\":\"委托问题\",\"body\":\"委托正文\","
                + "\"carry\":[{\"text\":\"携带物\"}]},"
                + "\"route\":{\"walk\":\"步行时间\",\"todo\":\"到店待办\"},"
                + "\"check\":{\"enabled\":true,\"checkId\":\"ask_arong\",\"tier\":\"easy\",\"skill\":\"察言\","
                + "\"successText\":\"检定成功\",\"failText\":\"检定失败\","
                + "\"mods\":[{\"value\":2,\"label\":\"修正说明\"}]}}";

        List<JourneyReviewTexts.Entry> entries = JourneyReviewTexts.collectBlocks(json);

        assertFields(entries, "到店开场",
                "blockConfig.enter.opener", "blockConfig.enter.aside",
                "blockConfig.enter.questions[].q", "blockConfig.enter.questions[].a",
                "blockConfig.outcome.title", "blockConfig.outcome.line", "blockConfig.outcome.lineIfFail",
                "blockConfig.outcome.open", "blockConfig.outcome.facts[].text",
                "blockConfig.deliver.reward", "blockConfig.deliver.line",
                "blockConfig.unlock.hook", "blockConfig.revisit.changes[].text",
                "blockConfig.brief.question", "blockConfig.brief.body", "blockConfig.brief.carry[].text",
                "blockConfig.route.walk", "blockConfig.route.todo",
                "blockConfig.check.skill", "blockConfig.check.successText", "blockConfig.check.failText",
                "blockConfig.check.mods[].label");
        assertEquals(22, entries.size(), fields(entries).toString());
    }

    /**
     * R13-NPC 复制/重开一期时映射不到的 npcId 会 fail-closed 置空;置空只影响 npcId 字段,
     * 不该让叙事文本收集或送检失败(否则复制出一条「引用不到 NPC」的剧情会在审核期炸)。
     */
    @Test
    void remappedNullNpcIdStillCollectsAndPassesReview() {
        String json = "{\"schemaVersion\":1,"
                + "\"call\":{\"npcId\":null,\"opener\":\"开场白\",\"brief\":\"委托简报\"},"
                + "\"ending\":{\"epilogues\":[{\"npcId\":null,\"relationKey\":\"relation.arong\","
                + "\"ally\":\"盟友尾声\",\"neutral\":\"中立尾声\",\"guarded\":\"戒备尾声\"}]}}";
        IWxContentSecurityService security = mock(IWxContentSecurityService.class);
        when(security.checkText(anyString(), any())).thenReturn(null);

        List<JourneyReviewTexts.Entry> entries = JourneyReviewTexts.collectJourneyStory(json);

        assertFields(entries, "盟友尾声", "journeyStory.call.opener", "journeyStory.call.brief",
                "journeyStory.ending.epilogues[].ally", "journeyStory.ending.epilogues[].neutral",
                "journeyStory.ending.epilogues[].guarded");
        assertNull(JourneyReviewTexts.check(security, "openid-1", entries));
    }

    /**
     * R19:章节故事流写入路径(DTO)的 beat 块正文全部要收到 ——
     * 漏一条等于该条正文既不送检也不进审核详情,而它正是玩家读到的台词。
     *
     * <p>负控:让 collectChapterNarrativeBlocks 直接返回空清单,本用例立刻红。
     */
    @Test
    void collectsChapterStoryFlowBeatTextsFromWritePayload() {
        ChapterDTO chapter = new ChapterDTO();
        chapter.setBlocks(Arrays.asList(
                block("text", "章节旁白", null, null, null),
                block("node", null, null, null, null),
                block("text", "到店开场", "enter", "opener", null),
                block("text", "账本上写着什么", "enter", "q", "night"),
                block("text", "带上那一页", "brief", "carry", null)));

        List<JourneyReviewTexts.Entry> entries =
                JourneyReviewTexts.collectChapterNarrativeBlocks(Collections.singletonList(chapter));

        assertFields(entries, "到店开场",
                "storyFlow.chapter1.enter.opener",
                "storyFlow.chapter1.enter.q(night)",
                "storyFlow.chapter1.brief.carry");
        assertEquals(3, entries.size(), fields(entries).toString());
    }

    /**
     * R19:审核详情读的是存量 blocks(物化后 nodeId 已在),收 beat 块正文;
     * 非 beat 的章节旁白不在本轮口径内。
     *
     * <p>负控:让 collectChapterBlocks 直接返回空清单,本用例立刻红。
     */
    @Test
    void collectsChapterStoryFlowBeatTextsFromStoredBlocks() {
        String blocks = "[{\"type\":\"text\",\"content\":\"章节旁白\"},"
                + "{\"type\":\"node\",\"nodeId\":501},"
                + "{\"type\":\"text\",\"beat\":\"outcome\",\"field\":\"fact\",\"nodeId\":501,"
                + "\"content\":\"翻到过\",\"when\":{\"var\":\"clue.page\",\"op\":\"GTE\",\"value\":1}},"
                + "{\"type\":\"text\",\"beat\":\"deliver\",\"field\":\"reward\",\"nodeId\":501,"
                + "\"content\":\"账本残页\"}]";

        List<JourneyReviewTexts.Entry> entries = JourneyReviewTexts.collectChapterBlocks(blocks, 2);

        assertFields(entries, "翻到过",
                "storyFlow.chapter2.outcome.fact", "storyFlow.chapter2.deliver.reward");
        assertEquals(2, entries.size(), fields(entries).toString());
    }

    @Test
    void brokenChapterBlocksCollectNothing() {
        assertTrue(JourneyReviewTexts.collectChapterNarrativeBlocks(null).isEmpty());
        assertTrue(JourneyReviewTexts.collectChapterBlocks("{broken", 1).isEmpty());
        assertTrue(JourneyReviewTexts.collectChapterBlocks(null, 1).isEmpty());
        assertTrue(JourneyReviewTexts.collectChapterNarrativeBlocks(
                Collections.singletonList(new ChapterDTO())).isEmpty());
    }

    @Test
    void collectsStateKeyLabelsAndSkipsBlanks() {
        ThemeStateKey labelled = new ThemeStateKey();
        labelled.setStateKey("clue.ledger");
        labelled.setLabel("账本残页");
        ThemeStateKey blank = new ThemeStateKey();
        blank.setStateKey("clue.blank");
        blank.setLabel("   ");

        List<JourneyReviewTexts.Entry> entries =
                JourneyReviewTexts.collectLabels(Arrays.asList(labelled, blank, null));

        assertEquals(1, entries.size());
        assertEquals("themeStateKey.label", entries.get(0).getField());
        assertEquals("账本残页", entries.get(0).getText());
    }

    @Test
    void blankOrBrokenConfigCollectsNothingInsteadOfThrowing() {
        assertTrue(JourneyReviewTexts.collectJourneyStory(null).isEmpty());
        assertTrue(JourneyReviewTexts.collectJourneyStory("  ").isEmpty());
        assertTrue(JourneyReviewTexts.collectJourneyStory("{broken").isEmpty());
        assertTrue(JourneyReviewTexts.collectBlocks(null).isEmpty());
        assertTrue(JourneyReviewTexts.collectBlocks("[]").isEmpty());
        assertTrue(JourneyReviewTexts.collectBlocks("{broken").isEmpty());
    }

    @Test
    void checkPassesWhenBatchClean() {
        IWxContentSecurityService security = mock(IWxContentSecurityService.class);
        when(security.checkText(anyString(), any())).thenReturn(null);

        String msg = JourneyReviewTexts.check(security, "openid-1", Arrays.asList(
                new JourneyReviewTexts.Entry("a", "第一句"),
                new JourneyReviewTexts.Entry("b", "第二句")));

        assertNull(msg);
    }

    @Test
    void checkNamesViolatingFieldFromTheFailingBatch() {
        IWxContentSecurityService security = mock(IWxContentSecurityService.class);
        when(security.checkText(anyString(), any())).thenReturn(null);
        when(security.checkText(contains("违规台词"), any())).thenReturn("内容包含违规信息");

        String msg = JourneyReviewTexts.check(security, "openid-1", Arrays.asList(
                new JourneyReviewTexts.Entry("blockConfig.enter.opener", "正常开场"),
                new JourneyReviewTexts.Entry("blockConfig.outcome.line", "违规台词")));

        assertNotNull(msg);
        assertTrue(msg.contains("blockConfig.outcome.line"), msg);
    }

    @Test
    void checkSplitsOverLongTextIntoBatchesAndLocatesWithinTheFailingOne() {
        IWxContentSecurityService security = mock(IWxContentSecurityService.class);
        when(security.checkText(anyString(), any())).thenReturn(null);
        when(security.checkText(contains("违规"), any())).thenReturn("内容包含违规信息");
        List<JourneyReviewTexts.Entry> entries = new ArrayList<JourneyReviewTexts.Entry>();
        for (int i = 0; i < 4; i++) {
            entries.add(new JourneyReviewTexts.Entry("blockConfig.brief.body", filler('x', 600)));
        }
        entries.add(new JourneyReviewTexts.Entry("blockConfig.brief.body", filler('y', 596) + "违规"));

        String msg = JourneyReviewTexts.check(security, null, entries);

        assertNotNull(msg);
        assertTrue(msg.contains("blockConfig.brief.body"), msg);
        // 单次送检的正文不得超过单批上限 —— 塞一个 3000 字符的 body 会被微信当成审核不可用而 fail-close。
        org.mockito.ArgumentCaptor<String> sent = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(security, org.mockito.Mockito.atLeast(2)).checkText(sent.capture(), any());
        for (String content : sent.getAllValues()) {
            assertTrue(content.length() <= JourneyReviewTexts.MAX_BATCH_CHARS,
                    "单次送检 " + content.length() + " 字符,超过分批上限");
        }
    }

    private static final String CONFIG = "{"
            + "\"sort\":{\"enabled\":true,\"prompt\":\"按到访顺序排\",\"items\":["
            + "{\"id\":\"a\",\"label\":\"留白咖啡\"},{\"id\":\"b\",\"label\":\"失物书店\"}]},"
            + "\"match\":{\"enabled\":true,\"prompt\":\"把线索连到出处\",\"left\":["
            + "{\"id\":\"l1\",\"label\":\"剪掉的一分钟\"},{\"id\":\"l2\",\"label\":\"合照背后的名字\"}],"
            + "\"right\":[{\"id\":\"r1\",\"label\":\"录音带\"},{\"id\":\"r2\",\"label\":\"相纸\"}]},"
            + "\"classify\":{\"enabled\":true,\"prompt\":\"把材料分进两箱\",\"bins\":["
            + "{\"id\":\"b1\",\"label\":\"可公开\"},{\"id\":\"b2\",\"label\":\"需授权\"}],\"items\":["
            + "{\"id\":\"i1\",\"label\":\"公共演奏录音\"},{\"id\":\"i2\",\"label\":\"私人告别\"}]},"
            + "\"compare\":{\"enabled\":true,\"prompt\":\"两版录音哪里不一样\","
            + "\"left\":{\"label\":\"母带\",\"items\":["
            + "{\"id\":\"c1\",\"time\":\"0:12\",\"text\":\"一段留白\"},{\"id\":\"c2\",\"time\":\"1:30\",\"text\":\"关门声\"}]},"
            + "\"right\":{\"label\":\"播出带\",\"items\":["
            + "{\"id\":\"d1\",\"time\":\"0:12\",\"text\":\"留白被剪掉\"},{\"id\":\"d2\",\"time\":\"1:30\",\"text\":\"关门声\"}]},"
            + "\"answer\":[\"c1\"]},"
            + "\"hiddenObject\":{\"enabled\":true,\"title\":\"找东西\",\"hotspots\":["
            + "{\"id\":\"h1\",\"label\":\"不该被收进来的标签\"}]}}";

    @Test
    void collectsSortMatchClassifyAndCompareTexts() {
        List<JourneyReviewTexts.Entry> entries = JourneyReviewTexts.collectBlocks(CONFIG);

        assertEquals(Arrays.asList(
                "按到访顺序排", "留白咖啡", "失物书店",
                "把线索连到出处", "剪掉的一分钟", "合照背后的名字", "录音带", "相纸",
                "把材料分进两箱", "可公开", "需授权", "公共演奏录音", "私人告别",
                "两版录音哪里不一样", "母带", "0:12", "一段留白", "1:30", "关门声",
                "播出带", "0:12", "留白被剪掉", "1:30", "关门声"), texts(entries));
        assertFields(entries, "留白咖啡",
                "blockConfig.sort.prompt", "blockConfig.sort.items[].label",
                "blockConfig.match.prompt", "blockConfig.match.left[].label",
                "blockConfig.match.right[].label",
                "blockConfig.classify.prompt", "blockConfig.classify.bins[].label",
                "blockConfig.classify.items[].label");
        assertFields(entries, "留白被剪掉",
                "blockConfig.compare.prompt", "blockConfig.compare.left.label",
                "blockConfig.compare.left.items[].time", "blockConfig.compare.left.items[].text",
                "blockConfig.compare.right.label", "blockConfig.compare.right.items[].time",
                "blockConfig.compare.right.items[].text");
    }

    /** 负控:去掉 classify.items 的收集,本用例红 —— 送审会漏掉整箱卡片文案。 */
    @Test
    void collectsClassifyItemLabels() {
        List<JourneyReviewTexts.Entry> entries = JourneyReviewTexts.collectBlocks(CONFIG);

        assertTrue(texts(entries).contains("公共演奏录音"), fields(entries).toString());
        assertTrue(texts(entries).contains("私人告别"), fields(entries).toString());
    }

    /** 负控:去掉 compare 两侧条目的 time / text 收集,本用例红 —— 对照屏整屏文字漏检。 */
    @Test
    void collectsCompareTimelineTexts() {
        List<String> fields = fields(JourneyReviewTexts.collectBlocks(CONFIG));

        assertTrue(fields.contains("blockConfig.compare.left.items[].time"), fields.toString());
        assertTrue(fields.contains("blockConfig.compare.left.items[].text"), fields.toString());
        assertTrue(fields.contains("blockConfig.compare.right.items[].time"), fields.toString());
        assertTrue(fields.contains("blockConfig.compare.right.items[].text"), fields.toString());
    }

    @Test
    void onlyListedSectionsAreCollected() {
        assertFalse(texts(JourneyReviewTexts.collectBlocks(CONFIG)).contains("不该被收进来的标签"),
                "R14 的收集范围只有 sort / match / classify / compare 的题面与标签");
    }

    @Test
    void playSectionsIgnoreBlankAndWrongShapes() {
        assertTrue(JourneyReviewTexts.collectBlocks("{\"sort\":\"prompt\"}").isEmpty());
        assertTrue(JourneyReviewTexts.collectBlocks(
                "{\"classify\":{\"bins\":\"x\",\"items\":[null,1]}}").isEmpty());
        assertTrue(JourneyReviewTexts.collectBlocks(
                "{\"sort\":{\"prompt\":\"   \",\"items\":[{\"label\":\"\"}]}}").isEmpty());
        assertTrue(JourneyReviewTexts.collectBlocks(
                "{\"compare\":{\"prompt\":\" \",\"left\":\"x\",\"right\":{\"items\":[1,{},{\"time\":\" \"}]}}}")
                .isEmpty());
    }

    private static List<String> texts(List<JourneyReviewTexts.Entry> entries) {
        List<String> texts = new ArrayList<String>();
        for (JourneyReviewTexts.Entry entry : entries) {
            texts.add(entry.getText());
        }
        return texts;
    }

    private static ChapterBlockDTO block(String type, String content, String beat, String field, String qid) {
        ChapterBlockDTO block = new ChapterBlockDTO();
        block.setType(type);
        block.setContent(content);
        block.setBeat(beat);
        block.setField(field);
        block.setQid(qid);
        return block;
    }

    private static void assertFields(List<JourneyReviewTexts.Entry> entries, String sampleText,
                                     String... expectedFields) {
        List<String> fields = fields(entries);
        for (String field : expectedFields) {
            assertTrue(fields.contains(field), "漏收集字段 " + field + ",实际:" + fields);
        }
        boolean hasText = false;
        for (JourneyReviewTexts.Entry entry : entries) {
            if (sampleText.equals(entry.getText())) {
                hasText = true;
            }
        }
        assertTrue(hasText, "收集到的文本丢了正文");
    }

    private static List<String> fields(List<JourneyReviewTexts.Entry> entries) {
        List<String> fields = new ArrayList<String>();
        for (JourneyReviewTexts.Entry entry : entries) {
            fields.add(entry.getField());
        }
        return fields;
    }

    private static String filler(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
