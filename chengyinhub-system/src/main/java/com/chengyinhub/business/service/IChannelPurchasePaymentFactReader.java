package com.chengyinhub.business.service;

import java.math.BigDecimal;

/**
 * 渠道订单「已验证付款事实」的只读回读端口（M8 实现）。
 *
 * <p>用途：收入分配域在给渠道成交单应计时需要一条**typed 且不可变**的付款事实。
 * 微信路径读的是 {@code wx_verified_payment_fact}；渠道成交没有那条记录，也不能伪造，
 * 只能回读 {@code marketing_channel_order_verification} 里服务端已确认的购买事实。
 *
 * <p>接入方式（由 {@link RevenueFactSource} 的实现方决定，本端口不自作全局装配）：
 * 当报名单没有微信交易号时，按 {@code cms_registration.parent_order_no}（渠道单号）回读本端口；
 * 返回空 ⇒ 该单仍按"缺付款事实"拒绝应计，绝不拿订单金额顶上。
 *
 * <p><b>fail-closed</b>：同一渠道单号跨账户命中多行时返回空（歧义不猜）。
 */
public interface IChannelPurchasePaymentFactReader {

    /** 渠道订单的已验证付款事实；未验证/找不到/存在歧义时返回 null。 */
    PaymentFact verifiedPaymentOf(String channelOrderNo);

    final class PaymentFact {
        public final long verificationId;
        public final String channelOrderNo;
        public final String providerKey;
        public final long amountFen;
        public final String currency;
        public final String factHash;

        public PaymentFact(long verificationId, String channelOrderNo, String providerKey,
                           long amountFen, String currency, String factHash) {
            this.verificationId = verificationId;
            this.channelOrderNo = channelOrderNo;
            this.providerKey = providerKey;
            this.amountFen = amountFen;
            this.currency = currency;
            this.factHash = factHash;
        }

        public BigDecimal amount() {
            return BigDecimal.valueOf(amountFen).movePointLeft(2);
        }
    }
}
