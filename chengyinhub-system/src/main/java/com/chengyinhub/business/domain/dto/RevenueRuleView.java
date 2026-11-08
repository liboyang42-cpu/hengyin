package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;
import java.util.Date;

/** 分配规则视图(草稿与已发布共用)。 */
public class RevenueRuleView {

    private Long id;
    private String ruleKey;
    private Integer version;
    private Integer scenario;
    private String currency;
    private BigDecimal authorBaseRate;
    private BigDecimal platformBaseRate;
    private BigDecimal referralRate;
    private Integer referralFundingSide;
    private String contractRef;
    private Long payeeMemberId;
    private Integer holdDays;
    private Integer status;
    private Date effectiveFrom;
    private Date effectiveTo;
    private String note;
    private Date createTime;
    private Date publishTime;
    private Date retireTime;
    /** 适用范围：0=全局默认 1=主题 2=活动（由 ruleKey 反解，供后台展示）。 */
    private Integer targetType;
    private Long targetId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRuleKey() { return ruleKey; }
    public void setRuleKey(String ruleKey) { this.ruleKey = ruleKey; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public Integer getScenario() { return scenario; }
    public void setScenario(Integer scenario) { this.scenario = scenario; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public BigDecimal getAuthorBaseRate() { return authorBaseRate; }
    public void setAuthorBaseRate(BigDecimal authorBaseRate) { this.authorBaseRate = authorBaseRate; }

    public BigDecimal getPlatformBaseRate() { return platformBaseRate; }
    public void setPlatformBaseRate(BigDecimal platformBaseRate) { this.platformBaseRate = platformBaseRate; }

    public BigDecimal getReferralRate() { return referralRate; }
    public void setReferralRate(BigDecimal referralRate) { this.referralRate = referralRate; }

    public Integer getReferralFundingSide() { return referralFundingSide; }
    public void setReferralFundingSide(Integer referralFundingSide) { this.referralFundingSide = referralFundingSide; }

    public String getContractRef() { return contractRef; }
    public void setContractRef(String contractRef) { this.contractRef = contractRef; }

    public Long getPayeeMemberId() { return payeeMemberId; }
    public void setPayeeMemberId(Long payeeMemberId) { this.payeeMemberId = payeeMemberId; }

    public Integer getHoldDays() { return holdDays; }
    public void setHoldDays(Integer holdDays) { this.holdDays = holdDays; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public Date getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(Date effectiveFrom) { this.effectiveFrom = effectiveFrom; }

    public Date getEffectiveTo() { return effectiveTo; }
    public void setEffectiveTo(Date effectiveTo) { this.effectiveTo = effectiveTo; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    public Date getPublishTime() { return publishTime; }
    public void setPublishTime(Date publishTime) { this.publishTime = publishTime; }

    public Date getRetireTime() { return retireTime; }
    public void setRetireTime(Date retireTime) { this.retireTime = retireTime; }

    public Integer getTargetType() { return targetType; }
    public void setTargetType(Integer targetType) { this.targetType = targetType; }

    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }
}
