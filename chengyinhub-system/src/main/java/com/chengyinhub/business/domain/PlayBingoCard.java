package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * 宾果任务卡。一人一主题一张(uk_member_topic)。
 *
 * <p>格子在开卡时按玩家历史抽定,之后不变 —— 中途换格子等于把已完成的进度作废。
 */
public class PlayBingoCard {

    private Long id;
    private Long memberId;
    private Long topicId;
    private String cellsJson;
    private Integer filledMask;
    private Integer rewardMask;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }

    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }

    public String getCellsJson() { return cellsJson; }
    public void setCellsJson(String cellsJson) { this.cellsJson = cellsJson; }

    public Integer getFilledMask() { return filledMask; }
    public void setFilledMask(Integer filledMask) { this.filledMask = filledMask; }

    public Integer getRewardMask() { return rewardMask; }
    public void setRewardMask(Integer rewardMask) { this.rewardMask = rewardMask; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
