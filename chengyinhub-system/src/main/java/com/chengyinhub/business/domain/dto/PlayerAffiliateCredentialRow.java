package com.chengyinhub.business.domain.dto;

import java.util.Date;

/**
 * 玩家自己名下的推广凭据元数据只读行。
 *
 * <p><b>不含 token 明文与 token_hash</b>：明文只在签发应答出现一次，库里只留摘要；
 * 这个投影只用来告诉玩家"我推广过什么、还在不在有效期"，不是可再用的分享链接。
 */
public class PlayerAffiliateCredentialRow {

    private String campaignKey;
    private Integer targetType;
    private Long targetId;
    /** 目标标题来自 cms_topic / cms_activity 的真实名称；目标被删时为空。 */
    private String targetTitle;
    private Integer status;
    private Date issuedAt;
    private Date expiresAt;

    public String getCampaignKey() { return campaignKey; }
    public void setCampaignKey(String campaignKey) { this.campaignKey = campaignKey; }

    public Integer getTargetType() { return targetType; }
    public void setTargetType(Integer targetType) { this.targetType = targetType; }

    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }

    public String getTargetTitle() { return targetTitle; }
    public void setTargetTitle(String targetTitle) { this.targetTitle = targetTitle; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public Date getIssuedAt() { return issuedAt; }
    public void setIssuedAt(Date issuedAt) { this.issuedAt = issuedAt; }

    public Date getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Date expiresAt) { this.expiresAt = expiresAt; }
}
