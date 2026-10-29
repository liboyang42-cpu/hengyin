package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** NOT_RUN. Actual owner-update orchestration with stub mapper row counts; no DB/rollback claim. */
class OWN_C03_10Test {
    private CmsTopicServiceImpl service;
    private CmsTopicMapper topics;
    private CmsTopicNodeMapper nodes;
    private CmsTopicChapterMapper chapters;
    private TopicUpdateDTO dto;

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
        topics = (CmsTopicMapper) ReflectionTestUtils.getField(service, "cmsTopicMapper");
        nodes = (CmsTopicNodeMapper) ReflectionTestUtils.getField(service, "cmsTopicNodeMapper");
        chapters = (CmsTopicChapterMapper) ReflectionTestUtils.getField(service, "cmsTopicChapterMapper");
        CmsTopic topic = new CmsTopic();
        topic.setId(700L);
        topic.setMemberId(42L);
        topic.setName("已有草稿");
        topic.setStatus(0);
        topic.setLifecycle(0);
        topic.setDelFlag(0);
        topic.setPublishMode("simple");
        topic.setProductType(ProductType.FREE_EXPLORE);
        when(topics.selectCmsTopicById(700L)).thenReturn(topic);
        when(topics.selectTopicIsDraft(700L)).thenReturn(1);
        CmsTopicNode oldNode = new CmsTopicNode();
        oldNode.setId(11L);
        oldNode.setTopicId(700L);
        CmsTopicChapter oldChapter = new CmsTopicChapter();
        oldChapter.setId(1L);
        oldChapter.setTopicId(700L);
        when(nodes.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenReturn(Collections.singletonList(oldNode));
        when(chapters.selectCmsTopicChapterList(any(CmsTopicChapter.class))).thenReturn(Collections.singletonList(oldChapter));
        dto = new TopicUpdateDTO();
        dto.setId(700L);
        dto.setName("作者明确清空章节");
        dto.setChapters(new ArrayList<ChapterDTO>());
        dto.setTickets(new ArrayList<ActivityTicketRequest>());
        dto.setCollaboratorIds(new ArrayList<Long>());
    }

    @Test
    void zeroMainRowUpdateStopsBeforeDeletingChildren() {
        when(topics.updateCmsTopic(any(CmsTopic.class))).thenReturn(0);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(service).updateTopicByOwner(dto, 42L, "creator"));
        assertTrue(failure.getMessage().contains("未重建章节与节点"));
        verify(nodes, never()).deleteCmsTopicNodeById(any());
        verify(chapters, never()).deleteCmsTopicChapterById(any());
        verify(nodes, never()).insertCmsTopicNode(any());
        verify(chapters, never()).insertCmsTopicChapter(any());
    }

    @Test
    void oneMainRowPreservesExistingExplicitReplacementBehavior() {
        when(topics.updateCmsTopic(any(CmsTopic.class))).thenReturn(1);
        assertEquals(Long.valueOf(700L), com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(service).updateTopicByOwner(dto, 42L, "creator"));
        verify(nodes).deleteCmsTopicNodeById(11L);
        verify(chapters).deleteCmsTopicChapterById(1L);
    }

    @Test
    void unexpectedAffectedCountDoesNotContinueReplacement() {
        when(topics.updateCmsTopic(any(CmsTopic.class))).thenReturn(2);
        assertThrows(IllegalStateException.class,
                () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(service).updateTopicByOwner(dto, 42L, "creator"));
        verify(nodes, never()).deleteCmsTopicNodeById(any());
        verify(chapters, never()).deleteCmsTopicChapterById(any());
    }
}
