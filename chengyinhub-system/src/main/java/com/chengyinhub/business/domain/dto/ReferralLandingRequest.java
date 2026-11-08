package com.chengyinhub.business.domain.dto;

/** 落地页只读解析请求：只带凭据明文，不带 inviterId/memberId 或任何价格/佣金参数。 */
public class ReferralLandingRequest {

    private String token;

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
}
