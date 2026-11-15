package com.chengyinhub.business.moneyswitch;

import java.util.Locale;

/**
 * The money areas the app's {@code MoneySwitch} reads. The wire key is the JSON field name in
 * {@code GET /api/app/money-switch}; unknown keys are refused, never mapped to an area.
 */
public enum MoneyArea {
    TICKETS("tickets"),
    PASS("pass"),
    MERCHANT_MONEY("merchantMoney"),
    PAYOUT("payout"),
    WITHDRAWAL("withdrawal"),
    REFUNDS("refunds");

    private final String key;

    MoneyArea(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    /** Null for anything that is not exactly one of the six keys (case-insensitive). */
    public static MoneyArea fromKey(String raw) {
        if (raw == null) {
            return null;
        }
        String wanted = raw.trim().toLowerCase(Locale.ROOT);
        for (MoneyArea area : values()) {
            if (area.key.toLowerCase(Locale.ROOT).equals(wanted)) {
                return area;
            }
        }
        return null;
    }
}
