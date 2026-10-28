package com.chengyinhub.business.domain.vo;

/** 玩家与真实报名商家的当前营销同意状态；未出现 OPT_IN 即 false。 */
public class MerchantCrmConsentMerchantVO {
    private Long merchantRowId;
    private Long merchantOwnerMemberId;
    private String merchantName;
    /** 商家 Logo(2026-10-28 营销消息页的分组头用) */
    private String merchantLogo;
    private Boolean inAppOptedIn;
    private Boolean couponOptedIn;

    public Long getMerchantRowId() { return merchantRowId; }
    public void setMerchantRowId(Long value) { merchantRowId = value; }
    public Long getMerchantOwnerMemberId() { return merchantOwnerMemberId; }
    public void setMerchantOwnerMemberId(Long value) { merchantOwnerMemberId = value; }
    public String getMerchantName() { return merchantName; }
    public void setMerchantName(String value) { merchantName = value; }
    public String getMerchantLogo() { return merchantLogo; }
    public void setMerchantLogo(String value) { merchantLogo = value; }
    public Boolean getInAppOptedIn() { return inAppOptedIn; }
    public void setInAppOptedIn(Boolean value) { inAppOptedIn = value; }
    public Boolean getCouponOptedIn() { return couponOptedIn; }
    public void setCouponOptedIn(Boolean value) { couponOptedIn = value; }
}
