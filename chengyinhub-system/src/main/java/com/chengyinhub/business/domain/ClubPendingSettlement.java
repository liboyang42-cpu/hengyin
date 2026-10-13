package com.chengyinhub.business.domain;

import java.math.BigDecimal;

/** 解散预检使用的未打款结算引用。 */
public class ClubPendingSettlement
{
    private Long id;
    private Long topicId;
    private BigDecimal amount;
    /** incoming=待收进来，outgoing=待付出去，unknown=归属待核。 */
    private String direction;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
}
