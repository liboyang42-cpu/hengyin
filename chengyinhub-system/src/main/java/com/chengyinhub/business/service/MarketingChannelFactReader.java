package com.chengyinhub.business.service;

import java.math.BigDecimal;

/**
 * 渠道付款事实的服务端回读端口(由 M8 渠道域实现,M2 只调用)。
 *
 * <p><b>为什么需要它</b>:渠道成交不能把调用方传来的 hash/金额当付款证明 —— 那等于让撮合方自证。
 * 本端口由渠道域用**自己的不可变已验证事实**实现,逐项核对
 * provider / 订单号 / SKU / 受益人 / 金额 / 币种 / 未撤销 / 唯一绑定。
 *
 * <p><b>默认关闭</b>:bean 缺失(真实 provider 未配置)时,{@code MODE_CHANNEL} 一律拒绝成交。
 */
public interface MarketingChannelFactReader {

    /**
     * 回读并校验一条渠道事实。
     *
     * @throws IllegalStateException 事实不存在、未验证、已撤销、与入参不匹配、或已被其它成交绑定
     */
    ChannelFact requireVerified(ChannelFactQuery query);

    /** 查询键(全部来自成交命令;它不是信任来源)。 */
    final class ChannelFactQuery {
        public long factId;
        public String providerKey;
        public String externalOrderNo;
        public long memberId;
        public long productId;
        public int quantity;
        public BigDecimal claimedAmount;
        public String claimedCurrency;

        public ChannelFactQuery(long factId, String providerKey, String externalOrderNo, long memberId,
                                long productId, int quantity, BigDecimal claimedAmount, String claimedCurrency) {
            this.factId = factId;
            this.providerKey = providerKey;
            this.externalOrderNo = externalOrderNo;
            this.memberId = memberId;
            this.productId = productId;
            this.quantity = quantity;
            this.claimedAmount = claimedAmount;
            this.claimedCurrency = claimedCurrency;
        }
    }

    /** 服务端确认过的事实(金额/币种/交易号以本对象为准)。 */
    final class ChannelFact {
        public String externalOrderNo;
        public BigDecimal amount;
        public String currency;
        public String providerTransactionId;
        /** 该事实是否已被其它成交绑定(渠道域负责判定唯一性)。 */
        public boolean bound;

        public ChannelFact(String externalOrderNo, BigDecimal amount, String currency,
                           String providerTransactionId, boolean bound) {
            this.externalOrderNo = externalOrderNo;
            this.amount = amount;
            this.currency = currency;
            this.providerTransactionId = providerTransactionId;
            this.bound = bound;
        }
    }
}
