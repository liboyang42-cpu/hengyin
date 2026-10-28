package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ApiRegistrationJoinInfoTest {
    @Test
    void noActiveRegistrationIsEmptySuccessAndUsesLoggedInMember() {
        ICmsRegistrationService service = mock(ICmsRegistrationService.class);
        ApiRegistrationController controller = spy(new ApiRegistrationController());
        doReturn(9003L).when(controller).getAppUserId();
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "registrationService", service);
        when(service.selectCmsRegistrationList(any())).thenReturn(Collections.emptyList());

        AjaxResult result = RegistrationQueryTestFixtures.wire(controller).getJoinRegistrationDetail("9008");

        assertEquals(200, result.get("code"));
        assertNull(result.get("data"));
        ArgumentCaptor<CmsRegistration> query = ArgumentCaptor.forClass(CmsRegistration.class);
        verify(service).selectCmsRegistrationList(query.capture());
        assertEquals(9003L, query.getValue().getMemberId());
        assertEquals(2, query.getValue().getRegistrationStatus());

        when(service.selectCmsRegistrationList(any())).thenThrow(new IllegalStateException("读取失败"));
        assertEquals(500, RegistrationQueryTestFixtures.wire(controller).getJoinRegistrationDetail("9008").get("code"));
    }
}
