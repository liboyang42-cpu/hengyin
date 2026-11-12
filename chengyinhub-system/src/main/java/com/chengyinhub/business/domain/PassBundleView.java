package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 公开可售读面的商品包视图（PassBundle 前缀）。
 *
 * <p>只含公开字段：商品名、价格、有效期、作品标题与冻结修订，不含题目正文、
 * 答案、商家秘密或未发布内容。客户端不能据此自行计算折扣或已支付状态。
 */
public class PassBundleView {

    private Long productId;
    private String productCode;
    private String productName;
    private Integer productKind;
    private Integer purchaseKind;
    private Boolean exploreCapacity;
    /** 1=SELF_PLAY_GRANT 2=TICKET_REGISTRATION 3=EXTRA_SEAT_GRANT */
    private Integer purchaseRoute;
    private String saleCity;
    private Long revision;
    private String termsVersion;
    private BigDecimal cashPrice;
    private Integer pointsPrice;
    private Integer validityKind;
    private Integer validDays;
    private Date fixedValidFrom;
    private Date fixedValidTo;
    private Integer seatCount;
    private Integer giftAllowed;
    private Integer saleStatus;
    private Date saleStartAt;
    private Date saleEndAt;
    private List<Item> items = new ArrayList<>();

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

    public String getSaleCity() { return saleCity; }
    public void setSaleCity(String saleCity) { this.saleCity = saleCity; }

    public Long getRevision() { return revision; }
    public void setRevision(Long revision) { this.revision = revision; }

    public String getTermsVersion() { return termsVersion; }
    public void setTermsVersion(String termsVersion) { this.termsVersion = termsVersion; }

    public BigDecimal getCashPrice() { return cashPrice; }
    public void setCashPrice(BigDecimal cashPrice) { this.cashPrice = cashPrice; }

    public Integer getPointsPrice() { return pointsPrice; }
    public void setPointsPrice(Integer pointsPrice) { this.pointsPrice = pointsPrice; }

    public Integer getValidityKind() { return validityKind; }
    public void setValidityKind(Integer validityKind) { this.validityKind = validityKind; }

    public Integer getValidDays() { return validDays; }
    public void setValidDays(Integer validDays) { this.validDays = validDays; }

    public Date getFixedValidFrom() { return fixedValidFrom; }
    public void setFixedValidFrom(Date fixedValidFrom) { this.fixedValidFrom = fixedValidFrom; }

    public Date getFixedValidTo() { return fixedValidTo; }
    public void setFixedValidTo(Date fixedValidTo) { this.fixedValidTo = fixedValidTo; }

    public Integer getSeatCount() { return seatCount; }
    public void setSeatCount(Integer seatCount) { this.seatCount = seatCount; }

    public Integer getGiftAllowed() { return giftAllowed; }
    public void setGiftAllowed(Integer giftAllowed) { this.giftAllowed = giftAllowed; }

    public Integer getSaleStatus() { return saleStatus; }
    public void setSaleStatus(Integer saleStatus) { this.saleStatus = saleStatus; }

    public Date getSaleStartAt() { return saleStartAt; }
    public void setSaleStartAt(Date saleStartAt) { this.saleStartAt = saleStartAt; }

    public Date getSaleEndAt() { return saleEndAt; }
    public void setSaleEndAt(Date saleEndAt) { this.saleEndAt = saleEndAt; }

    public List<Item> getItems() { return items; }
    public void setItems(List<Item> items) { this.items = items; }

    /** 公开作品条目；不含正文或答案。 */
    public static class Item {
        private Long topicId;
        private String topicTitle;
        private Long contentRevision;

        public Item() { }

        public Item(Long topicId, String topicTitle, Long contentRevision) {
            this.topicId = topicId;
            this.topicTitle = topicTitle;
            this.contentRevision = contentRevision;
        }

        public Long getTopicId() { return topicId; }
        public void setTopicId(Long topicId) { this.topicId = topicId; }

        public String getTopicTitle() { return topicTitle; }
        public void setTopicTitle(String topicTitle) { this.topicTitle = topicTitle; }

        public Long getContentRevision() { return contentRevision; }
        public void setContentRevision(Long contentRevision) { this.contentRevision = contentRevision; }
    }
}
