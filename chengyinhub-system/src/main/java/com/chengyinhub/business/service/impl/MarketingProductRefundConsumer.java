package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.GiftEntitlement;
import com.chengyinhub.business.domain.MarketingProductOrder;
import com.chengyinhub.business.domain.MarketingProductOrderLine;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.GiftEntitlementMapper;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.mapper.RefundApplicationMapper;
import com.chengyinhub.business.domain.dto.RefundSettledFact;
import com.chengyinhub.business.mapper.MarketingProductOrderMapper;
import com.chengyinhub.business.service.IMarketingProductPurchaseService;
import com.chengyinhub.business.service.IRefundSettledConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 已验证到账退款按原 sourceId 对齐冻结分摊，绝不从部分金额猜包内项。
 * 原退款链撤销目标报名并释放其容量；这里只核对事实，全部项到账后才撤销整单来源。
 * 缺少商品父付款与交付行绑定的订单仍由原退款链转人工，不在这里补造支付事实。
 */
@Service
public class MarketingProductRefundConsumer implements IRefundSettledConsumer {

    private static final Logger logger = LoggerFactory.getLogger(MarketingProductRefundConsumer.class);

    private final MarketingProductOrderMapper orderMapper;
    private final IMarketingProductPurchaseService purchaseService;
    private final CmsRegistrationMapper registrationMapper;
    private final RefundApplicationMapper refundMapper;
    private final GiftEntitlementMapper giftMapper;
    private final PlayerNodeProgressMapper progressMapper;

    public MarketingProductRefundConsumer(MarketingProductOrderMapper orderMapper,
                                          IMarketingProductPurchaseService purchaseService,
                                          CmsRegistrationMapper registrationMapper,
                                          RefundApplicationMapper refundMapper,
                                          GiftEntitlementMapper giftMapper,
                                          PlayerNodeProgressMapper progressMapper) {
        this.orderMapper = orderMapper;
        this.purchaseService = purchaseService;
        this.registrationMapper = registrationMapper;
        this.refundMapper = refundMapper;
        this.giftMapper = giftMapper;
        this.progressMapper = progressMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onRefundSettled(RefundSettledFact fact) {
        if (fact == null || fact.registrationId() == null) return;
        MarketingProductOrder order = orderMapper.selectByPrimaryRegistrationId(fact.registrationId());
        if (order == null) order = orderMapper.selectByRegistrationIdInJson(fact.registrationId());
        if (order == null) return;
        // 锁定读避免并发到账后仍用旧快照判定整包；锁冲突向原 inbox 传播并重试。
        List<CmsRegistration> rows = registrationMapper.selectByParentOrderNoForUpdate(order.getOrderNo(), null);
        order = orderMapper.selectByOrderNoForUpdate(order.getOrderNo());
        if (order == null || !"CNY".equals(fact.currency()) || !"CNY".equals(order.getCurrency())
                || !Integer.valueOf(MarketingProductOrder.PAYMENT_FACT_WECHAT).equals(order.getPaymentFactType())
                || fact.verifiedRefundFactId() <= 0 || fact.verifiedRefundFactHash() == null
                || fact.verifiedRefundFactHash().isBlank() || fact.refundApplicationId() == null
                || fact.outRefundNo() == null || fact.outRefundNo().isBlank()
                || (!Integer.valueOf(MarketingProductOrder.STATUS_PAID).equals(order.getStatus())
                    && !Integer.valueOf(MarketingProductOrder.STATUS_REFUNDED).equals(order.getStatus()))) {
            throw gap("MARKETING_REFUND_FACT_MISMATCH", fact);
        }
        long expectedFen = fen(order.getTotalCashAmount());
        if (expectedFen <= 0 || fact.totalFen() != expectedFen || fact.refundedFen() <= 0
                || fact.refundedFen() > expectedFen) {
            throw gap("MARKETING_REFUND_AMOUNT_MISMATCH", fact);
        }
        List<MarketingProductOrderLine> lines = orderMapper.selectFrozenLines(order.getOrderNo());
        if (lines == null || lines.isEmpty() || rows == null) {
            throw gap("MARKETING_REFUND_FROZEN_LINES_MISSING", fact);
        }
        Map<Long, CmsRegistration> byTopic = new HashMap<>();
        for (CmsRegistration row : rows) {
            if (row == null || row.getId() == null || row.getTopicId() == null
                    || !order.getOrderNo().equals(row.getParentOrderNo())
                    || !Objects.equals(order.getMemberId(), row.getMemberId())
                    || !Long.valueOf(1).equals(row.getOwnerType())
                    || !row.getTopicId().equals(row.getOwnerId())
                    || byTopic.put(row.getTopicId(), row) != null) {
                throw gap("MARKETING_REFUND_LINE_AMBIGUOUS", fact);
            }
        }
        boolean foundTarget = false;
        boolean allSettled = true;
        long allocatedFen = 0;
        Set<Long> frozenTopics = new HashSet<>();
        for (MarketingProductOrderLine line : lines) {
            if (line == null || line.getTopicId() == null || !order.getId().equals(line.getOrderId())
                    || !frozenTopics.add(line.getTopicId())) {
                throw gap("MARKETING_REFUND_LINE_AMBIGUOUS", fact);
            }
            long lineFen = fen(line.getPayableAmount());
            if (lineFen < 0) throw gap("MARKETING_REFUND_AMOUNT_MISMATCH", fact);
            allocatedFen = Math.addExact(allocatedFen, lineFen);
            CmsRegistration row = byTopic.get(line.getTopicId());
            if (row == null) {
                allSettled = false;
                continue;
            }
            if (fen(row.getPayableAmount()) != lineFen) {
                throw gap("MARKETING_REFUND_ALLOCATION_MISMATCH", fact);
            }
            List<RefundApplication> history = refundMapper.selectRegistrationHistoryForUpdate(row.getId());
            long settledFen = 0;
            RefundApplication current = null;
            for (RefundApplication refund : history) {
                if (!"registration".equals(refund.getSourceType())
                        || !row.getId().equals(refund.getSourceId())) {
                    throw gap("MARKETING_REFUND_FACT_MISMATCH", fact);
                }
                if (fact.refundApplicationId().equals(refund.getId())) current = refund;
                if (Integer.valueOf(1).equals(refund.getStatus())
                        && Integer.valueOf(RefundApplication.PAYOUT_SUCCESS).equals(refund.getPayoutStatus())) {
                    long amount = fen(refund.getRefundAmount());
                    if (amount <= 0) throw gap("MARKETING_REFUND_AMOUNT_MISMATCH", fact);
                    settledFen = Math.addExact(settledFen, amount);
                }
            }
            if (settledFen > lineFen) throw gap("MARKETING_REFUND_AMOUNT_MISMATCH", fact);
            boolean rowRevoked = Integer.valueOf(4).equals(row.getPaymentStatus())
                    && Integer.valueOf(3).equals(row.getRegistrationStatus());
            allSettled &= lineFen > 0 && settledFen == lineFen && rowRevoked;
            if (!fact.registrationId().equals(row.getId())) continue;
            foundTarget = true;
            if (current == null || !Integer.valueOf(1).equals(current.getStatus())
                    || !Integer.valueOf(RefundApplication.PAYOUT_SUCCESS).equals(current.getPayoutStatus())
                    || !fact.outRefundNo().equals(current.getRefundNo())
                    || !Objects.equals(order.getMemberId(), current.getMemberId())
                    || fen(current.getPaidAmount()) != lineFen
                    || fen(current.getRefundAmount()) != fact.refundedFen()) {
                throw gap("MARKETING_REFUND_FACT_MISMATCH", fact);
            }
            // 本次必须恰好退完目标行剩余额；重放读同一退款行，不把它再加一次。
            if (settledFen != lineFen || !rowRevoked) {
                throw gap("MARKETING_PARTIAL_REFUND_REQUIRES_LINE_HANDLING", fact);
            }
            requireUnused(order, row, fact);
        }
        if (!foundTarget || allocatedFen != expectedFen || !frozenTopics.containsAll(byTopic.keySet())) {
            throw gap("MARKETING_REFUND_LINE_AMBIGUOUS", fact);
        }
        if (allSettled) {
            purchaseService.revokeAfterRefund(order.getOrderNo(), "REFUND:" + fact.outRefundNo());
        }
        // 收入回冲仍由同事务的 MarketingRevenueRefundSettledConsumer 按原 sourceId/退款ID入队。
        logger.info("[营销商品退款] 已核对冻结项 orderNo={} registrationId={} allSettled={} key={}",
                order.getOrderNo(), fact.registrationId(), allSettled, fact.idempotencyKey());
    }

    private void requireUnused(MarketingProductOrder order, CmsRegistration row, RefundSettledFact fact) {
        Long beneficiary = row.getMemberId();
        if (Integer.valueOf(MarketingProductOrder.FULFILLMENT_GIFT).equals(order.getFulfillmentKind())) {
            GiftEntitlement gift = giftMapper.selectByOrderId(order.getId());
            if (gift == null || order.getPrimaryRegistrationId() == null
                    || !order.getId().equals(gift.getOrderId())
                    || !Objects.equals(order.getMemberId(), gift.getBuyerMemberId())
                    || !Objects.equals(order.getPrimaryRegistrationId(), gift.getSourceRegistrationId())
                    || (Integer.valueOf(GiftEntitlement.STATUS_CLAIMED).equals(gift.getStatus())
                        && (gift.getClaimedMemberId() == null || gift.getClaimedMemberId() <= 0
                            || !Objects.equals(gift.getSourceRegistrationId(), gift.getClaimedRegistrationId())))) {
                throw gap("MARKETING_REFUND_GIFT_OWNER_MISMATCH", fact);
            }
            if (gift.getClaimedMemberId() != null) beneficiary = gift.getClaimedMemberId();
        }
        if (beneficiary == null || beneficiary <= 0 || Integer.valueOf(1).equals(row.getVerificationStatus())
                || row.getFirstSuccessfulScanAt() != null) {
            throw gap("USED_CONTENT_NOT_REVOCABLE", fact);
        }
        PlayerNodeProgress probe = new PlayerNodeProgress();
        probe.setMemberId(beneficiary);
        probe.setTopicId(row.getTopicId());
        // 不限最新 playInstance：旧局已用也不能因新开空局而被判成未使用。
        List<PlayerNodeProgress> progress = progressMapper.selectPlayerNodeProgressList(probe);
        if (progress == null || !progress.isEmpty()) throw gap("USED_CONTENT_NOT_REVOCABLE", fact);
    }

    private static long fen(BigDecimal amount) {
        if (amount == null) throw new IllegalStateException("MARKETING_REFUND_AMOUNT_MISSING");
        return amount.movePointRight(2).longValueExact();
    }

    private static IllegalStateException gap(String code, RefundSettledFact fact) {
        return new IllegalStateException(code + " key=" + fact.idempotencyKey());
    }
}
