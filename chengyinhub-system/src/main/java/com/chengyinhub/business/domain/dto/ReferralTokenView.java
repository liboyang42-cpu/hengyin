package com.chengyinhub.business.domain.dto;

import java.util.Date;

/** 签发应答:明文 token 只在这一次返回,库里只留摘要。 */
public class ReferralTokenView {

    private String token;
    private Integer targetType;
    private Long targetId;
    private String campaignKey;
    private Date expiresAt;

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public Integer getTargetType() { return targetType; }
    public void setTargetType(Integer targetType) { this.targetType = targetType; }

    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }

    public String getCampaignKey() { return campaignKey; }
    public void setCampaignKey(String campaignKey) { this.campaignKey = campaignKey; }

    public Date getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Date expiresAt) { this.expiresAt = expiresAt; }
}
