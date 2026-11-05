package com.chengyinhub.business.domain.vo;

import java.math.BigDecimal;
import java.util.Date;

/**
 * F-16:俱乐部场次的活动结算投影,只读真实入账流水(CmsActivityServiceImpl.settleActivity 写的
 * ums_member_balance_detail:收款人 event_type=3、平台 event_type=7,event_id=活动 id,均为收入)。
 * 销售额 = 收款人到账 + 平台服务费 + 合作商家核销费(第十二轮 k5,event_type=95)(H8 起设计者份额恒为 0)。
 */
public class ClubSessionSettlementRow {
    private Long activityId;
    private String activityName;
    private Date startDate;
    private BigDecimal netAmount;
    private BigDecimal platformAmount;
    /** 第十二轮 k5:本场付给合作商家的核销费(event_type=95),减去结算后退款冲回的部分;没有为 0。 */
    private BigDecimal merchantFeeAmount;
    /** 本场收款流水的收款人;多笔流水收款人不同时 min/max 不等。 */
    private Long payeeMinMemberId;
    private Long payeeMaxMemberId;
    private Date settledAt;

    public Long getActivityId() { return activityId; }
    public void setActivityId(Long value) { activityId = value; }
    public String getActivityName() { return activityName; }
    public void setActivityName(String value) { activityName = value; }
    public Date getStartDate() { return startDate; }
    public void setStartDate(Date value) { startDate = value; }
    public BigDecimal getNetAmount() { return netAmount; }
    public void setNetAmount(BigDecimal value) { netAmount = value; }
    public BigDecimal getPlatformAmount() { return platformAmount; }
    public void setPlatformAmount(BigDecimal value) { platformAmount = value; }
    public BigDecimal getMerchantFeeAmount() { return merchantFeeAmount; }
    public void setMerchantFeeAmount(BigDecimal value) { merchantFeeAmount = value; }
    public Long getPayeeMinMemberId() { return payeeMinMemberId; }
    public void setPayeeMinMemberId(Long value) { payeeMinMemberId = value; }
    public Long getPayeeMaxMemberId() { return payeeMaxMemberId; }
    public void setPayeeMaxMemberId(Long value) { payeeMaxMemberId = value; }
    public Date getSettledAt() { return settledAt; }
    public void setSettledAt(Date value) { settledAt = value; }
}
