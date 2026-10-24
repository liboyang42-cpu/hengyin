package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.ProductRole;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.IProductRoleService;
import com.chengyinhub.common.constant.RoleEntityType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** CMS 复制只能产出平台自营主题，不能沿用通用复制的会员归属。 */
class TopicOfficialCopyBoundaryTest {

    private CmsTopicServiceImpl service;
    private CmsTopicMapper topicMapper;
    private IProductRoleService productRoleService;

    @BeforeEach
    void setUp() {
        service = new CmsTopicServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(service, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        topicMapper = mock(CmsTopicMapper.class);
        productRoleService = mock(IProductRoleService.class);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "productRoleService", productRoleService);
        ReflectionTestUtils.setField(service, "platformMemberId", 12345L);

        CmsTopic source = new CmsTopic();
        source.setId(41L);
        source.setName("官方源主题");
        when(topicMapper.selectCmsTopicById(41L)).thenReturn(source);
        doAnswer(invocation -> {
            ((CmsTopic) invocation.getArgument(0)).setId(42L);
            return 1;
        }).when(topicMapper).insertCmsTopic(any(CmsTopic.class));
        ProductRole persistedSlot = new ProductRole();
        persistedSlot.setEntityType(RoleEntityType.PLATFORM);
        when(productRoleService.activeContentSlot(42L)).thenReturn(persistedSlot);
    }

    @Test
    void officialCopyForcesConfiguredPlatformPayeeAndContentSlot() {
        when(productRoleService.isPlatformOwned(41L)).thenReturn(true);

        assertEquals(42L, com.chengyinhub.business.service.support.TopicCopyTestWiring.owner(service).copyOfficialCmsTopic(41L, Collections.<String, Object>singletonMap("copyType", 1)));

        org.mockito.ArgumentCaptor<CmsTopic> inserted = org.mockito.ArgumentCaptor.forClass(CmsTopic.class);
        verify(topicMapper).insertCmsTopic(inserted.capture());
        assertEquals(Long.valueOf(12345L), inserted.getValue().getMemberId());
        verify(productRoleService).declareContentSlot(42L, RoleEntityType.PLATFORM, null);
    }

    @Test
    void officialCopyRejectsNonPlatformSourceBeforeInsert() {
        when(productRoleService.isPlatformOwned(41L)).thenReturn(false);

        assertThrows(IllegalArgumentException.class,
                () -> com.chengyinhub.business.service.support.TopicCopyTestWiring.owner(service).copyOfficialCmsTopic(41L, Collections.<String, Object>emptyMap()));

        verify(topicMapper, never()).insertCmsTopic(any(CmsTopic.class));
    }


    @Test
    void officialCopyRejectsDeletedPlatformSourceBeforeInsert() {
        CmsTopic deleted = new CmsTopic();
        deleted.setId(41L);
        deleted.setDelFlag(2);
        when(topicMapper.selectCmsTopicById(41L)).thenReturn(deleted);
        when(productRoleService.isPlatformOwned(41L)).thenReturn(true);

        assertThrows(IllegalArgumentException.class,
                () -> com.chengyinhub.business.service.support.TopicCopyTestWiring.owner(service).copyOfficialCmsTopic(41L, Collections.<String, Object>emptyMap()));

        verify(topicMapper, never()).insertCmsTopic(any(CmsTopic.class));
    }


    @Test
    void officialCopyFailsClosedWhenPlatformContentSlotDoesNotPersist() {
        when(productRoleService.isPlatformOwned(41L)).thenReturn(true);
        when(productRoleService.activeContentSlot(42L)).thenReturn(null);

        assertThrows(IllegalStateException.class,
                () -> com.chengyinhub.business.service.support.TopicCopyTestWiring.owner(service).copyOfficialCmsTopic(41L, Collections.<String, Object>emptyMap()));

        verify(topicMapper).insertCmsTopic(any(CmsTopic.class));
    }
}
