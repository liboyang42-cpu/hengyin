package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/** 原订单分项/成功冲回不可变存储；写权属于结算资金事务。 */
public class CoopSettlementOriginalAdjustment {
    private Long id;
    private Long cashCloseId;
    private Long componentId;
    private Long receiptId;
    private Long settlementId;
    private Long payerRegistrationId;
    private BigDecimal amount;
    private Boolean headWasPaid;
    private BigDecimal walletDebit;
    private BigDecimal debtAmount;
    private Date createdAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getCashCloseId() { return cashCloseId; }
    public void setCashCloseId(Long value) { cashCloseId = value; }
    public Long getComponentId() { return componentId; }
    public void setComponentId(Long value) { componentId = value; }
    public Long getReceiptId() { return receiptId; }
    public void setReceiptId(Long value) { receiptId = value; }
    public Long getSettlementId() { return settlementId; }
    public void setSettlementId(Long value) { settlementId = value; }
    public Long getPayerRegistrationId() { return payerRegistrationId; }
    public void setPayerRegistrationId(Long value) { payerRegistrationId = value; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal value) { amount = value; }
    public Boolean getHeadWasPaid() { return headWasPaid; }
    public void setHeadWasPaid(Boolean value) { headWasPaid = value; }
    public BigDecimal getWalletDebit() { return walletDebit; }
    public void setWalletDebit(BigDecimal value) { walletDebit = value; }
    public BigDecimal getDebtAmount() { return debtAmount; }
    public void setDebtAmount(BigDecimal value) { debtAmount = value; }
    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date value) { createdAt = value; }
}
