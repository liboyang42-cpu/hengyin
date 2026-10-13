package com.chengyinhub.business.domain.vo;

import java.util.Date;
import java.util.List;

/** 俱乐部主理人报名名册的服务端白名单投影。 */
public class ClubRegistrationRosterVO {
    private Long clubId;
    private Long topicId;
    private boolean canRefund;
    private List<Ticket> omsTicketList;

    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public boolean isCanRefund() { return canRefund; }
    public void setCanRefund(boolean canRefund) { this.canRefund = canRefund; }
    public List<Ticket> getOmsTicketList() { return omsTicketList; }
    public void setOmsTicketList(List<Ticket> omsTicketList) { this.omsTicketList = omsTicketList; }

    public static class Ticket {
        private Long id;
        private String name;
        private Integer mode;
        private Long totalInventory;
        private Date signupDeadline;
        private Integer teamStatus;
        private List<Registration> cmsRegistrationList;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Integer getMode() { return mode; }
        public void setMode(Integer mode) { this.mode = mode; }
        public Long getTotalInventory() { return totalInventory; }
        public void setTotalInventory(Long totalInventory) { this.totalInventory = totalInventory; }
        public Date getSignupDeadline() { return signupDeadline; }
        public void setSignupDeadline(Date signupDeadline) { this.signupDeadline = signupDeadline; }
        public Integer getTeamStatus() { return teamStatus; }
        public void setTeamStatus(Integer teamStatus) { this.teamStatus = teamStatus; }
        public List<Registration> getCmsRegistrationList() { return cmsRegistrationList; }
        public void setCmsRegistrationList(List<Registration> cmsRegistrationList) {
            this.cmsRegistrationList = cmsRegistrationList;
        }
    }

    public static class Registration {
        private Long id;
        private Long memberId;
        private String avatar;
        private String nickname;
        private Integer paymentStatus;
        private Integer verificationStatus;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long getMemberId() { return memberId; }
        public void setMemberId(Long memberId) { this.memberId = memberId; }
        public String getAvatar() { return avatar; }
        public void setAvatar(String avatar) { this.avatar = avatar; }
        public String getNickname() { return nickname; }
        public void setNickname(String nickname) { this.nickname = nickname; }
        public Integer getPaymentStatus() { return paymentStatus; }
        public void setPaymentStatus(Integer paymentStatus) { this.paymentStatus = paymentStatus; }
        public Integer getVerificationStatus() { return verificationStatus; }
        public void setVerificationStatus(Integer verificationStatus) {
            this.verificationStatus = verificationStatus;
        }
    }
}
