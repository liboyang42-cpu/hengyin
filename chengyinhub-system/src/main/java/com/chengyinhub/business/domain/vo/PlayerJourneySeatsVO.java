package com.chengyinhub.business.domain.vo;

import java.util.Date;
import java.util.List;

/**
 * 已付多人票的席位摘要（P5）：只读投影，仅购票人本人可读。第 1 席为 BUYER，
 * 2..N 席携带真实邀请状态（INVITED/CLAIMED）与受让人会员号；邀请/领取/撤销/释放的
 * 写口在独立 {@code /api/play/seat-invitations} 控制器。财务/退款归属仍是原购票人。
 */
public class PlayerJourneySeatsVO {

    public static final String SEAT_VERIFIED = "VERIFIED";
    public static final String SEAT_RESERVED = "RESERVED";
    public static final String SEAT_AVAILABLE = "AVAILABLE";
    public static final String SEAT_EXPIRED = "EXPIRED";
    public static final String SEAT_BUYER = "BUYER";
    public static final String SEAT_INVITED = "INVITED";
    public static final String SEAT_CLAIMED = "CLAIMED";

    private Long registrationId;
    private Long ownerType;
    private Long ownerId;
    private Long topicId;
    private Integer seatCount;
    private Integer verifiedSeats;
    private Integer reservedSeats;
    private Integer availableSeats;
    private Integer expiredSeats;
    private Date expiresAt;
    private Long serverNow;
    private String financialOwner;
    private Boolean buyerIsCaller;
    private Boolean recipientIdentityVisible;
    private Boolean seatReclaimSupported;
    private Boolean seatReleaseSupported;
    private Boolean seatCancelSupported;
    private String supportNote;
    private Boolean readOnly;
    private List<Seat> seats;
    /** 现有事实无法确定的席位数一律 null，不按默认值补 0。 */
    private Integer freeSeats;
    private Integer invitedSeats;
    private List<String> unavailableFacts;

    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long registrationId) { this.registrationId = registrationId; }
    public Long getOwnerType() { return ownerType; }
    public void setOwnerType(Long ownerType) { this.ownerType = ownerType; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public Integer getSeatCount() { return seatCount; }
    public void setSeatCount(Integer seatCount) { this.seatCount = seatCount; }
    public Integer getVerifiedSeats() { return verifiedSeats; }
    public void setVerifiedSeats(Integer verifiedSeats) { this.verifiedSeats = verifiedSeats; }
    public Integer getReservedSeats() { return reservedSeats; }
    public void setReservedSeats(Integer reservedSeats) { this.reservedSeats = reservedSeats; }
    public Integer getAvailableSeats() { return availableSeats; }
    public void setAvailableSeats(Integer availableSeats) { this.availableSeats = availableSeats; }
    public Integer getExpiredSeats() { return expiredSeats; }
    public void setExpiredSeats(Integer expiredSeats) { this.expiredSeats = expiredSeats; }
    public Date getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Date expiresAt) { this.expiresAt = expiresAt; }
    public Long getServerNow() { return serverNow; }
    public void setServerNow(Long serverNow) { this.serverNow = serverNow; }
    public String getFinancialOwner() { return financialOwner; }
    public void setFinancialOwner(String financialOwner) { this.financialOwner = financialOwner; }
    public Boolean getBuyerIsCaller() { return buyerIsCaller; }
    public void setBuyerIsCaller(Boolean buyerIsCaller) { this.buyerIsCaller = buyerIsCaller; }
    public Boolean getRecipientIdentityVisible() { return recipientIdentityVisible; }
    public void setRecipientIdentityVisible(Boolean recipientIdentityVisible) { this.recipientIdentityVisible = recipientIdentityVisible; }
    public Boolean getSeatReclaimSupported() { return seatReclaimSupported; }
    public void setSeatReclaimSupported(Boolean seatReclaimSupported) { this.seatReclaimSupported = seatReclaimSupported; }
    public Boolean getSeatReleaseSupported() { return seatReleaseSupported; }
    public void setSeatReleaseSupported(Boolean seatReleaseSupported) { this.seatReleaseSupported = seatReleaseSupported; }
    public Boolean getSeatCancelSupported() { return seatCancelSupported; }
    public void setSeatCancelSupported(Boolean seatCancelSupported) { this.seatCancelSupported = seatCancelSupported; }
    public String getSupportNote() { return supportNote; }
    public void setSupportNote(String supportNote) { this.supportNote = supportNote; }
    public Boolean getReadOnly() { return readOnly; }
    public void setReadOnly(Boolean readOnly) { this.readOnly = readOnly; }
    public List<Seat> getSeats() { return seats; }
    public void setSeats(List<Seat> seats) { this.seats = seats; }
    public Integer getFreeSeats() { return freeSeats; }
    public void setFreeSeats(Integer freeSeats) { this.freeSeats = freeSeats; }
    public Integer getInvitedSeats() { return invitedSeats; }
    public void setInvitedSeats(Integer invitedSeats) { this.invitedSeats = invitedSeats; }
    public List<String> getUnavailableFacts() { return unavailableFacts; }
    public void setUnavailableFacts(List<String> unavailableFacts) { this.unavailableFacts = unavailableFacts; }

    /** 席位号、状态、核销时间与（购票人可见的）邀请事实；不含受让人手机号等隐私。 */
    public static class Seat {
        private Integer seatNo;
        private String state;
        private Date verifiedAt;
        private Long invitationId;
        private Long inviteeMemberId;
        private String invitationStatus;
        private Date invitationExpiresAt;

        public Integer getSeatNo() { return seatNo; }
        public void setSeatNo(Integer seatNo) { this.seatNo = seatNo; }
        public String getState() { return state; }
        public void setState(String state) { this.state = state; }
        public Date getVerifiedAt() { return verifiedAt; }
        public void setVerifiedAt(Date verifiedAt) { this.verifiedAt = verifiedAt; }
        public Long getInvitationId() { return invitationId; }
        public void setInvitationId(Long invitationId) { this.invitationId = invitationId; }
        public Long getInviteeMemberId() { return inviteeMemberId; }
        public void setInviteeMemberId(Long inviteeMemberId) { this.inviteeMemberId = inviteeMemberId; }
        public String getInvitationStatus() { return invitationStatus; }
        public void setInvitationStatus(String invitationStatus) { this.invitationStatus = invitationStatus; }
        public Date getInvitationExpiresAt() { return invitationExpiresAt; }
        public void setInvitationExpiresAt(Date invitationExpiresAt) { this.invitationExpiresAt = invitationExpiresAt; }
    }
}
