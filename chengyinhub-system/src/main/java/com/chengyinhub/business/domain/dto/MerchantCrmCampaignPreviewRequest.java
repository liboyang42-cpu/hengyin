package com.chengyinhub.business.domain.dto;

/** 仅引用服务端已保存分群；禁止携带 memberId 列表。 */
public class MerchantCrmCampaignPreviewRequest {
    private Long segmentId;
    private String channel;

    public Long getSegmentId() { return segmentId; }
    public void setSegmentId(Long value) { segmentId = value; }
    public String getChannel() { return channel; }
    public void setChannel(String value) { channel = value; }
}
