package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

import java.util.Date;

/**
 * P3 成长任务进度 growth_mission_progress。
 */
public class GrowthMissionProgress extends BaseEntity {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long memberId;
    private String missionCode;
    private String bizType;
    private Long bizId;
    private Date progressDate;
    private Integer completeCount;
    private Integer rewardExp;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getMissionCode() { return missionCode; }
    public void setMissionCode(String missionCode) { this.missionCode = missionCode; }
    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public Long getBizId() { return bizId; }
    public void setBizId(Long bizId) { this.bizId = bizId; }
    public Date getProgressDate() { return progressDate; }
    public void setProgressDate(Date progressDate) { this.progressDate = progressDate; }
    public Integer getCompleteCount() { return completeCount; }
    public void setCompleteCount(Integer completeCount) { this.completeCount = completeCount; }
    public Integer getRewardExp() { return rewardExp; }
    public void setRewardExp(Integer rewardExp) { this.rewardExp = rewardExp; }

    /** F0395:这条经验记录的来源。只有挂在报名核销事实上的才是已核验;其余为自报完成。 */
    public String getEvidenceSource() {
        return "registration".equals(bizType) && bizId != null && bizId > 0 ? "verified_store" : "self_reported";
    }
}
