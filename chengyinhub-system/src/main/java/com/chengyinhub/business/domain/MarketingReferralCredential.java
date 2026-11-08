package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * 推广来源凭据(服务端签发)。表 marketing_referral_credential。
 *
 * <p>只承载「谁 + 推广哪个目标 + 到什么时候有效」,<b>没有</b>金额、折扣、费率、佣金字段 ——
 * 归因不能授钱,这条设计上用表结构保证,而不是靠调用方自律。
 *
 * <p>明文 token 只在签发应答里出现一次;库里只留 {@link #tokenHash}。撤销由 {@link #status}
 * 表达(本条不提供运营撤销接口:新增权限点属 RBAC 变更,需另行批准;过期由 expiresAt 兜底)。
 */
public class MarketingReferralCredential {

    /** 目标类型:1 主题,2 活动。仅用于与报名单 owner_type 对齐。 */
    public static final int TARGET_TOPIC = 1;
    public static final int TARGET_ACTIVITY = 2;

    public static final int STATUS_ACTIVE = 0;
    public static final int STATUS_REVOKED = 1;

    private Long id;
    /** 公开标识(HTTP 可见,非秘密);与签名共同确定凭据身份。 */
    private String publicId;
    private Long sharerMemberId;
    private String campaignKey;
    private Integer targetType;
    private Long targetId;
    private Date issuedAt;
    private Date expiresAt;
    private Integer status;
    /** SHA-256(明文 token) 的十六进制。 */
    private String tokenHash;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPublicId() { return publicId; }
    public void setPublicId(String publicId) { this.publicId = publicId; }

    public Long getSharerMemberId() { return sharerMemberId; }
    public void setSharerMemberId(Long sharerMemberId) { this.sharerMemberId = sharerMemberId; }

    public String getCampaignKey() { return campaignKey; }
    public void setCampaignKey(String campaignKey) { this.campaignKey = campaignKey; }

    public Integer getTargetType() { return targetType; }
    public void setTargetType(Integer targetType) { this.targetType = targetType; }

    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }

    public Date getIssuedAt() { return issuedAt; }
    public void setIssuedAt(Date issuedAt) { this.issuedAt = issuedAt; }

    public Date getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Date expiresAt) { this.expiresAt = expiresAt; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
