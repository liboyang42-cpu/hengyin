package com.chengyinhub.business.merchantplans;

import java.util.List;
import java.util.Locale;

/**
 * S3a MON-04: the product types whose platform take is a setting.
 *
 * <ul>
 *   <li>{@link #OFFICIAL_TICKET}: theme tickets ({@code settlement_rule} merchant 0, biz_type {@code topic});</li>
 *   <li>{@link #THIRD_PARTY_TICKET}: tickets sold by someone other than the platform, by source:
 *       {@code club} (club-run themes, biz_type {@code topic_club}) and {@code organiser} (independent events,
 *       biz_type {@code activity});</li>
 *   <li>{@link #BUNDLE}: marketing bundles (today each line keeps its theme's frozen rate);</li>
 *   <li>{@link #TEMPLATE}: workshop template sales (today 100% minus the creator share);</li>
 *   <li>{@link #CLUB_DUES}: club dues paid to the club's own sub-merchant (K1); until someone sets it, the take is the
 *       club ticket take ({@code topic_club}, else {@code topic}). Only used while the collection model is active.</li>
 * </ul>
 */
public enum TakeProduct {
    OFFICIAL_TICKET("official_ticket", List.of()),
    THIRD_PARTY_TICKET("third_party_ticket", List.of("club", "organiser")),
    BUNDLE("bundle", List.of()),
    TEMPLATE("template", List.of()),
    /** K1: club dues collected by the club's own sub-merchant; today's value is the club third-party ticket take. */
    CLUB_DUES("club_dues", List.of());

    /** Product-wide row ({@code source = ''}). */
    public static final String ANY_SOURCE = "";

    private final String key;
    private final List<String> sources;

    TakeProduct(String key, List<String> sources) {
        this.key = key;
        this.sources = sources;
    }

    public String key() { return key; }

    /** Known sources; empty when the product has none. */
    public List<String> sources() { return sources; }

    public static TakeProduct fromKey(String raw) {
        if (raw == null) return null;
        String wanted = raw.trim().toLowerCase(Locale.ROOT);
        for (TakeProduct p : values()) if (p.key.equals(wanted)) return p;
        return null;
    }

    /** Normalised source: '' for none, a known source, or null when the source is not valid for this product. */
    public String normaliseSource(String raw) {
        String s = raw == null ? ANY_SOURCE : raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return ANY_SOURCE;
        return sources.contains(s) ? s : null;
    }

    /** The product and source behind a generic settlement_rule biz_type; null when the type is not a take we own. */
    public static Mapped ofSettlementBizType(String bizType) {
        if (bizType == null) return null;
        return switch (bizType.trim()) {
            case "topic" -> new Mapped(OFFICIAL_TICKET, ANY_SOURCE);
            case "topic_club" -> new Mapped(THIRD_PARTY_TICKET, "club");
            case "activity" -> new Mapped(THIRD_PARTY_TICKET, "organiser");
            default -> null;
        };
    }

    /** The settlement_rule biz_type whose platform rate is today's value for a product and source, or null. */
    public static String settlementBizTypeOf(TakeProduct product, String source) {
        if (product == OFFICIAL_TICKET) return "topic";
        if (product == CLUB_DUES) return "topic_club";
        if (product == THIRD_PARTY_TICKET) {
            if ("club".equals(source)) return "topic_club";
            if ("organiser".equals(source)) return "activity";
        }
        return null;
    }

    public record Mapped(TakeProduct product, String source) {}
}
