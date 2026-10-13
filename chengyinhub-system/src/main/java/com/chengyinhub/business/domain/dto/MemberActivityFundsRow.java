package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;
import java.util.Date;

/** 余额三段的活动侧原始数:某收款方一个活动下尚未入余额的实付,按是否涉诉拆开。 */
public class MemberActivityFundsRow {
    private Long activityId;
    private Long topicId;
    private Date endDate;
    private BigDecimal normalSales;
    private BigDecimal disputedSales;
    /** 仍可退(未核销)票里最晚的退款截止;held 行不取。 */
    private Date lastRefundDeadline;

    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public Date getEndDate() { return endDate; }
    public void setEndDate(Date endDate) { this.endDate = endDate; }
    public BigDecimal getNormalSales() { return normalSales; }
    public void setNormalSales(BigDecimal normalSales) { this.normalSales = normalSales; }
    public BigDecimal getDisputedSales() { return disputedSales; }
    public void setDisputedSales(BigDecimal disputedSales) { this.disputedSales = disputedSales; }
    public Date getLastRefundDeadline() { return lastRefundDeadline; }
    public void setLastRefundDeadline(Date lastRefundDeadline) { this.lastRefundDeadline = lastRefundDeadline; }
}
