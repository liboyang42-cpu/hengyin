package com.chengyinhub.business.service.support.rpg.adapter;

import com.chengyinhub.business.domain.route.PlayCheckReceipt;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/** C02 adapter 共用:服务器时钟、冻结短路、breakdown 只加键不改骰点口径。 */
final class RpgAdapterSupport {
    private RpgAdapterSupport() {}

    static long serverNow(Clock clock) {
        return (clock == null ? Clock.systemUTC() : clock).millis();
    }

    static boolean inactive(RpgAdapterContext context) {
        return context == null || context.getSpec() == null || !context.getSpec().active()
                || context.getState() == null;
    }

    /**
     * 只往明细里追加 C02 投影键。die / skill / situational / total / dc / natAuto
     * (以及已有的 bargain)保持 {@link com.chengyinhub.business.service.support.GameplayCheckEngine} 原值。
     */
    static void putExtra(PlayCheckReceipt receipt, String key, Object value) {
        if (receipt == null || key == null || value == null) return;
        Map<String, Object> breakdown = receipt.getBreakdown() == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(receipt.getBreakdown());
        breakdown.put(key, value);
        receipt.setBreakdown(breakdown);
    }

    /** Missing requestId stays Java null, never {@code ""}. */
    static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        return value.isBlank() ? null : value;
    }
}
