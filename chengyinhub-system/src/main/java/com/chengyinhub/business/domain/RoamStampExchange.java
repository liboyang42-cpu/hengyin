package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * 漫游集邮「投一张换一张」的一次交换 roam_stamp_exchange。
 *
 * given_stamp_id 是代价(我投出去的那张),received_stamp_id 是回报(换回来的别人那张)。
 * 两者缺一不可 —— 只留回报就变成「领一张」,而这套玩法的立论是写了才看得到。
 */
public class RoamStampExchange {
    private Long id;
    private Long memberId;
    private Long givenStampId;
    private Long receivedStampId;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public Long getGivenStampId() { return givenStampId; }
    public void setGivenStampId(Long givenStampId) { this.givenStampId = givenStampId; }
    public Long getReceivedStampId() { return receivedStampId; }
    public void setReceivedStampId(Long receivedStampId) { this.receivedStampId = receivedStampId; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
