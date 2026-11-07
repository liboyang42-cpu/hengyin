package com.chengyinhub.business.domain.vo;

import java.math.BigDecimal;
import java.util.Date;

/** 第十二轮 k5:商家在俱乐部场次结算时收到的核销费(一张报名一行),含之后退款冲回的部分。 */
public class MerchantSessionFeeLine {
    private Long detailId;
    private Long registrationId;
    private Long activityId;
    private Long topicId;
    private String activityTitle;
    private String ticketName;
    private Date occurredAt;
    private BigDecimal feeAmount;
    /** 合作单冻结单价(¥/人)。 */
    private BigDecimal unitFee;
    /** 退款冲回(负数,0 = 没有退款)。 */
    private BigDecimal clawbackAmount;
    private Integer status;

    public Long getDetailId() { return detailId; }
    public void setDetailId(Long value) { detailId = value; }
    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long value) { registrationId = value; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long value) { activityId = value; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long value) { topicId = value; }
    public String getActivityTitle() { return activityTitle; }
    public void setActivityTitle(String value) { activityTitle = value; }
    public String getTicketName() { return ticketName; }
    public void setTicketName(String value) { ticketName = value; }
    public Date getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Date value) { occurredAt = value; }
    public BigDecimal getFeeAmount() { return feeAmount; }
    public void setFeeAmount(BigDecimal value) { feeAmount = value; }
    public BigDecimal getUnitFee() { return unitFee; }
    public void setUnitFee(BigDecimal value) { unitFee = value; }
    public BigDecimal getClawbackAmount() { return clawbackAmount; }
    public void setClawbackAmount(BigDecimal value) { clawbackAmount = value; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer value) { status = value; }
}
