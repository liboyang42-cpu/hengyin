package com.chengyinhub.business.domain.dto;

/**
 * 加座（route=3）报价请求。
 *
 * <p>加座人身份只能由服务端从原单事实推出：这里只收原订单号与预定受赠人。
 * 客户端不能传 memberId / 父行 / 席位号来"加到别人队里"——所有归属校验都在
 * {@code MarketingExtraSeatServiceImpl} 里按原单与原 registration 核对。
 */
public class MarketingExtraSeatQuoteRequest {

    private Long productId;
    /** 被加座的原商品订单号；必须是本人在本域已付、非礼品、非加座的订单。 */
    private String originalOrderNo;
    /** 这一位加座给谁（必需）：写成码领取会让"同一人重复加座"无法在服务端拦。 */
    private Long recipientMemberId;

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public String getOriginalOrderNo() { return originalOrderNo; }
    public void setOriginalOrderNo(String originalOrderNo) { this.originalOrderNo = originalOrderNo; }

    public Long getRecipientMemberId() { return recipientMemberId; }
    public void setRecipientMemberId(Long recipientMemberId) { this.recipientMemberId = recipientMemberId; }
}
