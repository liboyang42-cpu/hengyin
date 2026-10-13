package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * 带队场次中的玩家核销事实。
 *
 * <p>一条报名单只能由一个带队场次核销；结算据此把商家接待的场次与该场次实际核销的玩家关联，
 * 不能再从 topicId + 领队 memberId 反推。</p>
 */
public class CoopGroupMemberVerify {
    private Long id;
    private Long topicId;
    private Long activityId;
    private Long registrationId;
    private Long verifierMemberId;
    private Date verifyTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }

    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }

    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long registrationId) { this.registrationId = registrationId; }

    public Long getVerifierMemberId() { return verifierMemberId; }
    public void setVerifierMemberId(Long verifierMemberId) { this.verifierMemberId = verifierMemberId; }

    public Date getVerifyTime() { return verifyTime; }
    public void setVerifyTime(Date verifyTime) { this.verifyTime = verifyTime; }
}
