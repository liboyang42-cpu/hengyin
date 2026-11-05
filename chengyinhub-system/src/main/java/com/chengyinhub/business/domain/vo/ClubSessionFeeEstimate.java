package com.chengyinhub.business.domain.vo;

import java.math.BigDecimal;
import java.util.Date;

/** 第十二轮 k5:未结算俱乐部场次上按核销付费商家费的当前估算(只读,结算时以锁读为准)。 */
public class ClubSessionFeeEstimate {
    private Long activityId;
    private Long merchantMemberId;
    private Date endDate;
    private BigDecimal amount;

    public Long getActivityId() { return activityId; }
    public void setActivityId(Long value) { activityId = value; }
    public Long getMerchantMemberId() { return merchantMemberId; }
    public void setMerchantMemberId(Long value) { merchantMemberId = value; }
    public Date getEndDate() { return endDate; }
    public void setEndDate(Date value) { endDate = value; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal value) { amount = value; }
}
