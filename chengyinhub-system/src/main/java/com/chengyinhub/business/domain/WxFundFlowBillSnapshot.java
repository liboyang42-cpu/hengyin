package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/** External bill facts in yuan; never a local balance mutation. */
public class WxFundFlowBillSnapshot {
    private Long id;
    private Date billDate;
    private String merchantId;
    private String accountType;
    private Date accountingTime;
    private String wxBusinessNo;
    private String flowNo;
    private String businessName;
    private String businessType;
    private String direction;
    private BigDecimal amount;
    private BigDecimal balance;
    private String initiator;
    private String remark;
    private String voucherNo;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Date getBillDate() { return billDate; }
    public void setBillDate(Date billDate) { this.billDate = billDate; }

    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }

    public String getAccountType() { return accountType; }
    public void setAccountType(String accountType) { this.accountType = accountType; }

    public Date getAccountingTime() { return accountingTime; }
    public void setAccountingTime(Date accountingTime) { this.accountingTime = accountingTime; }

    public String getWxBusinessNo() { return wxBusinessNo; }
    public void setWxBusinessNo(String wxBusinessNo) { this.wxBusinessNo = wxBusinessNo; }

    public String getFlowNo() { return flowNo; }
    public void setFlowNo(String flowNo) { this.flowNo = flowNo; }

    public String getBusinessName() { return businessName; }
    public void setBusinessName(String businessName) { this.businessName = businessName; }

    public String getBusinessType() { return businessType; }
    public void setBusinessType(String businessType) { this.businessType = businessType; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }

    public String getInitiator() { return initiator; }
    public void setInitiator(String initiator) { this.initiator = initiator; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }

    public String getVoucherNo() { return voucherNo; }
    public void setVoucherNo(String voucherNo) { this.voucherNo = voucherNo; }
}
