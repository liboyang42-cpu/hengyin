package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.payout.EcommercePersonalReceiverService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApiClubPersonalReceiverControllerTest {
    @Test
    void bindingUsesAuthenticatedSubjectAndOnlyReturnsReceiverStatus() {
        EcommercePersonalReceiverService service = mock(EcommercePersonalReceiverService.class);
        ApiClubPersonalReceiverController controller = authenticated(service);
        ApiClubPersonalReceiverController.BindRequest request = new ApiClubPersonalReceiverController.BindRequest();
        request.setClubId(9L);
        request.setJsCode("one-time-code");
        request.setConsent(true);
        Map<String, Object> status = Map.of("receiverState", "VERIFIED", "receiverType", "PERSONAL_OPENID");
        when(service.bind(55L, 9L, "one-time-code", true)).thenReturn(status);
        AjaxResult result = controller.bind(request);
        assertEquals(status, result.get("data"));
        verify(service).bind(55L, 9L, "one-time-code", true);
    }

    @Test
    void statusCannotChooseAnotherMemberSubject() {
        EcommercePersonalReceiverService service = mock(EcommercePersonalReceiverService.class);
        Map<String, Object> status = Map.of("receiverState", "UNBOUND");
        when(service.ownerStatus(55L, 9L)).thenReturn(status);
        assertEquals(status, authenticated(service).status(9L).get("data"));
        verify(service).ownerStatus(55L, 9L);
    }

    private static ApiClubPersonalReceiverController authenticated(EcommercePersonalReceiverService service) {
        return new ApiClubPersonalReceiverController(service) {
            @Override
            public Long getAppUserId() { return 55L; }
        };
    }
}
