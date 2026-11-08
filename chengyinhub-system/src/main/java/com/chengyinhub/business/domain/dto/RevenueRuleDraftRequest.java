package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;

/**
 * 分配规则草稿保存请求(运营/后台)。
 *
 * <p>草稿可反复保存，发布后冻结。费率必须显式给出；本接口<b>不</b>提供任何竞品比例默认值。
 */
public class RevenueRuleDraftRequest {

    /** 稳定逻辑键，同一键多版本；由 {@code RevenueRuleKeys.of(targetType,targetId,scenario)} 生成。 */
    private String ruleKey;
    /** 1=平台代售作者内容 2=作者自带码售自己内容 3=推荐他人内容。 */
    private Integer scenario;
    private String currency;
    private BigDecimal authorBaseRate;
    private BigDecimal platformBaseRate;
    private BigDecimal referralRate;
    /** 0=无推荐 1=从平台份额出 2=从作者份额出。 */
    private Integer referralFundingSide;
    /** 受审交易合同标识；发布(ACTIVE)必填。 */
    private String contractRef;
    /** 合同声明的作者收款人；发布(ACTIVE)必填。不得默认等于内容 owner。 */
    private Long payeeMemberId;
    private Integer holdDays;
    private String note;
    /** 适用范围：0=全局默认 1=主题 2=活动。与 targetId 一起编码进 ruleKey。 */
    private Integer targetType;
    private Long targetId;

    public String getRuleKey() { return ruleKey; }
    public void setRuleKey(String ruleKey) { this.ruleKey = ruleKey; }

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

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public Integer getTargetType() { return targetType; }
    public void setTargetType(Integer targetType) { this.targetType = targetType; }

    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }
}
