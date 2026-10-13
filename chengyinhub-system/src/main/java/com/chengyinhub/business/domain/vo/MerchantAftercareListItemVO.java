package com.chengyinhub.business.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.util.Date;

/** 商家售后列表白名单；不下发商家主体、打款凭证及申请人手机号/openid 等敏感字段(申请人仅昵称,受 CRM 读权限约束)。 */
public class MerchantAftercareListItemVO {
    private Long refundId;
    private String refundNo;
    private String sourceType;
    private Long sourceId;
    private BigDecimal refundAmount;
    private String reason;
    private String bucket;
    private String processing;
    private String merchantOpinion;
    private boolean canRespond;
    private boolean refunded;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
    /** 报名对应的活动/主题名,取不到为 null。 */
    private String activityTitle;
    /** 申请人昵称;仅当前岗位有 CRM 读权限时下发,否则 null。不下发手机号/openid。 */
    private String customerNickname;
    /** 商家 24h 未表态、已转平台客服的时间;未转为 null。 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date platformTakeoverAt;

    public String getActivityTitle() { return activityTitle; }
    public void setActivityTitle(String activityTitle) { this.activityTitle = activityTitle; }
    public String getCustomerNickname() { return customerNickname; }
    public void setCustomerNickname(String customerNickname) { this.customerNickname = customerNickname; }
    public Date getPlatformTakeoverAt() { return platformTakeoverAt; }
    public void setPlatformTakeoverAt(Date platformTakeoverAt) { this.platformTakeoverAt = platformTakeoverAt; }
    public Long getRefundId() { return refundId; }
    public void setRefundId(Long refundId) { this.refundId = refundId; }
    public String getRefundNo() { return refundNo; }
    public void setRefundNo(String refundNo) { this.refundNo = refundNo; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public BigDecimal getRefundAmount() { return refundAmount; }
    public void setRefundAmount(BigDecimal refundAmount) { this.refundAmount = refundAmount; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public String getProcessing() { return processing; }
    public void setProcessing(String processing) { this.processing = processing; }
    public String getMerchantOpinion() { return merchantOpinion; }
    public void setMerchantOpinion(String merchantOpinion) { this.merchantOpinion = merchantOpinion; }
    public boolean isCanRespond() { return canRespond; }
    public void setCanRespond(boolean canRespond) { this.canRespond = canRespond; }
    public boolean isRefunded() { return refunded; }
    public void setRefunded(boolean refunded) { this.refunded = refunded; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
