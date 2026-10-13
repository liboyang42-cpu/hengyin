package com.chengyinhub.business.controller;

import com.chengyinhub.business.domain.dto.CmsTopicCreateDTO;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import com.chengyinhub.common.constant.RoleEntityType;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.controller.business.CmsTopicController;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** CMS 官方入口只能创建平台自营主题，不能代写俱乐部或会员内容。 */
class CmsTopicCreatePublisherBoundaryTest {

    @Test
    void cmsCreate_rejectsClubPublisherBeforeServiceCall() {
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        CmsTopicController controller = new CmsTopicController();
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        CmsTopicCreateDTO request = new CmsTopicCreateDTO();
        request.setPublisherType(RoleEntityType.CLUB);
        request.setPublisherId(77L);
        request.setPayeeMemberId(88L);

        AjaxResult result = controller.add(request);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verifyNoInteractions(topicService);
    }


    @Test
    void cmsCopyDelegatesToOfficialCopyCommandInsteadOfGenericCopy() {
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        ITopicOperationsScopeAuthorizer scopeAuthorizer = mock(ITopicOperationsScopeAuthorizer.class);
        CmsTopicController controller = new CmsTopicController();
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(controller, "topicOperationsScopeAuthorizer", scopeAuthorizer);
        java.util.Map<String, Object> params = java.util.Collections.<String, Object>singletonMap("copyType", 1);
        org.mockito.Mockito.when(scopeAuthorizer.canViewTopic(12L)).thenReturn(true);
        org.mockito.Mockito.when(topicService.copyOfficialCmsTopic(12L, params)).thenReturn(13L);

        AjaxResult result = controller.copy(12L, params);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        org.mockito.Mockito.verify(topicService).copyOfficialCmsTopic(12L, params);
        org.mockito.Mockito.verify(topicService, org.mockito.Mockito.never()).copyCmsTopic(12L, params);
    }
}
