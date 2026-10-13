package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.ProductRole;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.common.constant.RoleEntityType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 圈层实例配置同时校验主题旧字段与内容主体单一真源。 */
class CircleThemeInstanceAuthorizationTest {

    @Test
    void legacyMemberIdCannotOverrideADifferentContentOwner() {
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        CmsTopicChapterMapper chapterMapper = mock(CmsTopicChapterMapper.class);
        IProductRoleService productRoleService = mock(IProductRoleService.class);
        CmsTopicServiceImpl service = new CmsTopicServiceImpl();
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "cmsTopicChapterMapper", chapterMapper);
        ReflectionTestUtils.setField(service, "productRoleService", productRoleService);

        CmsTopic topic = new CmsTopic();
        topic.setId(9L);
        topic.setMemberId(100L);
        topic.setCircleThemeCode("SOLO");
        topic.setSourceTemplateTopicId(1L);
        when(topicMapper.selectCmsTopicByIdForUpdate(9L)).thenReturn(topic);

        ProductRole content = new ProductRole();
        content.setEntityType(RoleEntityType.MEMBER);
        content.setEntityId(200L);
        when(productRoleService.activeContentSlot(9L)).thenReturn(content);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.configureCircleCandidates(9L, 100L, Arrays.asList("O1", "O2", "O3")));

        assertEquals("只有城市实例的内容主体本人可以配置供给角色", error.getMessage());
        verify(chapterMapper, never()).selectCmsTopicChapterList(any());
    }

    @Test
    void contentOwnerCanConfigureEvenWhenLegacyMemberIdIsStale() {
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        CmsTopicChapterMapper chapterMapper = mock(CmsTopicChapterMapper.class);
        IProductRoleService productRoleService = mock(IProductRoleService.class);
        CmsTopicServiceImpl service = new CmsTopicServiceImpl();
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "cmsTopicChapterMapper", chapterMapper);
        ReflectionTestUtils.setField(service, "productRoleService", productRoleService);

        CmsTopic topic = new CmsTopic();
        topic.setId(9L);
        topic.setMemberId(999L);
        topic.setCircleThemeCode("SOLO");
        topic.setSourceTemplateTopicId(1L);
        when(topicMapper.selectCmsTopicByIdForUpdate(9L)).thenReturn(topic);
        ProductRole content = new ProductRole();
        content.setEntityType(RoleEntityType.MEMBER);
        content.setEntityId(100L);
        when(productRoleService.activeContentSlot(9L)).thenReturn(content);
        java.util.List<CmsTopicChapter> chapters = Arrays.asList(
                chapter(1L, "O1", "EXPRESSION"), chapter(2L, "O2", "REST"),
                chapter(3L, "O3", "EXPERIENCE"));
        when(chapterMapper.selectCmsTopicChapterList(any())).thenReturn(chapters);
        when(chapterMapper.updateCmsTopicChapter(any())).thenReturn(1);
        when(topicMapper.clearCircleReviewedAt(org.mockito.ArgumentMatchers.eq(9L), any()))
                .thenReturn(1);
        when(topicMapper.selectCmsTopicById(9L)).thenReturn(topic);

        assertEquals(3, service.configureCircleCandidates(
                9L, 100L, Arrays.asList("O1", "O2", "O3")));
        verify(topicMapper).clearCircleReviewedAt(org.mockito.ArgumentMatchers.eq(9L), any());
    }

    @Test
    void onlyContentOwnerCanRefreshTheCityAgentReview() {
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        IProductRoleService productRoleService = mock(IProductRoleService.class);
        CmsTopicServiceImpl service = new CmsTopicServiceImpl();
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "productRoleService", productRoleService);
        CmsTopic topic = new CmsTopic();
        topic.setId(9L);
        topic.setCircleThemeCode("FRIENDS");
        when(topicMapper.selectCmsTopicByIdForUpdate(9L)).thenReturn(topic);
        ProductRole content = new ProductRole();
        content.setEntityType(RoleEntityType.MEMBER);
        content.setEntityId(200L);
        when(productRoleService.activeContentSlot(9L)).thenReturn(content);

        assertEquals("只有城市实例的内容主体本人可以复核供给", assertThrows(
                IllegalStateException.class, () -> service.reviewCircleSupplies(9L, 100L)).getMessage());
        verify(topicMapper, never()).updateCmsTopic(any());
    }

    private static CmsTopicChapter chapter(Long id, String code, String role) {
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(id);
        chapter.setCircleCandidateCode(code);
        chapter.setCircleRole(role);
        return chapter;
    }
}
