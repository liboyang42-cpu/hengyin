package com.chengyinhub.business.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Date;

/** Verified personal profit-share consent, independent of seller submerchant onboarding and club ownership. */
public class EcomPersonalReceiver {
    private Long memberId;
    private String appid;
    private String openid;
    private String state;
    private Date consentedAt;
    private Date verifiedAt;

    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    @JsonIgnore
    public String getAppid() { return appid; }
    public void setAppid(String appid) { this.appid = appid; }
    @JsonIgnore
    public String getOpenid() { return openid; }
    public void setOpenid(String openid) { this.openid = openid; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public Date getConsentedAt() { return consentedAt; }
    public void setConsentedAt(Date consentedAt) { this.consentedAt = consentedAt; }
    public Date getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(Date verifiedAt) { this.verifiedAt = verifiedAt; }
}
