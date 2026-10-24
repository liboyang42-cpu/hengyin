package com.chengyinhub.business.domain.vo;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/** Read-only operational projection. Deliberately has no provider/payment identifiers or raw source JSON. */
public class CoopSettlementManualCaseView {
    private Long id;
    private Long topicId;
    private String topicName;
    private String reason;
    private String state;
    private BigDecimal negativeAmount;
    private Date createdAt;
    private Source source;
    public Long getId(){return id;} public void setId(Long value){id=value;}
    public Long getTopicId(){return topicId;} public void setTopicId(Long value){topicId=value;}
    public String getTopicName(){return topicName;} public void setTopicName(String value){topicName=value;}
    public String getReason(){return reason;} public void setReason(String value){reason=value;}
    public String getState(){return state;} public void setState(String value){state=value;}
    public BigDecimal getNegativeAmount(){return negativeAmount;} public void setNegativeAmount(BigDecimal value){negativeAmount=value;}
    public Date getCreatedAt(){return createdAt;} public void setCreatedAt(Date value){createdAt=value;}
    public Source getSource(){return source;} public void setSource(Source value){source=value;}

    public static class Source {
        private boolean available;
        private String calculationVersion;
        private Long payerRegistrationId;
        private BigDecimal originalSales;
        private BigDecimal originalCash;
        private BigDecimal allocatedCost;
        private BigDecimal platformAmount;
        private BigDecimal guaranteeAmount;
        private BigDecimal merchantAmount;
        private List<Line> lines;
        public boolean isAvailable(){return available;} public void setAvailable(boolean value){available=value;}
        public String getCalculationVersion(){return calculationVersion;} public void setCalculationVersion(String value){calculationVersion=value;}
        public Long getPayerRegistrationId(){return payerRegistrationId;} public void setPayerRegistrationId(Long value){payerRegistrationId=value;}
        public BigDecimal getOriginalSales(){return originalSales;} public void setOriginalSales(BigDecimal value){originalSales=value;}
        public BigDecimal getOriginalCash(){return originalCash;} public void setOriginalCash(BigDecimal value){originalCash=value;}
        public BigDecimal getAllocatedCost(){return allocatedCost;} public void setAllocatedCost(BigDecimal value){allocatedCost=value;}
        public BigDecimal getPlatformAmount(){return platformAmount;} public void setPlatformAmount(BigDecimal value){platformAmount=value;}
        public BigDecimal getGuaranteeAmount(){return guaranteeAmount;} public void setGuaranteeAmount(BigDecimal value){guaranteeAmount=value;}
        public BigDecimal getMerchantAmount(){return merchantAmount;} public void setMerchantAmount(BigDecimal value){merchantAmount=value;}
        public List<Line> getLines(){return lines;} public void setLines(List<Line> value){lines=value;}
    }
    public static class Line {
        private Long settlementId;
        private Long coopOrderId;
        private Long sourceRegistrationId;
        private Long sourceClaimId;
        private String lineType;
        private BigDecimal originalAmount;
        private Integer shareMode;
        private BigDecimal shareRate;
        private BigDecimal fixedFee;
        public Long getSettlementId(){return settlementId;} public void setSettlementId(Long value){settlementId=value;}
        public Long getCoopOrderId(){return coopOrderId;} public void setCoopOrderId(Long value){coopOrderId=value;}
        public Long getSourceRegistrationId(){return sourceRegistrationId;} public void setSourceRegistrationId(Long value){sourceRegistrationId=value;}
        public Long getSourceClaimId(){return sourceClaimId;} public void setSourceClaimId(Long value){sourceClaimId=value;}
        public String getLineType(){return lineType;} public void setLineType(String value){lineType=value;}
        public BigDecimal getOriginalAmount(){return originalAmount;} public void setOriginalAmount(BigDecimal value){originalAmount=value;}
        public Integer getShareMode(){return shareMode;} public void setShareMode(Integer value){shareMode=value;}
        public BigDecimal getShareRate(){return shareRate;} public void setShareRate(BigDecimal value){shareRate=value;}
        public BigDecimal getFixedFee(){return fixedFee;} public void setFixedFee(BigDecimal value){fixedFee=value;}
    }
}
