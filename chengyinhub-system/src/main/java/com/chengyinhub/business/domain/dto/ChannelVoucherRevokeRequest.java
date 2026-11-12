package com.chengyinhub.business.domain.dto;

/** 撤销来源必须显式：运营手工 / 渠道回调 / 过期收敛，不允许空原因。 */
public class ChannelVoucherRevokeRequest {

    private String reason;
    private String source;
    private String requestId;

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
