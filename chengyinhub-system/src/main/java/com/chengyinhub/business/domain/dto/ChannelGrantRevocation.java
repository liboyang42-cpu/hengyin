package com.chengyinhub.business.domain.dto;

import java.util.List;

/** 撤销权威注册事实的请求；用于渠道取消/退款验证后的原单回查与席位回收。 */
public class ChannelGrantRevocation {

    private Long primaryRegistrationId;
    private List<Long> registrationIds;
    private String channelOrderNo;
    private String reason;
    private String requestId;

    public Long getPrimaryRegistrationId() { return primaryRegistrationId; }
    public void setPrimaryRegistrationId(Long primaryRegistrationId) { this.primaryRegistrationId = primaryRegistrationId; }

    public List<Long> getRegistrationIds() { return registrationIds; }
    public void setRegistrationIds(List<Long> registrationIds) { this.registrationIds = registrationIds; }

    public String getChannelOrderNo() { return channelOrderNo; }
    public void setChannelOrderNo(String channelOrderNo) { this.channelOrderNo = channelOrderNo; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
