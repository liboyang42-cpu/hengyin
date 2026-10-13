package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.IPlayerWxacodeService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiUmsMemberPlayerCodeTest {

    @Test
    void authenticatedPlayerGetsQrVoucherShape() {
        IPlayerWxacodeService codeService = mock(IPlayerWxacodeService.class);
        when(codeService.getOrCreate(88L)).thenReturn("https://oss.example/player-code.png");
        ApiUmsMemberController controller = spy(new ApiUmsMemberController());
        doReturn(88L).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "playerWxacodeService", codeService);

        AjaxResult result = controller.getPlayerWxacode();

        assertEquals(200, result.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals("https://oss.example/player-code.png", data.get("qr"));
        verify(codeService).getOrCreate(88L);
    }

    @Test
    void unauthenticatedRequestCannotConsumeWechatQuota() {
        IPlayerWxacodeService codeService = mock(IPlayerWxacodeService.class);
        ApiUmsMemberController controller = spy(new ApiUmsMemberController());
        doReturn(0L).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "playerWxacodeService", codeService);

        AjaxResult result = controller.getPlayerWxacode();

        assertEquals(500, result.get("code"));
        verify(codeService, never()).getOrCreate(0L);
    }
}
