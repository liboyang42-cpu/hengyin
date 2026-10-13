package com.chengyinhub.business.domain.dto;

import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * 俱乐部运营工作区的带队履约只读快照。
 *
 * <p>只投影运营调证所需的名称、进度和时间，禁止将参与者或责任人的联系方式透出。</p>
 */
public class ClubFulfillmentWorkspaceDTO {

    private Long clubId;
    private List<Session> sessions = Collections.emptyList();
    private List<GroupVerification> groupVerifications = Collections.emptyList();

    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }
    public List<Session> getSessions() { return sessions; }
    public void setSessions(List<Session> sessions) { this.sessions = sessions; }
    public List<GroupVerification> getGroupVerifications() { return groupVerifications; }
    public void setGroupVerifications(List<GroupVerification> groupVerifications) { this.groupVerifications = groupVerifications; }

    public static class Session {
        private Long activityId;
        private Long topicId;
        private String topicName;
        private String activityName;
        private Date assemblyTime;
        private String assemblyLocation;
        private Long leaderMemberId;
        private String leaderDisplayName;
        private Long currentChapterId;
        private Integer status;
        private List<Attendance> attendees = Collections.emptyList();

        public Long getActivityId() { return activityId; }
        public void setActivityId(Long activityId) { this.activityId = activityId; }
        public Long getTopicId() { return topicId; }
        public void setTopicId(Long topicId) { this.topicId = topicId; }
        public String getTopicName() { return topicName; }
        public void setTopicName(String topicName) { this.topicName = topicName; }
        public String getActivityName() { return activityName; }
        public void setActivityName(String activityName) { this.activityName = activityName; }
        public Date getAssemblyTime() { return assemblyTime; }
        public void setAssemblyTime(Date assemblyTime) { this.assemblyTime = assemblyTime; }
        public String getAssemblyLocation() { return assemblyLocation; }
        public void setAssemblyLocation(String assemblyLocation) { this.assemblyLocation = assemblyLocation; }
        public Long getLeaderMemberId() { return leaderMemberId; }
        public void setLeaderMemberId(Long leaderMemberId) { this.leaderMemberId = leaderMemberId; }
        public String getLeaderDisplayName() { return leaderDisplayName; }
        public void setLeaderDisplayName(String leaderDisplayName) { this.leaderDisplayName = leaderDisplayName; }
        public Long getCurrentChapterId() { return currentChapterId; }
        public void setCurrentChapterId(Long currentChapterId) { this.currentChapterId = currentChapterId; }
        public Integer getStatus() { return status; }
        public void setStatus(Integer status) { this.status = status; }
        public List<Attendance> getAttendees() { return attendees; }
        public void setAttendees(List<Attendance> attendees) { this.attendees = attendees; }
    }

    public static class Attendance {
        private Long activityId;
        private Long memberId;
        private String memberDisplayName;
        private Date arrivedAt;

        public Long getActivityId() { return activityId; }
        public void setActivityId(Long activityId) { this.activityId = activityId; }
        public Long getMemberId() { return memberId; }
        public void setMemberId(Long memberId) { this.memberId = memberId; }
        public String getMemberDisplayName() { return memberDisplayName; }
        public void setMemberDisplayName(String memberDisplayName) { this.memberDisplayName = memberDisplayName; }
        public Date getArrivedAt() { return arrivedAt; }
        public void setArrivedAt(Date arrivedAt) { this.arrivedAt = arrivedAt; }
    }

    public static class GroupVerification {
        private Long verificationId;
        private Long topicId;
        private Long activityId;
        private String topicName;
        private Long coopOrderId;
        private Long merchantMemberId;
        private String merchantDisplayName;
        private Long leaderMemberId;
        private String leaderDisplayName;
        private Date verifyTime;

        public Long getVerificationId() { return verificationId; }
        public void setVerificationId(Long verificationId) { this.verificationId = verificationId; }
        public Long getTopicId() { return topicId; }
        public void setTopicId(Long topicId) { this.topicId = topicId; }
        public Long getActivityId() { return activityId; }
        public void setActivityId(Long activityId) { this.activityId = activityId; }
        public String getTopicName() { return topicName; }
        public void setTopicName(String topicName) { this.topicName = topicName; }
        public Long getCoopOrderId() { return coopOrderId; }
        public void setCoopOrderId(Long coopOrderId) { this.coopOrderId = coopOrderId; }
        public Long getMerchantMemberId() { return merchantMemberId; }
        public void setMerchantMemberId(Long merchantMemberId) { this.merchantMemberId = merchantMemberId; }
        public String getMerchantDisplayName() { return merchantDisplayName; }
        public void setMerchantDisplayName(String merchantDisplayName) { this.merchantDisplayName = merchantDisplayName; }
        public Long getLeaderMemberId() { return leaderMemberId; }
        public void setLeaderMemberId(Long leaderMemberId) { this.leaderMemberId = leaderMemberId; }
        public String getLeaderDisplayName() { return leaderDisplayName; }
        public void setLeaderDisplayName(String leaderDisplayName) { this.leaderDisplayName = leaderDisplayName; }
        public Date getVerifyTime() { return verifyTime; }
        public void setVerifyTime(Date verifyTime) { this.verifyTime = verifyTime; }
    }
}
