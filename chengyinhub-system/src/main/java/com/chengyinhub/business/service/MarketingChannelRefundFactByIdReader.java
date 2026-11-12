package com.chengyinhub.business.service;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/**
 * 渠道退款事实的「按不可变主键回读」端口（M8 渠道域实现，收入分配域只读）。
 *
 * <p>为什么队列消费需要按 id 回读：任务行只存 {@code (task_kind, ref_id)}，
 * 消费时已拿不到当初的查询键。若把金额/币种抄进任务行，队列就变成了事实真源 ——
 * 那正是"伪造退款事实"的入口。所以消费时必须回原始验证表重新取一次。
 *
 * <p>与 {@link MarketingChannelRefundFactReader} 同源（同一张渠道验证表），
 * 区别只是入口：那个按查询键定位，这个按不可变主键回读。
 *
 * <p><b>非消费式复核</b>：本方法只读，不改 {@code consumed} 标记。同一退款事实被
 * 已经消费/或正在被重试时仍必须能回读到，否则任务会因"已被消费"而永久卡死。
 *
 * <p><b>默认关闭</b>：真实 provider 未接入时 bean 缺失，渠道冲销一律按"未验证"重试，
 * 绝不按调用方声称的金额入账。
 */
public interface MarketingChannelRefundFactByIdReader {

    /**
     * 按不可变主键回读一条<b>已确认完成</b>的渠道退款事实。
     *
     * @return 事实；实现方无法确认时返回 {@code null}（调用方按未验证处理，重试而非入账）
     */
    Fact requireVerifiedById(long factId);

    /** 服务端确认过的事实。金额/币种以本对象为准，调用方传的一律只是核对值。 */
    final class Fact {
        public final long factId;
        public final String factHash;
        /** 渠道侧退款单号。 */
        public final String channelRefundNo;
        /** 关联的我方报名单 id（主单）；NULL = 渠道域无法把它映射到报名单。 */
        public final Long registrationId;
        public final BigDecimal amount;
        public final String currency;
        public final boolean confirmed;

        /** 原渠道订单号（= 准入行 parent_order_no）。 */
        public final String parentOrderNo;
        /** 渠道 provider 键。 */
        public final String providerKey;
        /** true = 整笔渠道订单全额退款；false = 部分退款（当前无既定撤权/冲销口径）。 */
        public final boolean fullRefund;
        /** 该笔渠道退款覆盖的报名单范围；单通道单通常一行，多作品包为 N 行。 */
        public final List<Long> registrationIds;

        /** 兼容旧调用点：只关心单报名单时用这个构造。 */
        public Fact(long factId, String factHash, String channelRefundNo, Long registrationId,
                    BigDecimal amount, String currency, boolean confirmed) {
            this(factId, factHash, channelRefundNo, registrationId, amount, currency, confirmed,
                    null, null, true,
                    registrationId == null ? Collections.emptyList()
                            : Collections.singletonList(registrationId));
        }

        public Fact(long factId, String factHash, String channelRefundNo, Long registrationId,
                    BigDecimal amount, String currency, boolean confirmed,
                    String parentOrderNo, String providerKey, boolean fullRefund,
                    List<Long> registrationIds) {
            this.factId = factId;
            this.factHash = factHash;
            this.channelRefundNo = channelRefundNo;
            this.registrationId = registrationId;
            this.amount = amount;
            this.currency = currency;
            this.confirmed = confirmed;
            this.parentOrderNo = parentOrderNo;
            this.providerKey = providerKey;
            this.fullRefund = fullRefund;
            this.registrationIds = registrationIds == null
                    ? Collections.emptyList() : Collections.unmodifiableList(registrationIds);
        }
    }
}
