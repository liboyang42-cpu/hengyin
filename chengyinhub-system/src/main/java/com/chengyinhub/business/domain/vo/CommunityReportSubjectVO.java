package com.chengyinhub.business.domain.vo;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** 被举报方专用白名单投影；类型上不存在举报人、描述、证据字段。 */
public class CommunityReportSubjectVO {
    private Long id;
    private String targetType;
    private Long targetId;
    private String reasonCode;
    private String policyVersion;
    private String anonymityPolicy;
    private String stage;
    private String publicStage;
    private Date estimatedResolveAt;
    private String decisionSummary;
    private Integer version;
    private Date createTime;
    private List<CommunityReportTimelineVO> timeline = new ArrayList<>();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    public String getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(String policyVersion) { this.policyVersion = policyVersion; }
    public String getAnonymityPolicy() { return anonymityPolicy; }
    public void setAnonymityPolicy(String anonymityPolicy) { this.anonymityPolicy = anonymityPolicy; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public String getPublicStage() { return publicStage; }
    public void setPublicStage(String publicStage) { this.publicStage = publicStage; }
    public Date getEstimatedResolveAt() { return estimatedResolveAt; }
    public void setEstimatedResolveAt(Date estimatedResolveAt) { this.estimatedResolveAt = estimatedResolveAt; }
    public String getDecisionSummary() { return decisionSummary; }
    public void setDecisionSummary(String decisionSummary) { this.decisionSummary = decisionSummary; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public List<CommunityReportTimelineVO> getTimeline() { return timeline; }
    public void setTimeline(List<CommunityReportTimelineVO> timeline) { this.timeline = timeline; }
}
