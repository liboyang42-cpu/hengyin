package com.chengyinhub.business.domain.dto;

/** 平台后台撤销核销命令;原因是必填留痕,操作者不在请求体里(由已鉴权 controller 从登录态取)。 */
public class RedemptionReverseRequest {
    private Long redemptionId;
    private String reason;
    /** 第二轮拍板第 4 条:勾选「同时冲回因这次核销发的积分和经验」。缺省不冲回。 */
    private Boolean clawbackRewards;

    public Long getRedemptionId() { return redemptionId; }
    public void setRedemptionId(Long redemptionId) { this.redemptionId = redemptionId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Boolean getClawbackRewards() { return clawbackRewards; }
    public void setClawbackRewards(Boolean clawbackRewards) { this.clawbackRewards = clawbackRewards; }
}
