package com.chengyinhub.business.domain.dto;

/** 玩家显式管理单一商家、单一渠道同意；merchantOwnerMemberId 仅是授权对象，不是商家操作作用域。 */
public class MerchantCrmMarketingConsentRequest {
    private Long merchantRowId;
    private Long merchantOwnerMemberId;
    private String channel;
    private Boolean optedIn;
    private String requestId;

    public Long getMerchantRowId() { return merchantRowId; }
    public void setMerchantRowId(Long value) { merchantRowId = value; }
    public Long getMerchantOwnerMemberId() { return merchantOwnerMemberId; }
    public void setMerchantOwnerMemberId(Long value) { merchantOwnerMemberId = value; }
    public String getChannel() { return channel; }
    public void setChannel(String value) { channel = value; }
    public Boolean getOptedIn() { return optedIn; }
    public void setOptedIn(Boolean value) { optedIn = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
}
