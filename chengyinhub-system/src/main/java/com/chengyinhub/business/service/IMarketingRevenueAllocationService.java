package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.RevenueAccrualRequest;
import com.chengyinhub.business.domain.dto.RevenueAccrualResult;
import com.chengyinhub.business.domain.dto.RevenueReversalRequest;
import com.chengyinhub.business.domain.dto.RevenueReversalResult;
import com.chengyinhub.business.domain.marketing.MarketingRevenueAllocation;
import com.chengyinhub.business.domain.marketing.MarketingRevenueAllocationLine;

import java.util.List;

/**
 * 原单收入分配(CO-10 账本半边)。
 *
 * <p>只产出「谁该拿多少」的事实，<b>不</b>动钱包、不动余额、不动支付与既有 settlement 表；
 * 真实资金转移继续走已批准的受控结算/提现通道。
 *
 * <p><b>没有 markPaid</b>：在受控 creator 打款通道接线完成前，本域不提供"把快照标成已打款"的方法，
 * 避免暴露一个凭 id 就能把财务状态升级成"已付"的假接口。
 */
public interface IMarketingRevenueAllocationService {

    /**
     * 对一笔已支付报名单应计分配。
     *
     * <p>幂等优先：先按原单读既有快照，同 payload 重放返回、异 payload 冲突，
     * 全程不解析当前规则 —— 改规则/退规则/换来源都不会让旧重试失败或被重新定价。
     *
     * <p>依赖真实事实：已验证支付成功事实 + 扣费核算证据 + 受审合同收款人；
     * 缺任一项抛可区分理由码，不写任何虚假金额。
     */
    RevenueAccrualResult accrue(RevenueAccrualRequest request);

    /**
     * 退款/chargeback 反向分录。
     *
     * <p>幂等优先：同一 refundId 的分录存在时先返回同回执（不再撞累计封顶），
     * 同 refundId 换金额/币种/原单明确拒绝。
     *
     * <p>依赖通道退款成功事实（后台批准不算）；按原分录累计口径用最大余数法分摊，
     * 分笔部分退款无取整残差、不超各收款方、不读当前规则。
     */
    RevenueReversalResult reverse(RevenueReversalRequest request);

    /**
     * 渠道退款冲销：金额/币种从渠道域的不可变事实按主键回读，不信任调用方传值。
     *
     * <p>找不到对应分配快照时抛 {@code ALLOCATION_NOT_FOUND}（可重试）—— 退款先到、
     * 应计因费用未核算(FEE_BASIS_MISSING)还没产生时，任务保留待重试，费用补齐后同一任务能算出冲销，
     * 不会把退款永久吞掉。
     */
    RevenueReversalResult reverseChannelRefund(long channelRefundFactId, Long registrationId);

    /**
     * 按渠道退款事实的**原注册单范围**逐条冲销（渠道投递任务的消费入口）。
     *
     * <p>一条渠道退款事实可能覆盖多作品包的多行注册单；每条注册单按自己的实付冲满自己的净额，
     * 不把订单总额在每行重复计一次。任一行尚无分配快照时抛 {@code ALLOCATION_NOT_FOUND}
     * 让整笔回滚并保留待重试 —— 退款先到、费用未核算的场景不会被吞掉，也不会只冲一半。
     *
     * <p>部分退款无既定口径 ⇒ {@code PARTIAL_CHANNEL_REFUND_UNSUPPORTED}，保留待人工。
     */
    RevenueReversalResult reverseChannelRefundFact(long channelRefundFactId);

    MarketingRevenueAllocation getByOrder(Integer orderType, Long orderId);

    List<MarketingRevenueAllocationLine> linesOf(Long allocationId);

    List<MarketingRevenueAllocation> listByAuthor(Long authorMemberId);

    /**
     * 把已过冻结期的应计推进为可结算。时间由数据库时钟决定，**不接受调用方传时间**；
     * 只做 1→2，不涉及打款事实。
     */
    int advanceSettleable();
}
