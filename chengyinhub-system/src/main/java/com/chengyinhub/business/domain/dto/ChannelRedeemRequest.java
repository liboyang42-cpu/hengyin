package com.chengyinhub.business.domain.dto;

/** 玩家兑换请求：code 只用于本次校验，服务端不落明文。 */
public class ChannelRedeemRequest {

    private String code;
    private String requestId;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
