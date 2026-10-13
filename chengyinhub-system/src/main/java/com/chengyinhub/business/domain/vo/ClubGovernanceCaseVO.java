package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.ClubGovernanceCase;

import java.util.Date;

/** 治理工单白名单投影；不下发提交/裁决幂等键和平台审核员账号。 */
public class ClubGovernanceCaseVO {
    protected Long id;
    protected Long clubId;
    protected String caseType;
    protected Long filerMemberId;
    protected Long subjectMemberId;
    protected String targetType;
    protected Long targetId;
    protected Long relatedBanId;
    protected String reason;
    protected String status;
    protected String decisionReason;
    protected Date decidedAt;
    protected Integer version;
    protected Date createTime;

    public static ClubGovernanceCaseVO from(ClubGovernanceCase source) {
        return fill(new ClubGovernanceCaseVO(), source);
    }

    protected static <T extends ClubGovernanceCaseVO> T fill(T vo, ClubGovernanceCase source) {
        vo.id = source.getId();
        vo.clubId = source.getClubId();
        vo.caseType = source.getCaseType();
        vo.filerMemberId = source.getFilerMemberId();
        vo.subjectMemberId = source.getSubjectMemberId();
        vo.targetType = source.getTargetType();
        vo.targetId = source.getTargetId();
        vo.relatedBanId = source.getRelatedBanId();
        vo.reason = source.getReason();
        vo.status = source.getStatus();
        vo.decisionReason = source.getDecisionReason();
        vo.decidedAt = source.getDecidedAt();
        vo.version = source.getVersion();
        vo.createTime = source.getCreateTime();
        return vo;
    }

    public Long getId() { return id; }
    public Long getClubId() { return clubId; }
    public String getCaseType() { return caseType; }
    public Long getFilerMemberId() { return filerMemberId; }
    public Long getSubjectMemberId() { return subjectMemberId; }
    public String getTargetType() { return targetType; }
    public Long getTargetId() { return targetId; }
    public Long getRelatedBanId() { return relatedBanId; }
    public String getReason() { return reason; }
    public String getStatus() { return status; }
    public String getDecisionReason() { return decisionReason; }
    public Date getDecidedAt() { return decidedAt; }
    public Integer getVersion() { return version; }
    public Date getCreateTime() { return createTime; }
}
