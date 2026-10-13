package com.chengyinhub.common.constant;

import java.util.Date;

/** 合同二/三招商写闸：招商接受使用 [open, recruitDeadline) 半开区间。 */
public final class RecruitmentWindowPolicy {
    private RecruitmentWindowPolicy() {}

    public static final int RECRUITING_LIFECYCLE = 1;
    public static final int PRICING_LIFECYCLE = 2;
    public static final int PROJECT_REVIEW_LIFECYCLE = 7;

    public static boolean hasStarted(Integer lifecycle, Date start, Date now) {
        if (lifecycle != null && (lifecycle == RECRUITING_LIFECYCLE
                || lifecycle == PRICING_LIFECYCLE || lifecycle == PROJECT_REVIEW_LIFECYCLE)) return false;
        return start != null && now != null && !now.before(start);
    }

    /** 已发布主题先招募后审核；只放开待立项状态，不放开锁价后的商业阶段。 */
    public static void assertOpen(Integer lifecycle, Integer status, Integer userStatus,
                                  Date recruitDeadline, Date now) {
        Integer recruitmentState = Integer.valueOf(7).equals(lifecycle)
                && Integer.valueOf(1).equals(status) && Integer.valueOf(1).equals(userStatus)
                ? RECRUITING_LIFECYCLE : lifecycle;
        assertOpen(recruitmentState, recruitDeadline, now);
    }

    public static void assertOpen(Integer lifecycle, Date recruitDeadline, Date now) {
        if (lifecycle == null) {
            throw new IllegalStateException("招商写入已关闭：主题 lifecycle 未知");
        }
        // common 模块不反向依赖 system 枚举；仍以显式值域列举，不用 ordinal/range。
        if (lifecycle.intValue() != RECRUITING_LIFECYCLE
                && lifecycle.intValue() != PRICING_LIFECYCLE) {
            throw new IllegalStateException("招商写入已关闭：主题不在招商期");
        }
        if (recruitDeadline == null) {
            throw new IllegalStateException("招商写入已关闭：recruitDeadline 未配置");
        }
        if (now == null || !now.before(recruitDeadline)) {
            throw new IllegalStateException("招商写入已关闭：now >= recruitDeadline");
        }
    }
}
