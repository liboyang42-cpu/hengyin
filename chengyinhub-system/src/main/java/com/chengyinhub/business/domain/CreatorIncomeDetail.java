package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

import java.math.BigDecimal;
import java.util.Date;

/**
 * P3 创作者收益明细 creator_income_detail。
 */
public class CreatorIncomeDetail extends BaseEntity {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long creatorId;
    private String sourceType;
    private Long sourceId;
    private BigDecimal amount;
    private Integer incomeStatus;
    private Date occurTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCreatorId() { return creatorId; }
    public void setCreatorId(Long creatorId) { this.creatorId = creatorId; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public Integer getIncomeStatus() { return incomeStatus; }
    public void setIncomeStatus(Integer incomeStatus) { this.incomeStatus = incomeStatus; }
    public Date getOccurTime() { return occurTime; }
    public void setOccurTime(Date occurTime) { this.occurTime = occurTime; }
}
