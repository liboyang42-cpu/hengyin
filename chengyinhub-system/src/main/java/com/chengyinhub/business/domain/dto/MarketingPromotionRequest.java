package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 促销报价的输入:报价侧与建单侧<b>同一份</b>入参,保证两边算出同一个数。
 *
 * <p>行数据由调用方(报价/建单)从票真源读好后传入,本类不做任何 DB 访问;
 * 客户端能影响的只有 {@code offerCode} 一个"意图"字段,金额与资格一律服务端算。
 *
 * <p>没有送礼字段:礼品独立售价由 M3 的礼品 SKU 承接(2026-11-08 root 裁定),
 * 报名链不做送礼,也就不存在"谁来收礼"的入参。
 */
public class MarketingPromotionRequest {

    private Long memberId;
    /** 1=主题 2=活动,与 cms_registration.owner_type 对齐。 */
    private Integer ownerType;
    private Long ownerId;
    private List<Line> lines = new ArrayList<>();
    /** 选择的组合包业务码;空 = 不选。 */
    private String offerCode;

    public static class Line {
        private Long ticketId;
        private Integer quantity;
        /** 票面现价(元),来自 oms_ticket.price,不来自客户端。 */
        private BigDecimal unitListPrice;

        public Line() {
        }

        public Line(Long ticketId, Integer quantity, BigDecimal unitListPrice) {
            this.ticketId = ticketId;
            this.quantity = quantity;
            this.unitListPrice = unitListPrice;
        }

        public Long getTicketId() { return ticketId; }
        public void setTicketId(Long ticketId) { this.ticketId = ticketId; }
        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }
        public BigDecimal getUnitListPrice() { return unitListPrice; }
        public void setUnitListPrice(BigDecimal unitListPrice) { this.unitListPrice = unitListPrice; }
    }

    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }

    public Integer getOwnerType() { return ownerType; }
    public void setOwnerType(Integer ownerType) { this.ownerType = ownerType; }

    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }

    public List<Line> getLines() { return lines; }
    public void setLines(List<Line> lines) { this.lines = lines == null ? new ArrayList<>() : lines; }

    public String getOfferCode() { return offerCode; }
    public void setOfferCode(String offerCode) { this.offerCode = offerCode; }
}
