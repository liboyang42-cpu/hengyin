package com.chengyinhub.business.domain.dto;

/** 购票人邀请：seatNo 为空时由服务端挑最低空闲内含席位（2..N）。 */
public class SeatInvitationCreateRequest {

    private Long registrationId;
    private Integer seatNo;
    private String requestId;

    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long value) { registrationId = value; }
    public Integer getSeatNo() { return seatNo; }
    public void setSeatNo(Integer value) { seatNo = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
}
