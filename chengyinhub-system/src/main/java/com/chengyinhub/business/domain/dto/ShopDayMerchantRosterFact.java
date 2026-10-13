package com.chengyinhub.business.domain.dto;

import java.util.Date;

/** 探店日商家现场名单的内部事实；memberId 仅用于服务端逐店授权判断，禁止出现在响应。 */
public class ShopDayMerchantRosterFact {
    private Long registrationId;
    private Long memberId;
    private String ticketNo;
    private Date ticketStart;
    private Date ticketEnd;
    private String realName;
    private String nickname;
    private String avatar;
    private String phone;
    private String entitlementStatus;
    private Date checkinAt;
    private Date serviceStartedAt;

    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long registrationId) { this.registrationId = registrationId; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getTicketNo() { return ticketNo; }
    public void setTicketNo(String ticketNo) { this.ticketNo = ticketNo; }
    public Date getTicketStart() { return ticketStart; }
    public void setTicketStart(Date ticketStart) { this.ticketStart = ticketStart; }
    public Date getTicketEnd() { return ticketEnd; }
    public void setTicketEnd(Date ticketEnd) { this.ticketEnd = ticketEnd; }
    public String getRealName() { return realName; }
    public void setRealName(String realName) { this.realName = realName; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getEntitlementStatus() { return entitlementStatus; }
    public void setEntitlementStatus(String entitlementStatus) { this.entitlementStatus = entitlementStatus; }
    public Date getCheckinAt() { return checkinAt; }
    public void setCheckinAt(Date checkinAt) { this.checkinAt = checkinAt; }
    public Date getServiceStartedAt() { return serviceStartedAt; }
    public void setServiceStartedAt(Date serviceStartedAt) { this.serviceStartedAt = serviceStartedAt; }
}
