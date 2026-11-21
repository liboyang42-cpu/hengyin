package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 玩家成长聚合(等级/里程/连续打卡)
 *
 * @author cy
 * @date 2026-06-21
 */
public class PlayerGrowth
{
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long memberId;
    private Integer level;
    private Integer totalCheckins;
    private BigDecimal totalMileage;
    private Integer streakDays;
    private Date lastCheckinDate;
    /** P7:手里的补签卡(连续天数漏一天时自动用掉一张) */
    private Integer streakFreezes;
    /** P7:累计已自动用掉的补签卡 */
    private Integer streakFreezesUsed;
    private Date updateTime;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }

    public Integer getLevel() { return level; }
    public void setLevel(Integer level) { this.level = level; }

    public Integer getTotalCheckins() { return totalCheckins; }
    public void setTotalCheckins(Integer totalCheckins) { this.totalCheckins = totalCheckins; }

    public BigDecimal getTotalMileage() { return totalMileage; }
    public void setTotalMileage(BigDecimal totalMileage) { this.totalMileage = totalMileage; }

    public Integer getStreakDays() { return streakDays; }
    public void setStreakDays(Integer streakDays) { this.streakDays = streakDays; }

    public Date getLastCheckinDate() { return lastCheckinDate; }
    public void setLastCheckinDate(Date lastCheckinDate) { this.lastCheckinDate = lastCheckinDate; }

    public Integer getStreakFreezes() { return streakFreezes; }
    public void setStreakFreezes(Integer streakFreezes) { this.streakFreezes = streakFreezes; }

    public Integer getStreakFreezesUsed() { return streakFreezesUsed; }
    public void setStreakFreezesUsed(Integer streakFreezesUsed) { this.streakFreezesUsed = streakFreezesUsed; }

    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
