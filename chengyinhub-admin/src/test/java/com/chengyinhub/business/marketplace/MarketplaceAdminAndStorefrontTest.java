package com.chengyinhub.business.marketplace;

import com.chengyinhub.business.mapper.MarketplaceMapper;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketplaceAdminAndStorefrontTest {

    @Test
    void leadsNeverGoBelowTwoVisitorsAndClampTheWindow() {
        MarketplaceMapper mapper = mock(MarketplaceMapper.class);
        MarketplaceAdminService service = new MarketplaceAdminService(mapper);
        org.mockito.ArgumentCaptor<Date> since = org.mockito.ArgumentCaptor.forClass(Date.class);

        service.salesLeads(null, 1);
        verify(mapper).selectSalesLeads(since.capture(), eq(MarketplaceAdminService.MIN_LEAD_VISITORS),
                eq(MarketplaceAdminService.MAX_LEAD_ROWS));
        long days = (System.currentTimeMillis() - since.getValue().getTime()) / 86_400_000L;
        assertTrue(days >= 89 && days <= 90, "default window is 90 days");

        assertEquals(365, MarketplaceAdminService.clamp(9999, 90, 1, 365));
        assertEquals(1, MarketplaceAdminService.clamp(-5, 90, 1, 365));
        assertEquals(2, MarketplaceAdminService.clamp(0, 2, 2, 1000));
    }

    @Test
    void onlyExistingMerchantCouponsCanBeListedAsDeals() {
        MarketplaceMapper mapper = mock(MarketplaceMapper.class);
        MarketplaceAdminService service = new MarketplaceAdminService(mapper);
        when(mapper.countListableCoupon(3L)).thenReturn(0);
        assertThrows(IllegalArgumentException.class, () -> service.setDeal(3L, true, "op"));
        assertThrows(IllegalArgumentException.class, () -> service.setDeal(null, true, "op"));
        verify(mapper, never()).upsertDeal(anyLong(), anyInt(), anyString());

        when(mapper.countListableCoupon(4L)).thenReturn(1);
        service.setDeal(4L, false, "op");
        verify(mapper).upsertDeal(4L, 0, "op");
    }

    @Test
    void storefrontGroupsSkusUnderPublishedProductsAndIsNeverPurchasable() {
        Map<String, Object> p1 = row("productId", 10L, "productName", "手冲咖啡", "price", 28);
        Map<String, Object> p2 = row("productId", 11L, "productName", "贝果", "price", 15);
        Map<String, Object> s1 = row("skuId", 100L, "productId", 10, "skuName", "热", "price", "28.00");
        Map<String, Object> s2 = row("skuId", 101L, "productId", 10L, "skuName", "冰", "price", "30.00");
        Map<String, Object> orphan = row("skuId", 102L, "productId", 99L, "skuName", "x", "price", "1.00");
        Map<String, Object> out = MarketplaceStorefrontService.assemble(List.of(p1, p2), List.of(s1, s2, orphan));
        assertEquals(Boolean.FALSE, out.get("purchasable"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> products = (List<Map<String, Object>>) out.get("products");
        assertEquals(2, products.size());
        @SuppressWarnings("unchecked") List<Map<String, Object>> skus = (List<Map<String, Object>>) products.get(0).get("skus");
        assertEquals(2, skus.size());
        assertTrue(skus.stream().noneMatch(s -> s.containsKey("productId")));
        assertEquals(0, ((List<?>) products.get(1).get("skus")).size());

        Map<String, Object> empty = MarketplaceStorefrontService.assemble(null, null);
        assertEquals(0, ((List<?>) empty.get("products")).size());
        MarketplaceMapper mapper = mock(MarketplaceMapper.class);
        assertEquals(Boolean.FALSE, new MarketplaceStorefrontService(mapper).storefront(5L).get("purchasable"));
        verify(mapper).selectPublishedProducts(5L);
        verify(mapper).selectPublishedSkus(any());
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
