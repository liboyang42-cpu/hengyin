package com.chengyinhub.web.controller.business;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import com.chengyinhub.business.merchantgap.MerchantGapException;
import com.chengyinhub.business.merchantgap.MerchantOfferWithdrawalAdminService;
import com.chengyinhub.business.service.channel.platform.ChannelRejection;
import com.chengyinhub.business.service.channel.platform.ChannelWebhookAdminService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.domain.AjaxResult;

/** The two back-office routes added for the admin review pages: paths, permissions, audit log and refusal envelope. */
class ReviewQueueAdminControllersTest {
    @Test
    void offerWithdrawalIsReadOnlyUnderAuditList() throws Exception {
        assertEquals("/business/offer-withdrawal", MerchantOfferWithdrawalAdminController.class.getAnnotation(RequestMapping.class).value()[0]);
        Method list = MerchantOfferWithdrawalAdminController.class.getMethod("list", Long.class, Long.class, String.class, Integer.class, Integer.class);
        assertEquals("/list", list.getAnnotation(GetMapping.class).value()[0]);
        assertEquals("@ss.hasPermi('business:audit:list')", list.getAnnotation(PreAuthorize.class).value());
        for (Method m : MerchantOfferWithdrawalAdminController.class.getDeclaredMethods()) assertNull(m.getAnnotation(PostMapping.class), m.getName());

        MerchantOfferWithdrawalAdminService service = mock(MerchantOfferWithdrawalAdminService.class);
        when(service.list(8001L, null, "WITHDRAWN", 1, 20)).thenReturn(Map.of("total", 1L));
        AjaxResult result = new MerchantOfferWithdrawalAdminController(service).list(8001L, null, "WITHDRAWN", 1, 20);
        assertEquals(Map.of("total", 1L), result.get(AjaxResult.DATA_TAG));
        ResponseEntity<AjaxResult> refused = new MerchantOfferWithdrawalAdminController(service)
                .failed(new MerchantGapException(400, "INVALID_REQUEST", "结果筛选无效"));
        assertEquals(400, refused.getStatusCode().value());
        assertEquals("INVALID_REQUEST", refused.getBody().get("errorCode"));
    }

    @Test
    void webhookListIsAuditListAndRedriveIsAuditReviewWithOperLog() throws Exception {
        assertEquals("/business/im-webhook", ImWebhookDeliveryAdminController.class.getAnnotation(RequestMapping.class).value()[0]);
        Method list = ImWebhookDeliveryAdminController.class.getMethod("list", String.class, Long.class, Integer.class, Integer.class);
        Method redrive = ImWebhookDeliveryAdminController.class.getMethod("redrive", long.class);
        assertEquals("/deliveries", list.getAnnotation(GetMapping.class).value()[0]);
        assertEquals("@ss.hasPermi('business:audit:list')", list.getAnnotation(PreAuthorize.class).value());
        assertEquals("/deliveries/{deliveryId}/redrive", redrive.getAnnotation(PostMapping.class).value()[0]);
        assertEquals("@ss.hasPermi('business:audit:review')", redrive.getAnnotation(PreAuthorize.class).value());
        assertNotNull(redrive.getAnnotation(Log.class), "every redrive is written to sys_oper_log");

        ChannelWebhookAdminService service = mock(ChannelWebhookAdminService.class);
        when(service.redrive(42L, 2L)).thenReturn(Map.of("state", "PENDING"));
        ImWebhookDeliveryAdminController controller = new ImWebhookDeliveryAdminController(service) {
            @Override
            public Long getUserId() { return 42L; }
        };
        assertEquals(Map.of("state", "PENDING"), controller.redrive(2L).get(AjaxResult.DATA_TAG));
        verify(service).redrive(42L, 2L);
        ResponseEntity<AjaxResult> refused = controller.failed(new ChannelRejection(409, "DELIVERY_NOT_RETRYABLE", "该投递不可重试"));
        assertEquals(409, refused.getStatusCode().value());
        assertEquals("DELIVERY_NOT_RETRYABLE", refused.getBody().get("errorCode"));
    }
}
