package com.chengyinhub.business.domain.marketing;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 创作者/推荐人收入分配规则(表 marketing_revenue_rule)，按 {@code rule_key + version} 版本化。
 *
 * <p>守恒模型：{@code authorBaseRate + platformBaseRate = 1} 先二分内容净额；
 * {@code referralRate} 不是第三笔叠加，而是由 {@link #referralFundingSide} 指明从平台或作者份额里出。
 * 因此「作者分成 + 推荐佣金 + 平台留存 = 净额」恒成立，不会把联盟剩余份额误当平台最终收入。
 *
 * <p><b>50/75/20 只是竞品参考样本，不在此设为默认值</b>：所有费率必须由运营在草稿中显式配置，
 * 未配置就没有可用规则，宁可不分配也不套用未授权比例。
 */
public class MarketingRevenueRule {

    public static final int SCENARIO_PLATFORM_SELLS_AUTHOR_CONTENT = 1;
    public static final int SCENARIO_AUTHOR_OWN_CODE = 2;
    public static final int SCENARIO_REFERRER_RECOMMENDS = 3;

    public static final int STATUS_DRAFT = 0;
    public static final int STATUS_ACTIVE = 1;
    public static final int STATUS_RETIRED = 2;

    public static final int FUNDING_NONE = 0;
    public static final int FUNDING_PLATFORM = 1;
    public static final int FUNDING_AUTHOR = 2;

    private Long id;
    private String ruleKey;
    private Integer version;
    private Integer scenario;
    private String currency;
    private BigDecimal authorBaseRate;
    private BigDecimal platformBaseRate;
    private BigDecimal referralRate;
    private Integer referralFundingSide;
    /** 受审交易合同/协议标识；ACTIVE 必填，缺它不得给真实订单授钱。 */
    private String contractRef;
    /** 合同声明的作者收款权利人。不得默认取内容 owner —— owner 只是内容关联。 */
    private Long payeeMemberId;
    private Integer holdDays;
    private Integer status;
    private Date effectiveFrom;
    private Date effectiveTo;
    private String note;
    private Long createBy;
    private Date createTime;
    private Date publishTime;
    private Date retireTime;

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

    public Long getCreateBy() { return createBy; }
    public void setCreateBy(Long createBy) { this.createBy = createBy; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    public Date getPublishTime() { return publishTime; }
    public void setPublishTime(Date publishTime) { this.publishTime = publishTime; }

    public Date getRetireTime() { return retireTime; }
    public void setRetireTime(Date retireTime) { this.retireTime = retireTime; }
}
