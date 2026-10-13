package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * 官方活动报名(uk_event_member 每人每活动一次;不占用户发布额度)。
 *
 * @author cy
 * @date 2026-07-08
 */
public class EventSignup
{
    private Long id;
    private Long eventId;
    private Long memberId;
    private Integer status;         // 1已报名 2进行中 3已完成 4已领奖
    private Integer progress;       // 个人完成任务数
    private Integer rewardClaimed;  // 0未发 1已发
    private Date createTime;
    private Date updateTime;

    public static final int ST_SIGNED = 1;
    public static final int ST_DOING = 2;
    public static final int ST_DONE = 3;
    public static final int ST_REWARDED = 4;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getEventId() { return eventId; }
    public void setEventId(Long eventId) { this.eventId = eventId; }

    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public Integer getProgress() { return progress; }
    public void setProgress(Integer progress) { this.progress = progress; }

    public Integer getRewardClaimed() { return rewardClaimed; }
    public void setRewardClaimed(Integer rewardClaimed) { this.rewardClaimed = rewardClaimed; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
