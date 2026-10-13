package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * 微信支付/退款/转账回调 inbox(P1-5,表 wx_payment_callback_inbox)。
 * 字段设计照抄 {@link WxContentCallbackInbox}(内容审核回调)已验证的 fencing 范式。
 */
public class WxPaymentCallbackInbox {
    public static final int STATE_WAITING = 0;
    public static final int STATE_PROCESSING = 1;
    public static final int STATE_DONE = 2;
    public static final int STATE_RETRYABLE = 3;
    public static final int STATE_IGNORED_STALE = 4;

    public static final int BIZ_TYPE_PAYMENT = 1;
    public static final int BIZ_TYPE_REFUND = 2;
    public static final int BIZ_TYPE_TRANSFER = 3;

    private Long id;
    private String notifyHash;
    private Integer bizType;
    private Long bizId;
    private String eventType;
    private Integer state;
    private String ownerToken;
    private Long fenceToken;
    private Date leaseUntil;
    private Integer attemptCount;
    private String lastErrorCode;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getNotifyHash() { return notifyHash; }
    public void setNotifyHash(String notifyHash) { this.notifyHash = notifyHash; }
    public Integer getBizType() { return bizType; }
    public void setBizType(Integer bizType) { this.bizType = bizType; }
    public Long getBizId() { return bizId; }
    public void setBizId(Long bizId) { this.bizId = bizId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
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
    public String getLastErrorCode() { return lastErrorCode; }
    public void setLastErrorCode(String lastErrorCode) { this.lastErrorCode = lastErrorCode; }
}
