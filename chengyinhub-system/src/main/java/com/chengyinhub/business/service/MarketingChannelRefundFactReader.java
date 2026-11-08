package com.chengyinhub.business.service;

import java.math.BigDecimal;

/**
 * 渠道**退款已完成**事实的服务端回读端口(由 M8 渠道域实现,M2 只调用)。
 *
 * <p>只有渠道域自己的不可变事实能证明"钱已退给顾客"。调用方传的 reason/金额/hash 都只是**查询键与核对值**,
 * 不构成退款证明 —— 这和服务端不接受客户端 order claim 是同一条纪律。
 *
 * <p><b>默认关闭</b>:bean 缺失(真实 provider 未接入)时渠道退款消费一律拒绝。
 */
public interface MarketingChannelRefundFactReader {

    /**
     * 回读并校验一条渠道退款事实:必须匹配原渠道订单、金额、币种与受益范围,且状态为"已确认退款"。
     *
     * @throws IllegalStateException 事实不存在/未确认/与原单不匹配/已被消费过(由实现方判定)
     */
    ChannelRefundFact requireVerifiedRefund(ChannelRefundQuery query);

    /** 查询键。它不证明任何事。 */
    final class ChannelRefundQuery {
        public String parentOrderNo;
        public String providerKey;
        public String channelRefundNo;
        public long refundFactId;
        public String refundFactHash;
        public BigDecimal claimedAmount;
        public String claimedCurrency;

        public ChannelRefundQuery(String parentOrderNo, String providerKey, String channelRefundNo,
                                  long refundFactId, String refundFactHash,
                                  BigDecimal claimedAmount, String claimedCurrency) {
            this.parentOrderNo = parentOrderNo;
            this.providerKey = providerKey;
            this.channelRefundNo = channelRefundNo;
            this.refundFactId = refundFactId;
            this.refundFactHash = refundFactHash;
            this.claimedAmount = claimedAmount;
            this.claimedCurrency = claimedCurrency;
        }
    }

    /** 服务端确认过的退款事实(金额/币种以本对象为准)。 */
    final class ChannelRefundFact {
        /**
         * 该事实在渠道域的不可变主键与哈希 —— 冲销任务的幂等键用它,
         * 这样"同一笔渠道退款"重复回调只入队一次,且不与微信 refund 的命名空间相撞。
         */
        public long factId;
        public String factHash;
        public String parentOrderNo;
        public String providerKey;
        public String channelRefundNo;
        public BigDecimal amount;
        public String currency;
        /** 退款是否已真实完成(渠道侧确认)。false ⇒ 只是申请,不得撤准入。 */
        public boolean confirmed;
        /** true = 可撤销全部受影响准入;false = 部分退款(本域无既定政策,按 §缺口 处理)。 */
        public boolean fullRefund;
        /** 该退款事实是否已被本域消费过(用于幂等)。 */
        public boolean alreadyConsumed;

        public ChannelRefundFact(long factId, String factHash,
                                 String parentOrderNo, String providerKey, String channelRefundNo,
                                 BigDecimal amount, String currency, boolean confirmed,
                                 boolean fullRefund, boolean alreadyConsumed) {
            this.factId = factId;
            this.factHash = factHash;
            this.parentOrderNo = parentOrderNo;
            this.providerKey = providerKey;
            this.channelRefundNo = channelRefundNo;
            this.amount = amount;
            this.currency = currency;
            this.confirmed = confirmed;
            this.fullRefund = fullRefund;
            this.alreadyConsumed = alreadyConsumed;
        }
    }
}
