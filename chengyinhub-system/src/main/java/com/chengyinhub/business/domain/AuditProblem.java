package com.chengyinhub.business.domain;

import java.util.Date;

/** O-05 #4:审核逐项驳回原因(audit_problem),一条问题一行,作者在审核时间线里只读查看。 */
public class AuditProblem {
    public static final String ITEM_TOPIC = "TOPIC";
    public static final String ITEM_CHAPTER = "CHAPTER";
    public static final String ITEM_NODE = "NODE";
    public static final String ITEM_TEMPLATE = "TEMPLATE";

    private Long id;
    private Long taskId;
    private Long recordId;
    private String bizType;
    private Long bizId;
    private Long ownerMemberId;
    private String itemType;
    private Long itemId;
    private String reasonCode;
    private String reasonText;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getRecordId() { return recordId; }
    public void setRecordId(Long recordId) { this.recordId = recordId; }
    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public Long getBizId() { return bizId; }
    public void setBizId(Long bizId) { this.bizId = bizId; }
    public Long getOwnerMemberId() { return ownerMemberId; }
    public void setOwnerMemberId(Long ownerMemberId) { this.ownerMemberId = ownerMemberId; }
    public String getItemType() { return itemType; }
    public void setItemType(String itemType) { this.itemType = itemType; }
    public Long getItemId() { return itemId; }
    public void setItemId(Long itemId) { this.itemId = itemId; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    public String getReasonText() { return reasonText; }
    public void setReasonText(String reasonText) { this.reasonText = reasonText; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
