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
        adaptNewParentPorts(copy);
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

    /**
     * 新引入的两条父行/合同写口在老夹具里没有 stub。与 {@link TopicPublicationTestWiring}
     * 对 {@code lockTopicContentBudget} 的既有做法一致:只在 mapper 是普通 mock、且夹具自己没 stub 过时补一个宽松应答,
     * 绝不覆盖测试显式给定的应答(真库 IT 与显式负例照常走真实实现)。
     */
    private static void adaptNewParentPorts(TopicContentCopyServiceImpl copy) {
        Object mapperField = ReflectionTestUtils.getField(copy, "cmsTopicMapper");
        if (!(mapperField instanceof com.chengyinhub.business.mapper.CmsTopicMapper)) return;
        com.chengyinhub.business.mapper.CmsTopicMapper mapper =
                (com.chengyinhub.business.mapper.CmsTopicMapper) mapperField;
        if (!org.mockito.Mockito.mockingDetails(mapper).isMock()
                || org.mockito.Mockito.mockingDetails(mapper).isSpy()) return;
        boolean hasContractWrite = org.mockito.Mockito.mockingDetails(mapper).getStubbings().stream()
                .anyMatch(stub -> "updateTopicLocationContract".equals(stub.getInvocation().getMethod().getName()));
        if (!hasContractWrite) {
            // 老夹具可能既没有 platformMemberId 也没有 memberId(副本 member_id 为 null):
            // owner 参数用 nullable(Long) 才匹配得上;anyLong() 不匹配 null。
            org.mockito.Mockito.lenient().doAnswer(call -> 1).when(mapper).updateTopicLocationContract(
                    org.mockito.ArgumentMatchers.anyLong(),
                    org.mockito.ArgumentMatchers.nullable(Long.class),
                    org.mockito.ArgumentMatchers.nullable(String.class),
                    org.mockito.ArgumentMatchers.nullable(String.class),
                    org.mockito.ArgumentMatchers.nullable(java.util.Date.class));
        }
    }
}
