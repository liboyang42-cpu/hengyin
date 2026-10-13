package com.chengyinhub.business.domain.dto;

/** 创建合规触达；收件人始终由 segmentId 在服务端重算冻结。 */
public class MerchantCrmCampaignRequest extends MerchantCrmCampaignPreviewRequest {
    private Long couponId;
    private String title;
    private String content;
    private String requestId;

    public Long getCouponId() { return couponId; }
    public void setCouponId(Long value) { couponId = value; }
    public String getTitle() { return title; }
    public void setTitle(String value) { title = value; }
    public String getContent() { return content; }
    public void setContent(String value) { content = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
}
