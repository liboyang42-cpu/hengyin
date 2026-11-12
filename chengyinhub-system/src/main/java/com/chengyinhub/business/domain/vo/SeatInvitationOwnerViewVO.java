package com.chengyinhub.business.domain.vo;

import java.util.Date;
import java.util.List;

/**
 * 购票人视角的席位邀请总览：原单 N、购票人自用第 1 席、可邀请 N-1 席，
 * 以及待领取/已领取/空闲/过期/撤销/释放的**守恒**计数。
 *
 * <p>财务归属恒为购票人；受让人不会变成订单所有人，也不会产生新的已付行。
 */
public class SeatInvitationOwnerViewVO {

    private Long registrationId;
    private Long ownerType;
    private Long ownerId;
    private Long topicId;
    private Integer seatCount;
    private Integer buyerSeatNo;
    private Integer invitableSeats;
    private Integer pendingSeats;
    private Integer claimedSeats;
    private Integer freeSeats;
    private Integer expiredSeats;
    private Integer cancelledSeats;
    private Integer releasedSeats;
    private Long serverNow;
    private String financialOwner;
    private Boolean readOnly;
    private List<SeatInvitationVO> invitations;

    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long value) { registrationId = value; }
    public Long getOwnerType() { return ownerType; }
    public void setOwnerType(Long value) { ownerType = value; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long value) { ownerId = value; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long value) { topicId = value; }
    public Integer getSeatCount() { return seatCount; }
    public void setSeatCount(Integer value) { seatCount = value; }
    public Integer getBuyerSeatNo() { return buyerSeatNo; }
    public void setBuyerSeatNo(Integer value) { buyerSeatNo = value; }
    public Integer getInvitableSeats() { return invitableSeats; }
    public void setInvitableSeats(Integer value) { invitableSeats = value; }
    public Integer getPendingSeats() { return pendingSeats; }
    public void setPendingSeats(Integer value) { pendingSeats = value; }
    public Integer getClaimedSeats() { return claimedSeats; }
    public void setClaimedSeats(Integer value) { claimedSeats = value; }
    public Integer getFreeSeats() { return freeSeats; }
    public void setFreeSeats(Integer value) { freeSeats = value; }
    public Integer getExpiredSeats() { return expiredSeats; }
    public void setExpiredSeats(Integer value) { expiredSeats = value; }
    public Integer getCancelledSeats() { return cancelledSeats; }
    public void setCancelledSeats(Integer value) { cancelledSeats = value; }
    public Integer getReleasedSeats() { return releasedSeats; }
    public void setReleasedSeats(Integer value) { releasedSeats = value; }
    public Long getServerNow() { return serverNow; }
    public void setServerNow(Long value) { serverNow = value; }
    public String getFinancialOwner() { return financialOwner; }
    public void setFinancialOwner(String value) { financialOwner = value; }
    public Boolean getReadOnly() { return readOnly; }
    public void setReadOnly(Boolean value) { readOnly = value; }
    public List<SeatInvitationVO> getInvitations() { return invitations; }
    public void setInvitations(List<SeatInvitationVO> value) { invitations = value; }
}
