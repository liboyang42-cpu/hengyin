package com.chengyinhub.business.domain;

/**
 * 「这名会员凭一次已领取的付费席位邀请，可走原已付行」的只读事实。
 *
 * <p>它不是礼品事实，也不是第二套准入真源：原 {@code cms_registration} 仍是唯一已付行，
 * 财务/退款归属不动；本事实只把 invitation 的 recipient 绑定投影给
 * {@code CurrentPlayRegistration}，并在原行退款/撤销时由 SQL 直接消失。
 */
public class SeatRecipientFact {

    private Long invitationId;
    private Long registrationId;
    private Integer seatNo;
    private Long inviteeMemberId;

    public Long getInvitationId() { return invitationId; }
    public void setInvitationId(Long value) { invitationId = value; }
    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long value) { registrationId = value; }
    public Integer getSeatNo() { return seatNo; }
    public void setSeatNo(Integer value) { seatNo = value; }
    public Long getInviteeMemberId() { return inviteeMemberId; }
    public void setInviteeMemberId(Long value) { inviteeMemberId = value; }
}
