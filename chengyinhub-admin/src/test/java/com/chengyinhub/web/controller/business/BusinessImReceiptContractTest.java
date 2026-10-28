package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.service.IImService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Binding and annotation contract, not a substitute for HTTP security integration. */
class BusinessImReceiptContractTest {
    @Test void receiptUsesAuthenticatedAdministratorAndOriginalSendPermission() throws Exception {
        BusinessImController controller = spy(new BusinessImController());
        IImService service = mock(IImService.class);
        ReflectionTestUtils.setField(controller, "imService", service);
        doReturn(7L).when(controller).getUserId();
        Map<String, Object> receipt = Map.of("found", false);
        when(service.adminSendReceipt(7L, 9007199254740993L, "request_0001")).thenReturn(receipt);
        AjaxResult result = controller.sendReceipt("9007199254740993", "request_0001");
        assertSame(receipt, result.get("data"));
        verify(service).adminSendReceipt(7L, 9007199254740993L, "request_0001");
        verifyNoMoreInteractions(service);
        String permission = BusinessImController.class.getMethod("sendReceipt", String.class, String.class)
                .getAnnotation(PreAuthorize.class).value();
        assertEquals("@ss.hasPermi('business:im:send')", permission);
    }
}
