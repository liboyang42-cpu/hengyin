package com.chengyinhub.common.utils;

import com.chengyinhub.web.controller.api.ApiStatsController;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** owner 2026-10-23 D4(P1-094):商家漏斗与看板的零分母比率统一回 null,舍入与原各处实现一致。 */
class SafeRatioTest {

    @Test
    void zeroDenominatorReturnsNullEverywhere() {
        assertNull(SafeRatio.ratio(3, 0, 2));
        assertNull(SafeRatio.percent(1, 0, 1));
        assertNull(SafeRatio.change(5, 0, 4));
        assertNull(SafeRatio.ratio(0, 0, 2));
    }

    @Test
    void roundingMatchesTheReplacedImplementations() {
        // MerchantInsight 原 round2((double) a / b)
        assertEquals(Math.round((double) 2 / 3 * 100) / 100.0, SafeRatio.ratio(2, 3, 2));
        assertEquals(0.0, SafeRatio.ratio(0, 7, 2));
        // ApiStats 原 Math.round(part * 1000.0 / total) / 10.0
        assertEquals(Math.round(7 * 1000.0 / 12) / 10.0, SafeRatio.percent(7, 12, 1));
        assertEquals(0.0, SafeRatio.percent(0, 3, 1));
        // MerchantAggregate 原 BigDecimal HALF_UP 4 位 toPlainString
        assertEquals("-0.3333", SafeRatio.change(2, 3, 4));
        assertEquals("1.0000", SafeRatio.change(6, 3, 4));
    }

    @Test
    void clubDashboardRateIsNullWithoutParticipants() {
        ApiStatsController controller = new ApiStatsController();
        assertNull(ReflectionTestUtils.invokeMethod(controller, "rate", 1, 0));
        assertEquals(58.3, (Double) ReflectionTestUtils.invokeMethod(controller, "rate", 7, 12));
    }

    @Test
    void merchantFunnelAndDashboardUseTheSharedHelper() throws Exception {
        String funnel = read("../chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/MerchantAggregateReadServiceImpl.java");
        String insight = read("../chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/MerchantInsightServiceImpl.java");
        String stats = read("src/main/java/com/chengyinhub/web/controller/api/ApiStatsController.java");
        assertTrue(funnel.contains("SafeRatio.change(current, previous, 4)"));
        assertTrue(insight.contains("SafeRatio.ratio(redeems, entitlements, 2)"));
        assertTrue(insight.contains("SafeRatio.ratio(repeatMembers, redeemMembers, 2)"));
        assertTrue(insight.contains("SafeRatio.ratio(quotaUsed, quotaTotal, 2)"));
        assertTrue(stats.contains("SafeRatio.percent(part, total, 1)"));
        assertFalse(stats.contains(": 0;"), "完成率不得再用 0 冒充零分母");
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }
}
