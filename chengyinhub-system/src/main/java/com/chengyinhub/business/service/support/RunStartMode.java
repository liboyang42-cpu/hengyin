package com.chengyinhub.business.service.support;

import java.util.Locale;

/**
 * CG-ALL-11 开局方式取值(业主 2026-11-17 批准)。
 * DESK = 桌面完成:不定位开局，整局奖励只发探索值与徽章;ALT = 在路线替代站 300 米内开局。
 * 读写在 {@code IShopDayProofService}(开局闸 runStarted 的原 owner)。
 */
public final class RunStartMode {

    public static final String DESK = "DESK";
    public static final String ALT = "ALT";

    private RunStartMode() {
    }

    /** 只认 DESK / ALT,其它一律当作正常开局(null)。 */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String mode = raw.trim().toUpperCase(Locale.ROOT);
        return DESK.equals(mode) || ALT.equals(mode) ? mode : null;
    }
}
