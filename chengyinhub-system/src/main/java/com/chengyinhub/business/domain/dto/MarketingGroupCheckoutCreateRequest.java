package com.chengyinhub.business.domain.dto;

/**
 * 团体/学校商品建单请求。
 *
 * <p>在报价字段之外只增加报名必需的实名信息、幂等键与原报名报价签名。
 * 不接收 payableAmount/price/discount 等金额字段——成交金额只能由原服务端重算。
 */
public class MarketingGroupCheckoutCreateRequest extends MarketingGroupCheckoutQuoteRequest {

    private String realName;
    private String phone;
    private String email;
    private String address;
    private Long addressId;
    /** 票源俱乐部标记；与原报名一致，仅审计，服务端仍按事实决定是否采用。 */
    private Long sourceClubId;
    /** 票源渠道原文；仅审计，长度不超过 64。 */
    private String sourceChannelCode;
    /** 每个结账意图生成一次；网络重试必须复用，服务端据此重放原报名单。 */
    private String requestId;
    /** 来自 {@link MarketingGroupCheckoutQuoteResult#getQuote()} 的原报名签名。 */
    private String quoteSign;
    /** 原报名促销指纹；报价返回非空时必须原样带回。 */
    private String marketingSign;
    /** APP = 原生 App 支付；空/其他 = 小程序 JSAPI。 */
    private String payChannel;

    public String getRealName() { return realName; }
    public void setRealName(String realName) { this.realName = realName; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public Long getAddressId() { return addressId; }
    public void setAddressId(Long addressId) { this.addressId = addressId; }

    public Long getSourceClubId() { return sourceClubId; }
    public void setSourceClubId(Long sourceClubId) { this.sourceClubId = sourceClubId; }

    public String getSourceChannelCode() { return sourceChannelCode; }
    public void setSourceChannelCode(String sourceChannelCode) { this.sourceChannelCode = sourceChannelCode; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getQuoteSign() { return quoteSign; }
    public void setQuoteSign(String quoteSign) { this.quoteSign = quoteSign; }

    public String getMarketingSign() { return marketingSign; }
    public void setMarketingSign(String marketingSign) { this.marketingSign = marketingSign; }

    public String getPayChannel() { return payChannel; }
    public void setPayChannel(String payChannel) { this.payChannel = payChannel; }
}
