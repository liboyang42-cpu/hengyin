package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.dto.CreatorReviewDTO;
import com.chengyinhub.business.service.ICreatorCenterService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreatorCenterControllerSafetyTest {

    private ICreatorCenterService creatorCenterService;
    private TestableCreatorCenterController controller;

    @BeforeEach
    void setUp() {
        creatorCenterService = mock(ICreatorCenterService.class);
        controller = new TestableCreatorCenterController(71L);
        ReflectionTestUtils.setField(controller, "creatorCenterService", creatorCenterService);
    }

    @Test
    void reviewUsesAuthenticatedBackofficeUserInsteadOfClientSuppliedIdentity() {
        CreatorReviewDTO request = new CreatorReviewDTO();
        request.setId(11L);
        request.setApplyStatus(2);
        request.setReviewReason("资料信息不足");
        when(creatorCenterService.review(any(CreatorReviewDTO.class), eq(71L))).thenReturn(1);

        AjaxResult result = controller.review(request);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(creatorCenterService).review(request, 71L);
    }

    private static class TestableCreatorCenterController extends CreatorCenterController {
        private final Long userId;

        private TestableCreatorCenterController(Long userId) {
            this.userId = userId;
        }

        @Override
        public Long getUserId() {
            return userId;
        }
    }
}
