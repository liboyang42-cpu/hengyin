package com.chengyinhub.business.domain.dto;

/** 购票人撤销/释放：invitationId 必须是本人原报名单下的邀请。 */
public class SeatInvitationActionRequest {

    private Long invitationId;
    private String requestId;

    public Long getInvitationId() { return invitationId; }
    public void setInvitationId(Long value) { invitationId = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
}
