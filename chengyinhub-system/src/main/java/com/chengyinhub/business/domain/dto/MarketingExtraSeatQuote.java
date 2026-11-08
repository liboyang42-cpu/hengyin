package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 加座（route=3）报价结果。价格全部由服务端按商品行重算，客户端只拿签名回传下单。
 *
 * <p>与普通商品报价的差别只有三处，都是加座特有的合同：
 * 1. 必须带原订单号与受赠人，签名把二者一起冻结；
 * 2. 一份加座 = 一位，{@code quantity} 恒为 1（N 位 = N 笔独立付款）；
 * 3. 显式回报 {@code partyBinding=NOT_APPLICABLE}——自玩域没有队伍名册，
 *    这一位不会"加入原队"，只取得自己那份准入。
 */
public class MarketingExtraSeatQuote {

    private Long productId;
    private String productCode;
    private String productName;
    private Integer productKind;
    private Long productRevision;
    private Integer fulfillmentKind;
    private Integer quantity;
    private BigDecimal unitCashPrice;
    private Integer unitPointsPrice;
    private BigDecimal totalCashAmount;
    private Integer totalPointsAmount;
    private String currency;
    private Integer seatCount;
    private String termsVersion;

    private String originalOrderNo;
    private Integer originalPaidSeats;
    /** 恒为 null：自玩增购没有可读容量资源，客户端不得据此置灰按钮。 */
    private Integer partyCapacity;
    /** 恒为 null：同上，不按任何 team 配置推算剩余位。 */
    private Integer seatsRemaining;
    private Long recipientMemberId;

    private String partyBinding;
    private String partyBindingReason;
    private String admissionMode;
    private Boolean admissionRequiresClaim;

    private List<Integer> availablePayModes;
    private String cashUnavailableReason;
    private String quoteSign;
    private Date quoteExpiresAt;

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

    public Integer getSeatCount() { return seatCount; }
    public void setSeatCount(Integer seatCount) { this.seatCount = seatCount; }

    public String getTermsVersion() { return termsVersion; }
    public void setTermsVersion(String termsVersion) { this.termsVersion = termsVersion; }

    public String getOriginalOrderNo() { return originalOrderNo; }
    public void setOriginalOrderNo(String originalOrderNo) { this.originalOrderNo = originalOrderNo; }

    public Integer getOriginalPaidSeats() { return originalPaidSeats; }
    public void setOriginalPaidSeats(Integer originalPaidSeats) { this.originalPaidSeats = originalPaidSeats; }

    public Integer getPartyCapacity() { return partyCapacity; }
    public void setPartyCapacity(Integer partyCapacity) { this.partyCapacity = partyCapacity; }

    public Integer getSeatsRemaining() { return seatsRemaining; }
    public void setSeatsRemaining(Integer seatsRemaining) { this.seatsRemaining = seatsRemaining; }

    public Long getRecipientMemberId() { return recipientMemberId; }
    public void setRecipientMemberId(Long recipientMemberId) { this.recipientMemberId = recipientMemberId; }

    public String getPartyBinding() { return partyBinding; }
    public void setPartyBinding(String partyBinding) { this.partyBinding = partyBinding; }

    public String getPartyBindingReason() { return partyBindingReason; }
    public void setPartyBindingReason(String partyBindingReason) { this.partyBindingReason = partyBindingReason; }

    public String getAdmissionMode() { return admissionMode; }
    public void setAdmissionMode(String admissionMode) { this.admissionMode = admissionMode; }

    public Boolean getAdmissionRequiresClaim() { return admissionRequiresClaim; }
    public void setAdmissionRequiresClaim(Boolean admissionRequiresClaim) { this.admissionRequiresClaim = admissionRequiresClaim; }

    public List<Integer> getAvailablePayModes() { return availablePayModes; }
    public void setAvailablePayModes(List<Integer> availablePayModes) { this.availablePayModes = availablePayModes; }

    public String getCashUnavailableReason() { return cashUnavailableReason; }
    public void setCashUnavailableReason(String cashUnavailableReason) { this.cashUnavailableReason = cashUnavailableReason; }

    public String getQuoteSign() { return quoteSign; }
    public void setQuoteSign(String quoteSign) { this.quoteSign = quoteSign; }

    public Date getQuoteExpiresAt() { return quoteExpiresAt; }
    public void setQuoteExpiresAt(Date quoteExpiresAt) { this.quoteExpiresAt = quoteExpiresAt; }
}
