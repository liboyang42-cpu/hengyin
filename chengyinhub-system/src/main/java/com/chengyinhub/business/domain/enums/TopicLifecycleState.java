package com.chengyinhub.business.domain.enums;

/** 主题生命周期唯一值域。 */
public enum TopicLifecycleState {
    DRAFT(0, "草稿"),
    RECRUITING(1, "招募中"),
    PRICING(2, "待定价"),
    SELLING(3, "售票中"),
    RUNNING(4, "进行中"),
    SETTLING(5, "结算中"),
    CLOSED(6, "已关闭"),
    /** 商业主题立项审核中的必经态；追加为 7，保留存量 0-6 ordinal 契约。 */
    PENDING_REVIEW(7, "立项待审");

    private final int code;
    private final String label;

    TopicLifecycleState(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() { return code; }
    public String getLabel() { return label; }

    /** 显式列举已开售及其后继态；不能用数值范围，否则追加态/未知值会被误放行。 */
    public boolean isSaleStarted() {
        return this == SELLING || this == RUNNING || this == SETTLING || this == CLOSED;
    }

    /** 直接售卖的唯一具名放行态；履约中和结算态不得继续建单。 */
    public boolean isDirectSaleOpen() {
        return this == SELLING;
    }

    /** 终价确认进入 PRICING 后商业成本与售卖条款即冻结，审核等待期不重新打开写窗。 */
    public boolean isCommercialTermsFrozen() {
        return this == PRICING || isSaleStarted();
    }

    /** 开跑前的精确状态集合；新增状态必须在生命周期真源显式裁决。 */
    public boolean isRunNotReady() {
        return this == DRAFT || this == RECRUITING || this == PRICING || this == PENDING_REVIEW;
    }

    /** 已进入结算或关闭的精确终态集合。 */
    public boolean isRunTerminal() {
        return this == SETTLING || this == CLOSED;
    }

    /** 写闸对 NULL/未知 fail-closed。 */
    public static boolean commercialTermsFrozen(Integer code) {
        try {
            return fromCode(code).isCommercialTermsFrozen();
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** 持久化值只接受显式登记状态；未知值不得按 ordinal/range 猜测。 */
    public static TopicLifecycleState fromCode(Integer code) {
        if (code != null) {
            for (TopicLifecycleState state : values()) {
                if (state.code == code) return state;
            }
        }
        throw new IllegalArgumentException("未知主题生命周期状态:" + code);
    }
}
