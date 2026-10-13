package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

import java.util.Date;

/**
 * P3 玩家成长账户 member_growth。
 */
public class MemberGrowth extends BaseEntity {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long memberId;
    private Integer levelNo;
    private Integer expValue;
    private Date lastActiveTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public Integer getLevelNo() { return levelNo; }
    public void setLevelNo(Integer levelNo) { this.levelNo = levelNo; }
    public Integer getExpValue() { return expValue; }
    public void setExpValue(Integer expValue) { this.expValue = expValue; }
    public Date getLastActiveTime() { return lastActiveTime; }
    public void setLastActiveTime(Date lastActiveTime) { this.lastActiveTime = lastActiveTime; }
}
