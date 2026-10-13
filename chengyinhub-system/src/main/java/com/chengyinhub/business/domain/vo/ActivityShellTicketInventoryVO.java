package com.chengyinhub.business.domain.vo;

/** 主题票与其活动壳票的库存投影。 */
public class ActivityShellTicketInventoryVO
{
    private Long activityId;
    private Long topicTicketId;
    private Long remainingInventory;

    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public Long getTopicTicketId() { return topicTicketId; }
    public void setTopicTicketId(Long topicTicketId) { this.topicTicketId = topicTicketId; }
    public Long getRemainingInventory() { return remainingInventory; }
    public void setRemainingInventory(Long remainingInventory) { this.remainingInventory = remainingInventory; }
}
