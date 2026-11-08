package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;
import java.util.List;

/** 应计结果：原单快照 + 分录。replayed=true 表示该原单此前已应计，本次未新增写入。 */
public class RevenueAccrualResult {

    private Long allocationId;
    private Integer orderType;
    private Long orderId;
    private Long ruleId;
    private String ruleKey;
    private Integer ruleVersion;
    private Integer scenario;
    private String currency;
    private BigDecimal paidAmount;
    private BigDecimal netAmount;
    private BigDecimal authorAmount;
    private BigDecimal referrerAmount;
    private BigDecimal platformAmount;
    private String contractRef;
    private Long authorMemberId;
    private Integer status;
    private boolean replayed;
    private List<Line> lines;

    public static class Line {
        private String role;
        private Long recipientMemberId;
        private BigDecimal amount;
        private Long amountFen;
        private BigDecimal rate;
        private Integer fundingSide;

        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }

        public Long getRecipientMemberId() { return recipientMemberId; }
        public void setRecipientMemberId(Long recipientMemberId) { this.recipientMemberId = recipientMemberId; }

        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }

        public Long getAmountFen() { return amountFen; }
        public void setAmountFen(Long amountFen) { this.amountFen = amountFen; }

        public BigDecimal getRate() { return rate; }
        public void setRate(BigDecimal rate) { this.rate = rate; }

        public Integer getFundingSide() { return fundingSide; }
        public void setFundingSide(Integer fundingSide) { this.fundingSide = fundingSide; }
    }

    public Long getAllocationId() { return allocationId; }
    public void setAllocationId(Long allocationId) { this.allocationId = allocationId; }

    public Integer getOrderType() { return orderType; }
    public void setOrderType(Integer orderType) { this.orderType = orderType; }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }

    public String getRuleKey() { return ruleKey; }
    public void setRuleKey(String ruleKey) { this.ruleKey = ruleKey; }

    public Integer getRuleVersion() { return ruleVersion; }
    public void setRuleVersion(Integer ruleVersion) { this.ruleVersion = ruleVersion; }

    public Integer getScenario() { return scenario; }
    public void setScenario(Integer scenario) { this.scenario = scenario; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public BigDecimal getPaidAmount() { return paidAmount; }
    public void setPaidAmount(BigDecimal paidAmount) { this.paidAmount = paidAmount; }

    public BigDecimal getNetAmount() { return netAmount; }
    public void setNetAmount(BigDecimal netAmount) { this.netAmount = netAmount; }

    public BigDecimal getAuthorAmount() { return authorAmount; }
    public void setAuthorAmount(BigDecimal authorAmount) { this.authorAmount = authorAmount; }

    public BigDecimal getReferrerAmount() { return referrerAmount; }
    public void setReferrerAmount(BigDecimal referrerAmount) { this.referrerAmount = referrerAmount; }

    public BigDecimal getPlatformAmount() { return platformAmount; }
    public void setPlatformAmount(BigDecimal platformAmount) { this.platformAmount = platformAmount; }

    public String getContractRef() { return contractRef; }
    public void setContractRef(String contractRef) { this.contractRef = contractRef; }

    public Long getAuthorMemberId() { return authorMemberId; }
    public void setAuthorMemberId(Long authorMemberId) { this.authorMemberId = authorMemberId; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public boolean isReplayed() { return replayed; }
    public void setReplayed(boolean replayed) { this.replayed = replayed; }

    public List<Line> getLines() { return lines; }
    public void setLines(List<Line> lines) { this.lines = lines; }
}
