package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.TopicCreateDTO;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** NOT_RUN. Actual create orchestration, stub insert contract; not a DB failure reproduction. */
class OWN_C03_12Test {
    private CmsTopicServiceImpl service;
    private CmsTopicMapper topics;
    private CmsTopicChapterMapper chapters;
    private CmsTopicNodeMapper nodes;

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
        chapters = (CmsTopicChapterMapper) ReflectionTestUtils.getField(service, "cmsTopicChapterMapper");
        nodes = (CmsTopicNodeMapper) ReflectionTestUtils.getField(service, "cmsTopicNodeMapper");
    }

    @Test void zeroRowsEvenWithAnIdCannotCreateChildren() {
        insertResult(0, 700L);
        assertRejectedBeforeChildren();
    }
    @Test void missingGeneratedIdCannotCreateChildren() {
        insertResult(1, null);
        assertRejectedBeforeChildren();
    }
    @Test void nonpositiveGeneratedIdCannotCreateChildren() {
        insertResult(1, 0L);
        assertRejectedBeforeChildren();
    }
    @Test void validGeneratedIdentityKeepsExistingDraftFlow() {
        insertResult(1, 700L);
        assertEquals(Long.valueOf(700), com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(service).createTopic(draft(), 42L, "creator"));
        verify(topics).updateTopicDraftState(700L, 1);
    }

    private void insertResult(int rows, Long id) {
        doAnswer(call -> { ((CmsTopic) call.getArgument(0)).setId(id); return rows; })
                .when(topics).insertCmsTopic(any(CmsTopic.class));
    }
    private TopicCreateDTO draft() {
        TopicCreateDTO dto = new TopicCreateDTO();
        dto.setName("只存草稿");
        dto.setProductType(ProductType.FREE_EXPLORE);
        dto.setPublishMode("simple");
        dto.setSaveDraft(1);
        dto.setChapters(Collections.emptyList());
        return dto;
    }
    private void assertRejectedBeforeChildren() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(service).createTopic(draft(), 42L, "creator"));
        assertTrue(failure.getMessage().contains("未生成有效主题身份"));
        verify(topics, never()).updateTopicDraftState(any(), any());
        verify(chapters, never()).insertCmsTopicChapter(any());
        verify(nodes, never()).insertCmsTopicNode(any());
        verifyNoInteractions(ReflectionTestUtils.getField(service, "productRoleService"));
    }
}
