package com.chengyinhub.business.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** 商家售后详情白名单；平台处理态与商家意见态明确拆开。 */
public class MerchantAftercareDetailVO {
    private Long refundId;
    private String refundNo;
    private String sourceType;
    private Long sourceId;
    private BigDecimal refundAmount;
    private String reason;
    private Integer refundStatus;
    private Integer payoutStatus;
    private String processing;
    private String merchantOpinion;
    private boolean canRespond;
    private List<String> allowedDecisions = new ArrayList<>();
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
    private String refundPolicyCode;
    private Integer refundPolicyVersion;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date refundDeadline;
    private List<MerchantAftercareResponseVO> responses = new ArrayList<>();
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
    public Integer getRefundStatus() { return refundStatus; }
    public void setRefundStatus(Integer refundStatus) { this.refundStatus = refundStatus; }
    public Integer getPayoutStatus() { return payoutStatus; }
    public void setPayoutStatus(Integer payoutStatus) { this.payoutStatus = payoutStatus; }
    public String getProcessing() { return processing; }
    public void setProcessing(String processing) { this.processing = processing; }
    public String getMerchantOpinion() { return merchantOpinion; }
    public void setMerchantOpinion(String merchantOpinion) { this.merchantOpinion = merchantOpinion; }
    public boolean isCanRespond() { return canRespond; }
    public void setCanRespond(boolean canRespond) { this.canRespond = canRespond; }
    public List<String> getAllowedDecisions() { return allowedDecisions; }
    public void setAllowedDecisions(List<String> allowedDecisions) { this.allowedDecisions = allowedDecisions; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public String getRefundPolicyCode() { return refundPolicyCode; }
    public void setRefundPolicyCode(String refundPolicyCode) { this.refundPolicyCode = refundPolicyCode; }
    public Integer getRefundPolicyVersion() { return refundPolicyVersion; }
    public void setRefundPolicyVersion(Integer refundPolicyVersion) { this.refundPolicyVersion = refundPolicyVersion; }
    public Date getRefundDeadline() { return refundDeadline; }
    public void setRefundDeadline(Date refundDeadline) { this.refundDeadline = refundDeadline; }
    public List<MerchantAftercareResponseVO> getResponses() { return responses; }
    public void setResponses(List<MerchantAftercareResponseVO> responses) { this.responses = responses; }
}
