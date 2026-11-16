package com.chengyinhub.web.controller.api.modcommerce;

import com.chengyinhub.business.appgaps.WorkshopOwnedRead;
import com.chengyinhub.business.appleiap.WorkshopAppleIapService;
import com.chengyinhub.business.modcommerce.ModCommerceService;
import com.chengyinhub.business.platformpass.PlatformPassAppleService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.web.controller.api.ApiAppleNotificationController;
import com.chengyinhub.web.controller.api.appgaps.ApiAppGapsWorkshopController;
import com.chengyinhub.web.controller.api.appgaps.NotBuiltFeature;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** R6c workshop-iap: switch-closed refusal, error mapping, and the notification endpoint's product dispatch. */
class ApiModCommerceControllerTest {

    private static ApiModCommerceController controller(ModCommerceService service, Long member) {
        return new ApiModCommerceController(service) { @Override Long signedInMember() { return member; } };
    }

    /** Routes that sell: refused while the money switch's pass area is closed. */
    private static List<ResponseEntity<AjaxResult>> sellRoutes(ApiModCommerceController c, MockHttpServletResponse r) {
        List<ResponseEntity<AjaxResult>> out = new ArrayList<>();
        out.add(c.offers(null, r));
        out.add(c.offer("MCO_x", r));
        out.add(c.create(new ModCommerceService.Intent("req", "MCO_x", "0".repeat(64)), r));
        out.add(c.lookup("req", r));
        out.add(c.byRequest("req", r));
        out.add(c.payment("WKO_x", r));
        out.add(c.nativeContext("WKO_x", r));
        out.add(c.nativeDispatch("WKO_x", r));
        return out;
    }

    /** Routes that deliver what Apple already charged (MERCHANT-2): only the feature flag gates them. */
    private static List<ResponseEntity<AjaxResult>> deliverRoutes(ApiModCommerceController c, MockHttpServletResponse r) {
        List<ResponseEntity<AjaxResult>> out = new ArrayList<>();
        out.add(c.storeKit(r));
        out.add(c.cancelRequest(new ModCommerceService.Intent("req", "MCO_x", "0".repeat(64)), r));
        out.add(c.order("WKO_x", r));
        out.add(c.orderOffer("WKO_x", r));
        out.add(c.nativeTransaction("WKO_x", new ApiModCommerceController.Signed("jws"), r));
        out.add(c.restore(new ApiModCommerceController.Signed("jws"), r));
        out.add(c.reconcile("WKO_x", r));
        out.add(c.cancel("WKO_x", r));
        out.add(c.reversalStatus("WKO_x", r));
        out.add(c.licenses(null, r));
        out.add(c.license("WKO_x", r));
        return out;
    }

    private static List<ResponseEntity<AjaxResult>> everyRoute(ApiModCommerceController c, MockHttpServletResponse r) {
        List<ResponseEntity<AjaxResult>> out = new ArrayList<>(sellRoutes(c, r));
        out.addAll(deliverRoutes(c, r));
        return out;
    }

    @Test
    void featureOffAnswersExactlyTheNamedRefusalOfTodayAndTouchesNothing() {
        ModCommerceService service = mock(ModCommerceService.class);
        when(service.open()).thenReturn(false);
        when(service.deliverable()).thenReturn(false);
        MockHttpServletResponse catchAllResponse = new MockHttpServletResponse();
        AjaxResult today = new ApiAppGapsWorkshopController(new WorkshopOwnedRead()) {
            @Override public Long getAppUserId() { return 300L; }
        }.commerceOff(new MockHttpServletRequest("POST", "/api/mod-commerce/orders"), catchAllResponse);
        assertEquals(503, today.get("code"), "baseline: the catch-all refusal");

        MockHttpServletResponse response = new MockHttpServletResponse();
        for (ResponseEntity<AjaxResult> answer : everyRoute(controller(service, 300L), response)) {
            assertEquals(200, answer.getStatusCode().value());
            assertEquals(today, answer.getBody());
        }
        assertEquals("MOD_COMMERCE", response.getHeader(NotBuiltFeature.HEADER));
        verify(service, atLeastOnce()).open();
        verify(service, atLeastOnce()).deliverable();
        verifyNoMoreInteractions(service);
    }

    @Test
    void closedSwitchStillDeliversButRefusesEverySellRoute() {
        ModCommerceService service = mock(ModCommerceService.class);
        when(service.open()).thenReturn(false);
        when(service.deliverable()).thenReturn(true);
        when(service.licenses(300L, null)).thenReturn(List.of(Map.of("licenseId", "WKL_1")));
        when(service.restore(300L, "jws")).thenReturn(Map.of("orderKey", "WKO_x", "licenseState", "ACTIVE"));
        when(service.nativeTransaction(300L, "WKO_x", "jws")).thenReturn(Map.of("orderKey", "WKO_x"));
        ApiModCommerceController c = controller(service, 300L);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertEquals(List.of(Map.of("licenseId", "WKL_1")), c.licenses(null, response).getBody().get("data"));
        assertEquals("ACTIVE", ((Map<?, ?>) c.restore(new ApiModCommerceController.Signed("jws"), response)
                .getBody().get("data")).get("licenseState"));
        assertEquals(200, c.nativeTransaction("WKO_x", new ApiModCommerceController.Signed("jws"), response)
                .getBody().get("code"));

        for (ResponseEntity<AjaxResult> answer : sellRoutes(c, new MockHttpServletResponse())) {
            assertEquals(503, answer.getBody().get("code"));
        }
        verify(service, never()).offers(any());
        verify(service, never()).create(anyLong(), any());
        verify(service, never()).payment(anyLong(), anyString());
        verify(service, never()).nativeDispatch(anyLong(), anyString());
    }

    @Test
    void signedOutAnswers401BeforeAnythingElse() {
        ModCommerceService service = mock(ModCommerceService.class);
        for (ResponseEntity<AjaxResult> answer : everyRoute(controller(service, null), new MockHttpServletResponse())) {
            assertEquals(401, answer.getBody().get("code"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void openSwitchServesDataAndMapsRefusalsToHttpStatus() {
        ModCommerceService service = mock(ModCommerceService.class);
        when(service.open()).thenReturn(true);
        when(service.offers(null)).thenReturn(List.of(Map.of("offerId", "MCO_1")));
        when(service.payment(300L, "WKO_x")).thenThrow(new ServiceException("ORDER_NOT_PAYABLE", 409));
        ApiModCommerceController c = controller(service, 300L);
        ResponseEntity<AjaxResult> ok = c.offers(null, new MockHttpServletResponse());
        assertEquals(200, ok.getBody().get("code"));
        assertEquals(List.of(Map.of("offerId", "MCO_1")), ok.getBody().get("data"));
        ResponseEntity<AjaxResult> refused = c.payment("WKO_x", new MockHttpServletResponse());
        assertEquals(409, refused.getStatusCode().value());
        assertEquals("ORDER_NOT_PAYABLE", refused.getBody().get("errorCode"));
    }

    @Test
    void everyAppRouteIsMappedUnderModCommerce() {
        RequestMapping base = ApiModCommerceController.class.getAnnotation(RequestMapping.class);
        assertArrayEquals(new String[]{"/api/mod-commerce"}, base.value());
        List<String> routes = new ArrayList<>();
        for (Method m : ApiModCommerceController.class.getDeclaredMethods()) {
            GetMapping get = m.getAnnotation(GetMapping.class);
            PostMapping post = m.getAnnotation(PostMapping.class);
            if (get != null) routes.add("GET " + get.value()[0]);
            if (post != null) routes.add("POST " + post.value()[0]);
        }
        for (String expected : List.of("GET /offers", "GET /offers/{offerId}", "POST /orders",
                "GET /orders/by-request/{requestId}/lookup", "GET /orders/by-request/{requestId}", "POST /orders/by-request/cancel",
                "GET /orders/{orderKey}", "GET /orders/{orderKey}/offer", "POST /orders/{orderKey}/payment",
                "GET /orders/{orderKey}/native-context", "POST /orders/{orderKey}/native-dispatch",
                "POST /orders/{orderKey}/native-transaction", "POST /native-transactions/restore",
                "POST /orders/{orderKey}/reconcile", "POST /orders/{orderKey}/cancel", "GET /orders/{orderKey}/reversal-status",
                "GET /licenses", "GET /licenses/{licenseId}", "GET /storekit")) {
            assertTrue(routes.contains(expected), expected);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    @Test
    void notificationEndpointDispatchesPassThenTemplatesThenLegacy() {
        PlatformPassAppleService pass = mock(PlatformPassAppleService.class);
        ModCommerceService templates = mock(ModCommerceService.class);
        WorkshopAppleIapService legacy = mock(WorkshopAppleIapService.class);
        var endpoint = new ApiAppleNotificationController(pass, provider(legacy), provider(templates));
        var command = new ApiAppleNotificationController.Notification("signed");

        when(pass.notificationIfPass("signed")).thenReturn(Optional.of(Map.of("state", "APPLIED", "by", "pass")));
        assertEquals(Map.of("state", "APPLIED", "by", "pass"), endpoint.notification(command).getBody().get("data"));
        verifyNoInteractions(templates, legacy);

        when(pass.notificationIfPass("signed")).thenReturn(Optional.empty());
        when(templates.notificationIfTemplate("signed")).thenReturn(Optional.of(Map.of("state", "APPLIED", "by", "template")));
        assertEquals(Map.of("state", "APPLIED", "by", "template"), endpoint.notification(command).getBody().get("data"));
        verifyNoInteractions(legacy);

        when(templates.notificationIfTemplate("signed")).thenReturn(Optional.empty());
        when(legacy.notification("signed")).thenReturn(Map.of("state", "APPLIED", "by", "legacy"));
        assertEquals(Map.of("state", "APPLIED", "by", "legacy"), endpoint.notification(command).getBody().get("data"));

        when(templates.notificationIfTemplate(anyString())).thenThrow(new ServiceException("APPLE_ORDER_NOT_MATCHED", 503));
        assertEquals(503, endpoint.notification(command).getStatusCode().value(), "an unapplied refund asks Apple to retry");
    }
}
