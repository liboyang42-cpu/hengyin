package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * 个人路线原局或已验证队伍共享原局的免费提示额度。
 *
 * <p>键是 {@code (topic_id, activity_id, play_instance_id, owner_member_id)}，不含 nodeId：同一局内跨节点共用
 * 同一行；{@code quota_total} 在第一次使用时由冻结配置写入一次，之后只加 {@code quota_used}，
 * 不因主题改价/改额度而重置。
 *
 * <p>这张表只记「免费次数」这一件事，不是第二套积分钱包；积分仍只在
 * {@code ums_member} / {@code ums_member_points_detail} 里扣。
 */
public class PlayHelpRunQuota {
    private Long id;
    private Long topicId;
    private Long activityId;
    private String playInstanceId;
    // shared-game 命名空间使用已核验的 team owner ID；个人局仍为会员 ID。
    private Long ownerMemberId;
    // 仅旧额度关联查询使用，来自 game_runtime_session 的服务端原局绑定，无新增表列。
    private Long sharedRunId;
    private Integer quotaTotal;
    private Integer quotaUsed;
    private Integer version;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }

    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }

    public String getPlayInstanceId() { return playInstanceId; }
    public void setPlayInstanceId(String playInstanceId) { this.playInstanceId = playInstanceId; }

    public Long getOwnerMemberId() { return ownerMemberId; }
    public void setOwnerMemberId(Long ownerMemberId) { this.ownerMemberId = ownerMemberId; }

    public Long getSharedRunId() { return sharedRunId; }
    public void setSharedRunId(Long sharedRunId) { this.sharedRunId = sharedRunId; }

    public Integer getQuotaTotal() { return quotaTotal; }
    public void setQuotaTotal(Integer quotaTotal) { this.quotaTotal = quotaTotal; }

    public Integer getQuotaUsed() { return quotaUsed; }
    public void setQuotaUsed(Integer quotaUsed) { this.quotaUsed = quotaUsed; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
