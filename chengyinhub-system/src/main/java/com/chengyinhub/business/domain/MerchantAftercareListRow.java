package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/** 商家售后列表内部投影；归属字段只参与 SQL 过滤，不向小程序输出。 */
public class MerchantAftercareListRow {
    private Long id;
    private String refundNo;
    private String sourceType;
    private Long sourceId;
    private BigDecimal refundAmount;
    private Integer status;
    private String reason;
    private Integer payoutStatus;
    private String merchantOpinion;
    private Date createTime;
    private String activityTitle;
    private String customerNickname;
    private Date platformTakeoverAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRefundNo() { return refundNo; }
    public void setRefundNo(String refundNo) { this.refundNo = refundNo; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public BigDecimal getRefundAmount() { return refundAmount; }
    public void setRefundAmount(BigDecimal refundAmount) { this.refundAmount = refundAmount; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Integer getPayoutStatus() { return payoutStatus; }
    public void setPayoutStatus(Integer payoutStatus) { this.payoutStatus = payoutStatus; }
    public String getMerchantOpinion() { return merchantOpinion; }
    public void setMerchantOpinion(String merchantOpinion) { this.merchantOpinion = merchantOpinion; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public String getActivityTitle() { return activityTitle; }
    public void setActivityTitle(String activityTitle) { this.activityTitle = activityTitle; }
    public String getCustomerNickname() { return customerNickname; }
    public void setCustomerNickname(String customerNickname) { this.customerNickname = customerNickname; }
    public Date getPlatformTakeoverAt() { return platformTakeoverAt; }
    public void setPlatformTakeoverAt(Date platformTakeoverAt) { this.platformTakeoverAt = platformTakeoverAt; }
}
