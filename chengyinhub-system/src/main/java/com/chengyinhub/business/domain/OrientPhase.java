package com.chengyinhub.business.domain;

/**
 * Doc2 F1a 城市定向阶段。topic_route_session.phase 存其名;NULL 的存量行按 {@link #derive} 推导。
 * 终态(FINISHED/ABANDONED/DISQUALIFIED)不可再迁移。
 */
public enum OrientPhase {
    PREPARED, RUNNING, FINISHED, ABANDONED, DISQUALIFIED;

    public static final int STATUS_DISQUALIFIED = 3;

    public boolean isTerminal() {
        return this == FINISHED || this == ABANDONED || this == DISQUALIFIED;
    }

    /**
     * status 是既有真源,phase 只细分它:1 一律 FINISHED,3 一律 DISQUALIFIED;
     * 2 = 既有「已结束」(主题 END / 预算耗尽 / 力竭也落 2),无 phase 时一律视为 ABANDONED;
     * 0 = 进行中,无 phase 时有计时事实即 RUNNING,否则 PREPARED。
     */
    public static OrientPhase derive(Integer status, String phase, boolean clockExists) {
        int s = status == null ? TopicRouteSession.STATUS_RUNNING : status;
        if (s == TopicRouteSession.STATUS_COMPLETED) return FINISHED;
        if (s == STATUS_DISQUALIFIED) return DISQUALIFIED;
        if (s == TopicRouteSession.STATUS_ENDED) return DISQUALIFIED.name().equals(phase) ? DISQUALIFIED : ABANDONED;
        if (RUNNING.name().equals(phase) || clockExists) return RUNNING;
        return PREPARED;
    }
}
