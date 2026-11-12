package com.chengyinhub.business.domain.dto;

/** 回调受理回执；{@code accepted=false} 时 reasonCode 说明未通过验证或重复。 */
public class ChannelInboundCallbackView {

    private Boolean accepted;
    private String eventId;
    private String channelOrderNo;
    private Integer status;
    private String reasonCode;
    private Boolean replayed;

    public Boolean getAccepted() { return accepted; }
    public void setAccepted(Boolean accepted) { this.accepted = accepted; }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public String getChannelOrderNo() { return channelOrderNo; }
    public void setChannelOrderNo(String channelOrderNo) { this.channelOrderNo = channelOrderNo; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }

    public Boolean getReplayed() { return replayed; }
    public void setReplayed(Boolean replayed) { this.replayed = replayed; }
}
