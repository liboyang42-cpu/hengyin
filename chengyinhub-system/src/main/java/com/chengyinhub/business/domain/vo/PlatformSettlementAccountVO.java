package com.chengyinhub.business.domain.vo;

/** 活动平台分润收款账户的最小配置投影，不暴露会员个人资料。 */
public class PlatformSettlementAccountVO {
    private Long memberId;
    private boolean configured;
    private boolean usable;

    public Long getMemberId() {
        return memberId;
    }

    public void setMemberId(Long memberId) {
        this.memberId = memberId;
    }

    public boolean isConfigured() {
        return configured;
    }

    public void setConfigured(boolean configured) {
        this.configured = configured;
    }

    public boolean isUsable() {
        return usable;
    }

    public void setUsable(boolean usable) {
        this.usable = usable;
    }
}
