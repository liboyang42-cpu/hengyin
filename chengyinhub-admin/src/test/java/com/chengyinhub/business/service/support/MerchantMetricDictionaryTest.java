package com.chengyinhub.business.service.support;

import com.chengyinhub.business.access.merchant.MerchantPermission;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 字典只能描述 /api/merchant/dashboard 已下发的字段:看板加/删字段时这里必须一起改。 */
class MerchantMetricDictionaryTest {

    @Test
    void pathsMatchDashboardFields() {
        Set<String> paths = new LinkedHashSet<>();
        for (Map<String, Object> m : MerchantMetricDictionary.metrics()) paths.add(String.valueOf(m.get("path")));
        assertEquals(new LinkedHashSet<>(Arrays.asList("revenue", "revenue7d", "pendingOrders", "insight.exposure", "insight.exposureUv",
                "insight.checkin.total", "insight.checkin.redeemRate", "insight.checkin.slotBuckets",
                "insight.checkin.repeatRate", "insight.checkin.avgWaitMinutes", "insight.supplyCount")), paths);
    }

    @Test
    void everyMetricHasDefinitionAndKnownPermission() {
        for (Map<String, Object> m : MerchantMetricDictionary.metrics()) {
            assertFalse(String.valueOf(m.get("definition")).isEmpty());
            assertTrue(MerchantPermission.all().contains(String.valueOf(m.get("requiredPermission"))), m.toString());
        }
        assertEquals(MerchantMetricDictionary.VERSION, MerchantMetricDictionary.view().get("version"));
    }
}
