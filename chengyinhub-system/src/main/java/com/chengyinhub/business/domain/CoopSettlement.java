package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 商家结算单 coop_settlement(报告§5.5 · B4-1)。
 *
 * 合作商家按核销分润:分成型=share_rate×核销销售额,固定型=¥F×核销人头;挂 T+7 打款。
 */
public class CoopSettlement extends BaseEntity {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String cashHeadKind;

    public String getCashHeadKind() { return cashHeadKind; }
    public void setCashHeadKind(String value) { cashHeadKind = value; }
    private String settlementRoute;
    private String sourceContractType;
    private Long sourceContractId;
    private String commercialContextType;
    private Long commercialContextId;
    private String beneficiaryRole;
    private Long beneficiaryId;
    private String serviceUnit;
    private Long topicId;
    private Long coopOrderId;
    /** 收款商家memberId(=coop_order.to_id) */
    private Long merchantId;
    /** 0引流 1分成 2固定 */
    private Integer shareMode;
    private BigDecimal shareRate;
    private BigDecimal fixedFee;
    /** 核销人头数 */
    private Integer verifiedHeads;
    /** 核销销售额(分成型用) */
    private BigDecimal verifiedSales;
    /** 商家应收 */
    private BigDecimal amount;
    /** T9 结算审计快照:被本次结算计入的已核销报名 id(CSV,供对账/纠纷回溯) */
    private String verifiedRegIds;
    /** 0待打款 1已打款 2作废 */
    private Integer status;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date payableTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date settleTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date payoutTime;
    private Integer delFlag;
    /** 分项类型:PLATFORM/INITIATOR_GUARANTEE/INITIATOR_MARGIN/LEAD_FEE/MERCHANT_VERIFY/DEPOSIT_DEDUCTION */
    private String lineType;
    /** 收款方类型:platform/initiator/club/merchant */
    private String payeeType;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSettlementRoute() { return settlementRoute; }
    public void setSettlementRoute(String value) { this.settlementRoute = value; }
    public String getSourceContractType() { return sourceContractType; }
    public void setSourceContractType(String value) { this.sourceContractType = value; }
    public Long getSourceContractId() { return sourceContractId; }
    public void setSourceContractId(Long value) { this.sourceContractId = value; }
    public String getCommercialContextType() { return commercialContextType; }
    public void setCommercialContextType(String value) { this.commercialContextType = value; }
    public Long getCommercialContextId() { return commercialContextId; }
    public void setCommercialContextId(Long value) { this.commercialContextId = value; }
    public String getBeneficiaryRole() { return beneficiaryRole; }
    public void setBeneficiaryRole(String value) { this.beneficiaryRole = value; }
    public Long getBeneficiaryId() { return beneficiaryId; }
    public void setBeneficiaryId(Long value) { this.beneficiaryId = value; }
    public String getServiceUnit() { return serviceUnit; }
    public void setServiceUnit(String value) { this.serviceUnit = value; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public Long getCoopOrderId() { return coopOrderId; }
    public void setCoopOrderId(Long coopOrderId) { this.coopOrderId = coopOrderId; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public Integer getShareMode() { return shareMode; }
    public void setShareMode(Integer shareMode) { this.shareMode = shareMode; }
    public BigDecimal getShareRate() { return shareRate; }
    public void setShareRate(BigDecimal shareRate) { this.shareRate = shareRate; }
    public BigDecimal getFixedFee() { return fixedFee; }
    public void setFixedFee(BigDecimal fixedFee) { this.fixedFee = fixedFee; }
    public Integer getVerifiedHeads() { return verifiedHeads; }
    public void setVerifiedHeads(Integer verifiedHeads) { this.verifiedHeads = verifiedHeads; }
    public BigDecimal getVerifiedSales() { return verifiedSales; }
    public void setVerifiedSales(BigDecimal verifiedSales) { this.verifiedSales = verifiedSales; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getVerifiedRegIds() { return verifiedRegIds; }
    public void setVerifiedRegIds(String verifiedRegIds) { this.verifiedRegIds = verifiedRegIds; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Date getPayableTime() { return payableTime; }
    public void setPayableTime(Date payableTime) { this.payableTime = payableTime; }
    public Date getSettleTime() { return settleTime; }
    public void setSettleTime(Date settleTime) { this.settleTime = settleTime; }
    public Date getPayoutTime() { return payoutTime; }
    public void setPayoutTime(Date payoutTime) { this.payoutTime = payoutTime; }
    public Integer getDelFlag() { return delFlag; }
    public void setDelFlag(Integer delFlag) { this.delFlag = delFlag; }
    public String getLineType() { return lineType; }
    public void setLineType(String lineType) { this.lineType = lineType; }
    public String getPayeeType() { return payeeType; }
    public void setPayeeType(String payeeType) { this.payeeType = payeeType; }
}
