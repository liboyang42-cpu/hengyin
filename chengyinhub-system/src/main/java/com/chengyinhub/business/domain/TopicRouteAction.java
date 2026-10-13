package com.chengyinhub.business.domain;

import java.util.Date;

/** 路线推进幂等回执。 */
public class TopicRouteAction {
    private Long id;
    private Long sessionId;
    private Long memberId;
    private Long nodeId;
    private String actionId;
    private String receiptId;
    private String outcomeCode;
    private String responseJson;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
    public String getActionId() { return actionId; }
    public void setActionId(String actionId) { this.actionId = actionId; }
    public String getReceiptId() { return receiptId; }
    public void setReceiptId(String receiptId) { this.receiptId = receiptId; }
    public String getOutcomeCode() { return outcomeCode; }
    public void setOutcomeCode(String outcomeCode) { this.outcomeCode = outcomeCode; }
    public String getResponseJson() { return responseJson; }
    public void setResponseJson(String responseJson) { this.responseJson = responseJson; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
