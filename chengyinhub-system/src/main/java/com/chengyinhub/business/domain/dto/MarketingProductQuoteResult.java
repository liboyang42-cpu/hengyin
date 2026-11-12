package com.chengyinhub.business.domain.dto;

import com.chengyinhub.business.domain.PassBundleView;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/** 商品报价结果。availablePayModes 只报告服务端真能完成的通道。 */
public class MarketingProductQuoteResult {

    private String quoteSign;
    private Date quoteExpiresAt;
    private Long productId;
    private String productCode;
    private String productName;
    private Integer productKind;
    private Integer purchaseKind;
    private Boolean exploreCapacity;
    /** 1=SELF_PLAY_GRANT 2=TICKET_REGISTRATION 3=EXTRA_SEAT_GRANT */
    private Integer purchaseRoute;
    /** route=2 时前端应转入既有报名结算；此处只给提示码，不给可成交按钮 */
    private String purchaseRouteReason;
    /** route=2 时转入既有报名结算所需的真实标识（ownerType/ownerId/ticketId/tickets）。 */
    private java.util.Map<String, Object> checkout;
    private Long productRevision;
    private Integer fulfillmentKind;
    private Integer quantity;
    private BigDecimal unitCashPrice;
    private Integer unitPointsPrice;
    private BigDecimal totalCashAmount;
    private Integer totalPointsAmount;
    private String currency;
    private String pointsCurrency;
    private Integer validityKind;
    private Integer validDays;
    private Date fixedValidFrom;
    private Date fixedValidTo;
    private Boolean activatesOnFirstUse;
    private Integer seatCount;
    private String termsVersion;
    private List<PassBundleView.Item> items;
    private List<Integer> availablePayModes;
    private String cashUnavailableReason;
    private String giftClaimMode;

    public String getQuoteSign() { return quoteSign; }
    public void setQuoteSign(String quoteSign) { this.quoteSign = quoteSign; }

    public Date getQuoteExpiresAt() { return quoteExpiresAt; }
    public void setQuoteExpiresAt(Date quoteExpiresAt) { this.quoteExpiresAt = quoteExpiresAt; }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public Integer getProductKind() { return productKind; }
    public void setProductKind(Integer productKind) { this.productKind = productKind; }

    public Integer getPurchaseKind() { return purchaseKind; }
    public void setPurchaseKind(Integer purchaseKind) { this.purchaseKind = purchaseKind; }

    public Boolean getExploreCapacity() { return exploreCapacity; }
    public void setExploreCapacity(Boolean exploreCapacity) { this.exploreCapacity = exploreCapacity; }

    public Integer getPurchaseRoute() { return purchaseRoute; }
    public void setPurchaseRoute(Integer purchaseRoute) { this.purchaseRoute = purchaseRoute; }

    public String getPurchaseRouteReason() { return purchaseRouteReason; }
    public void setPurchaseRouteReason(String purchaseRouteReason) { this.purchaseRouteReason = purchaseRouteReason; }

    public java.util.Map<String, Object> getCheckout() { return checkout; }
    public void setCheckout(java.util.Map<String, Object> checkout) { this.checkout = checkout; }

    public Long getProductRevision() { return productRevision; }
    public void setProductRevision(Long productRevision) { this.productRevision = productRevision; }

    public Integer getFulfillmentKind() { return fulfillmentKind; }
    public void setFulfillmentKind(Integer fulfillmentKind) { this.fulfillmentKind = fulfillmentKind; }

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

    public String getPointsCurrency() { return pointsCurrency; }
    public void setPointsCurrency(String pointsCurrency) { this.pointsCurrency = pointsCurrency; }

    public Integer getValidityKind() { return validityKind; }
    public void setValidityKind(Integer validityKind) { this.validityKind = validityKind; }

    public Integer getValidDays() { return validDays; }
    public void setValidDays(Integer validDays) { this.validDays = validDays; }

    public Date getFixedValidFrom() { return fixedValidFrom; }
    public void setFixedValidFrom(Date fixedValidFrom) { this.fixedValidFrom = fixedValidFrom; }

    public Date getFixedValidTo() { return fixedValidTo; }
    public void setFixedValidTo(Date fixedValidTo) { this.fixedValidTo = fixedValidTo; }

    public Boolean getActivatesOnFirstUse() { return activatesOnFirstUse; }
    public void setActivatesOnFirstUse(Boolean activatesOnFirstUse) { this.activatesOnFirstUse = activatesOnFirstUse; }

    public Integer getSeatCount() { return seatCount; }
    public void setSeatCount(Integer seatCount) { this.seatCount = seatCount; }

    public String getTermsVersion() { return termsVersion; }
    public void setTermsVersion(String termsVersion) { this.termsVersion = termsVersion; }

    public List<PassBundleView.Item> getItems() { return items; }
    public void setItems(List<PassBundleView.Item> items) { this.items = items; }

    public List<Integer> getAvailablePayModes() { return availablePayModes; }
    public void setAvailablePayModes(List<Integer> availablePayModes) { this.availablePayModes = availablePayModes; }

    public String getCashUnavailableReason() { return cashUnavailableReason; }
    public void setCashUnavailableReason(String cashUnavailableReason) { this.cashUnavailableReason = cashUnavailableReason; }

    public String getGiftClaimMode() { return giftClaimMode; }
    public void setGiftClaimMode(String giftClaimMode) { this.giftClaimMode = giftClaimMode; }

}
