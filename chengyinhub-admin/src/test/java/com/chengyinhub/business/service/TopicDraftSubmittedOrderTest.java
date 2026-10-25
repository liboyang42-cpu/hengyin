package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.dto.ChapterDTO;
import com.chengyinhub.business.domain.dto.NodeDTO;
import com.chengyinhub.business.domain.dto.TopicCreateDTO;
import com.chengyinhub.business.domain.dto.TopicUpdateDTO;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TopicDraftSubmittedOrderTest {
    private static final Long TOPIC = 700L;
    private static final Long MEMBER = 42L;
    private CmsTopicServiceImpl service;
    private CmsTopicMapper topics;
    private CmsTopicChapterMapper chapters;
    private CmsTopicNodeMapper nodes;
    private List<CmsTopicChapter> chapterRows;
    private List<CmsTopicNode> nodeRows;

    @BeforeEach
    void setUp() throws IllegalAccessException {
        service = new CmsTopicServiceImpl();
        // 仅装配注入依赖；保存/重建方法保持真实执行，普通字段保留自身初始值。
        for (Field field : CmsTopicServiceImpl.class.getDeclaredFields()) {
            if (!field.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class)) continue;
            field.setAccessible(true);
            if (field.get(service) == null) field.set(service, mock(field.getType()));
        }
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.wireRelocatedCollaborators(service, publicationField -> org.mockito.Mockito.mock(publicationField.getType()));
        topics = (CmsTopicMapper) ReflectionTestUtils.getField(service, "cmsTopicMapper");
        chapters = (CmsTopicChapterMapper) ReflectionTestUtils.getField(service, "cmsTopicChapterMapper");
        nodes = (CmsTopicNodeMapper) ReflectionTestUtils.getField(service, "cmsTopicNodeMapper");
        when(topics.insertCmsTopic(any())).thenAnswer(inv -> {
            CmsTopic inserted = inv.getArgument(0);
            inserted.setId(TOPIC);
            // The parent lock must read the actual inserted owner/topic row, not anyLong success.
            org.mockito.Mockito.doReturn(inserted).when(topics).selectCmsTopicById(TOPIC);
            return 1;
        });
        when(topics.updateCmsTopic(any())).thenReturn(1);
        chapterRows = new java.util.ArrayList<>();
        nodeRows = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(call -> new java.util.ArrayList<>(chapterRows))
                .when(chapters).selectCmsTopicChapterList(any());
        org.mockito.Mockito.doAnswer(call -> new java.util.ArrayList<>(nodeRows))
                .when(nodes).selectCmsTopicNodeList(any());
        org.mockito.Mockito.doAnswer(call -> chapterRows.removeIf(row -> row.getId().equals(call.getArgument(0))) ? 1 : 0)
                .when(chapters).deleteCmsTopicChapterById(any());
        org.mockito.Mockito.doAnswer(call -> nodeRows.removeIf(row -> row.getId().equals(call.getArgument(0))) ? 1 : 0)
                .when(nodes).deleteCmsTopicNodeById(any());
        AtomicLong chapterIds = new AtomicLong(800L);
        AtomicLong nodeIds = new AtomicLong(900L);
        when(chapters.insertCmsTopicChapter(any())).thenAnswer(inv -> {
            CmsTopicChapter chapter = inv.getArgument(0);
            assertNull(chapter.getId(), "重建不能复用客户端旧章 ID");
            chapter.setId(chapterIds.incrementAndGet());
            chapterRows.add(chapter);
            return 1;
        });
        when(nodes.insertCmsTopicNode(any())).thenAnswer(inv -> {
            CmsTopicNode node = inv.getArgument(0);
            assertNull(node.getId(), "重建不能复用客户端旧站点 ID");
            node.setId(nodeIds.incrementAndGet());
            nodeRows.add(node);
            return 1;
        });
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(service, "themeStateKeyService",
                new com.chengyinhub.business.service.support.ThemeStateKeyService(
                        mock(com.chengyinhub.business.mapper.ThemeStateKeyMapper.class)));
        service = com.chengyinhub.business.service.support.TopicPublicationTestWiring.transactional(service);
    }

    @Test
    void createDraftAssignsChapterAndPerChapterNodeOrderFromSubmittedArrays() {
        TopicCreateDTO dto = draft(new TopicCreateDTO());
        dto.setChapters(Arrays.asList(chapter("章甲", 11L, "甲1", "甲2"),
                chapter("章乙", 12L, "乙1", "乙2")));

        assertEquals(TOPIC, com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(service).createTopic(dto, MEMBER, "creator"));

        assertInsertedOrder(Arrays.asList("章甲", "章乙"), Arrays.asList("甲1", "甲2", "乙1", "乙2"));
        verify(topics).updateTopicDraftState(TOPIC, 1);
    }

    @Test
    void rebuildDraftUsesNewSubmittedOrderAndRestartsNodeOrderForEachChapter() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setMemberId(MEMBER);
        topic.setStatus(0);
        topic.setLifecycle(0);
        topic.setDelFlag(0);
        topic.setProductType(ProductType.FREE_EXPLORE);
        when(topics.selectCmsTopicById(TOPIC)).thenReturn(topic);
        when(topics.selectTopicIsDraft(TOPIC)).thenReturn(1);
        CmsTopicChapter oldChapter = new CmsTopicChapter();
        oldChapter.setId(11L);
        oldChapter.setTopicId(TOPIC);
        CmsTopicNode oldNode = new CmsTopicNode();
        oldNode.setId(101L);
        oldNode.setTopicId(TOPIC);
        oldNode.setStatus(1L);
        // The original old rows and subsequent inserts/deletes share one fixture table.
        chapterRows.add(oldChapter);
        nodeRows.add(oldNode);
        TopicUpdateDTO dto = draft(new TopicUpdateDTO());
        dto.setId(TOPIC);
        ChapterDTO first = chapter("章乙", 12L, "乙1", "乙2");
        ChapterDTO second = chapter("章甲", 11L, "甲1", "甲2");
        Collections.reverse(first.getNodes());
        Collections.reverse(second.getNodes());
        dto.setChapters(Arrays.asList(first, second));

        assertEquals(TOPIC, com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(service).updateTopicByOwner(dto, MEMBER, "creator"));

        verify(chapters).deleteCmsTopicChapterById(11L);
        verify(nodes).deleteCmsTopicNodeById(101L);
        verify(topics, never()).insertCmsTopic(any());
        assertInsertedOrder(Arrays.asList("章乙", "章甲"), Arrays.asList("乙2", "乙1", "甲2", "甲1"));
    }

    private <T extends TopicCreateDTO> T draft(T dto) {
        dto.setName("顺序草稿");
        dto.setProductType(ProductType.FREE_EXPLORE);
        dto.setPublishMode("ai_simple");
        dto.setSaveDraft(1);
        dto.setTickets(Collections.emptyList());
        dto.setCollaboratorIds(Collections.emptyList());
        return dto;
    }

    private ChapterDTO chapter(String name, Long oldId, String first, String second) {
        ChapterDTO chapter = new ChapterDTO();
        chapter.setId(oldId);
        chapter.setName(name);
        NodeDTO a = new NodeDTO();
        a.setId(oldId * 10 + 1);
        a.setName(first);
        a.setNodeTime(1);
        a.setSortID(90);
        NodeDTO b = new NodeDTO();
        b.setId(oldId * 10 + 2);
        b.setName(second);
        b.setNodeTime(1);
        b.setSortID(-7);
        chapter.setNodes(Arrays.asList(a, b));
        return chapter;
    }

    private void assertInsertedOrder(List<String> chapterNames, List<String> nodeNames) {
        ArgumentCaptor<CmsTopicChapter> chapterCaptor = ArgumentCaptor.forClass(CmsTopicChapter.class);
        ArgumentCaptor<CmsTopicNode> nodeCaptor = ArgumentCaptor.forClass(CmsTopicNode.class);
        verify(chapters, times(2)).insertCmsTopicChapter(chapterCaptor.capture());
        verify(nodes, times(4)).insertCmsTopicNode(nodeCaptor.capture());
        List<CmsTopicChapter> writtenChapters = chapterCaptor.getAllValues();
        List<CmsTopicNode> writtenNodes = nodeCaptor.getAllValues();
        assertEquals(chapterNames, writtenChapters.stream().map(CmsTopicChapter::getName).collect(Collectors.toList()));
        assertEquals(Arrays.asList(1L, 2L), writtenChapters.stream().map(CmsTopicChapter::getSortId).collect(Collectors.toList()));
        assertEquals(nodeNames, writtenNodes.stream().map(CmsTopicNode::getName).collect(Collectors.toList()));
        assertEquals(Arrays.asList(1L, 2L, 1L, 2L), writtenNodes.stream().map(CmsTopicNode::getSortId).collect(Collectors.toList()));
        for (int i = 0; i < writtenNodes.size(); i++) {
            assertEquals(writtenChapters.get(i / 2).getId(), writtenNodes.get(i).getChapterId());
            assertEquals(TOPIC, writtenNodes.get(i).getTopicId());
        }
        writtenChapters.forEach(chapter -> assertEquals(TOPIC, chapter.getTopicId()));
    }
}
