package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;

/** 商品覆盖的单个作品；金额为按商品内分买价比例分摊后的渠道实付。 */
public class ChannelGrantTopic {

    private Long topicId;
    private String topicTitle;
    private Long contentRevision;
    private BigDecimal listAmount;
    private BigDecimal allocatedAmount;

    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }

    public String getTopicTitle() { return topicTitle; }
    public void setTopicTitle(String topicTitle) { this.topicTitle = topicTitle; }

    public Long getContentRevision() { return contentRevision; }
    public void setContentRevision(Long contentRevision) { this.contentRevision = contentRevision; }

    public BigDecimal getAllocatedAmount() { return allocatedAmount; }
    public void setAllocatedAmount(BigDecimal allocatedAmount) { this.allocatedAmount = allocatedAmount; }

    public BigDecimal getListAmount() { return listAmount; }
    public void setListAmount(BigDecimal listAmount) { this.listAmount = listAmount; }
}
