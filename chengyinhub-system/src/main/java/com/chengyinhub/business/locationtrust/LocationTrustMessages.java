package com.chengyinhub.business.locationtrust;

import java.util.List;

/** Player-facing wording for trust decisions. Plain words; says what to do next, never accuses. */
public final class LocationTrustMessages {
    private LocationTrustMessages() {
    }

    /** Message for a refused write (retryable). */
    public static String rejected(List<String> reasons) {
        if (reasons.contains(LocationTrustPolicy.ACCURACY_POOR)) return "定位精度不够,请到开阔处再试";
        if (reasons.contains(LocationTrustPolicy.FUTURE_FIX)) return "手机时间和定位时间对不上,请校准时间后再试";
        return "定位无效,请重新定位后再试";
    }

    /** Message for a write that was kept on record but not credited. */
    public static String withheld(List<String> reasons) {
        if (reasons.contains(LocationTrustPolicy.SIMULATED)) return "这次定位来自模拟软件,不计入奖励";
        if (reasons.contains(LocationTrustPolicy.SPEED_IMPOSSIBLE)) return "定位跳得太快,这次先不计入,稍等片刻再试";
        return "这次定位暂不计入,稍后再试";
    }

    /** Short machine-readable code for clients: first reason or empty. */
    public static String code(List<String> reasons) {
        return reasons.isEmpty() ? "" : reasons.get(0);
    }
}
