package com.chengyinhub.business.domain.enums;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 席位邀请 cms_registration_seat_invitation.status 唯一值域与迁移表。
 * 只描述邀请本身，不涉及报名支付/退款；Mapper CAS（where status = from）仍是并发真源，本表是其前置守卫。
 */
public enum SeatInvitationState {
    PENDING(0, "待领取"),
    CLAIMED(1, "已领取"),
    CANCELLED(2, "已撤销"),
    EXPIRED(3, "已过期"),
    RELEASED(4, "已释放");

    private final int code;
    private final String label;

    SeatInvitationState(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() { return code; }
    public String getLabel() { return label; }

    /** PENDING → CLAIMED（领取 CAS）/ CANCELLED（购票人撤销）/ EXPIRED（服务端过期）；CLAIMED → RELEASED。其余为终态。 */
    public Set<SeatInvitationState> targets() {
        switch (this) {
            case PENDING: return Collections.unmodifiableSet(EnumSet.of(CLAIMED, CANCELLED, EXPIRED));
            case CLAIMED: return Collections.unmodifiableSet(EnumSet.of(RELEASED));
            default: return Collections.emptySet();
        }
    }

    public boolean isTerminal() {
        return targets().isEmpty();
    }

    public static SeatInvitationState fromCodeOrNull(Integer code) {
        if (code == null) return null;
        for (SeatInvitationState state : values()) {
            if (state.code == code) return state;
        }
        return null;
    }

    /** 未登记值 fail-closed。 */
    public static boolean canTransition(Integer from, Integer to) {
        SeatInvitationState source = fromCodeOrNull(from);
        SeatInvitationState target = fromCodeOrNull(to);
        return source != null && target != null && source.targets().contains(target);
    }

    /** 守卫：代码里不该出现的迁移直接拒绝（编程错误，不是用户错误）。 */
    public static void requireTransition(int from, int to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException("席位邀请非法状态迁移:" + from + "->" + to);
        }
    }
}
