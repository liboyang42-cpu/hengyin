package com.chengyinhub.business.domain.dto;

/** 受让人领取：token 来自购票人分享；recipient 身份只来自登录态。 */
public class SeatInvitationClaimRequest {

    private String token;
    private String requestId;

    public String getToken() { return token; }
    public void setToken(String value) { token = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
}
