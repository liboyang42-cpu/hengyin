package com.chengyinhub.web.service.play;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.mapper.GameRuntimeSessionMapper;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 章节呈现冻结的读侧契约（只跑真实投影代码，不跑 Spring / 存储 / HTTP）。
 *
 * <p>两条边界必须同时成立：
 * <ul>
 *   <li>新局（冻结行带呈现字段）只读冻结章节，作者之后改章节名/图/音频不影响进行中的局，
 *       也不再查实时章节表；</li>
 *   <li>老局（冻结行没有呈现字段）走原来的实时章节读取，且 blocks 仍优先用早已冻结的那一份 ——
 *       老快照不能因为缺新键被拒绝或被改写。</li>
 * </ul>
 */
class PlayFrozenChapterProjectionTest {

    private final ObjectMapper json = new ObjectMapper();

    private PlayNodeQueryApplication query(ICmsTopicChapterService chapters) {
        PlayNodeQueryApplication query = new PlayNodeQueryApplication();
        CmsTopic topic = new CmsTopic();
        topic.setId(21L); topic.setName("主题"); topic.setProductType(1);
        ICmsTopicService topics = mock(ICmsTopicService.class);
        when(topics.selectCmsTopicById(21L)).thenReturn(topic);
        IPlayerNodeProgressService progress = mock(IPlayerNodeProgressService.class);
        when(progress.selectPuzzlePersonalBest(any(), any())).thenReturn(null);
        GameRuntimeSessionMapper sessions = mock(GameRuntimeSessionMapper.class);
        when(sessions.selectStateJsonsByTopic(any(), any(), any())).thenReturn(new ArrayList<String>());
        when(sessions.selectPlayerSessionsByTopic(any(), any(), any())).thenReturn(new ArrayList<>());
        ReflectionTestUtils.setField(query, "cmsTopicService", topics);
        ReflectionTestUtils.setField(query, "cmsTopicChapterService", chapters);
        ReflectionTestUtils.setField(query, "playerNodeProgressService", progress);
        ReflectionTestUtils.setField(query, "gameRuntimeSessionMapper", sessions);
        return query;
    }

    private PlayNodeQueryApplication.NodeProjection read(ObjectNode snapshot, List<CmsTopicNode> nodes) {
        PlayNodeQueryApplication.NodeProjection read = new PlayNodeQueryApplication.NodeProjection(
                7L, true, 21L, 0L, null, true, false, null, nodes, List.of(),
                Map.of(), Map.of(), new HashSet<>(), 0, new HashSet<>(), new ArrayList<>(), null,
                1, null, false, null);
        read.contentSnapshot = snapshot;
        read.playableNodeIds = new HashSet<>();
        return read;
    }

    private CmsTopicNode node(long id, long chapterId, long templateId) {
        CmsTopicNode node = new CmsTopicNode();
        node.setId(id); node.setTopicId(21L); node.setChapterId(chapterId);
        node.setTemplateId(templateId); node.setStatus(1L); node.setSortId(id);
        return node;
    }

    private ObjectNode snapshot(String chapterName) {
        ObjectNode snapshot = json.createObjectNode();
        ObjectNode answer = snapshot.putObject("nodeAnswers").putObject("11");
        answer.put("contentSnapshotVersion", 1).put("templateId", 31L);
        answer.putObject("nodePresentation").put("id", 11L).put("topicId", 21L).put("chapterId", 20L);
        answer.putObject("templatePresentation").put("title", "原游戏").put("validationMethod", 1);
        answer.put("chapterAudioUrl", "chapter-audio");
        ObjectNode chapter = snapshot.putArray("thoughtChapters").addObject();
        chapter.put("chapterId", 20).put("authorIndex", 0).put("name", chapterName)
                .put("description", "章简介").put("audioUrl", "chapter-audio").put("required", true);
        chapter.putArray("blocks").addObject().put("type", "text").put("content", "冻结正文");
        return snapshot;
    }

    private CmsTopicChapter liveChapter(String name) {
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(20L); chapter.setTopicId(21L); chapter.setName(name);
        chapter.setDescription("实时简介"); chapter.setAudioUrl("live-audio"); chapter.setRequired(1);
        chapter.setBlocksJson("[{\"type\":\"text\",\"content\":\"实时正文\"}]");
        return chapter;
    }

    @Test
    void frozenChapterPresentationWinsAndLiveChapterTableIsNotRead() {
        ICmsTopicChapterService chapters = mock(ICmsTopicChapterService.class);
        when(chapters.selectCmsTopicChapterList(any())).thenReturn(List.of(liveChapter("作者改后的章名")));
        // presentedContents 让章节音频一致性有个参照:与冻结行一致。
        PlayNodeQueryApplication.NodeProjection read = read(snapshot("发布时的章名"),
                List.of(node(11L, 20L, 31L)));
        ObjectNode presented = json.createObjectNode().put("contentSnapshotVersion", 1);
        presented.putObject("nodePresentation").put("id", 11L).put("topicId", 21L).put("chapterId", 20L);
        presented.put("chapterAudioUrl", "chapter-audio");
        read.presentedContents.put(11L, presented);

        Map<String, Object> data = query(chapters).finishNodes(read);

        List<?> rows = (List<?>) data.get("chapters");
        Map<?, ?> chapter = (Map<?, ?>) rows.get(0);
        assertEquals("发布时的章名", chapter.get("name"));
        assertEquals("章简介", chapter.get("description"));
        assertEquals("chapter-audio", chapter.get("audioUrl"));
        // blocks 仍在首个未完成节点块之前照常下发
        List<?> blocks = (List<?>) chapter.get("blocks");
        assertEquals("冻结正文", ((Map<?, ?>) blocks.get(0)).get("content"));
        verify(chapters, never()).selectCmsTopicChapterList(any());
    }

    @Test
    void legacySnapshotWithoutPresentationStillReadsLiveChapterAndKeepsFrozenBlocks() {
        ICmsTopicChapterService chapters = mock(ICmsTopicChapterService.class);
        when(chapters.selectCmsTopicChapterList(any())).thenReturn(List.of(liveChapter("老局可见的章名")));
        ObjectNode snapshot = snapshot("ignored");
        // 章节呈现冻结上线前的形态:只有 blocks,没有 name/description/audioUrl/required。
        ObjectNode legacy = (ObjectNode) snapshot.path("thoughtChapters").get(0);
        for (String field : new String[]{"name", "description", "audioUrl", "required"}) legacy.remove(field);
        PlayNodeQueryApplication.NodeProjection read = read(snapshot, List.of(node(11L, 20L, 31L)));

        Map<String, Object> data = query(chapters).finishNodes(read);

        List<?> rows = (List<?>) data.get("chapters");
        Map<?, ?> chapter = (Map<?, ?>) rows.get(0);
        assertEquals("老局可见的章名", chapter.get("name"));
        assertEquals("live-audio", chapter.get("audioUrl"));
        assertEquals("冻结正文", ((Map<?, ?>) ((List<?>) chapter.get("blocks")).get(0)).get("content"));
        verify(chapters).selectCmsTopicChapterList(any());
    }

    @Test
    void identityIsTheExistingVersionPairAndAbsentUnlessTheRunHasFrozenContent() {
        ICmsTopicChapterService chapters = mock(ICmsTopicChapterService.class);
        when(chapters.selectCmsTopicChapterList(any())).thenReturn(List.of(liveChapter("章")));
        CmsTopic topic = new CmsTopic(); topic.setId(21L); topic.setPublishedConfigVersion(5L);

        Map<String, Object> without = query(chapters).finishNodes(read(snapshot("章"), List.of(node(11L, 20L, 31L))));
        assertEquals(1, without.get("contentSnapshotVersion"));
        assertFalse(without.containsKey("publishedConfigVersion"), "没取到主题行时不编发布号");

        PlayNodeQueryApplication.NodeProjection withTopic = new PlayNodeQueryApplication.NodeProjection(
                7L, true, 21L, 0L, null, true, false, null, List.of(node(11L, 20L, 31L)), List.of(),
                Map.of(), Map.of(), new HashSet<>(), 0, new HashSet<>(), new ArrayList<>(), null,
                1, topic, false, null);
        withTopic.contentSnapshot = snapshot("章");
        Map<String, Object> response = query(chapters).finishNodes(withTopic);
        assertEquals(5L, response.get("publishedConfigVersion"));
        assertEquals(1, response.get("contentSnapshotVersion"));
    }

    @Test
    void undeclaredClientCapabilityIsUnconfirmedNotUnsupported() {
        ICmsTopicChapterService chapters = mock(ICmsTopicChapterService.class);
        when(chapters.selectCmsTopicChapterList(any())).thenReturn(List.of(liveChapter("章")));
        Map<String, Object> data = query(chapters).finishNodes(read(snapshot("章"), List.of(node(11L, 20L, 31L))));
        // 投影是 JsonNode(ObjectNode):与 ContentCapabilityRequirements 的其它消费者同一形状,
        // 按 Map 强转只是测试里的旧假设。
        com.fasterxml.jackson.databind.JsonNode compatibility =
                (com.fasterxml.jackson.databind.JsonNode) data.get("compatibility");
        assertNotNull(compatibility);
        assertTrue(compatibility.path("supported").isNull());
        assertEquals("UNDECLARED", compatibility.path("source").asText());
        assertFalse(compatibility.path("requiredCapabilities").isEmpty());
        assertTrue(compatibility.path("missingCapabilities").isEmpty());
    }
}
