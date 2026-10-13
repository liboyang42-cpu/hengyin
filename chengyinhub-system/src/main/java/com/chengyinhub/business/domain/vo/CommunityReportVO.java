package com.chengyinhub.business.domain.vo;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.domain.CommunityReportCase;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** 举报人本人投影；不返回内部哈希、幂等键或任何平台账号。 */
public class CommunityReportVO {
    private Long id;
    private String targetType;
    private Long targetId;
    private String reasonCode;
    private String policyVersion;
    private String description;
    private List<Long> evidenceAssetIds = new ArrayList<>();
    private String anonymityPolicy;
    private String stage;
    private String publicStage;
    private Date estimatedResolveAt;
    private String decisionSummary;
    private Integer version;
    private Date createTime;
    private List<CommunityReportTimelineVO> timeline = new ArrayList<>();

    public static CommunityReportVO from(CommunityReportCase row) {
        CommunityReportVO vo = new CommunityReportVO();
        vo.id = row.getId();
        vo.targetType = row.getTargetType();
        vo.targetId = row.getTargetId();
        vo.reasonCode = row.getReasonCode();
        vo.policyVersion = row.getPolicyVersion();
        vo.description = row.getDescription();
        if (row.getEvidenceAssetIdsJson() != null) {
            vo.evidenceAssetIds = JSON.parseArray(row.getEvidenceAssetIdsJson(), Long.class);
        }
        vo.anonymityPolicy = row.getAnonymityPolicy();
        vo.stage = row.getStage();
        vo.publicStage = publicStage(row.getStage());
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
    public String getPolicyVersion() { return policyVersion; }
    public String getDescription() { return description; }
    public List<Long> getEvidenceAssetIds() { return evidenceAssetIds; }
    public String getAnonymityPolicy() { return anonymityPolicy; }
    public String getStage() { return stage; }
    public String getPublicStage() { return publicStage; }
    public Date getEstimatedResolveAt() { return estimatedResolveAt; }
    public String getDecisionSummary() { return decisionSummary; }
    public Integer getVersion() { return version; }
    public Date getCreateTime() { return createTime; }
    public List<CommunityReportTimelineVO> getTimeline() { return timeline; }
    public void setTimeline(List<CommunityReportTimelineVO> timeline) { this.timeline = timeline; }

    public static String publicStage(String stage) {
        if (CommunityReportCase.STAGE_SUBMITTED.equals(stage)) return "RECEIVED";
        if (CommunityReportCase.STAGE_RESOLVED.equals(stage)
                || CommunityReportCase.STAGE_CLOSED.equals(stage)) return "DECIDED";
        return "IN_REVIEW";
    }
}
