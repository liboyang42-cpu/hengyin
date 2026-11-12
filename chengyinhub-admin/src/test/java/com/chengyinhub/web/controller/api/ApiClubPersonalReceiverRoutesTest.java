package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.payout.EcommercePersonalReceiverService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApiClubPersonalReceiverRoutesTest {
    @Test
    void bindingUsesAuthenticatedSubjectAndOnlyReturnsReceiverStatus() {
        EcommercePersonalReceiverService service = mock(EcommercePersonalReceiverService.class);
        ApiClubController controller = authenticated(service);
        ApiClubController.PersonalReceiverBindRequest request = new ApiClubController.PersonalReceiverBindRequest();
        request.setClubId(9L);
        request.setJsCode("one-time-code");
        request.setConsent(true);
        Map<String, Object> status = Map.of("receiverState", "VERIFIED", "receiverType", "PERSONAL_OPENID");
        when(service.bind(55L, 9L, "one-time-code", true)).thenReturn(status);
        AjaxResult result = controller.bindPersonalReceiver(request);
        assertEquals(status, result.get("data"));
        verify(service).bind(55L, 9L, "one-time-code", true);
    }

    @Test
    void statusCannotChooseAnotherMemberSubject() {
        EcommercePersonalReceiverService service = mock(EcommercePersonalReceiverService.class);
        Map<String, Object> status = Map.of("receiverState", "UNBOUND");
        when(service.ownerStatus(55L, 9L)).thenReturn(status);
        assertEquals(status, authenticated(service).personalReceiverStatus(9L).get("data"));
        verify(service).ownerStatus(55L, 9L);
    }

    private static ApiClubController authenticated(EcommercePersonalReceiverService service) {
        ApiClubController controller = new ApiClubController() {
            @Override
            public Long getAppUserId() { return 55L; }
        };
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "personalReceiverService", service);
        return controller;
    }
}
