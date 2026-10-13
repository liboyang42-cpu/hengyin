package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/** 收款方结算欠款(ums_member_settlement_debt):冲销/扣划时余额扣不动的差额。 */
public class UmsMemberSettlementDebt {
    private Long id;
    private Long memberId;
    private String sourceType;
    private Long sourceId;
    private BigDecimal amount;
    private BigDecimal repaidAmount;
    private Integer status;
    private Date dueTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public BigDecimal getRepaidAmount() { return repaidAmount; }
    public void setRepaidAmount(BigDecimal repaidAmount) { this.repaidAmount = repaidAmount; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Date getDueTime() { return dueTime; }
    public void setDueTime(Date dueTime) { this.dueTime = dueTime; }
}
