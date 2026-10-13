package com.chengyinhub.business.controller;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.dto.CmsTopicCreateDTO;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.mapper.ClubLeadMapper;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.ITopicAdminCommandService;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import com.chengyinhub.common.constant.RoleEntityType;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.controller.business.CmsTopicController;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 详情、履约、复制与删除必须先经过主题运营范围闸。 */
class CmsTopicControllerScopeTest {

    @Test
    void getInfoRejectsOutOfScopeBeforeAnyDetailRead() {
        ITopicOperationsScopeAuthorizer scopeAuthorizer = mock(ITopicOperationsScopeAuthorizer.class);
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        when(scopeAuthorizer.canViewTopic(71L)).thenReturn(false);
        CmsTopicController controller = new CmsTopicController();
        ReflectionTestUtils.setField(controller, "topicOperationsScopeAuthorizer", scopeAuthorizer);
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);

        AjaxResult result = controller.getInfo(71L);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("主题不存在或当前账号无查看范围", result.get(AjaxResult.MSG_TAG));
        verify(topicService, never()).selectCmsTopicById(71L);
    }

    @Test
    void leadFulfillmentRejectsOutOfScopeBeforeReadingLeaderData() {
        ITopicOperationsScopeAuthorizer scopeAuthorizer = mock(ITopicOperationsScopeAuthorizer.class);
        ClubLeadMapper clubLeadMapper = mock(ClubLeadMapper.class);
        when(scopeAuthorizer.canViewTopic(72L)).thenReturn(false);
        CmsTopicController controller = new CmsTopicController();
        ReflectionTestUtils.setField(controller, "topicOperationsScopeAuthorizer", scopeAuthorizer);
        ReflectionTestUtils.setField(controller, "clubLeadMapper", clubLeadMapper);

        AjaxResult result = controller.leadFulfillment(72L);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("主题不存在或当前账号无查看范围", result.get(AjaxResult.MSG_TAG));
        verify(clubLeadMapper, never()).selectStatesByTopic(72L);
    }

    @Test
    void copyRejectsOutOfScopeBeforeCopyService() {
        ITopicOperationsScopeAuthorizer scopeAuthorizer = mock(ITopicOperationsScopeAuthorizer.class);
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        when(scopeAuthorizer.canViewTopic(73L)).thenReturn(false);
        CmsTopicController controller = new CmsTopicController();
        ReflectionTestUtils.setField(controller, "topicOperationsScopeAuthorizer", scopeAuthorizer);
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);

        AjaxResult result = controller.copy(73L, null);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("主题不存在或当前账号无操作范围", result.get(AjaxResult.MSG_TAG));
        verify(topicService, never()).copyOfficialCmsTopic(73L, null);
    }

    @Test
    void addRejectsNonGlobalScopeBeforeCreatingAnOfficialTopic() {
        ITopicOperationsScopeAuthorizer scopeAuthorizer = mock(ITopicOperationsScopeAuthorizer.class);
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        when(scopeAuthorizer.hasGlobalTopicScope()).thenReturn(false);
        CmsTopicCreateDTO request = new CmsTopicCreateDTO();
        request.setPublisherType(RoleEntityType.PLATFORM);
        CmsTopicController controller = new CmsTopicController();
        ReflectionTestUtils.setField(controller, "topicOperationsScopeAuthorizer", scopeAuthorizer);
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);

        AjaxResult result = controller.add(request);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("当前账号无官方主题创建范围", result.get(AjaxResult.MSG_TAG));
        verify(topicService, never()).createTopicByPublisher(org.mockito.ArgumentMatchers.any(CmsTopicCreateDTO.class),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void roamRewardsRejectsNonGlobalScopeBeforeReadingLocationEvidence() {
        ITopicOperationsScopeAuthorizer scopeAuthorizer = mock(ITopicOperationsScopeAuthorizer.class);
        RoamMapper roamMapper = mock(RoamMapper.class);
        when(scopeAuthorizer.hasGlobalTopicScope()).thenReturn(false);
        CmsTopicController controller = new CmsTopicController();
        ReflectionTestUtils.setField(controller, "topicOperationsScopeAuthorizer", scopeAuthorizer);
        ReflectionTestUtils.setField(controller, "roamMapper", roamMapper);

        AjaxResult result = controller.roamRewards(null, null, null);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("当前账号无漫游奖励调证范围", result.get(AjaxResult.MSG_TAG));
        verify(roamMapper, never()).selectAdminRewardEvidence(null, null, null);
    }

    @Test
    void exportFiltersOutOfScopeTopics() {
        ITopicOperationsScopeAuthorizer scopeAuthorizer = mock(ITopicOperationsScopeAuthorizer.class);
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        when(scopeAuthorizer.hasGlobalTopicScope()).thenReturn(false);
        CmsTopicController controller = new CmsTopicController();
        ReflectionTestUtils.setField(controller, "topicOperationsScopeAuthorizer", scopeAuthorizer);
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);

        controller.export(new MockHttpServletResponse(), new CmsTopic());

        verify(topicService, never()).selectCmsTopicList(org.mockito.ArgumentMatchers.any(CmsTopic.class));
    }

    @Test
    void listRejectsNonGlobalScopeBeforeQuery() {
        ITopicOperationsScopeAuthorizer scopeAuthorizer = mock(ITopicOperationsScopeAuthorizer.class);
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        CmsTopicController controller = new CmsTopicController();
        ReflectionTestUtils.setField(controller, "topicOperationsScopeAuthorizer", scopeAuthorizer);
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);

        controller.list(new CmsTopic());

        verify(topicService, never()).selectCmsTopicList(org.mockito.ArgumentMatchers.any(CmsTopic.class));
    }

    @Test
    void removeDelegatesToTheScopedOfficialDeleteCommand() {
        ITopicAdminCommandService commandService = mock(ITopicAdminCommandService.class);
        when(commandService.deleteOfficialTopics(new Long[]{74L})).thenReturn(1);
        CmsTopicController controller = new CmsTopicController();
        ReflectionTestUtils.setField(controller, "topicAdminCommandService", commandService);

        AjaxResult result = controller.remove(new Long[]{74L});

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(commandService).deleteOfficialTopics(new Long[]{74L});
    }
}
