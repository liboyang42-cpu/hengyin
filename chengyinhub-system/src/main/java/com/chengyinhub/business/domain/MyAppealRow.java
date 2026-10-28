package com.chengyinhub.business.domain;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.Date;

/** 我的申诉一行(文档二 C1):计分申诉 appeal 或成绩争议 dispute。 */
public class MyAppealRow {

    private String kind;
    private Long id;
    /** 0 处理中 / 1 已采纳 / 2 已驳回(orient_score_appeal / orient_result_dispute 同口径)。 */
    private Integer status;
    private Long sessionId;
    private Long topicId;
    private String topicName;
    private Long nodeId;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date createTime;
    private String resolutionNote;

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public String getTopicName() { return topicName; }
    public void setTopicName(String topicName) { this.topicName = topicName; }
    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public String getResolutionNote() { return resolutionNote; }
    public void setResolutionNote(String resolutionNote) { this.resolutionNote = resolutionNote; }
}
