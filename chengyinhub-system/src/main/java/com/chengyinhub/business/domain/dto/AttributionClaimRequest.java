package com.chengyinhub.business.domain.dto;

/** 下单来源归因领取请求。orderType 当前只支持 1(报名单)。 */
public class AttributionClaimRequest {

    private Integer orderType;
    private Long orderId;
    private String token;

    public Integer getOrderType() { return orderType; }
    public void setOrderType(Integer orderType) { this.orderType = orderType; }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
}
