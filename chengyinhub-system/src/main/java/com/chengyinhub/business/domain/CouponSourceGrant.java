package com.chengyinhub.business.domain;

/** 原奖励来源交付回执；historyId 为空表示尚未成功交付。 */
public class CouponSourceGrant {
    private Long memberId;
    private Long couponId;
    private Long historyId;
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long value) { memberId = value; }
    public Long getCouponId() { return couponId; }
    public void setCouponId(Long value) { couponId = value; }
    public Long getHistoryId() { return historyId; }
    public void setHistoryId(Long value) { historyId = value; }
}
