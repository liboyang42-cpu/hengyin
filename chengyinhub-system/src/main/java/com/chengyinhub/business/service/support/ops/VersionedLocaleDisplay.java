package com.chengyinhub.business.service.support.ops;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * C11 版本化语言 / 日期 / 时区 / 可读性展示。v2：市场目录时区不是事件时区。
 * 后续改展示必须升版本。
 *
 * <p>CN 目录：zh-CN、Asia/Shanghai、24 小时 {@code yyyy-MM-dd HH:mm:ss}、线下消费走微信支付。
 * US 目录：en-US、America/New_York、12 小时 {@code MMM d, yyyy h:mm a}、虚拟道具走 Apple IAP。
 * 事件格式化必须传入真实 {@link ZoneId} / {@link EventTimezoneSource}，不得用市场目录猜时区。
 * 备案 / 协议签署 / 商店审核 / 真实生产容量不由本类判定，固定 {@link #NOT_RUN}。
 */
public final class VersionedLocaleDisplay {

    public static final int PROFILE_VERSION = 2;
    public static final String NOT_RUN = "NOT_RUN";
    public static final String EVENT_TIMEZONE_SOURCE = "locale.eventTimezone";

    public enum Market {
        CN, US
    }

    public enum LegalGate {
        ICP_FILING,
        PRIVACY_AGREEMENT,
        USER_AGREEMENT,
        WECHAT_MINIPROGRAM_REVIEW,
        APP_STORE_REVIEW,
        PLAY_STORE_REVIEW,
        PRODUCTION_CAPACITY,
        PRODUCTION_DDL
    }

    private static final Profile CN = new Profile(
            Market.CN,
            Locale.forLanguageTag("zh-CN"),
            ZoneId.of("Asia/Shanghai"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withLocale(Locale.CHINA),
            "zh-CN",
            "微信支付（线下消费/核销）",
            "24h yyyy-MM-dd HH:mm:ss · 中文不加负字距");

    private static final Profile US = new Profile(
            Market.US,
            Locale.forLanguageTag("en-US"),
            ZoneId.of("America/New_York"),
            DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a").withLocale(Locale.US),
            "en-US",
            "Apple IAP（虚拟道具；线下核销仍不走 IAP）",
            "12h MMM d, yyyy h:mm a · Dynamic Type 可读");

    private VersionedLocaleDisplay() {
    }

    public static Profile profile(Market market) {
        if (market == Market.US) {
            return US;
        }
        if (market == Market.CN) {
            return CN;
        }
        throw new IllegalArgumentException("未知市场");
    }

    /**
     * 无事件时区不得用市场目录猜。null instant 仍为 UNKNOWN；非空 instant 为 WAITING_SOURCE。
     */
    public static String format(Market market, Instant instant) {
        profile(market);
        if (instant == null) {
            return "UNKNOWN";
        }
        throw new WaitingSourceException(EVENT_TIMEZONE_SOURCE);
    }

    public static String format(Market market, Instant instant, ZoneId zone) {
        Profile profile = profile(market);
        if (instant == null) {
            return "UNKNOWN";
        }
        if (zone == null) {
            throw new WaitingSourceException(EVENT_TIMEZONE_SOURCE);
        }
        return profile.dateTimeFormatter.withZone(zone).format(instant);
    }

    public static String format(Market market, Instant instant, EventTimezoneSource source, String eventId) {
        if (source == null) {
            throw new WaitingSourceException(EVENT_TIMEZONE_SOURCE);
        }
        ZoneId zone = source.zoneFor(eventId);
        if (zone == null) {
            throw new WaitingSourceException(EVENT_TIMEZONE_SOURCE);
        }
        return format(market, instant, zone);
    }

    /** 事件/用户时区真源；缺源不得用 CN=上海 / US=纽约 代替。 */
    public interface EventTimezoneSource {
        ZoneId zoneFor(String eventId);
    }

    public static final class WaitingSourceException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final String waitingCapability;

        public WaitingSourceException(String waitingCapability) {
            super("WAITING_SOURCE " + waitingCapability);
            this.waitingCapability = waitingCapability;
        }

        public String getWaitingCapability() {
            return waitingCapability;
        }
    }

    public static Map<LegalGate, String> legalGateStatuses() {
        Map<LegalGate, String> gates = new LinkedHashMap<LegalGate, String>();
        for (LegalGate gate : LegalGate.values()) {
            gates.put(gate, NOT_RUN);
        }
        return Collections.unmodifiableMap(gates);
    }

    public static String legalGateStatus(LegalGate gate) {
        Objects.requireNonNull(gate, "gate");
        return NOT_RUN;
    }

    public static final class Profile {
        public final int version = PROFILE_VERSION;
        public final Market market;
        public final Locale locale;
        public final ZoneId zoneId;
        public final DateTimeFormatter dateTimeFormatter;
        public final String languageTag;
        public final String paymentChannel;
        public final String readability;

        Profile(Market market, Locale locale, ZoneId zoneId, DateTimeFormatter dateTimeFormatter,
                String languageTag, String paymentChannel, String readability) {
            this.market = market;
            this.locale = locale;
            this.zoneId = zoneId;
            this.dateTimeFormatter = dateTimeFormatter;
            this.languageTag = languageTag;
            this.paymentChannel = paymentChannel;
            this.readability = readability;
        }

        public Map<String, String> asView() {
            Map<String, String> view = new LinkedHashMap<String, String>();
            view.put("version", String.valueOf(version));
            view.put("market", market.name());
            view.put("languageTag", languageTag);
            view.put("timezone", zoneId.getId());
            view.put("eventTimezone", "WAITING_SOURCE");
            view.put("paymentChannel", paymentChannel);
            view.put("readability", readability);
            view.put("legalGates", NOT_RUN);
            return Collections.unmodifiableMap(view);
        }
    }
}
