package com.chengyinhub.business.domain.dto;

/** 后台管理员为 sys_user 显式授予或撤销官方会员代理身份时的请求。 */
public class OfficialEventV2OperatorAuthorizationRequest {
    private Long sysUserId;
    private Long memberId;
    private String reason;

    public Long getSysUserId() { return sysUserId; }
    public void setSysUserId(Long sysUserId) { this.sysUserId = sysUserId; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
