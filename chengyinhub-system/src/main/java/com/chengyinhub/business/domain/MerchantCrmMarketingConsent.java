package com.chengyinhub.business.domain;

import java.util.Date;

/** 玩家针对单一商家、单一触达渠道的追加式同意事件。 */
public class MerchantCrmMarketingConsent {
    public static final String IN_APP = "IN_APP";
    public static final String COUPON = "COUPON";
    public static final String OPT_IN = "OPT_IN";
    public static final String OPT_OUT = "OPT_OUT";

    private Long id;
    private Long memberId;
    private Long merchantOwnerMemberId;
    private Long merchantRowId;
    private String channel;
    private String eventType;
    private String requestId;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long value) { memberId = value; }
    public Long getMerchantOwnerMemberId() { return merchantOwnerMemberId; }
    public void setMerchantOwnerMemberId(Long value) { merchantOwnerMemberId = value; }
    public Long getMerchantRowId() { return merchantRowId; }
    public void setMerchantRowId(Long value) { merchantRowId = value; }
    public String getChannel() { return channel; }
    public void setChannel(String value) { channel = value; }
    public String getEventType() { return eventType; }
    public void setEventType(String value) { eventType = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date value) { createTime = value; }
}
