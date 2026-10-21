package com.chengyinhub.business.citygame.analytics.value;

import java.util.Locale;
import java.util.Set;

/** 事件种类与明确不算有意义动作的动作码。 */
final class ValueKinds {
    static final String ENTRY = "ENTRY";
    static final String MEANINGFUL_ACTION = "MEANINGFUL_ACTION";
    static final String SERVICE_SUCCESS = "SERVICE_SUCCESS";
    static final String PUBLIC_GOAL = "PUBLIC_GOAL";
    static final String COOPERATIVE_GOAL = "COOPERATIVE_GOAL";
    static final String SKIP = "SKIP";
    static final String EXIT = "EXIT";
    static final String SERVICE_RATING = "SERVICE_RATING";
    static final String NET_POINTS = "NET_POINTS";
    static final String VISIT_BUDGET = "VISIT_BUDGET";
    static final String DEFINITION = "DEFINITION";
    static final String COVERAGE = "COVERAGE";

    static final Set<String> BEHAVIOR = Set.of(
            ENTRY, MEANINGFUL_ACTION, SERVICE_SUCCESS, PUBLIC_GOAL, COOPERATIVE_GOAL,
            SKIP, EXIT, SERVICE_RATING, NET_POINTS, VISIT_BUDGET);

    /** 点击、走路、每日登录、收租登录。连字符会先折成下划线。 */
    private static final Set<String> NOISE = Set.of(
            "click", "clicks", "walk", "walking", "daily_login", "rent_login", "collect_rent");

    private ValueKinds() {
    }

    static boolean behavior(String kind) {
        return BEHAVIOR.contains(kind);
    }

    static String norm(String code) {
        if (code == null) {
            return null;
        }
        String normalized = code.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return normalized.isEmpty() ? null : normalized;
    }

    static boolean noise(String code) {
        String normalized = norm(code);
        return normalized != null && NOISE.contains(normalized);
    }

    static boolean actionCode(String code) {
        String normalized = norm(code);
        return normalized != null && normalized.matches("[a-z0-9][a-z0-9_]{0,95}");
    }
}
