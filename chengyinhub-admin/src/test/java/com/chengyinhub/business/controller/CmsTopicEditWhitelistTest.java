package com.chengyinhub.business.controller;

import java.math.BigDecimal;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.dto.CmsTopicEditDTO;
import com.chengyinhub.business.domain.dto.TopicPlazaRecommendationDTO;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IProductRoleService;
import com.chengyinhub.business.service.ITopicAdminCommandService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.controller.business.CmsTopicController;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 普通编辑的业务规则已下沉到 TopicAdminCommandService；Controller 仅负责鉴权、审计和 HTTP 映射。
 */
class CmsTopicEditWhitelistTest {

    private ITopicAdminCommandService commandService;

    private CmsTopicController controller() {
        commandService = mock(ITopicAdminCommandService.class);
        CmsTopicController controller = new CmsTopicController();
        ReflectionTestUtils.setField(controller, "topicAdminCommandService", commandService);
        return controller;
    }

    @Test
    void editDelegatesToControlledCommand() {
        CmsTopicEditDTO dto = new CmsTopicEditDTO();
        dto.setId(1L);
        dto.setName("正常改个名");
        CmsTopicController controller = controller();
        when(commandService.editTopicWithinScope(dto)).thenReturn(1);

        AjaxResult result = controller.edit(dto);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(commandService).editTopicWithinScope(dto);
    }

    @Test
    void editMapsBusinessRejectionWithoutBypassingCommand() {
        CmsTopicEditDTO dto = new CmsTopicEditDTO();
        dto.setId(1L);
        dto.setName("锁阵容后改日期");
        CmsTopicController controller = controller();
        when(commandService.editTopicWithinScope(dto))
                .thenThrow(new IllegalArgumentException("当前主题阶段不允许该编辑；阵容与结算请走对应运营流程"));

        AjaxResult result = controller.edit(dto);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(commandService).editTopicWithinScope(dto);
    }

    @Test
    void plazaRecommendationDelegatesToTheNarrowGovernanceCommand() {
        TopicPlazaRecommendationDTO dto = new TopicPlazaRecommendationDTO();
        dto.setIsRecommend(1);
        CmsTopicController controller = controller();
        when(commandService.updatePlazaRecommendation(1L, 1)).thenReturn(1);

        AjaxResult result = controller.updatePlazaRecommendation(1L, dto);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(commandService).updatePlazaRecommendation(1L, 1);
    }
}
