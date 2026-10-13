package com.chengyinhub.business.domain.dto;

public class MerchantOperatorAcceptRequest {
    private String token;
    private String requestId;
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
