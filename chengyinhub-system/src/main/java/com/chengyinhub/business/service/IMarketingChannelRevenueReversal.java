package com.chengyinhub.business.service;

/**
 * 渠道退款完成后的**净额冲销**入队端口(typed,独立于微信退款)。
 *
 * <p><b>为什么必须独立</b>:微信侧冲销走 {@code enqueueReversal(refundId, registrationId)},
 * 其 {@code refundId} 是 {@code refund_application.id};渠道(OTA)没有、也不允许伪造那条记录,
 * 更不允许构造一个假的 {@code RefundSettledFact} 去借用微信链路 —— 那会把"钱退给了谁、
 * 退了多少"混成两套互相矛盾的证据。
 *
 * <p><b>调用位置(唯一)</b>:{@code MarketingProductFulfillmentServiceImpl.consumeVerifiedChannelRefund}
 * 在**服务端已确认渠道退款完成**之后、撤销准入的同一事务内。渠道退款事实由
 * {@link MarketingChannelRefundFactReader}(M8 实现)回读并校验,本端口只负责把这件已确认的事
 * 变成一条可重试的冲销任务。
 *
 * <p><b>实现方(M5/M0)</b>必须:
 * <ol>
 *   <li>{@code @Transactional(propagation = MANDATORY)}:与调用方原事务同生共死;</li>
 *   <li>用独立任务类型(不可复用微信 {@code KIND_REVERSE} 的 ref_id 命名空间),
 *       幂等键取渠道退款事实,同一退款重复入队只有一行;</li>
 *   <li><b>逆序不能丢</b>:若此刻尚无应计/分摊(费用未核),任务必须**持久留在队列**(pending/failed + 原因码),
 *       等应计补齐后再执行冲销,不得当"与本次无关"直接丢弃;</li>
 *   <li>不得在入队时计算金额、不得读同单未提交数据、不得开新事务。</li>
 * </ol>
 *
 * <p>本端口缺失(bean 未提供)时,{@code consumeVerifiedChannelRefund} **拒绝执行并抛错**:
 * 宁可整笔退款撤销事务回滚由回调重投,也不做"撤了准入、收入却永远不冲销"的半套账。
 */
public interface IMarketingChannelRevenueReversal {

    /**
     * 渠道退款已完成 → 净额冲销入队。必须在调用方事务内调用。
     *
     * @param channelOrderNo 渠道订单号(= 本域准入行 parent_order_no)
     * @param providerKey    渠道标识
     * @param channelRefundNo 渠道退款单号
     * @param refundFactId   渠道退款事实主键(渠道域不可变事实)
     * @param refundFactHash 渠道退款事实哈希(用于定位与复核,不是付款证明)
     * @return 任务 id
     */
    Long enqueueChannelReversal(String channelOrderNo, String providerKey, String channelRefundNo,
                                long refundFactId, String refundFactHash);
}
