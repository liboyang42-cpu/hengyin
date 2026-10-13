package com.chengyinhub.business.domain;

import java.util.Date;

public class MerchantClaimBatchCommand {
    private Long id;
    private Long actorId;
    private String requestId;
    private String requestFingerprint;
    private String action;
    private Date createTime;
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getActorId() { return actorId; }
    public void setActorId(Long actorId) { this.actorId = actorId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String requestFingerprint) { this.requestFingerprint = requestFingerprint; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
