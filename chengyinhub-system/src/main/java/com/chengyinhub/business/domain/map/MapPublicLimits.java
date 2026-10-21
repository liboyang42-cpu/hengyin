package com.chengyinhub.business.domain.map;

import java.math.BigDecimal;

/**
 * 公开地图的配置输入和硬安全上限。请求超过生效值就拒绝，不改写。
 * 生效值是配置与硬上限中较小的那个，避免把配置调大以后越过安全封顶。
 * 面积上限是滥用封顶，不是客户端 0.5° 候选被采纳后的业务半径。
 */
public final class MapPublicLimits {

    public static final String DATUM = "GCJ-02";
    /** 公开规则版本。语句变化时必须改它，旧游标随之失效。 */
    public static final String WATERMARK = "map-public-v1";

    public static final int CONFIGURED_PAGE_LIMIT = 50;
    public static final int HARD_PAGE_LIMIT = 100;
    public static final int CONFIGURED_LAYER_COUNT = 5;
    public static final int HARD_LAYER_COUNT = 8;

    public static final String CONFIGURED_MAX_AREA_DEG2 = "1";
    public static final String HARD_MAX_AREA_DEG2 = "4";

    private MapPublicLimits() {
    }

    public static int cap(int configured, int hard) {
        if (configured < 1 || hard < 1) {
            throw new IllegalArgumentException("LIMIT_CONFIG");
        }
        return Math.min(configured, hard);
    }

    public static int effectivePageLimit() {
        return cap(CONFIGURED_PAGE_LIMIT, HARD_PAGE_LIMIT);
    }

    public static int effectiveLayerCount() {
        return cap(CONFIGURED_LAYER_COUNT, HARD_LAYER_COUNT);
    }

    public static BigDecimal effectiveMaxArea() {
        return new BigDecimal(CONFIGURED_MAX_AREA_DEG2).min(new BigDecimal(HARD_MAX_AREA_DEG2));
    }
}
