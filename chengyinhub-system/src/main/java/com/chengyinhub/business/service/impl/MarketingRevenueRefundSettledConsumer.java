package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.RefundSettledFact;
import com.chengyinhub.business.domain.marketing.MarketingRevenueAllocation;
import com.chengyinhub.business.mapper.MarketingRevenueAllocationMapper;
import com.chengyinhub.business.service.IMarketingRevenuePostingService;
import com.chengyinhub.business.service.IRefundSettledConsumer;
import org.springframework.stereotype.Service;

/**
 * 真实 caller：把"退款已真实到账"接入收入分配冲销队列。
 *
 * <p>接入点就是既有 {@link IRefundSettledConsumer} 端口 —— 由
 * {@code RefundServiceImpl.applyRefundPayoutEvent} 的 SUCCESS 分支在
 * {@code updatePayoutTerminal} CAS 成功后、<b>同一事务</b>内广播。
 * 不新开事务、不自己在回调里查同单未提交数据。
 *
 * <p>遵守端口契约：
 * <ul>
 *   <li><b>与本域无关时静默返回</b>：该报名单既没有分配快照、也从未有过本域应计任务
 *       —— 这才是唯一允许的 no-op。</li>
 *   <li><b>退款先到、费用未核算时不得丢</b>：分配快照是应计消费后才产生的。若该报名单仍有
 *       有效的应计任务（{@code KIND_ACCRUE} 存在，通常是费用来源未核 → {@code FEE_BASIS_MISSING}
 *       一直重试），则本域<b>确有关联</b>，必须照常入队冲销：让冲销任务以
 *       {@code ALLOCATION_NOT_FOUND} 持久重试，等应计补齐后再冲；绝不在这里 no-op 丢掉。</li>
 *   <li><b>相关却拿到坏事实必须响亮失败</b>：已确认该单有分配快照，但币种非 CNY 或
 *       实退金额 ≤ 0 时，直接抛错让整笔退款回调回滚并由微信重投，由人工核对。
 *       记录后 return 会把"该冲销的没冲销"变成静默漏账 —— 这是被明确否掉的写法。</li>
 *   <li><b>不重复冲销</b>：入队按 {@code (task_kind, ref_id)} 幂等，
 *       同一退款单重复广播只更新同一行；真正冲销由消费侧按 refundId 幂等。</li>
 *   <li><b>不自行开事务</b>：入队与原退款终态同事务提交，父事务未提交时不做跨事务读取。</li>
 *   <li><b>不产生钱包/余额/支付状态</b>：这里只插一行队列任务。</li>
 * </ul>
 */
@Service
public class MarketingRevenueRefundSettledConsumer implements IRefundSettledConsumer {

    private static final String CURRENCY_CNY = "CNY";

    /** 构造签名保持两参（root 裁定 A）：相关性判据走 postingService，不额外注入 mapper。 */
    private final IMarketingRevenuePostingService postingService;
    private final MarketingRevenueAllocationMapper allocationMapper;

    public MarketingRevenueRefundSettledConsumer(IMarketingRevenuePostingService postingService,
                                                 MarketingRevenueAllocationMapper allocationMapper) {
        this.postingService = postingService;
        this.allocationMapper = allocationMapper;
    }

    @Override
    public void onRefundSettled(RefundSettledFact fact) {
        if (fact == null || fact.registrationId() == null || fact.refundApplicationId() == null) {
            return;
        }
        // 先判"与本域是否相关":
        //   a) 有分配快照 ⇒ 明确相关;
        //   b) 没有分配快照,但该单曾有应计任务 ⇒ 也相关(应计还卡在费用未核的持久重试里);
        //   c) 两者都没有 ⇒ 本域从未参与这单,才允许 no-op。
        MarketingRevenueAllocation allocation = allocationMapper.selectByOrder(
                MarketingRevenueAllocation.ORDER_REGISTRATION, fact.registrationId());
        if (allocation == null && !postingService.hasAccrualTask(fact.registrationId())) {
            return;
        }
        // 相关却拿到坏事实：不能 log+return 把冲销吞掉。抛错让退款回调整笔回滚、
        // 由微信重投（消费侧按 refundId 幂等），并留下可追的错误；漏记冲销比多一次重投贵得多。
        if (!CURRENCY_CNY.equals(fact.currency())) {
            throw new IllegalStateException("退款事实币种与收入分配不一致 registrationId="
                    + fact.registrationId() + " currency=" + fact.currency());
        }
        if (fact.refundedFen() <= 0) {
            throw new IllegalStateException("退款实退金额非正，无法冲销 registrationId="
                    + fact.registrationId() + " refundedFen=" + fact.refundedFen());
        }
        // 同一事务内入队：任务与退款事实同提交，回滚则一起回滚。
        // 分配快照尚未产生时,消费侧会以 ALLOCATION_NOT_FOUND 失败并按指数退避重试,
        // 等应计补齐后同一任务自动算出冲销 —— 退款不会被永久吞掉。
        postingService.enqueueReversal(fact.refundApplicationId(), fact.registrationId());
    }
}
