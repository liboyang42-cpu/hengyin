package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.service.support.TopicStateKeyRefService;
import com.chengyinhub.business.service.support.TopicStateKeyRefService.RefCollection;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R8 状态键引用收集/删除前查引用:节点块 + 主题数值规则 + 剧情配置三处来源;
 * 坏配置 fail-closed,不能把「读不出引用」当成「没有引用」。
 */
class TopicStateKeyRefServiceTest {

    private static final Long TOPIC_ID = 31L;
    private static final Long NODE_ID = 501L;
    private static final Long TEMPLATE_ID = 601L;

    private static final String JOURNEY_STORY = "{\"schemaVersion\":1,"
            + "\"stages\":[{\"chapterId\":11,\"end\":{\"changed\":["
            + "{\"when\":{\"var\":\"clue.page\",\"op\":\"GTE\",\"value\":1},\"text\":\"a\"},"
            + "{\"when\":{\"op\":\"HAS_TAG\",\"value\":\"tag.hurried\"},\"text\":\"b\"}]}}],"
            + "\"ending\":{\"endings\":[{\"code\":\"b\",\"title\":\"B\",\"fallback\":true}],"
            + "\"optionalClues\":[\"clue.voice\"]}}";

    private CmsTopicNodeMapper nodeMapper;
    private CmsMemberTemplateMapper templateMapper;
    private CmsTopicChapterMapper chapterMapper;
    private TopicStateKeyRefService service;

    @BeforeEach
    void setUp() {
        nodeMapper = mock(CmsTopicNodeMapper.class);
        templateMapper = mock(CmsMemberTemplateMapper.class);
        chapterMapper = mock(CmsTopicChapterMapper.class);
        service = new TopicStateKeyRefService(nodeMapper, templateMapper, chapterMapper);
    }

    @Test
    void referencesOfSeesNodeBlockRef() {
        stubNodeAndTemplate("{\"schemaVersion\":1,"
                + "\"requires\":[{\"var\":\"clue.ledger_page\",\"op\":\"EQ\",\"value\":1}]}");

        assertEquals(Collections.singletonList("节点「书房」"),
                service.referencesOf(topic(null), "clue.ledger_page"));
    }

    @Test
    void referencesOfSeesJourneyRulesExhaustTag() {
        CmsTopic topic = topic("{\"schemaVersion\":1,\"checkEnabled\":true,"
                + "\"recovery\":{\"exhaustTag\":\"tag.injured\"}}");

        List<String> positions = service.referencesOf(topic, "tag.injured");

        assertEquals(Collections.singletonList("主题数值规则"), positions);
    }

    /**
     * R22-SEC S15:checkEnabled=false 只关检定,不豁免 exhaustTag 的注册闸 ——
     * 该 tag 运行期(力竭休整)仍会被写进本局,未注册不能放行。
     *
     * <p>负控:collectJourneyRules 改回 checkEnabled=false 提前 return,本用例立刻红。
     */
    @Test
    void exhaustTagStillCollectedWhenCheckDisabled() {
        CmsTopic topic = topic("{\"schemaVersion\":1,\"checkEnabled\":false,"
                + "\"recovery\":{\"exhaustTag\":\"tag.injured\"}}");

        RefCollection refs = service.collect(topic);

        assertEquals("tag.injured", refs.getExhaustTag());
        assertFalse(refs.isJourneyRulesCheckEnabled(), "建局判定仍按 checkEnabled");
        assertEquals(Collections.singletonList("主题数值规则"),
                service.referencesOf(topic, "tag.injured"));
    }

    @Test
    void referencesOfIsEmptyForUnreferencedKey() {
        stubNodeAndTemplate("{\"schemaVersion\":1,"
                + "\"requires\":[{\"var\":\"clue.ledger_page\",\"op\":\"EQ\",\"value\":1}]}");

        assertTrue(service.referencesOf(topic(null), "counter.coins").isEmpty());
    }

    /** 同一个键被节点与主题数值规则同时引用时,两个位置都要列出来。 */
    @Test
    void referencesOfListsEveryPosition() {
        stubNodeAndTemplate("{\"schemaVersion\":1,"
                + "\"requires\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.injured\"}]}");
        CmsTopic topic = topic("{\"schemaVersion\":1,\"checkEnabled\":true,"
                + "\"recovery\":{\"exhaustTag\":\"tag.injured\"}}");

        assertEquals(Arrays.asList("节点「书房」", "主题数值规则"),
                service.referencesOf(topic, "tag.injured"));
    }

    @Test
    void referencesOfFailsClosedWhenNodeConfigUnparsable() {
        stubNodeAndTemplate("{broken");

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.referencesOf(topic(null), "counter.coins"));

        assertTrue(error.getMessage().contains("书房"), error.getMessage());
    }

    @Test
    void referencesOfFailsClosedWhenJourneyRulesUnparsable() {
        ServiceException error = assertThrows(ServiceException.class,
                () -> service.referencesOf(topic("{broken"), "counter.coins"));

        assertTrue(error.getMessage().contains("旅程"), error.getMessage());
    }

    /** 空 / 缺省剧情配置按「没引用」处理,老主题不能因为新列的存在被拒。 */
    @Test
    void collectSkipsBlankJourneyStory() {
        RefCollection refs = service.collect(topic(null));

        assertTrue(refs.getStoryKeys().isEmpty());
        assertTrue(refs.getParseErrors().isEmpty());
    }

    /**
     * 剧情配置的 cond 与 optionalClues 引用同一份位置清单:删键被拒时要能列出「主题剧情配置」。
     *
     * <p>负控:把 collectJourneyStory 里的 collectStoryKeys 摘掉,本用例立刻红。
     */
    @Test
    void referencesOfSeesJourneyStoryConditionsAndOptionalClues() {
        CmsTopic topic = topicWithStory(JOURNEY_STORY);

        assertEquals(Collections.singletonList("主题剧情配置"), service.referencesOf(topic, "clue.page"));
        assertEquals(Collections.singletonList("主题剧情配置"), service.referencesOf(topic, "clue.voice"));
    }

    /** 模型解析收键:cond 走 StateKeyRefs.collectWhen,optionalClues 是裸键名。 */
    @Test
    void collectSeesJourneyStoryKeysFromModel() {
        RefCollection refs = service.collect(topicWithStory(JOURNEY_STORY));

        assertTrue(refs.getStoryKeys().contains("clue.page"), refs.getStoryKeys().toString());
        assertTrue(refs.getStoryKeys().contains("tag.hurried"), refs.getStoryKeys().toString());
        assertTrue(refs.getStoryKeys().contains("clue.voice"), refs.getStoryKeys().toString());
        assertTrue(refs.getParseErrors().isEmpty(), refs.getParseErrors().toString());
    }

    /**
     * R11-C 解冲突口径:尾声的 relationKey 与 when 条件一样收进引用集合,
     * 删键时能列出「主题剧情配置」(R8-STORY 那份收集不覆盖本服务)。
     *
     * <p>负控:把 collectJourneyStory 里的 collectJourneyStoryRelationKeys 并入摘掉,本用例立刻红。
     */
    @Test
    void referencesOfSeesEpilogueRelationKey() {
        CmsTopic topic = topicWithStory("{\"schemaVersion\":1,"
                + "\"ending\":{\"endings\":[{\"code\":\"b\",\"title\":\"B\",\"fallback\":true}],"
                + "\"epilogues\":[{\"npcId\":7,\"relationKey\":\"relation.arong\","
                + "\"ally\":\"a\",\"neutral\":\"b\",\"guarded\":\"c\"}]}}");

        assertEquals(Collections.singletonList("主题剧情配置"), service.referencesOf(topic, "relation.arong"));
    }

    /**
     * R19:叙事搬进章节故事流后,beat 块 when 引用的键必须进引用集合 ——
     * 删键时能列出「章节故事流」,发布闸/试检查也才有这份事实可用。
     *
     * <p>负控:摘掉 collectChapters 的 chapterKeys.addAll,本用例立刻红。
     */
    @Test
    void referencesOfSeesChapterStoryFlowConditions() {
        stubChapter("[{\"type\":\"text\",\"beat\":\"brief\",\"field\":\"carry\",\"nodeId\":501,"
                + "\"content\":\"带上那一页\",\"when\":{\"var\":\"clue.page\",\"op\":\"GTE\",\"value\":1}},"
                + "{\"type\":\"text\",\"beat\":\"outcome\",\"field\":\"fact\",\"nodeId\":501,"
                + "\"content\":\"翻到过\",\"when\":{\"op\":\"HAS_TAG\",\"value\":\"tag.hurried\"}}]");

        assertEquals(Collections.singletonList("章节故事流"), service.referencesOf(topic(null), "clue.page"));
        assertEquals(Collections.singletonList("章节故事流"), service.referencesOf(topic(null), "tag.hurried"));
    }

    /** 非叙事块上的 when 不是真源(写侧只允许 beat 块带 when),收集按 beat 标签过滤。 */
    @Test
    void collectChapterKeysSkipsBlocksWithoutBeat() {
        stubChapter("[{\"type\":\"text\",\"content\":\"旁白\","
                + "\"when\":{\"var\":\"clue.page\",\"op\":\"GTE\",\"value\":1}}]");

        RefCollection refs = service.collect(topic(null));

        assertTrue(refs.getChapterKeys().isEmpty(), refs.getChapterKeys().toString());
    }

    /**
     * R22-SEC S14:章节 blocks 坏 JSON 记 ParseError(与节点块同一口径),删除前查引用 fail-closed;
     * 读不出引用不能当成「没引用」放行删键,发布闸也据此拦发布。
     *
     * <p>负控:collectChapters 改回裸 addAll(不接 ParseError),本用例立刻红。
     */
    @Test
    void chapterBlocksBrokenJsonFailsClosedLikeNodeBlocks() {
        stubChapter("{broken");

        RefCollection refs = service.collect(topic(null));
        assertEquals(1, refs.getParseErrors().size(), refs.getParseErrors().toString());
        assertEquals(TopicStateKeyRefService.KIND_CHAPTER_BLOCKS, refs.getParseErrors().get(0).getKind());
        assertTrue(refs.getChapterKeys().isEmpty());

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.referencesOf(topic(null), "counter.coins"));
        assertTrue(error.getMessage().contains("章节故事流"), error.getMessage());
    }

    /** 剧情配置结构坏了 = 读不出引用:删除 fail-closed,不能当「没被引用」放行。 */
    @Test
    void referencesOfFailsClosedWhenJourneyStoryUnparsable() {
        ServiceException error = assertThrows(ServiceException.class,
                () -> service.referencesOf(topicWithStory("{broken"), "counter.coins"));

        assertTrue(error.getMessage().contains("剧情"), error.getMessage());
    }

    /** 剧情配置缺 schemaVersion(过不了模型校验)同样是解析错误,不静默跳过。 */
    @Test
    void referencesOfFailsClosedWhenJourneyStoryInvalid() {
        ServiceException error = assertThrows(ServiceException.class,
                () -> service.referencesOf(topicWithStory("{\"stages\":[]}"), "counter.coins"));

        assertTrue(error.getMessage().contains("剧情"), error.getMessage());
    }

    private void stubNodeAndTemplate(String advancedConfigJson) {
        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE_ID);
        node.setTopicId(TOPIC_ID);
        node.setName("书房");
        node.setTemplateId(TEMPLATE_ID);
        node.setStatus(1L);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.singletonList(node));
        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setId(TEMPLATE_ID);
        template.setAdvancedConfigJson(advancedConfigJson);
        when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template);
    }

    /** 该主题现存章节;blocksJson 传 null = 没有故事流的老主题。 */
    private void stubChapter(String blocksJson) {
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(701L);
        chapter.setTopicId(TOPIC_ID);
        chapter.setBlocksJson(blocksJson);
        when(chapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class)))
                .thenReturn(Collections.singletonList(chapter));
    }

    private static CmsTopic topic(String journeyRulesJson) {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setJourneyRulesJson(journeyRulesJson);
        return topic;
    }

    private static CmsTopic topicWithStory(String journeyStoryJson) {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setJourneyStoryJson(journeyStoryJson);
        return topic;
    }
}
