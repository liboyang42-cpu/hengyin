package com.chengyinhub.business.domain;

import java.util.Date;

/** 与优惠券变更同事务提交的命令终态。 */
public class CouponCommandReceipt {
    private Long id;
    private Long actorMemberId;
    private Integer scopeType;
    private Long merchantId;
    private Long ownerMemberId;
    private String requestId;
    private String operation;
    private Integer payloadVersion;
    private byte[] payloadSha256;
    private String outcome;
    private String reasonCode;
    private Integer resultCode;
    private String resultMessage;
    private Long couponId;
    private Integer couponStatusAtExecution;
    private Date completedAt;
    public Long getId() { return id; }
    public void setId(Long value) { this.id = value; }
    public Long getActorMemberId() { return actorMemberId; }
    public void setActorMemberId(Long value) { this.actorMemberId = value; }
    public Integer getScopeType() { return scopeType; }
    public void setScopeType(Integer value) { this.scopeType = value; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long value) { this.merchantId = value; }
    public Long getOwnerMemberId() { return ownerMemberId; }
    public void setOwnerMemberId(Long value) { this.ownerMemberId = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { this.requestId = value; }
    public String getOperation() { return operation; }
    public void setOperation(String value) { this.operation = value; }
    public Integer getPayloadVersion() { return payloadVersion; }
    public void setPayloadVersion(Integer value) { this.payloadVersion = value; }
    public byte[] getPayloadSha256() { return payloadSha256; }
    public void setPayloadSha256(byte[] value) { this.payloadSha256 = value; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String value) { this.outcome = value; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String value) { this.reasonCode = value; }
    public Integer getResultCode() { return resultCode; }
    public void setResultCode(Integer value) { this.resultCode = value; }
    public String getResultMessage() { return resultMessage; }
    public void setResultMessage(String value) { this.resultMessage = value; }
    public Long getCouponId() { return couponId; }
    public void setCouponId(Long value) { this.couponId = value; }
    public Integer getCouponStatusAtExecution() { return couponStatusAtExecution; }
    public void setCouponStatusAtExecution(Integer value) { this.couponStatusAtExecution = value; }
    public Date getCompletedAt() { return completedAt; }
    public void setCompletedAt(Date value) { this.completedAt = value; }
}
