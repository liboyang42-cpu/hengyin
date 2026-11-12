package com.chengyinhub.business.domain.dto;

import java.util.Date;

/** 归因结果。replayed=true 表示该单此前已由同一凭据归因,本次未新增写入。 */
public class AttributionClaimResult {

    private Integer orderType;
    private Long orderId;
    private String campaignKey;
    private Long sharerMemberId;
    private Date attributedAt;
    private boolean replayed;

    public Integer getOrderType() { return orderType; }
    public void setOrderType(Integer orderType) { this.orderType = orderType; }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public String getCampaignKey() { return campaignKey; }
    public void setCampaignKey(String campaignKey) { this.campaignKey = campaignKey; }

    public Long getSharerMemberId() { return sharerMemberId; }
    public void setSharerMemberId(Long sharerMemberId) { this.sharerMemberId = sharerMemberId; }

    public Date getAttributedAt() { return attributedAt; }
    public void setAttributedAt(Date attributedAt) { this.attributedAt = attributedAt; }

    public boolean isReplayed() { return replayed; }
    public void setReplayed(boolean replayed) { this.replayed = replayed; }
}
