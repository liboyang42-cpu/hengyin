package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ChannelRefundVerification;
import com.chengyinhub.business.exception.ChannelVoucherException;
import com.chengyinhub.business.mapper.ChannelRefundVerificationMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 退款事实先独立提交，再交给消费方。这样消费失败（例如退款链路暂时不可用）时，
 * 已验证的退款事实不会随回调事务一起丢失。
 *
 * <p>三条重复/冲突口径：
 * <ul>
 *   <li>同 {@code (provider,event)} 且载荷指纹一致 ⇒ 幂等重放，读回原行，不双记；</li>
 *   <li>同 {@code (provider,event)} 但载荷变了 ⇒ {@code CHANNEL_REFUND_EVENT_CONFLICT}；</li>
 *   <li>同渠道退款单号但事实不同 ⇒ {@code CHANNEL_REFUND_NO_CONFLICT}。</li>
 * </ul>
 */
@Service
public class ChannelRefundFactStore {

    private final ChannelRefundVerificationMapper refundMapper;

    public ChannelRefundFactStore(ChannelRefundVerificationMapper refundMapper) {
        this.refundMapper = refundMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public ChannelRefundVerification store(ChannelRefundVerification refund) {
        ChannelRefundVerification existing =
                refundMapper.selectByProviderEvent(refund.getProviderKey(), refund.getEventId());
        if (existing != null) {
            if (!samePayload(existing, refund)) {
                throw ChannelVoucherException.conflict("CHANNEL_REFUND_EVENT_CONFLICT",
                        "同一渠道退款事件对应了不同载荷，拒绝覆盖原始事实");
            }
            return existing;
        }
        ChannelRefundVerification byRefundNo =
                refundMapper.selectByAccountRefundNo(refund.getAccountId(), refund.getChannelRefundNo());
        if (byRefundNo != null && !samePayload(byRefundNo, refund)) {
            throw ChannelVoucherException.conflict("CHANNEL_REFUND_NO_CONFLICT",
                    "同一渠道退款单号对应了不同事实，拒绝重复记账");
        }
        try {
            refundMapper.insert(refund);
        } catch (DuplicateKeyException concurrent) {
            ChannelRefundVerification winner =
                    refundMapper.selectByProviderEvent(refund.getProviderKey(), refund.getEventId());
            if (winner != null) {
                if (!samePayload(winner, refund)) {
                    throw ChannelVoucherException.conflict("CHANNEL_REFUND_EVENT_CONFLICT",
                            "同一渠道退款事件对应了不同载荷，拒绝覆盖原始事实");
                }
                return winner;
            }
            ChannelRefundVerification byNo = refundMapper.selectByAccountRefundNo(
                    refund.getAccountId(), refund.getChannelRefundNo());
            if (byNo != null && !samePayload(byNo, refund)) {
                throw ChannelVoucherException.conflict("CHANNEL_REFUND_NO_CONFLICT",
                        "同一渠道退款单号对应了不同事实，拒绝重复记账");
            }
            throw concurrent;
        }
        return refundMapper.selectById(refund.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public int markConsumed(Long id) {
        return refundMapper.markConsumed(id, new java.util.Date(), new java.util.Date());
    }

    @Transactional(rollbackFor = Exception.class)
    public int markNoRevenueToReverse(Long id, String reasonCode) {
        return refundMapper.markNoRevenueToReverse(id, reasonCode,
                new java.util.Date(), new java.util.Date());
    }

    /** 载荷指纹一致 = 订单/退款单/金额/币种/是否全退全同。 */
    private boolean samePayload(ChannelRefundVerification a, ChannelRefundVerification b) {
        return same(a.getChannelOrderNo(), b.getChannelOrderNo())
                && same(a.getChannelRefundNo(), b.getChannelRefundNo())
                && same(a.getProviderKey(), b.getProviderKey())
                && a.getAmount() != null && b.getAmount() != null
                && a.getAmount().compareTo(b.getAmount()) == 0
                && same(a.getCurrency(), b.getCurrency())
                && (a.getFullRefund() == null ? b.getFullRefund() == null
                    : a.getFullRefund().equals(b.getFullRefund()));
    }

    private boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
