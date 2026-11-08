package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;

/**
 * 应计入账请求(服务端内部受控调用)。
 *
 * <p><b>没有任何金额基数字段</b>：折后实付、币种、支付事实都从
 * {@code wx_verified_payment_fact} + 原订单读取，调用方无法用参数抬高或压低实付。
 *
 * <p>三项扣费必须带 {@link #feeBasisRef} 证据引用：未知就是未知，
 * <b>不允许默认 0 把虚假净额写进账</b>；缺证据会落待核事实而不是入账。
 */
public class RevenueAccrualRequest {

    private Long registrationId;
    /** 税费(含 VAT/销售税)，按合同核算；null 表示尚未核算。 */
    private BigDecimal taxAmount;
    /** 支付手续费。 */
    private BigDecimal paymentFeeAmount;
    /** 合同约定的第三方渠道佣金(OTA 等)。 */
    private BigDecimal channelCommissionAmount;
    /** 三项扣费的核算证据引用(合同/账单/对账批次号)。为空即视为未核算，不得应计。 */
    private String feeBasisRef;
    /** 触发事实描述，仅留痕。 */
    private String sourceFact;

    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long registrationId) { this.registrationId = registrationId; }

    public BigDecimal getTaxAmount() { return taxAmount; }
    public void setTaxAmount(BigDecimal taxAmount) { this.taxAmount = taxAmount; }

    public BigDecimal getPaymentFeeAmount() { return paymentFeeAmount; }
    public void setPaymentFeeAmount(BigDecimal paymentFeeAmount) { this.paymentFeeAmount = paymentFeeAmount; }

    public BigDecimal getChannelCommissionAmount() { return channelCommissionAmount; }
    public void setChannelCommissionAmount(BigDecimal channelCommissionAmount) { this.channelCommissionAmount = channelCommissionAmount; }

    public String getFeeBasisRef() { return feeBasisRef; }
    public void setFeeBasisRef(String feeBasisRef) { this.feeBasisRef = feeBasisRef; }

    public String getSourceFact() { return sourceFact; }
    public void setSourceFact(String sourceFact) { this.sourceFact = sourceFact; }
}
