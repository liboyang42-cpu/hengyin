package com.chengyinhub.business.domain;

import java.util.Date;

/** 俱乐部治理举报/申诉；只能由平台审核员裁决，俱乐部管理者不可读取或处理。 */
public class ClubGovernanceCase {
    public static final String TYPE_REPORT = "REPORT";
    public static final String TYPE_APPEAL = "APPEAL";
    public static final String TARGET_CLUB = "CLUB";
    public static final String TARGET_ACTIVITY = "ACTIVITY";
    public static final String TARGET_MEMBER = "MEMBER";
    public static final String TARGET_BAN = "BAN";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";

    private Long id;
    private Long clubId;
    private String caseType;
    private Long filerMemberId;
    private Long subjectMemberId;
    private String targetType;
    private Long targetId;
    private String evidenceJson;
    private Long relatedBanId;
    private String reason;
    private String status;
    private String decisionReason;
    private Long decidedByUserId;
    private Date decidedAt;
    private String submitRequestId;
    private String decisionRequestId;
    private Integer version;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }
    public String getCaseType() { return caseType; }
    public void setCaseType(String caseType) { this.caseType = caseType; }
    public Long getFilerMemberId() { return filerMemberId; }
    public void setFilerMemberId(Long filerMemberId) { this.filerMemberId = filerMemberId; }
    public Long getSubjectMemberId() { return subjectMemberId; }
    public void setSubjectMemberId(Long subjectMemberId) { this.subjectMemberId = subjectMemberId; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }
    public String getEvidenceJson() { return evidenceJson; }
    public void setEvidenceJson(String evidenceJson) { this.evidenceJson = evidenceJson; }
    public Long getRelatedBanId() { return relatedBanId; }
    public void setRelatedBanId(Long relatedBanId) { this.relatedBanId = relatedBanId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDecisionReason() { return decisionReason; }
    public void setDecisionReason(String decisionReason) { this.decisionReason = decisionReason; }
    public Long getDecidedByUserId() { return decidedByUserId; }
    public void setDecidedByUserId(Long decidedByUserId) { this.decidedByUserId = decidedByUserId; }
    public Date getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Date decidedAt) { this.decidedAt = decidedAt; }
    public String getSubmitRequestId() { return submitRequestId; }
    public void setSubmitRequestId(String submitRequestId) { this.submitRequestId = submitRequestId; }
    public String getDecisionRequestId() { return decisionRequestId; }
    public void setDecisionRequestId(String decisionRequestId) { this.decisionRequestId = decisionRequestId; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
