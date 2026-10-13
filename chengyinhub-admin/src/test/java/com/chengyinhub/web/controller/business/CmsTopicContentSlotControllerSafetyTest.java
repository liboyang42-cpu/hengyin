package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.dto.TopicContentSlotReassignDTO;
import com.chengyinhub.business.service.ITopicContentOwnershipService;
import com.chengyinhub.common.constant.RoleEntityType;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** P0:内容槽与票款归属不能被商家主体占用。 */
class CmsTopicContentSlotControllerSafetyTest {

    @Test
    void merchantContentOwnerIsRejectedByOwnershipService() {
        ITopicContentOwnershipService service = mock(ITopicContentOwnershipService.class);
        CmsTopicController controller = new CmsTopicController();
        ReflectionTestUtils.setField(controller, "topicContentOwnershipService", service);
        TopicContentSlotReassignDTO request = new TopicContentSlotReassignDTO();
        request.setEntityType(RoleEntityType.MERCHANT);
        request.setEntityId(77L);
        doThrow(new IllegalArgumentException("内容主体类型仅允许 PLATFORM/MEMBER/CLUB"))
                .when(service).reassignContentSlot(900L, RoleEntityType.MERCHANT, 77L);

        AjaxResult result = controller.reassignContentSlot(900L, request);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertFalse(String.valueOf(result.get(AjaxResult.MSG_TAG)).contains(RoleEntityType.MERCHANT),
                "拒绝文案不能再把 MERCHANT 列为内容槽合法主体");
        verify(service).reassignContentSlot(900L, RoleEntityType.MERCHANT, 77L);
    }
}
