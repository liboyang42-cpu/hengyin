package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.UserPermission;
import com.chengyinhub.business.service.IUserPermissionService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class UserPermissionControllerTest {
    @Test
    void invalidRecordNeverWrites() {
        IUserPermissionService service = mock(IUserPermissionService.class);
        UserPermissionController controller = controller(service);
        assertEquals(500, controller.edit(null).get(AjaxResult.CODE_TAG));
        for (Long id : new Long[] { null, 0L, -1L }) {
            UserPermission value = new UserPermission();
            value.setId(id);
            assertEquals(500, controller.edit(value).get(AjaxResult.CODE_TAG));
        }
        verifyNoInteractions(service);
    }

    @Test
    void outOfRangeForEachEditableQuotaNeverWrites() {
        IUserPermissionService service = mock(IUserPermissionService.class);
        UserPermissionController controller = controller(service);
        for (int invalid : new int[] { -1, 100, Integer.MAX_VALUE }) {
            for (int field = 0; field < 3; field++) {
                UserPermission value = new UserPermission();
                value.setId(1L);
                if (field == 0) value.setMaxThemes(invalid);
                if (field == 1) value.setMaxEvents(invalid);
                if (field == 2) value.setMaxCoupons(invalid);
                assertEquals(500, controller.edit(value).get(AjaxResult.CODE_TAG));
            }
        }
        verifyNoInteractions(service);
    }

    @Test
    void boundariesAndNullKeepExistingUpdateContract() {
        IUserPermissionService service = mock(IUserPermissionService.class);
        UserPermissionController controller = controller(service);
        when(service.update(any(UserPermission.class))).thenReturn(1);
        for (Integer limit : new Integer[] { null, 0, 99 }) {
            UserPermission value = new UserPermission();
            value.setId(1L);
            value.setMaxThemes(limit);
            value.setMaxEvents(limit);
            value.setMaxCoupons(limit);
            value.setCanCreateTheme(true);
            assertEquals(200, controller.edit(value).get(AjaxResult.CODE_TAG));
            verify(service).update(value);
        }
    }

    private UserPermissionController controller(IUserPermissionService service) {
        UserPermissionController controller = new UserPermissionController();
        ReflectionTestUtils.setField(controller, "userPermissionService", service);
        return controller;
    }
}
