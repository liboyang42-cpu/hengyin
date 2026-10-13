package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.CommunityReportCase;

import java.util.Date;

/** 通用审核列表的去标识投影；不因有 audit:list 就扩大到举报人 PII、正文或证据。 */
public class CommunityReportPlatformVO {
    private Long id;
    private String targetType;
    private Long targetId;
    private String reasonCode;
    private String anonymityPolicy;
    private String stage;
    private Date estimatedResolveAt;
    private String decisionSummary;
    private Integer version;
    private Date createTime;
    private String policyVersion;
    private Boolean policyMatched;
    private String reasonLabel;
    private String priority;
    private Integer firstResponseMinutes;
    private Date firstResponseDueAt;
    private Date firstResponseAt;
    private Boolean firstResponseOverdue;

    public static CommunityReportPlatformVO from(CommunityReportCase row) {
        CommunityReportPlatformVO vo = new CommunityReportPlatformVO();
        vo.id = row.getId();
        vo.targetType = row.getTargetType();
        vo.targetId = row.getTargetId();
        vo.reasonCode = row.getReasonCode();
        vo.policyVersion = row.getPolicyVersion();
        vo.reasonLabel = row.getReasonLabelSnapshot();
        vo.priority = row.getPrioritySnapshot();
        vo.firstResponseMinutes = row.getFirstResponseMinutesSnapshot();
        vo.anonymityPolicy = row.getAnonymityPolicy();
        vo.stage = row.getStage();
        vo.estimatedResolveAt = row.getEstimatedResolveAt();
        vo.decisionSummary = row.getDecisionSummary();
        vo.version = row.getVersion();
        vo.createTime = row.getCreateTime();
        return vo;
    }

    public Long getId() { return id; }
    public String getTargetType() { return targetType; }
    public Long getTargetId() { return targetId; }
    public String getReasonCode() { return reasonCode; }
    public String getAnonymityPolicy() { return anonymityPolicy; }
    public String getStage() { return stage; }
    public Date getEstimatedResolveAt() { return estimatedResolveAt; }
    public String getDecisionSummary() { return decisionSummary; }
    public Integer getVersion() { return version; }
    public Date getCreateTime() { return createTime; }
    public String getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(String policyVersion) { this.policyVersion = policyVersion; }
    public Boolean getPolicyMatched() { return policyMatched; }
    public void setPolicyMatched(Boolean policyMatched) { this.policyMatched = policyMatched; }
    public String getReasonLabel() { return reasonLabel; }
    public void setReasonLabel(String reasonLabel) { this.reasonLabel = reasonLabel; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public Integer getFirstResponseMinutes() { return firstResponseMinutes; }
    public void setFirstResponseMinutes(Integer firstResponseMinutes) {
        this.firstResponseMinutes = firstResponseMinutes;
    }
    public Date getFirstResponseDueAt() { return firstResponseDueAt; }
    public void setFirstResponseDueAt(Date firstResponseDueAt) {
        this.firstResponseDueAt = firstResponseDueAt;
    }
    public Date getFirstResponseAt() { return firstResponseAt; }
    public void setFirstResponseAt(Date firstResponseAt) { this.firstResponseAt = firstResponseAt; }
    public Boolean getFirstResponseOverdue() { return firstResponseOverdue; }
    public void setFirstResponseOverdue(Boolean firstResponseOverdue) {
        this.firstResponseOverdue = firstResponseOverdue;
    }
}
