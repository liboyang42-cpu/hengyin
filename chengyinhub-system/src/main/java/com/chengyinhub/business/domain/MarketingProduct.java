package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 可售商品目录 marketing_product（C06 商品侧，<b>仅目录/配置</b>）。
 *
 * <p>裁决 A：真正的订单/权益/席位在 {@code cms_registration} 与
 * {@code registration_entitlement}。本表不承载可玩授权、支付状态或资金真源。
 *
 * <p>{@link #purchaseKind} / {@link #exploreCapacity} 是 M3 提供给 M2-F
 * 的唯一 kind→{@code PurchaseKind} 映射来源：普通自玩/城市包/时限包/年卡恒为
 * {@code SELF_PLAY_PASS(2)} 且 {@code exploreCapacity=0}，不占③探索票章节容量；
 * 只有真正探索票才 {@code EXPLORE_PASS(3)} + {@code exploreCapacity=1}。
 */
public class MarketingProduct {

    public static final int KIND_SINGLE_QUEST = 1;
    public static final int KIND_CITY_BUNDLE = 2;
    public static final int KIND_TIME_LIMITED_PASS = 3;
    public static final int KIND_FIXED_TERM_PASS = 4;
    public static final int KIND_GROUP_TEAM = 5;
    public static final int KIND_SCHOOL_GROUP = 6;
    public static final int KIND_GIFT_PASS = 7;
    public static final int KIND_EXTRA_SEAT = 8;

    public static final int VALIDITY_NONE = 0;
    public static final int VALIDITY_RELATIVE_DAYS = 1;
    public static final int VALIDITY_FIXED_WINDOW = 2;
    public static final int VALIDITY_ACTIVATE_ON_FIRST_USE = 3;

    /** 1 = M2F 自玩/pass grant；2 = 既有 ticket 报名；3 = 加座 grant。 */
    public static final int ROUTE_SELF_PLAY_GRANT = 1;
    public static final int ROUTE_TICKET_REGISTRATION = 2;
    public static final int ROUTE_EXTRA_SEAT_GRANT = 3;

    public static final int STATUS_DRAFT = 0;
    public static final int STATUS_ON_SALE = 1;
    public static final int STATUS_OFF_SALE = 2;

    private Long id;
    private String productCode;
    private String productName;
    private Integer productKind;
    /** 落 cms_registration.purchase_kind：2=自玩通行证 3=探索票。 */
    private Integer purchaseKind;
    /** 1=该行是③探索票，才允许 reserveEntitlements 占章节容量。 */
    private Integer exploreCapacity;
    private String saleCity;
    private Long parentProductId;
    /** 履约路由：决定走 M2F grant 还是既有 ticket 报名；见 ROUTE_*。 */
    private Integer fulfillmentRoute;
    /** route=2 时绑定的既有 oms_ticket.id；席位/团体由原链路承载。 */
    private Long ticketId;
    private BigDecimal cashPrice;
    private Integer pointsPrice;
    private Integer validityKind;
    private Integer validDays;
    private Date fixedValidFrom;
    private Date fixedValidTo;
    private Integer seatCount;
    private Integer giftAllowed;
    private Integer giftClaimDays;
    private Integer status;
    private Date saleStartAt;
    private Date saleEndAt;
    private String termsVersion;
    private Long revision;
    private Date createTime;
    private Date updateTime;

    /** 该商品覆盖的行是否占③探索票章节容量。 */
    public boolean occupiesExploreCapacity() {
        return exploreCapacity != null && exploreCapacity == 1;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public Integer getProductKind() { return productKind; }
    public void setProductKind(Integer productKind) { this.productKind = productKind; }

    public Integer getPurchaseKind() { return purchaseKind; }
    public void setPurchaseKind(Integer purchaseKind) { this.purchaseKind = purchaseKind; }

    public Integer getExploreCapacity() { return exploreCapacity; }
    public void setExploreCapacity(Integer exploreCapacity) { this.exploreCapacity = exploreCapacity; }

    public String getSaleCity() { return saleCity; }
    public void setSaleCity(String saleCity) { this.saleCity = saleCity; }

    public Long getParentProductId() { return parentProductId; }
    public void setParentProductId(Long parentProductId) { this.parentProductId = parentProductId; }

    public Integer getFulfillmentRoute() { return fulfillmentRoute; }
    public void setFulfillmentRoute(Integer fulfillmentRoute) { this.fulfillmentRoute = fulfillmentRoute; }

    public Long getTicketId() { return ticketId; }
    public void setTicketId(Long ticketId) { this.ticketId = ticketId; }

    /** 该 SKU 是否必须走既有 ticket 报名链路（团体/学校）。 */
    public boolean usesTicketRegistration() {
        return fulfillmentRoute != null && fulfillmentRoute == ROUTE_TICKET_REGISTRATION;
    }

    /** 该 SKU 是加座（独立订单 + 加座 grant，不改原单数量）。 */
    public boolean isExtraSeatRoute() {
        return fulfillmentRoute != null && fulfillmentRoute == ROUTE_EXTRA_SEAT_GRANT;
    }

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

    public Integer getGiftClaimDays() { return giftClaimDays; }
    public void setGiftClaimDays(Integer giftClaimDays) { this.giftClaimDays = giftClaimDays; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public Date getSaleStartAt() { return saleStartAt; }
    public void setSaleStartAt(Date saleStartAt) { this.saleStartAt = saleStartAt; }

    public Date getSaleEndAt() { return saleEndAt; }
    public void setSaleEndAt(Date saleEndAt) { this.saleEndAt = saleEndAt; }

    public String getTermsVersion() { return termsVersion; }
    public void setTermsVersion(String termsVersion) { this.termsVersion = termsVersion; }

    public Long getRevision() { return revision; }
    public void setRevision(Long revision) { this.revision = revision; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
