package com.chengyinhub.business.domain.dto;

/** 签发推广来源凭据的请求。只描述"推广哪个目标",不含任何价格/佣金参数。 */
public class ReferralTokenIssueRequest {

    /** 1 主题,2 活动。 */
    private Integer targetType;
    private Long targetId;
    /** 推广活动标识,仅用于分组统计;默认 share。 */
    private String campaignKey;
    /** 有效期(秒)。默认 30 天,上限 90 天。 */
    private Integer ttlSeconds;

    public Integer getTargetType() { return targetType; }
    public void setTargetType(Integer targetType) { this.targetType = targetType; }

    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }

    public String getCampaignKey() { return campaignKey; }
    public void setCampaignKey(String campaignKey) { this.campaignKey = campaignKey; }

    public Integer getTtlSeconds() { return ttlSeconds; }
    public void setTtlSeconds(Integer ttlSeconds) { this.ttlSeconds = ttlSeconds; }
}
