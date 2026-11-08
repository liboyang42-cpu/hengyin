package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;
import java.util.Date;

/** 对账差异行：每条都能定位到渠道单号/兑换码。 */
public class ChannelReconciliationIssue {

    private String issueType;
    private String channelOrderNo;
    private String voucherPublicId;
    private BigDecimal amount;
    private Integer status;
    private Date occurredAt;

    public String getIssueType() { return issueType; }
    public void setIssueType(String issueType) { this.issueType = issueType; }

    public String getChannelOrderNo() { return channelOrderNo; }
    public void setChannelOrderNo(String channelOrderNo) { this.channelOrderNo = channelOrderNo; }

    public String getVoucherPublicId() { return voucherPublicId; }
    public void setVoucherPublicId(String voucherPublicId) { this.voucherPublicId = voucherPublicId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public Date getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Date occurredAt) { this.occurredAt = occurredAt; }
}
