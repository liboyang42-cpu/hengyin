package com.chengyinhub.business.domain.dto;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 定价预览结果(报告§5.4)。系统算底价 P_min,发布者在其上定终价。
 */
public class PricingResult implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 平台佣金率(小数,如 0.10) */
    private BigDecimal platformRate;

    /** Σ 分成型比例(小数,如 0.30) */
    private BigDecimal sumShareRate;

    /** Σ 固定型每核销人头费 */
    private BigDecimal sumFixedFee;

    /** 带队成本摊到每人(guided 票才 >0) */
    private BigDecimal leadCostPerHead;

    /** 发布者自担的权益成本(通常 0) */
    private BigDecimal perkCost;

    /** 每人成本 C */
    private BigDecimal costC;

    /** 最低售价 P_min(地板价) */
    private BigDecimal priceMin;

    /** 票种 guided/self */
    private String subType;

    /** 阵容明细 */
    private List<Lineup> lineup;

    /** 同城同类已开售样本均价；样本不可可靠识别时为空。 */
    private BigDecimal cityReferencePrice;
    private Integer cityReferenceSampleSize;
    private String cityReferenceLabel;

    /** 主题此刻能否确认终价(lifecycle==2 定价阶段)。与 confirmPrice 同一判据,供前端事先置灰。 */
    private Boolean canConfirm;

    /** canConfirm=false 时的原因,与 confirmPrice 拒绝时的原话一致;可确认时为空。 */
    private String confirmBlockReason;

    public static class Lineup implements Serializable {
        private static final long serialVersionUID = 1L;
        private Long toId;
        private String toType;
        private Integer shareMode;
        private BigDecimal shareRate;
        private BigDecimal fixedFee;

        public Long getToId() { return toId; }
        public void setToId(Long toId) { this.toId = toId; }
        public String getToType() { return toType; }
        public void setToType(String toType) { this.toType = toType; }
        public Integer getShareMode() { return shareMode; }
        public void setShareMode(Integer shareMode) { this.shareMode = shareMode; }
        public BigDecimal getShareRate() { return shareRate; }
        public void setShareRate(BigDecimal shareRate) { this.shareRate = shareRate; }
        public BigDecimal getFixedFee() { return fixedFee; }
        public void setFixedFee(BigDecimal fixedFee) { this.fixedFee = fixedFee; }
    }

    public BigDecimal getPlatformRate() { return platformRate; }
    public void setPlatformRate(BigDecimal platformRate) { this.platformRate = platformRate; }
    public BigDecimal getSumShareRate() { return sumShareRate; }
    public void setSumShareRate(BigDecimal sumShareRate) { this.sumShareRate = sumShareRate; }
    public BigDecimal getSumFixedFee() { return sumFixedFee; }
    public void setSumFixedFee(BigDecimal sumFixedFee) { this.sumFixedFee = sumFixedFee; }
    public BigDecimal getLeadCostPerHead() { return leadCostPerHead; }
    public void setLeadCostPerHead(BigDecimal leadCostPerHead) { this.leadCostPerHead = leadCostPerHead; }
    public BigDecimal getPerkCost() { return perkCost; }
    public void setPerkCost(BigDecimal perkCost) { this.perkCost = perkCost; }
    public BigDecimal getCostC() { return costC; }
    public void setCostC(BigDecimal costC) { this.costC = costC; }
    public BigDecimal getPriceMin() { return priceMin; }
    public void setPriceMin(BigDecimal priceMin) { this.priceMin = priceMin; }
    public String getSubType() { return subType; }
    public void setSubType(String subType) { this.subType = subType; }
    public List<Lineup> getLineup() { return lineup; }
    public void setLineup(List<Lineup> lineup) { this.lineup = lineup; }
    public BigDecimal getCityReferencePrice() { return cityReferencePrice; }
    public void setCityReferencePrice(BigDecimal cityReferencePrice) { this.cityReferencePrice = cityReferencePrice; }
    public Integer getCityReferenceSampleSize() { return cityReferenceSampleSize; }
    public void setCityReferenceSampleSize(Integer cityReferenceSampleSize) { this.cityReferenceSampleSize = cityReferenceSampleSize; }
    public String getCityReferenceLabel() { return cityReferenceLabel; }
    public void setCityReferenceLabel(String cityReferenceLabel) { this.cityReferenceLabel = cityReferenceLabel; }
    public Boolean getCanConfirm() { return canConfirm; }
    public void setCanConfirm(Boolean canConfirm) { this.canConfirm = canConfirm; }
    public String getConfirmBlockReason() { return confirmBlockReason; }
    public void setConfirmBlockReason(String confirmBlockReason) { this.confirmBlockReason = confirmBlockReason; }
}
