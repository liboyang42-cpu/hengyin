package com.chengyinhub.business.service.support;

import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.service.IProductRoleService;
import com.chengyinhub.business.service.impl.CmsMemberTemplateServiceImpl;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.business.service.impl.TopicContentCopyServiceImpl;
import org.springframework.test.util.ReflectionTestUtils;

/** Explicit test-only assembly for the original mapper fixtures. No mock copy implementation or production fallback. */
public final class TopicCopyTestWiring {
    private TopicCopyTestWiring() { }

    public static TopicContentCopyServiceImpl owner(CmsTopicServiceImpl fixture) {
        wire(fixture);
        return (TopicContentCopyServiceImpl) ReflectionTestUtils.getField(fixture, "topicContentCopyService");
    }

    public static CmsTopicServiceImpl wire(CmsTopicServiceImpl facade) {
        CmsMemberTemplateServiceImpl templates = new CmsMemberTemplateServiceImpl();
        ReflectionTestUtils.setField(templates, "cmsMemberTemplateMapper",
                (CmsMemberTemplateMapper) TopicPublicationTestWiring.get(facade, "cmsMemberTemplateMapper"));
        TopicPlatformContentSlotGuard slots = new TopicPlatformContentSlotGuard(
                (IProductRoleService) ReflectionTestUtils.getField(facade, "productRoleService"));
        TopicContentCopyServiceImpl copy = new TopicContentCopyServiceImpl();
        for (String field : new String[]{"cmsTopicMapper", "cmsTopicChapterMapper", "cmsTopicNodeMapper",
                "cmsMemberTemplateMapper", "cmsCollaboratorsMapper", "npcProfileMapper", "sysCategoryService",
                "productRoleService", "platformMemberId"}) {
            ReflectionTestUtils.setField(copy, field, com.chengyinhub.business.service.support.TopicPublicationTestWiring.get(facade, field));
        }
        ReflectionTestUtils.setField(copy, "cmsMemberTemplateService", templates);
        ReflectionTestUtils.setField(copy, "platformContentSlotGuard", slots);
        // 复制连同已注册状态键:沿用 facade 的注册服务;老夹具没有时给一个空注册表(Mockito 列表默认空)
        Object stateKeys = TopicPublicationTestWiring.get(facade, "themeStateKeyService");
        ReflectionTestUtils.setField(copy, "themeStateKeyService", stateKeys instanceof ThemeStateKeyService ? stateKeys
                : new ThemeStateKeyService(org.mockito.Mockito.mock(com.chengyinhub.business.mapper.ThemeStateKeyMapper.class)));
        ReflectionTestUtils.setField(facade, "cmsMemberTemplateService", templates);
        ReflectionTestUtils.setField(facade, "platformContentSlotGuard", slots);
        ReflectionTestUtils.setField(facade, "topicContentCopyService", copy);
        return facade;
    }
}
