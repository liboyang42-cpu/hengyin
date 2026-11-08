package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.MarketingProductOrder;
import com.chengyinhub.business.domain.dto.RefundSettledFact;
import com.chengyinhub.business.mapper.MarketingProductOrderMapper;
import com.chengyinhub.business.service.IMarketingProductPurchaseService;
import com.chengyinhub.business.service.IRefundSettledConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * C07 已到账退款 → 营销购买来源撤销（真实 caller）。
 *
 * <p>由 {@code RefundServiceImpl.notifyRefundSettled} 在 {@code SUCCESS} + payout CAS 成功、
 * 同一事务内广播。**不吞异常**：与本域相关的异常一律抛出，让原退款回调回滚并由
 * 微信重投 / 原 inbox 保留失败态，绝不 ack 成功却丢掉撤权任务。
 *
 * <p>能自动处理的情况：全额退款且营销侧只含未使用内容。
 * 不能自动处理的（部分退款、已用内容、事实不一致）抛出稳定异常：
 * 保留 {@code RefundSettledFact.idempotencyKey()} + orderNo 供追查与人工处理，
 * 由原 inbox/人工通道重试，而不是静默 return。
 */
@Service
public class MarketingProductRefundConsumer implements IRefundSettledConsumer {

    private static final Logger logger = LoggerFactory.getLogger(MarketingProductRefundConsumer.class);

    private final MarketingProductOrderMapper orderMapper;
    private final IMarketingProductPurchaseService purchaseService;

    public MarketingProductRefundConsumer(MarketingProductOrderMapper orderMapper,
                                          IMarketingProductPurchaseService purchaseService) {
        this.orderMapper = orderMapper;
        this.purchaseService = purchaseService;
    }

    @Override
    public void onRefundSettled(RefundSettledFact fact) {
        if (fact == null || fact.registrationId() == null) return;
        MarketingProductOrder order = orderMapper.selectByPrimaryRegistrationId(fact.registrationId());
        if (order == null) order = orderMapper.selectByRegistrationIdInJson(fact.registrationId());
        if (order == null) return; // 与本域无关：按端口契约静默返回。

        if (!"CNY".equals(fact.currency()) || order.getTotalCashAmount() == null
                || !Integer.valueOf(MarketingProductOrder.PAYMENT_FACT_WECHAT)
                .equals(order.getPaymentFactType())) {
            throw new IllegalStateException("MARKETING_REFUND_FACT_MISMATCH orderNo=" + order.getOrderNo()
                    + " currency=" + fact.currency() + " factType=" + order.getPaymentFactType()
                    + " key=" + fact.idempotencyKey());
        }
        long expectedFen = order.getTotalCashAmount().movePointRight(2).longValueExact();
        if (fact.totalFen() != expectedFen || fact.refundedFen() <= 0) {
            throw new IllegalStateException("MARKETING_REFUND_AMOUNT_MISMATCH orderNo=" + order.getOrderNo()
                    + " refundedFen=" + fact.refundedFen() + " totalFen=" + fact.totalFen()
                    + " expectedFen=" + expectedFen + " key=" + fact.idempotencyKey());
        }
        if (fact.refundedFen() < fact.totalFen()) {
            // 部分退款必须按原锁定政策逐行处理；营销侧不整体回收，交给原 inbox 重试/人工。
            throw new IllegalStateException("MARKETING_PARTIAL_REFUND_REQUIRES_LINE_HANDLING orderNo="
                    + order.getOrderNo() + " refundedFen=" + fact.refundedFen()
                    + " totalFen=" + fact.totalFen() + " key=" + fact.idempotencyKey());
        }
        // 已用内容会由 revokeAfterRefund 抛 USED_CONTENT_NOT_REVOCABLE；此处不捕获，
        // 让退款回调保留失败态并由人工按原合同处理，避免"钱退了、权还在"或静默回收。
        purchaseService.revokeAfterRefund(order.getOrderNo(), "REFUND:" + fact.outRefundNo());
        logger.info("[营销商品退款] 已撤销营销来源 orderNo={} key={}", order.getOrderNo(),
                fact.idempotencyKey());
    }
}
