package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 商品订单 marketing_product_order（C06 商品 / C07 支付事实的交接行）。
 *
 * <p><b>现金与积分是真两列</b>：现金单只有在调用方提交已验证的微信支付事实后才会
 * 从 {@link #STATUS_PENDING_CASH} 进入 {@link #STATUS_PAID}；积分单走现有
 * {@code ums_member.point} 扣减 + {@code ums_member_points_detail} 流水，流水 id 写入
 * {@link #paymentFactId}。不新增钱包，不伪造 PAID。
 *
 * <p>本表不承载退款金额与结算；退款撤销只消费 C07 已经成立的事实，把订单标记为
 * {@link #STATUS_REFUNDED} 并驱动授权撤销。
 */
public class MarketingProductOrder {

    public static final int FULFILLMENT_SELF = 1;
    public static final int FULFILLMENT_GIFT = 2;
    public static final int FULFILLMENT_EXTRA_SEAT = 3;

    public static final int PAY_MODE_CASH = 1;
    public static final int PAY_MODE_POINTS = 2;

    public static final int STATUS_PENDING_CASH = 0;
    public static final int STATUS_PAID = 1;
    public static final int STATUS_CANCELLED = 2;
    public static final int STATUS_REFUNDED = 3;

    public static final int PAYMENT_FACT_WECHAT = 1;
    public static final int PAYMENT_FACT_POINTS = 2;

    public static final int GRANT_PENDING = 0;
    public static final int GRANT_DONE = 1;
    public static final int GRANT_AWAITING_ACTIVATION = 2;

    private Long id;
    private String orderNo;
    private Long memberId;
    private Long productId;
    private Long productRevision;
    private Integer fulfillmentKind;
    private Long giftRecipientMemberId;
    private Long originalOrderId;
    private Integer payMode;
    private Integer quantity;
    private BigDecimal unitCashPrice;
    private Integer unitPointsPrice;
    private BigDecimal totalCashAmount;
    private Integer totalPointsAmount;
    private String currency;
    private String productSnapshotJson;
    private String quoteSign;
    private Date quoteExpiresAt;
    private Integer status;
    private Integer paymentFactType;
    private Long paymentFactId;
    /** M2-F 权威 grant 产生的父 cms_registration.id；营销单只是映射，不是第二订单真源。 */
    private Long primaryRegistrationId;
    private String primaryRegistrationNo;
    private String registrationIdsJson;
    private Integer fulfillmentState;
    private Date paidAt;
    private Date refundedAt;
    private String revokedReason;
    private String requestId;
    private String requestFingerprint;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }

    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public Long getProductRevision() { return productRevision; }
    public void setProductRevision(Long productRevision) { this.productRevision = productRevision; }

    public Integer getFulfillmentKind() { return fulfillmentKind; }
    public void setFulfillmentKind(Integer fulfillmentKind) { this.fulfillmentKind = fulfillmentKind; }

    public Long getGiftRecipientMemberId() { return giftRecipientMemberId; }
    public void setGiftRecipientMemberId(Long giftRecipientMemberId) { this.giftRecipientMemberId = giftRecipientMemberId; }

    public Long getOriginalOrderId() { return originalOrderId; }
    public void setOriginalOrderId(Long originalOrderId) { this.originalOrderId = originalOrderId; }

    public Integer getPayMode() { return payMode; }
    public void setPayMode(Integer payMode) { this.payMode = payMode; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public BigDecimal getUnitCashPrice() { return unitCashPrice; }
    public void setUnitCashPrice(BigDecimal unitCashPrice) { this.unitCashPrice = unitCashPrice; }

    public Integer getUnitPointsPrice() { return unitPointsPrice; }
    public void setUnitPointsPrice(Integer unitPointsPrice) { this.unitPointsPrice = unitPointsPrice; }

    public BigDecimal getTotalCashAmount() { return totalCashAmount; }
    public void setTotalCashAmount(BigDecimal totalCashAmount) { this.totalCashAmount = totalCashAmount; }

    public Integer getTotalPointsAmount() { return totalPointsAmount; }
    public void setTotalPointsAmount(Integer totalPointsAmount) { this.totalPointsAmount = totalPointsAmount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getProductSnapshotJson() { return productSnapshotJson; }
    public void setProductSnapshotJson(String productSnapshotJson) { this.productSnapshotJson = productSnapshotJson; }

    public String getQuoteSign() { return quoteSign; }
    public void setQuoteSign(String quoteSign) { this.quoteSign = quoteSign; }

    public Date getQuoteExpiresAt() { return quoteExpiresAt; }
    public void setQuoteExpiresAt(Date quoteExpiresAt) { this.quoteExpiresAt = quoteExpiresAt; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public Integer getPaymentFactType() { return paymentFactType; }
    public void setPaymentFactType(Integer paymentFactType) { this.paymentFactType = paymentFactType; }

    public Long getPaymentFactId() { return paymentFactId; }
    public void setPaymentFactId(Long paymentFactId) { this.paymentFactId = paymentFactId; }

    public Long getPrimaryRegistrationId() { return primaryRegistrationId; }
    public void setPrimaryRegistrationId(Long primaryRegistrationId) { this.primaryRegistrationId = primaryRegistrationId; }

    public String getPrimaryRegistrationNo() { return primaryRegistrationNo; }
    public void setPrimaryRegistrationNo(String primaryRegistrationNo) { this.primaryRegistrationNo = primaryRegistrationNo; }

    public String getRegistrationIdsJson() { return registrationIdsJson; }
    public void setRegistrationIdsJson(String registrationIdsJson) { this.registrationIdsJson = registrationIdsJson; }

    public Integer getFulfillmentState() { return fulfillmentState; }
    public void setFulfillmentState(Integer fulfillmentState) { this.fulfillmentState = fulfillmentState; }

    public Date getPaidAt() { return paidAt; }
    public void setPaidAt(Date paidAt) { this.paidAt = paidAt; }

    public Date getRefundedAt() { return refundedAt; }
    public void setRefundedAt(Date refundedAt) { this.refundedAt = refundedAt; }

    public String getRevokedReason() { return revokedReason; }
    public void setRevokedReason(String revokedReason) { this.revokedReason = revokedReason; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String requestFingerprint) { this.requestFingerprint = requestFingerprint; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
