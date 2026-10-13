package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/** 探店日期级成本与 CM/N_BE 必要输入；版本只追加。 */
public class TopicEditionCost {
    private Long id;
    private Long topicId;
    private Long version;
    private Integer current;
    private BigDecimal taxCost;
    private BigDecimal paymentCost;
    private BigDecimal materialCost;
    private BigDecimal kitCost;
    private BigDecimal insuranceRiskCost;
    private BigDecimal acquisitionCost;
    private BigDecimal platformLaborCost;
    private BigDecimal otherCost;
    private BigDecimal platformOnsiteHours;
    private BigDecimal fixedCompensation;
    private BigDecimal bonuses;
    private BigDecimal breakEvenFixedCost;
    private BigDecimal variableCostPerTicket;
    /**
     * 承接费的每已赚票单价（2026-08-25 换轨，取代按工时的 EXTRA）。开售时冻进条款。
     * 0 = 这一期只发保底 F，是合法配置。
     */
    private BigDecimal headcountRate;
    /** 承接费本期封顶。{@code null} = 不封顶。 */
    private BigDecimal headcountCap;
    /** 本期项目负责人 sys_user.user_id：EXTRA 事前审批人，开售时冻进条款与 configHash。 */
    private Long extraApproverUserId;
    private String note;
    private Date createdAt;

    public BigDecimal platformCostTotal() {
        return amount(taxCost).add(amount(paymentCost)).add(amount(materialCost)).add(amount(kitCost))
                .add(amount(insuranceRiskCost)).add(amount(acquisitionCost))
                .add(amount(platformLaborCost)).add(amount(otherCost));
    }

    private static BigDecimal amount(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public Integer getCurrent() { return current; }
    public void setCurrent(Integer current) { this.current = current; }
    public BigDecimal getTaxCost() { return taxCost; }
    public void setTaxCost(BigDecimal v) { this.taxCost = v; }
    public BigDecimal getPaymentCost() { return paymentCost; }
    public void setPaymentCost(BigDecimal v) { this.paymentCost = v; }
    public BigDecimal getMaterialCost() { return materialCost; }
    public void setMaterialCost(BigDecimal v) { this.materialCost = v; }
    public BigDecimal getKitCost() { return kitCost; }
    public void setKitCost(BigDecimal v) { this.kitCost = v; }
    public BigDecimal getInsuranceRiskCost() { return insuranceRiskCost; }
    public void setInsuranceRiskCost(BigDecimal v) { this.insuranceRiskCost = v; }
    public BigDecimal getAcquisitionCost() { return acquisitionCost; }
    public void setAcquisitionCost(BigDecimal v) { this.acquisitionCost = v; }
    public BigDecimal getPlatformLaborCost() { return platformLaborCost; }
    public void setPlatformLaborCost(BigDecimal v) { this.platformLaborCost = v; }
    public BigDecimal getOtherCost() { return otherCost; }
    public void setOtherCost(BigDecimal v) { this.otherCost = v; }
    public BigDecimal getPlatformOnsiteHours() { return platformOnsiteHours; }
    public void setPlatformOnsiteHours(BigDecimal v) { this.platformOnsiteHours = v; }
    public BigDecimal getFixedCompensation() { return fixedCompensation; }
    public void setFixedCompensation(BigDecimal v) { this.fixedCompensation = v; }
    public Long getExtraApproverUserId() { return extraApproverUserId; }
    public void setExtraApproverUserId(Long v) { this.extraApproverUserId = v; }
    public BigDecimal getBonuses() { return bonuses; }
    public void setBonuses(BigDecimal v) { this.bonuses = v; }
    public BigDecimal getHeadcountRate() { return headcountRate; }
    public void setHeadcountRate(BigDecimal v) { this.headcountRate = v; }
    public BigDecimal getHeadcountCap() { return headcountCap; }
    public void setHeadcountCap(BigDecimal v) { this.headcountCap = v; }
    public BigDecimal getBreakEvenFixedCost() { return breakEvenFixedCost; }
    public void setBreakEvenFixedCost(BigDecimal v) { this.breakEvenFixedCost = v; }
    public BigDecimal getVariableCostPerTicket() { return variableCostPerTicket; }
    public void setVariableCostPerTicket(BigDecimal v) { this.variableCostPerTicket = v; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
}
