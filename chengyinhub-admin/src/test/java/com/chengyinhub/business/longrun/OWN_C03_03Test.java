package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** NOT_RUN. Real owner-update mapping path with stub persistence; no database/rollback proof. */
class OWN_C03_03Test {
    private static final Long TOPIC = 700L;
    private static final Long MEMBER = 42L;
    private CmsTopicServiceImpl service;
    private CmsTopicNodeMapper nodes;
    private CmsTopicChapterMapper chapters;

    @BeforeEach
    void setup() throws Exception {
        service = new CmsTopicServiceImpl();
        for (Field field : CmsTopicServiceImpl.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
            field.setAccessible(true);
            if (field.get(service) == null && field.isAnnotationPresent(Autowired.class)) {
                ReflectionTestUtils.setField(service, field.getName(), mock(field.getType()));
            }
        }
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.wireRelocatedCollaborators(service, publicationField -> org.mockito.Mockito.mock(publicationField.getType()));
        CmsTopicMapper topics = (CmsTopicMapper) ReflectionTestUtils.getField(service, "cmsTopicMapper");
        nodes = (CmsTopicNodeMapper) ReflectionTestUtils.getField(service, "cmsTopicNodeMapper");
        chapters = (CmsTopicChapterMapper) ReflectionTestUtils.getField(service, "cmsTopicChapterMapper");
        OmsTicketMapper tickets = (OmsTicketMapper) ReflectionTestUtils.getField(service, "omsTicketMapper");
        CmsTopic existing = new CmsTopic();
        existing.setId(TOPIC);
        existing.setMemberId(MEMBER);
        existing.setName("已有前置关系的草稿");
        existing.setStatus(0);
        existing.setLifecycle(0);
        existing.setDelFlag(0);
        existing.setProductType(ProductType.FREE_EXPLORE);
        existing.setPublishMode("simple");
        when(topics.selectCmsTopicById(TOPIC)).thenReturn(existing);
        when(topics.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(existing);
        when(topics.selectTopicIsDraft(TOPIC)).thenReturn(1);
        when(topics.updateCmsTopic(any(CmsTopic.class))).thenReturn(1);
        when(tickets.selectOmsTicketList(any(OmsTicket.class))).thenReturn(Collections.emptyList());
        when(chapters.selectCmsTopicChapterList(any(CmsTopicChapter.class))).thenReturn(Collections.emptyList());
        CmsTopicNode first = stored(11L, 1L, null);
        CmsTopicNode dependent = stored(12L, 2L, 11L);
        when(nodes.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenReturn(Arrays.asList(first, dependent));
        AtomicLong chapterIds = new AtomicLong(800L);
        AtomicLong nodeIds = new AtomicLong(900L);
        doAnswer(call -> { ((CmsTopicChapter) call.getArgument(0)).setId(chapterIds.incrementAndGet()); return 1; })
                .when(chapters).insertCmsTopicChapter(any(CmsTopicChapter.class));
        doAnswer(call -> { ((CmsTopicNode) call.getArgument(0)).setId(nodeIds.incrementAndGet()); return 1; })
                .when(nodes).insertCmsTopicNode(any(CmsTopicNode.class));
        when(chapters.updateCmsTopicChapter(any(CmsTopicChapter.class))).thenReturn(1);
        when(nodes.updateCmsTopicNode(any(CmsTopicNode.class))).thenReturn(1);
    }

    @Test
    void legacyCrossChapterReorderKeepsPredecessorByIdentity() {
        assertEquals(TOPIC, com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(service).updateTopicByOwner(edit(false, true, true), MEMBER, "creator"));
        assertRestored(901L, 902L); // dependent inserted before its predecessor, in another chapter
    }

    @Test
    void compiledBlocksAlsoKeepPredecessorAfterReorder() {
        assertEquals(TOPIC, com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(service).updateTopicByOwnerV2WithReview(edit(true, true, true), MEMBER, "creator").getTopicId());
        assertRestored(901L, 902L);
    }

    @Test
    void retainedDependentCannotSilentlyLoseDeletedPredecessor() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(service).updateTopicByOwner(edit(false, true, false), MEMBER, "creator"));
        assertTrue(failure.getMessage().contains("前置解锁引用不在本次提交里"));
        verify(nodes, never()).updateCmsTopicNode(any(CmsTopicNode.class));
    }

    @Test
    void removedDependentDoesNotKeepAnOrphanRelation() {
        assertEquals(TOPIC, com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(service).updateTopicByOwner(edit(false, false, true), MEMBER, "creator"));
        verify(nodes, never()).updateCmsTopicNode(any(CmsTopicNode.class));
    }

    @Test
    void failedPredecessorWriteCannotReportSuccessfulUpdate() {
        when(nodes.updateCmsTopicNode(any(CmsTopicNode.class))).thenReturn(0);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(service).updateTopicByOwner(edit(false, true, true), MEMBER, "creator"));
        assertTrue(failure.getMessage().contains("前置解锁保存失败"));
    }

    private CmsTopicNode stored(Long id, Long chapter, Long predecessor) {
        CmsTopicNode node = new CmsTopicNode();
        node.setId(id);
        node.setTopicId(TOPIC);
        node.setChapterId(chapter);
        node.setUnlockAfterNodeId(predecessor);
        return node;
    }

    private TopicUpdateDTO edit(boolean blocks, boolean includeDependent, boolean includePredecessor) {
        TopicUpdateDTO dto = new TopicUpdateDTO();
        dto.setId(TOPIC);
        dto.setName("重排但不解除前置");
        dto.setProductType(ProductType.FREE_EXPLORE);
        dto.setPublishMode("simple");
        dto.setTickets(new ArrayList<ActivityTicketRequest>());
        dto.setCollaboratorIds(new ArrayList<Long>());
        List<ChapterDTO> result = new ArrayList<ChapterDTO>();
        if (includeDependent) result.add(chapter(2L, 12L, blocks));
        if (includePredecessor) result.add(chapter(1L, 11L, blocks));
        dto.setChapters(result);
        return dto;
    }

    private ChapterDTO chapter(Long chapterId, Long nodeId, boolean blocks) {
        ChapterDTO chapter = new ChapterDTO();
        chapter.setId(chapterId);
        chapter.setName("章节" + chapterId);
        NodeDTO node = new NodeDTO();
        node.setId(nodeId);
        node.setName("节点" + nodeId);
        node.setNodeTime(1);
        chapter.setNodes(Collections.singletonList(node));
        if (blocks) {
            chapter.setSchemaVersion(1);
            ChapterBlockDTO block = new ChapterBlockDTO();
            block.setType("node");
            block.setNodeIndex(0);
            chapter.setBlocks(Collections.singletonList(block));
        }
        return chapter;
    }

    private void assertRestored(Long dependent, Long predecessor) {
        ArgumentCaptor<CmsTopicNode> patch = ArgumentCaptor.forClass(CmsTopicNode.class);
        verify(nodes).updateCmsTopicNode(patch.capture());
        assertEquals(dependent, patch.getValue().getId());
        assertEquals(predecessor, patch.getValue().getUnlockAfterNodeId());
        verify(nodes).deleteCmsTopicNodeById(11L);
        verify(nodes).deleteCmsTopicNodeById(12L);
    }
}
