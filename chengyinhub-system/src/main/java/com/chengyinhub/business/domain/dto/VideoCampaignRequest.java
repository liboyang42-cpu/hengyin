package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;

/**
 * 视频悬赏活动创建/更新请求。金额与窗口是<b>可配置参考值</b>,不代表已批准的线上费率。
 *
 * @author chengyinhub
 */
public class VideoCampaignRequest
{
    private String campaignKey;
    private String title;
    private String description;
    /** 活动归属的创作者会员 id;成交归因只按它聚合。null = 未指定(归因读面显示未配置)。 */
    private Long ownerMemberId;
    private String currency;
    private BigDecimal ratePerThousand;
    private BigDecimal perVideoCap;
    private Integer windowDays;
    private BigDecimal totalBudgetCap;
    /** 0=现金兑付未启用(默认);1=仅标记配置,仍需独立兑付集成。 */
    private Integer cashPayoutEnabled;
    private String ruleVersion;
    private String configJson;

    public String getCampaignKey() { return campaignKey; }
    public void setCampaignKey(String campaignKey) { this.campaignKey = campaignKey; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Long getOwnerMemberId() { return ownerMemberId; }
    public void setOwnerMemberId(Long ownerMemberId) { this.ownerMemberId = ownerMemberId; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public BigDecimal getRatePerThousand() { return ratePerThousand; }
    public void setRatePerThousand(BigDecimal ratePerThousand) { this.ratePerThousand = ratePerThousand; }
    public BigDecimal getPerVideoCap() { return perVideoCap; }
    public void setPerVideoCap(BigDecimal perVideoCap) { this.perVideoCap = perVideoCap; }
    public Integer getWindowDays() { return windowDays; }
    public void setWindowDays(Integer windowDays) { this.windowDays = windowDays; }
    public BigDecimal getTotalBudgetCap() { return totalBudgetCap; }
    public void setTotalBudgetCap(BigDecimal totalBudgetCap) { this.totalBudgetCap = totalBudgetCap; }
    public Integer getCashPayoutEnabled() { return cashPayoutEnabled; }
    public void setCashPayoutEnabled(Integer cashPayoutEnabled) { this.cashPayoutEnabled = cashPayoutEnabled; }
    public String getRuleVersion() { return ruleVersion; }
    public void setRuleVersion(String ruleVersion) { this.ruleVersion = ruleVersion; }
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }
}
