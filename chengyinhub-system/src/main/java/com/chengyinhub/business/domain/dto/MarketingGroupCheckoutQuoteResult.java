package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 团体/学校商品报价结果。
 *
 * <p>{@link #quote} 是既有 {@code /api/registration/quote} 的原始结果，成交金额、
 * 积分抵扣与 quoteSign 都以它为准；本对象的 catalogCashPrice/catalogPointsPrice
 * 只是商品目录展示价，不得当作实付。
 */
public class MarketingGroupCheckoutQuoteResult {

    private Long productId;
    private String productCode;
    private String productName;
    private Integer productKind;
    private Long productRevision;
    private String termsVersion;
    /** 1=SELF_PLAY_GRANT 2=TICKET_REGISTRATION 3=EXTRA_SEAT_GRANT；本链路恒为 2。 */
    private Integer purchaseRoute;
    private String purchaseRouteReason;
    /** 金额唯一权威：REGISTRATION_CHECKOUT。 */
    private String amountAuthority;
    /** 目录展示价，不参与成交。 */
    private BigDecimal catalogCashPrice;
    private Integer catalogPointsPrice;
    /** 商品配置的每份席位数，仅展示；不改变购买张数。 */
    private Integer seatCount;

    private Integer quantity;
    private String participateDate;
    private Integer isUsePoint;
    private String offerCode;

    /** 原报名标的：恒为活动(2)。 */
    private Integer ownerType;
    private Long activityId;
    private Long topicId;
    private Long ticketId;

    /** 场次事实，来自原 oms_ticket；过期/售罄与否仍由原报价/建单闸判定。 */
    private Date sessionStartAt;
    private Date sessionEndAt;
    private Date signupDeadline;
    private Long remainingInventory;

    /** 客户端下一步应调用的端点（同一商品绑定，不重复传 owner/ticket）。 */
    private String quoteEndpoint;
    private String createEndpoint;

    /** 原报名报价结果（含 quoteSign / payAmount / pointsUsed / marketingSign）。 */
    private RegistrationQuoteResult quote;

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public Integer getProductKind() { return productKind; }
    public void setProductKind(Integer productKind) { this.productKind = productKind; }

    public Long getProductRevision() { return productRevision; }
    public void setProductRevision(Long productRevision) { this.productRevision = productRevision; }

    public String getTermsVersion() { return termsVersion; }
    public void setTermsVersion(String termsVersion) { this.termsVersion = termsVersion; }

    public Integer getPurchaseRoute() { return purchaseRoute; }
    public void setPurchaseRoute(Integer purchaseRoute) { this.purchaseRoute = purchaseRoute; }

    public String getPurchaseRouteReason() { return purchaseRouteReason; }
    public void setPurchaseRouteReason(String purchaseRouteReason) { this.purchaseRouteReason = purchaseRouteReason; }

    public String getAmountAuthority() { return amountAuthority; }
    public void setAmountAuthority(String amountAuthority) { this.amountAuthority = amountAuthority; }

    public BigDecimal getCatalogCashPrice() { return catalogCashPrice; }
    public void setCatalogCashPrice(BigDecimal catalogCashPrice) { this.catalogCashPrice = catalogCashPrice; }

    public Integer getCatalogPointsPrice() { return catalogPointsPrice; }
    public void setCatalogPointsPrice(Integer catalogPointsPrice) { this.catalogPointsPrice = catalogPointsPrice; }

    public Integer getSeatCount() { return seatCount; }
    public void setSeatCount(Integer seatCount) { this.seatCount = seatCount; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public String getParticipateDate() { return participateDate; }
    public void setParticipateDate(String participateDate) { this.participateDate = participateDate; }

    public Integer getIsUsePoint() { return isUsePoint; }
    public void setIsUsePoint(Integer isUsePoint) { this.isUsePoint = isUsePoint; }

    public String getOfferCode() { return offerCode; }
    public void setOfferCode(String offerCode) { this.offerCode = offerCode; }

    public Integer getOwnerType() { return ownerType; }
    public void setOwnerType(Integer ownerType) { this.ownerType = ownerType; }

    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }

    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }

    public Long getTicketId() { return ticketId; }
    public void setTicketId(Long ticketId) { this.ticketId = ticketId; }

    public Date getSessionStartAt() { return sessionStartAt; }
    public void setSessionStartAt(Date sessionStartAt) { this.sessionStartAt = sessionStartAt; }

    public Date getSessionEndAt() { return sessionEndAt; }
    public void setSessionEndAt(Date sessionEndAt) { this.sessionEndAt = sessionEndAt; }

    public Date getSignupDeadline() { return signupDeadline; }
    public void setSignupDeadline(Date signupDeadline) { this.signupDeadline = signupDeadline; }

    public Long getRemainingInventory() { return remainingInventory; }
    public void setRemainingInventory(Long remainingInventory) { this.remainingInventory = remainingInventory; }

    public String getQuoteEndpoint() { return quoteEndpoint; }
    public void setQuoteEndpoint(String quoteEndpoint) { this.quoteEndpoint = quoteEndpoint; }

    public String getCreateEndpoint() { return createEndpoint; }
    public void setCreateEndpoint(String createEndpoint) { this.createEndpoint = createEndpoint; }

    public RegistrationQuoteResult getQuote() { return quote; }
    public void setQuote(RegistrationQuoteResult quote) { this.quote = quote; }
}
