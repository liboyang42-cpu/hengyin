package com.chengyinhub.business.domain.dto;

/** 礼品领取请求：token 明文只由买家转交，服务端只按哈希匹配。 */
public class GiftClaimRequest {

    private String token;
    private String requestId;

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
