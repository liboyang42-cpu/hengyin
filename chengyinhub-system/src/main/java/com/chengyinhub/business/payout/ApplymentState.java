package com.chengyinhub.business.payout;

import java.util.Locale;

/** 二级商户进件状态：微信 applyment_state 原值 + 本地 DRAFT（已登记、尚未提交）。 */
public enum ApplymentState {
    DRAFT, CHECKING, ACCOUNT_NEED_VERIFY, AUDITING, REJECTED, NEED_SIGN, FINISH, FROZEN, CANCELED;

    /** 微信返回值转本地状态；未知值返回 null，调用方不得覆盖已存状态。 */
    public static ApplymentState fromWechat(String value) {
        if (value == null) return null;
        String v = value.trim().toUpperCase(Locale.ROOT);
        if (v.startsWith("APPLYMENT_STATE_")) v = v.substring("APPLYMENT_STATE_".length());
        if ("DRAFT".equals(v)) return null; // 本地状态，不接受微信回传
        for (ApplymentState s : values()) if (s.name().equals(v)) return s;
        return null;
    }

    /**
     * 查询结果能否覆盖当前状态：CANCELED 是终态；FINISH 只能变为 FROZEN（微信冻结二级商户）；
     * FROZEN 只能回到 FINISH。其余过程态按微信最新值覆盖。
     */
    public boolean canMoveTo(ApplymentState next) {
        if (next == null || next == this) return false;
        if (this == CANCELED) return false;
        if (this == FINISH) return next == FROZEN;
        if (this == FROZEN) return next == FINISH;
        return true;
    }
}
