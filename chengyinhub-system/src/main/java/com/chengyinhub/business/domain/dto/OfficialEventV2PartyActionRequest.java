package com.chengyinhub.business.domain.dto;

/** 商家/俱乐部针对自己的活动邀约执行的受控动作。 */
public class OfficialEventV2PartyActionRequest {
    private String reason;

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
