package com.chengyinhub.business.domain.dto;

import com.chengyinhub.business.domain.SettlementDetail;

import java.util.Date;
import java.util.List;

public class SettlementGenerateDTO {
    private String batchNo;
    private Long merchantId;
    private Date periodStart;
    private Date periodEnd;
    private String idempotencyKey;
    private List<SettlementDetail> details;

    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public Date getPeriodStart() { return periodStart; }
    public void setPeriodStart(Date periodStart) { this.periodStart = periodStart; }
    public Date getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(Date periodEnd) { this.periodEnd = periodEnd; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public List<SettlementDetail> getDetails() { return details; }
    public void setDetails(List<SettlementDetail> details) { this.details = details; }
}
