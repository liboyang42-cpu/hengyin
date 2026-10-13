package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * P3 世界任务 ip_quest。
 */
public class IpQuest extends BaseEntity {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long worldId;
    private Long chapterId;
    private String questName;
    private String questType;
    private String targetBizType;
    private Long targetBizId;
    private Integer rewardExp;
    private String status;
    private Integer sortNo;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getWorldId() { return worldId; }
    public void setWorldId(Long worldId) { this.worldId = worldId; }
    public Long getChapterId() { return chapterId; }
    public void setChapterId(Long chapterId) { this.chapterId = chapterId; }
    public String getQuestName() { return questName; }
    public void setQuestName(String questName) { this.questName = questName; }
    public String getQuestType() { return questType; }
    public void setQuestType(String questType) { this.questType = questType; }
    public String getTargetBizType() { return targetBizType; }
    public void setTargetBizType(String targetBizType) { this.targetBizType = targetBizType; }
    public Long getTargetBizId() { return targetBizId; }
    public void setTargetBizId(Long targetBizId) { this.targetBizId = targetBizId; }
    public Integer getRewardExp() { return rewardExp; }
    public void setRewardExp(Integer rewardExp) { this.rewardExp = rewardExp; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getSortNo() { return sortNo; }
    public void setSortNo(Integer sortNo) { this.sortNo = sortNo; }
}
