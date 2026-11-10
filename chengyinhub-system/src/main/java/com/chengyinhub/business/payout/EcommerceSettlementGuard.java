package com.chengyinhub.business.payout;

import com.chengyinhub.business.mapper.EcomPayoutMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * l-payout2：现有结算链路与电商收付通的唯一接点。
 * <ul>
 *   <li>主题结算冻结后（现有结算点）：按内部账本为以二级商户为卖家的已付单落分账计划；</li>
 *   <li>T+14 到期入余额：结算头里由微信收款的那部分（已在二级商户 / 分账接收方手里）不再记进会员余额；</li>
 *   <li>结算后退款冲回：由微信收款的分项改由「分账回退」追回，不再扣会员钱包。</li>
 * </ul>
 * {@code payout.ecommerce.enabled} 关闭时 {@link #active()} 为假，调用方不进入任何新分支，也不读新表。
 */
@Component
public class EcommerceSettlementGuard {
    private static final Logger log = LoggerFactory.getLogger(EcommerceSettlementGuard.class);

    private final PayoutSwitches switches;
    private final EcomPayoutMapper mapper;
    private final EcommerceProfitShareService profitSharing;

    public EcommerceSettlementGuard(PayoutSwitches switches, EcomPayoutMapper mapper, EcommerceProfitShareService profitSharing) {
        this.switches = switches;
        this.mapper = mapper;
        this.profitSharing = profitSharing;
    }

    public boolean active() { return switches.ecommerceEnabled(); }

    /** 结算头里由微信（二级商户）收款的净额，元。未启用时恒为 0 且不读库。 */
    public BigDecimal partnerPortion(Long settlementId) {
        if (!active() || settlementId == null) return BigDecimal.ZERO;
        BigDecimal portion = mapper.sumPartnerPortionForSettlement(settlementId);
        return portion == null ? BigDecimal.ZERO : portion;
    }

    /** 账本分项的原支付是否由二级商户收款。未启用时恒为 false 且不读库。 */
    public boolean isPartnerPaymentFact(Long paymentFactId) {
        if (!active() || paymentFactId == null) return false;
        return mapper.countPartnerPaymentFact(paymentFactId) > 0;
    }

    /**
     * 主题结算事务提交后调用：落分账计划（幂等）。失败只记日志，由定时任务「分账提交与重试」补齐，
     * 绝不影响已经提交的主题结算。分账开关关闭时直接返回。
     */
    public void afterTopicSettled(Long topicId) {
        if (!switches.profitSharingEnabled() || topicId == null) return;
        try {
            profitSharing.planTopic(topicId);
        } catch (RuntimeException e) {
            log.warn("ecom profit share plan after settlement failed topicId={} type={}", topicId, e.getClass().getSimpleName());
        }
    }
}
