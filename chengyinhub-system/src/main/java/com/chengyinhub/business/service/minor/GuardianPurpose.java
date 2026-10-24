package com.chengyinhub.business.service.minor;

/**
 * 监护授权用途（封闭取值）。PURCHASE 与 INFO_PROCESS 是两项独立同意，互不代替。
 */
public enum GuardianPurpose {
    /** 购买（报名/支付）。 */
    PURCHASE,
    /** 个人信息处理。 */
    INFO_PROCESS,
    /** 16-17 岁白天低风险线路单独参与。 */
    SOLO_DAYTIME_PLAY;

    public static GuardianPurpose parse(String raw) {
        if (raw == null) return null;
        for (GuardianPurpose p : values()) {
            if (p.name().equals(raw.trim())) return p;
        }
        return null;
    }
}
