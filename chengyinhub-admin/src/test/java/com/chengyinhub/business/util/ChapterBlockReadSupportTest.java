package com.chengyinhub.business.util;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.common.constant.ProductType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChapterBlockReadSupportTest
{
    private final Logger supportLogger = (Logger) LoggerFactory.getLogger(ChapterBlockReadSupport.class);
    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

    @AfterEach
    void tearDown()
    {
        supportLogger.detachAppender(logAppender);
    }

    @Test
    void legacyFallbackPreservesParagraphRenderingAndOrdersNullSortLast()
    {
        String firstParagraph = "雨夜，巷口那盏灯还亮着";
        String secondParagraph = "老板说，这灯从没关过";
        String description = "  " + firstParagraph + "\n\n" + secondParagraph + "  ";
        CmsTopicChapter chapter = chapter(71L, description, null);
        CmsTopicNode unsorted = node(303L, null);
        CmsTopicNode second = node(202L, 20L);
        CmsTopicNode first = node(101L, 10L);

        List<Object> blocks = ChapterBlockReadSupport.resolve(
                chapter, Arrays.asList(unsorted, second, first));

        Map<?, ?> textBlock = (Map<?, ?>) blocks.get(0);
        assertEquals(ChapterBlockReadSupport.TYPE_TEXT,
                textBlock.get(ChapterBlockReadSupport.KEY_TYPE));
        assertSame(description, textBlock.get(ChapterBlockReadSupport.KEY_CONTENT));
        chapter.setBlocks(blocks);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode wireChapter = mapper.valueToTree(chapter);
        List<?> deliveredBlocks = mapper.convertValue(wireChapter.path("blocks"), List.class);
        assertEquals(legacyPlayerParagraphs(description),
                ChapterBlockReadSupport.textParagraphs(deliveredBlocks));
        assertEquals(Arrays.asList(first.getId(), second.getId(), unsorted.getId()),
                nodeIds(blocks));
    }

    @Test
    void storedArrayIsReturnedWithoutLegacyFallback() throws Exception
    {
        Map<String, Object> storedText = new LinkedHashMap<>();
        storedText.put(ChapterBlockReadSupport.KEY_TYPE, ChapterBlockReadSupport.TYPE_TEXT);
        storedText.put(ChapterBlockReadSupport.KEY_CONTENT, "已经物化的正文");
        List<Object> stored = Collections.<Object>singletonList(storedText);
        CmsTopicChapter chapter = chapter(72L, "旧 description 不应覆盖物化内容",
                new ObjectMapper().writeValueAsString(stored));

        List<Object> resolved = ChapterBlockReadSupport.resolve(
                chapter, Collections.singletonList(node(999L, 1L)));

        assertEquals(stored, resolved);
    }

    @Test
    void invalidStoredJsonWarnsWithChapterIdAndFallsBack()
    {
        Long chapterId = 73L;
        String description = "第一段\n第二段";
        CmsTopicChapter chapter = chapter(chapterId, description, "[");
        logAppender.start();
        supportLogger.addAppender(logAppender);

        List<Object> resolved = ChapterBlockReadSupport.resolve(chapter, Collections.emptyList());

        assertEquals(legacyPlayerParagraphs(description),
                ChapterBlockReadSupport.textParagraphs(resolved));
        assertTrue(logAppender.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
                && Arrays.asList(event.getArgumentArray()).contains(chapterId)));
    }

    @Test
    void trailingJsonTokenWarnsAndFallsBack() throws Exception
    {
        Long chapterId = 74L;
        String description = "尾随内容不能被静默接受";
        String arrayWithTrailingDocument = new ObjectMapper()
                .writeValueAsString(Collections.emptyList()) + " {}";
        CmsTopicChapter chapter = chapter(chapterId, description, arrayWithTrailingDocument);
        logAppender.start();
        supportLogger.addAppender(logAppender);

        List<Object> resolved = ChapterBlockReadSupport.resolve(chapter, Collections.emptyList());

        assertEquals(legacyPlayerParagraphs(description),
                ChapterBlockReadSupport.textParagraphs(resolved));
        assertTrue(logAppender.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
                && Arrays.asList(event.getArgumentArray()).contains(chapterId)));
    }

    @Test
    void topicNodeProjectionKeepsOnlyTheRequestedChapter()
    {
        CmsTopicChapter chapter = chapter(75L, "章节正文", null);
        CmsTopicNode ownNode = node(501L, 2L);
        ownNode.setChapterId(chapter.getId());
        CmsTopicNode otherChapterNode = node(502L, 1L);
        otherChapterNode.setChapterId(chapter.getId() + 1);

        List<Object> resolved = ChapterBlockReadSupport.resolveFromTopicNodes(
                chapter, Arrays.asList(otherChapterNode, ownNode));

        assertEquals(Collections.singletonList(ownNode.getId()), nodeIds(resolved));
    }

    @Test
    void bothProductModesResolveBlocksFromTopicOrLegacyTicketMode()
    {
        OmsTicket legacyFreeExploreTicket = new OmsTicket();
        legacyFreeExploreTicket.setMode(ProductType.FREE_EXPLORE);

        assertTrue(ChapterBlockReadSupport.shouldResolveBlocks(
                ProductType.FREE_EXPLORE, Collections.emptyList()));
        assertTrue(ChapterBlockReadSupport.shouldResolveBlocks(
                null, Collections.singletonList(legacyFreeExploreTicket)));
        assertTrue(ChapterBlockReadSupport.shouldResolveBlocks(
                ProductType.ORIENTEERING, Collections.emptyList()));
    }

    @Test
    void playerProjectionStopsAtFirstIncompleteNodeAndHidesFollowingBlocks()
    {
        List<Object> blocks = Arrays.<Object>asList(
                textBlock("序章"), nodeBlock(101L), textBlock("已解锁"),
                nodeBlock(102L), textBlock("未解锁剧透"), nodeBlock(103L));
        Set<Long> completed = new LinkedHashSet<>(Collections.singletonList(101L));

        List<Object> projected = ChapterBlockReadSupport.projectForPlayer(blocks, completed);

        assertEquals(Arrays.<Object>asList(
                textBlock("序章"), nodeBlock(101L), textBlock("已解锁"), nodeBlock(102L)), projected);
    }

    @Test
    void playerProjectionHidesPostArrivalNarrativeOfIncompleteStation()
    {
        Map<String, Object> enterBeat = beatBlock("enter", "opener", "阿荣抬头看你", 101L);
        Map<String, Object> outcomeBeat = beatBlock("outcome", "line", "阿荣松口了", 101L);
        Map<String, Object> deliverBeat = beatBlock("deliver", "reward", "阿荣的旧钥匙", 101L);
        List<Object> blocks = Arrays.<Object>asList(enterBeat, nodeBlock(101L), outcomeBeat, deliverBeat);

        List<Object> projected = ChapterBlockReadSupport.projectForPlayer(
                blocks, Collections.<Long>emptySet());

        assertEquals(Arrays.<Object>asList(enterBeat, nodeBlock(101L)), projected,
                "到店前只下发进场段,结论与交付段在首个未完成节点块之后被切掉");
    }

    @Test
    void playerProjectionReturnsAllBlocksWhenEveryNodeIsComplete()
    {
        List<Object> blocks = Arrays.<Object>asList(
                textBlock("序章"), nodeBlock(101L), textBlock("尾声"));

        assertEquals(blocks, ChapterBlockReadSupport.projectForPlayer(
                blocks, Collections.singleton(101L)));
    }

    @Test
    void legacyTopicWithoutModeKeepsLinearReadDefault()
    {
        OmsTicket legacyOrienteeringTicket = new OmsTicket();
        legacyOrienteeringTicket.setMode(ProductType.ORIENTEERING);

        assertTrue(ChapterBlockReadSupport.shouldResolveBlocks(
                null, Collections.singletonList(legacyOrienteeringTicket)));
        assertTrue(ChapterBlockReadSupport.shouldResolveBlocks(null, Collections.emptyList()));
    }

    @Test
    void optionalChapterStaysOutsideTheStoryFlow()
    {
        CmsTopicChapter required = chapter(76L, "主线章节", null);
        required.setRequired(1);
        CmsTopicChapter optional = chapter(77L, "可选章节", null);
        optional.setRequired(0);

        assertTrue(ChapterBlockReadSupport.isRequiredChapter(required));
        assertFalse(ChapterBlockReadSupport.isRequiredChapter(optional));
    }

    private static CmsTopicChapter chapter(Long id, String description, String blocksJson)
    {
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(id);
        chapter.setDescription(description);
        chapter.setBlocksJson(blocksJson);
        return chapter;
    }

    private static CmsTopicNode node(Long id, Long sortId)
    {
        CmsTopicNode node = new CmsTopicNode();
        node.setId(id);
        node.setSortId(sortId);
        return node;
    }

    private static Map<String, Object> textBlock(String content)
    {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put(ChapterBlockReadSupport.KEY_TYPE, ChapterBlockReadSupport.TYPE_TEXT);
        block.put(ChapterBlockReadSupport.KEY_CONTENT, content);
        return block;
    }

    private static Map<String, Object> nodeBlock(Long nodeId)
    {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put(ChapterBlockReadSupport.KEY_TYPE, ChapterBlockReadSupport.TYPE_NODE);
        block.put(ChapterBlockReadSupport.KEY_NODE_ID, nodeId);
        return block;
    }

    /** R18 叙事块(存量形状):text + beat + field + nodeId。 */
    private static Map<String, Object> beatBlock(String beat, String field, String content, Long nodeId)
    {
        Map<String, Object> block = new LinkedHashMap<>(textBlock(content));
        block.put("beat", beat);
        block.put("field", field);
        block.put(ChapterBlockReadSupport.KEY_NODE_ID, nodeId);
        return block;
    }

    private static List<Long> nodeIds(List<Object> blocks)
    {
        return blocks.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(block -> ChapterBlockReadSupport.TYPE_NODE.equals(
                        block.get(ChapterBlockReadSupport.KEY_TYPE)))
                .map(block -> (Long) block.get(ChapterBlockReadSupport.KEY_NODE_ID))
                .collect(Collectors.toList());
    }

    /** 迁移前 pages/play/index.js openChapterFull 的独立语义模型。 */
    private static List<String> legacyPlayerParagraphs(String description)
    {
        List<String> paragraphs = new ArrayList<>();
        for (String part : String.valueOf(description == null ? "" : description).split("\\n+"))
        {
            String value = part.trim();
            if (!value.isEmpty())
            {
                paragraphs.add(value);
            }
        }
        return paragraphs;
    }
}
