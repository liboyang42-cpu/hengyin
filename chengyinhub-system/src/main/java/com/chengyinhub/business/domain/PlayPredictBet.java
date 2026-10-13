package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * 竞猜下注。一轮 = (nodeId, playDay),一人一轮一注(uk_round_member)。
 *
 * <p>它同时是发奖的幂等键:{@code rewardStatus} 从 0 改成 1 用条件更新,
 * 重复结算命中 0 行,奖不会重复发。
 */
public class PlayPredictBet {

    /** 未结算 */
    public static final int REWARD_PENDING = 0;
    /** 猜中并已发奖 */
    public static final int REWARD_WON = 1;
    /** 没猜中 */
    public static final int REWARD_LOST = 2;
    /** 商家没结算,整轮作废 */
    public static final int REWARD_VOID = 3;

    private Long id;
    private Long nodeId;
    private Long topicId;
    private Long activityId;
    private Date playDay;
    private Long memberId;
    private String optionKey;
    private Integer rewardStatus;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }

    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }

    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }

    public Date getPlayDay() { return playDay; }
    public void setPlayDay(Date playDay) { this.playDay = playDay; }

    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }

    public String getOptionKey() { return optionKey; }
    public void setOptionKey(String optionKey) { this.optionKey = optionKey; }

    public Integer getRewardStatus() { return rewardStatus; }
    public void setRewardStatus(Integer rewardStatus) { this.rewardStatus = rewardStatus; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
