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
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** NOT_RUN. Real create/update duration projection with stub SQL; no persistence validation. */
class OWN_C03_18Test {
    private CmsTopicServiceImpl service;
    private CmsTopicMapper topics;

    @BeforeEach void setup() throws Exception {
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
        CmsTopic existing = new CmsTopic();
        existing.setId(700L);
        existing.setMemberId(42L);
        existing.setName("半填草稿");
        existing.setStatus(0);
        existing.setLifecycle(0);
        existing.setDelFlag(0);
        existing.setProductType(ProductType.FREE_EXPLORE);
        existing.setPublishMode("simple");
        when(topics.selectCmsTopicById(700L)).thenReturn(existing);
        when(topics.selectTopicIsDraft(700L)).thenReturn(1);
        when(topics.updateCmsTopic(any(CmsTopic.class))).thenReturn(1);
        doAnswer(call -> { ((CmsTopic) call.getArgument(0)).setId(700L); return 1; })
                .when(topics).insertCmsTopic(any(CmsTopic.class));
        CmsTopicChapterMapper chapters = (CmsTopicChapterMapper) ReflectionTestUtils.getField(service, "cmsTopicChapterMapper");
        CmsTopicNodeMapper nodes = (CmsTopicNodeMapper) ReflectionTestUtils.getField(service, "cmsTopicNodeMapper");
        doAnswer(call -> { ((CmsTopicChapter) call.getArgument(0)).setId(800L); return 1; })
                .when(chapters).insertCmsTopicChapter(any(CmsTopicChapter.class));
        doAnswer(call -> { ((CmsTopicNode) call.getArgument(0)).setId(900L); return 1; })
                .when(nodes).insertCmsTopicNode(any(CmsTopicNode.class));
    }
    @Test void createDraftWithMissingNodeDurationStoresZeroTotal() {
        assertEquals(Long.valueOf(700), com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(service).createTopic(fill(new TopicCreateDTO()), 42L, "creator"));
        ArgumentCaptor<CmsTopic> inserted = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topics).insertCmsTopic(inserted.capture());
        assertEquals(Long.valueOf(0), inserted.getValue().getTotalTime());
    }
    @Test void editDraftWithMissingNodeDurationStoresZeroTotal() {
        TopicUpdateDTO dto = fill(new TopicUpdateDTO());
        dto.setId(700L);
        assertEquals(Long.valueOf(700), com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(service).updateTopicByOwner(dto, 42L, "creator"));
        ArgumentCaptor<CmsTopic> patch = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topics).updateCmsTopic(patch.capture());
        assertEquals(Long.valueOf(0), patch.getValue().getTotalTime());
    }
    @Test void missingTimeDoesNotEraseKnownDurationsOrMutateInput() {
        NodeDTO missing = node(null);
        assertEquals(Long.valueOf(12), service.calculateTotalTime(Arrays.asList(node(5), missing, node(7))));
        assertNull(missing.getNodeTime());
    }
    @Test void nullNodeIsStillInvalidRatherThanAcceptedAsMissingTime() {
        assertThrows(NullPointerException.class, () -> service.calculateTotalTime(Collections.singletonList(null)));
    }
    private NodeDTO node(Integer duration) {
        NodeDTO node = new NodeDTO();
        node.setName("待补时长节点");
        node.setNodeTime(duration);
        return node;
    }
    private <T extends TopicCreateDTO> T fill(T dto) {
        dto.setName("半填草稿");
        dto.setProductType(ProductType.FREE_EXPLORE);
        dto.setPublishMode("simple");
        dto.setSaveDraft(1);
        ChapterDTO chapter = new ChapterDTO();
        chapter.setName("章节");
        chapter.setNodes(Collections.singletonList(node(null)));
        dto.setChapters(Collections.singletonList(chapter));
        dto.setTickets(Collections.emptyList());
        dto.setCollaboratorIds(Collections.emptyList());
        return dto;
    }
}
