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
 * C11 版本化语言 / 日期 / 时区 / 可读性展示。v1 锁定既定中美差异，后续改展示必须升版本。
 *
 * <p>CN：zh-CN、Asia/Shanghai、24 小时 {@code yyyy-MM-dd HH:mm:ss}、线下消费走微信支付。
 * US：en-US、America/New_York、12 小时 {@code MMM d, yyyy h:mm a}、虚拟道具走 Apple IAP。
 * 备案 / 协议签署 / 商店审核 / 真实生产容量不由本类判定，固定 {@link #NOT_RUN}。
 */
public final class VersionedLocaleDisplay {

    public static final int PROFILE_VERSION = 1;
    public static final String NOT_RUN = "NOT_RUN";

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

    public static String format(Market market, Instant instant) {
        Profile profile = profile(market);
        if (instant == null) {
            return "UNKNOWN";
        }
        return profile.dateTimeFormatter.withZone(profile.zoneId).format(instant);
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
            view.put("paymentChannel", paymentChannel);
            view.put("readability", readability);
            view.put("legalGates", NOT_RUN);
            return Collections.unmodifiableMap(view);
        }
    }
}
