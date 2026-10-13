package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

/** 局内广播逐成员投递回读。 */
public class GameBroadcastDelivery extends BaseEntity {
    private static final long serialVersionUID = 1L;
    private Long id;
    private Long broadcastId;
    private Long memberId;
    private String status;
    private Long messageId;
    private String errorCode;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getBroadcastId() { return broadcastId; }
    public void setBroadcastId(Long broadcastId) { this.broadcastId = broadcastId; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getMessageId() { return messageId; }
    public void setMessageId(Long messageId) { this.messageId = messageId; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
}
