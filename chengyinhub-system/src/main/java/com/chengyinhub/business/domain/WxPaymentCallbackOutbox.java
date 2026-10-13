package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * 出站重试队列(P1-5,表 wx_payment_callback_outbox)——我方主动发起的终态查询/补发重试。
 * 任务书 1.6 节铁律:自动重试上限默认3次,超过转 MANUAL_REQUIRED;
 * moneyAffecting=true 的动作(如二次转账)禁止自动重试,只能人工触发。
 */
public class WxPaymentCallbackOutbox {
    public static final int STATE_PENDING = 0;
    public static final int STATE_DISPATCHING = 1;
    public static final int STATE_DONE = 2;
    public static final int STATE_RETRYABLE = 3;
    public static final int STATE_MANUAL_REQUIRED = 4;

    public static final int BIZ_TYPE_PAYMENT_QUERY = 1;
    public static final int BIZ_TYPE_REFUND_QUERY = 2;
    public static final int BIZ_TYPE_TRANSFER_QUERY = 3;

    private Long id;
    private Integer bizType;
    private Long bizId;
    private String idempotencyKey;
    private Boolean moneyAffecting;
    private Integer state;
    private String ownerToken;
    private Long fenceToken;
    private Date leaseUntil;
    private Integer attemptCount;
    private Integer maxAttempts;
    private Date nextAttemptAt;
    private String lastErrorCode;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getBizType() { return bizType; }
    public void setBizType(Integer bizType) { this.bizType = bizType; }
    public Long getBizId() { return bizId; }
    public void setBizId(Long bizId) { this.bizId = bizId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public Boolean getMoneyAffecting() { return moneyAffecting; }
    public void setMoneyAffecting(Boolean moneyAffecting) { this.moneyAffecting = moneyAffecting; }
    public Integer getState() { return state; }
    public void setState(Integer state) { this.state = state; }
    public String getOwnerToken() { return ownerToken; }
    public void setOwnerToken(String ownerToken) { this.ownerToken = ownerToken; }
    public Long getFenceToken() { return fenceToken; }
    public void setFenceToken(Long fenceToken) { this.fenceToken = fenceToken; }
    public Date getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(Date leaseUntil) { this.leaseUntil = leaseUntil; }
    public Integer getAttemptCount() { return attemptCount; }
    public void setAttemptCount(Integer attemptCount) { this.attemptCount = attemptCount; }
    public Integer getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(Integer maxAttempts) { this.maxAttempts = maxAttempts; }
    public Date getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Date nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public String getLastErrorCode() { return lastErrorCode; }
    public void setLastErrorCode(String lastErrorCode) { this.lastErrorCode = lastErrorCode; }
}
