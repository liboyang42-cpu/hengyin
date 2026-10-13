package com.chengyinhub.business.domain;

import java.math.BigDecimal;

/** 解散预检使用的未了结合作保证金引用。 */
public class ClubPendingDeposit
{
    private Long id;
    private Long topicId;
    private Integer depositStatus;
    private BigDecimal amount;
    /** 现有退款重试接口能读取该邀约，且合作已取消或主题已结算。 */
    private boolean retryable;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public Integer getDepositStatus() { return depositStatus; }
    public void setDepositStatus(Integer depositStatus) { this.depositStatus = depositStatus; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public boolean isRetryable() { return retryable; }
    public void setRetryable(boolean retryable) { this.retryable = retryable; }
}
