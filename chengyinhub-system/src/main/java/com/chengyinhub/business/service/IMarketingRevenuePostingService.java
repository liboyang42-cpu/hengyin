package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.marketing.MarketingRevenuePostingTask;

import java.util.List;

/**
 * 收入分配的**可靠投递**入口。
 *
 * <p>分两半，边界必须分清：
 * <ol>
 *   <li><b>enqueue</b>：在调用方的原事务里执行。任务行与支付/退款事实同事务提交；
 *       调用方回滚则任务一起回滚。这里*不*做任何金额计算、不读同单未提交数据、
 *       不开新事务 —— 因为此刻原事实还没提交。</li>
 *   <li><b>consumeDue</b>：原事实提交之后，由调度（{@code marketingRevenuePostingTask.run()}）
 *       在独立事务里领取并执行。领取用<b>租约</b>：进程崩溃/超时后由
 *       {@link #reclaimExpiredLeases()} 把过期 CLAIMED 行放回队列，不会永久卡死。</li>
 * </ol>
 *
 * <p>禁止的做法（此前实现犯过、已修正）：在支付回调里直接开 REQUIRES_NEW 去读同单。
 * 那只隔离了 rollbackOnly，解决不了未提交事实的可见性与行锁等待。
 */
public interface IMarketingRevenuePostingService {

    /** 应计入队。必须在调用方事务内调用（MANDATORY）；返回任务 id。 */
    Long enqueueAccrual(Long registrationId);

    /** 冲销入队。必须在调用方事务内调用（MANDATORY）；返回任务 id。 */
    Long enqueueReversal(Long refundId, Long registrationId);

    /**
     * 渠道退款冲销入队**不在本接口**：它需要 typed 渠道退款事实与 provider/order/hash 校验，
     * 由 {@link IMarketingChannelRevenueReversal#enqueueChannelReversal} 承担（M8 渠道域提供）。
     *
     * <p>此处刻意不保留 {@code (factId, registrationId)} 的弱签名：那会允许只凭两个 id
     * 就排入冲销任务，绕过退款事实的 confirmed/provider/金额币种核对。
     */

    /** 消费到期任务（独立事务 + 租约）。返回本轮成功处理的任务数。 */
    int consumeDue(int limit);

    /** 回收租约过期任务。返回回收行数。 */
    int reclaimExpiredLeases();

    List<MarketingRevenuePostingTask> tasksOf(Long orderId);

    /**
     * 该报名单是否存在<b>本域应计入队记录</b>（存在即说明本域确实参与过这单）。
     *
     * <p>给退款消费端判"相关性"用：分配快照是应计消费<b>之后</b>才产生的，
     * 退款先到时快照可能还没有；此时若只看快照就 no-op，会把应冲销的退款永久丢掉。
     * 判据用既有队列表 {@code marketing_revenue_posting_task}（{@code KIND_ACCRUE} + ref=registrationId），
     * 不新增表、不新增框架。
     */
    boolean hasAccrualTask(Long registrationId);
}
