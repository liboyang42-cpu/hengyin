package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.CmsCollaborators;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.dto.TopicCreateDTO;
import com.chengyinhub.business.domain.dto.TopicUpdateDTO;
import com.chengyinhub.business.mapper.CmsCollaboratorsMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** NOT_RUN / SOURCE_ONLY. Real create/FULL update with injected dependencies; no DB/rollback claim. */
class OWN_C03_14Test {
    private CmsTopicServiceImpl service;
    private CmsTopicMapper topics;
    private CmsCollaboratorsMapper collaborators;
    private CmsTopicChapterMapper chapters;
    private final List<Object> mapperMocks = new ArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        // Same dependency injection fixture as OWN_C03_12Test; use actual public orchestration.
        service = new CmsTopicServiceImpl();
        mapperMocks.clear();
        for (Field field : CmsTopicServiceImpl.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
            field.setAccessible(true);
            if (field.get(service) == null && field.isAnnotationPresent(Autowired.class)) {
                Object dependency = mock(field.getType());
                ReflectionTestUtils.setField(service, field.getName(), dependency);
                if (field.getType().getPackage().getName().endsWith(".mapper")) mapperMocks.add(dependency);
            }
        }
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.wireRelocatedCollaborators(service, publicationField -> org.mockito.Mockito.mock(publicationField.getType()));
        topics = (CmsTopicMapper) ReflectionTestUtils.getField(service, "cmsTopicMapper");
        collaborators = (CmsCollaboratorsMapper) ReflectionTestUtils.getField(service, "cmsCollaboratorsMapper");
        chapters = (CmsTopicChapterMapper) ReflectionTestUtils.getField(service, "cmsTopicChapterMapper");
        doAnswer(call -> { ((CmsTopic) call.getArgument(0)).setId(700L); return 1; })
                .when(topics).insertCmsTopic(any(CmsTopic.class));
        when(topics.updateCmsTopic(any())).thenReturn(1);
        existing(0, 1);
    }

    private void existing(int status, int draft) {
        CmsTopic topic = new CmsTopic();
        topic.setId(700L);
        topic.setMemberId(42L);
        topic.setName("原主题");
        topic.setStatus(status);
        topic.setDelFlag(0);
        topic.setLifecycle(0);
        topic.setProductType(ProductType.FREE_EXPLORE);
        topic.setPublishMode("simple");
        topic.setStartDate(new Date(System.currentTimeMillis() + 86400000L * 3));
        when(topics.selectCmsTopicById(700L)).thenReturn(topic);
        when(topics.selectCmsTopicByIdForUpdate(700L)).thenReturn(topic);
        when(topics.selectTopicIsDraft(700L)).thenReturn(draft);
    }

    private TopicUpdateDTO payload(List<Long> ids) {
        TopicUpdateDTO dto = new TopicUpdateDTO();
        dto.setId(700L);
        dto.setName("保存草稿");
        dto.setProductType(ProductType.FREE_EXPLORE);
        dto.setPublishMode("simple");
        dto.setSaveDraft(1);
        dto.setChapters(Collections.emptyList());
        dto.setTickets(Collections.emptyList());
        dto.setCollaboratorIds(ids);
        return dto;
    }

    private Long create(TopicCreateDTO dto, boolean v2) {
        return v2 ? com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(service).createTopicV2(dto, 42L, "creator") : com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(service).createTopic(dto, 42L, "creator");
    }

    private Long update(TopicUpdateDTO dto, boolean v2) {
        return v2 ? com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(service).updateTopicByOwnerV2(dto, 42L, "creator")
                : com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(service).updateTopicByOwner(dto, 42L, "creator");
    }

    private void noMapperWrites() {
        for (Object mapper : mapperMocks) {
            mockingDetails(mapper).getInvocations().forEach(call -> {
                String method = call.getMethod().getName();
                assertTrue(method.startsWith("select") || method.startsWith("count"),
                        "Invalid payload must not mutate any aggregate mapper: " + method);
            });
        }
    }

    @Test void createRejectsNullZeroNegativeBeforeAnyAggregateWrite() {
        for (boolean v2 : new boolean[] {false, true}) {
            for (Long invalid : Arrays.asList(null, 0L, -1L)) {
                IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                        () -> create(payload(Arrays.asList(8L, invalid, 9L)), v2));
                assertEquals("合作者用户ID必须为正整数", error.getMessage());
                noMapperWrites();
            }
        }
    }

    @Test void fullUpdateRejectsNullZeroNegativeBeforeAnyAggregateWrite() {
        for (boolean v2 : new boolean[] {false, true}) {
            for (Long invalid : Arrays.asList(null, 0L, -1L)) {
                IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                        () -> update(payload(Arrays.asList(8L, invalid, 9L)), v2));
                assertEquals("合作者用户ID必须为正整数", error.getMessage());
                noMapperWrites();
            }
        }
        verify(topics, atLeastOnce()).selectCmsTopicById(700L);
    }

    @Test void createPreservesPositiveDuplicatesAndTheirOrder() {
        assertEquals(Long.valueOf(700L), create(payload(Arrays.asList(9L, 8L, 9L)), false));
        assertSavedIds(9L, 8L, 9L);
    }

    @Test void fullUpdatePreservesPositiveDuplicatesAndTheirOrder() {
        assertEquals(Long.valueOf(700L), update(payload(Arrays.asList(9L, 8L, 9L)), false));
        assertSavedIds(9L, 8L, 9L);
        verify(topics, never()).insertCmsTopic(any());
    }

    private void assertSavedIds(Long... expected) {
        ArgumentCaptor<CmsCollaborators> saved = ArgumentCaptor.forClass(CmsCollaborators.class);
        verify(collaborators, times(expected.length)).insertCmsCollaborators(saved.capture());
        assertEquals(Arrays.asList(expected), saved.getAllValues().stream()
                .map(CmsCollaborators::getMemberId).collect(Collectors.toList()));
        for (CmsCollaborators row : saved.getAllValues()) {
            assertEquals(Long.valueOf(1L), row.getDataType());
            assertEquals(Long.valueOf(700L), row.getDataId());
        }
    }

    private void storedCollaborator() {
        CmsCollaborators row = new CmsCollaborators();
        row.setId(31L);
        row.setMemberId(8L);
        when(collaborators.selectCmsCollaboratorsList(any())).thenReturn(Collections.singletonList(row));
    }

    @Test void explicitEmptyListStillClearsExistingCollaborators() {
        storedCollaborator();
        assertEquals(Long.valueOf(700L), update(payload(Collections.emptyList()), false));
        verify(collaborators).deleteCmsCollaboratorsById(31L);
        verify(collaborators, never()).insertCmsCollaborators(any());
    }

    @Test void absentListWithExistingRowsStillFailsCompletenessGate() {
        storedCollaborator();
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> update(payload(null), false));
        assertTrue(error.getMessage().contains("合作者(collaboratorIds)"));
        noMapperWrites();
    }

    @Test void absentListWithoutExistingRowsStillAllowsCreateAndFullUpdate() {
        assertEquals(Long.valueOf(700L), create(payload(null), false));
        assertEquals(Long.valueOf(700L), update(payload(null), false));
        verify(collaborators, never()).insertCmsCollaborators(any());
    }

    @Test void whitelistStillIgnoresInvalidCollaboratorList() {
        existing(1, 0);
        assertEquals(Long.valueOf(700L), update(payload(Arrays.asList(null, 0L, -1L)), false));
        verify(topics).updateCmsTopic(any());
        verifyNoInteractions(collaborators);
    }

    @Test void ownerGateStillPrecedesCollaboratorValidation() {
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(service).updateTopicByOwner(payload(Collections.singletonList(null)), 77L, "other"));
        assertEquals("无权编辑该主题", error.getMessage());
        noMapperWrites();
        verifyNoInteractions(collaborators);
    }

    @Test void completenessGateStillPrecedesInvalidElementValidation() {
        CmsTopicChapter oldChapter = new CmsTopicChapter();
        oldChapter.setId(21L);
        when(chapters.selectCmsTopicChapterList(any())).thenReturn(Collections.singletonList(oldChapter));
        TopicUpdateDTO dto = payload(Collections.singletonList(null));
        dto.setChapters(null);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> update(dto, false));
        assertTrue(error.getMessage().contains("章节与节点(chapters)"));
        noMapperWrites();
    }
}
