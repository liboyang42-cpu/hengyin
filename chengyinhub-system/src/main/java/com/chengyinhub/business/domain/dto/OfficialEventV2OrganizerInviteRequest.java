package com.chengyinhub.business.domain.dto;

/** 当前有效主办邀请另一位白名单会员作为候任主办。 */
public class OfficialEventV2OrganizerInviteRequest {
    private Long memberId;
    private String reason;

    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
