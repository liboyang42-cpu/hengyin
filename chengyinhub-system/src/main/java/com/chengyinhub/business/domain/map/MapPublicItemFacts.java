package com.chengyinhub.business.domain.map;

import com.chengyinhub.business.util.ActivityLifecycle;

import java.util.List;

/**
 * 逐项版本、视觉状态和动作。只解释这一行已经选出的列。
 * 图层可读不产生报名或导航。缺依据的动作不放进列表。
 */
public final class MapPublicItemFacts {

    public static final String NAVIGATE = "navigate";
    /** 报名动作名。本读取没有报名闸的事实，所以不会放进 {@link #actions}。 */
    public static final String REGISTER = "register";

    private MapPublicItemFacts() {
    }

    public static String visibilityVersion(MapPublicRow row) {
        if (row == null || row.getVisibilityVersion() == null) {
            return null;
        }
        String version = row.getVisibilityVersion().trim();
        return version.isEmpty() ? null : version;
    }

    public static String visualState(MapPublicRow row) {
        if (row == null || row.getSourceType() == null) {
            return null;
        }
        return switch (row.getSourceType()) {
            case "roam_poi" -> roamState(row);
            case "store" -> storeState(row.getBusinessStatus());
            case "activity" -> ActivityLifecycle.timeBucket(row.getStartDate(), row.getEndDate());
            default -> null;
        };
    }

    /** 只列出这一行已经能证明的动作。没有的名字不出现。 */
    public static List<String> actions(MapPublicRow row) {
        if (row != null && "store".equals(row.getSourceType())
                && Integer.valueOf(1).equals(row.getLocationVerified())) {
            return List.of(NAVIGATE);
        }
        return List.of();
    }

    private static String roamState(MapPublicRow row) {
        if (row.getFound() == null || row.getPendingRedeem() == null) {
            return null;
        }
        if (Boolean.TRUE.equals(row.getPendingRedeem())) {
            return "pending_redeem";
        }
        if (Boolean.TRUE.equals(row.getFound())) {
            return "found";
        }
        return "unfound";
    }

    /** 与打烊门禁一致：只有 0 是打烊；空值和 1 是营业中。其他值没有视觉状态。 */
    private static String storeState(Integer businessStatus) {
        if (businessStatus == null || businessStatus == 1) {
            return "open";
        }
        if (businessStatus == 0) {
            return "closed";
        }
        return null;
    }
}
