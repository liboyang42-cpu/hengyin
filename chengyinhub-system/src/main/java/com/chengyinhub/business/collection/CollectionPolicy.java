package com.chengyinhub.business.collection;

import java.util.Locale;

/**
 * S2 collection model: how the money for one sale is collected.
 *
 * <ul>
 *   <li>{@link #PLATFORM}: the platform merchant account collects. Only for the platform's own official products.</li>
 *   <li>{@link #SPLIT}: the seller's own WeChat Pay sub-merchant (收付通二级商户 / 服务商子商户) collects; the platform's take
 *       is a profit-sharing receiver amount on that payment. Required for every third-party seller.</li>
 *   <li>{@link #REFUSE}: the sale is refused (a third-party seller that cannot be paid by split settlement yet).</li>
 * </ul>
 */
public enum CollectionPolicy {
    PLATFORM, SPLIT, REFUSE;

    /** Null for anything that is not exactly one of the three names (case-insensitive). */
    public static CollectionPolicy parse(String raw) {
        if (raw == null) return null;
        String wanted = raw.trim().toUpperCase(Locale.ROOT);
        for (CollectionPolicy p : values()) {
            if (p.name().equals(wanted)) return p;
        }
        return null;
    }
}
