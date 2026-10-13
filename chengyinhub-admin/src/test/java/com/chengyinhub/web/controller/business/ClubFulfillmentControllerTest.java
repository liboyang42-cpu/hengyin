package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.dto.ClubFulfillmentWorkspaceDTO;
import com.chengyinhub.business.service.IClubFulfillmentWorkspaceService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClubFulfillmentControllerTest {

    private IClubFulfillmentWorkspaceService fulfillmentService;
    private ClubController controller;

    @BeforeEach
    void setUp() {
        fulfillmentService = mock(IClubFulfillmentWorkspaceService.class);
        controller = new ClubController();
        ReflectionTestUtils.setField(controller, "fulfillmentWorkspaceService", fulfillmentService);
    }

    @Test
    void fulfillmentReturnsTheServiceSnapshot() {
        ClubFulfillmentWorkspaceDTO snapshot = new ClubFulfillmentWorkspaceDTO();
        snapshot.setClubId(7L);
        when(fulfillmentService.getFulfillment(7L)).thenReturn(snapshot);

        AjaxResult result = controller.fulfillment(7L);

        assertEquals(snapshot, result.get(AjaxResult.DATA_TAG));
    }

    @Test
    void fulfillmentRequiresBothClubAndTopicReadPermissions() throws Exception {
        PreAuthorize permission = ClubController.class.getMethod("fulfillment", Long.class)
                .getAnnotation(PreAuthorize.class);

        assertNotNull(permission);
        assertTrue(permission.value().contains("business:club:query"));
        assertTrue(permission.value().contains("business:topic:list"));
    }
}
