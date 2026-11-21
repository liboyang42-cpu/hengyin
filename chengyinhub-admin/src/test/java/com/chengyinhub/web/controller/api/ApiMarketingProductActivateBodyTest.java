package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.IGiftEntitlementService;
import com.chengyinhub.business.service.IMarketingProductCatalogService;
import com.chengyinhub.business.service.IMarketingProductPurchaseService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** R6a admission: first-use activation takes the app's JSON {topicId} and still takes the old ?topicId=. */
class ApiMarketingProductActivateBodyTest {
    @Test
    void jsonBodyAndQueryParameterBothReachTheSameServiceCall() {
        IMarketingProductPurchaseService purchases = mock(IMarketingProductPurchaseService.class);
        when(purchases.activateOnUse(42L, 88L)).thenReturn(Map.of("activated", true));
        var controller = new ApiMarketingProductController(mock(IMarketingProductCatalogService.class), purchases,
                mock(IGiftEntitlementService.class)) {
            @Override public Long getAppUserId() { return 42L; }
        };
        assertEquals(200, controller.activate(null, Map.of("topicId", 88)).get("code"));
        assertEquals(200, controller.activate(88L, null).get("code"));
        verify(purchases, org.mockito.Mockito.times(2)).activateOnUse(42L, 88L);
        assertEquals(400, controller.activate(null, Map.of("topicId", true)).get("code"));
        assertEquals(400, controller.activate(null, null).get("code"));
        assertEquals(400, controller.activate(null, Map.of("topicId", 1.5)).get("code"));
        verify(purchases, never()).activateOnUse(42L, 0L);
        verify(purchases, org.mockito.Mockito.times(2)).activateOnUse(anyLong(), anyLong());
    }
}
