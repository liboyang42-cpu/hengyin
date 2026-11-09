package com.chengyinhub.business.service.support;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/** Resolves a consumed parent payment without pretending that its delivery rows paid separately. */
@Service
public class MarketingCashRefundOriginalSupport {
    private final MarketingProductOrderMapper orders;
    private final CmsRegistrationMapper registrations;
    private final CmsTopicMapper topics;
    private final GiftEntitlementMapper gifts;
    private final org.springframework.jdbc.core.JdbcTemplate currentReads;
    private final RefundApplicationMapper refunds;
    private final WechatVerifiedPaymentFactService facts;

    public MarketingCashRefundOriginalSupport(MarketingProductOrderMapper orders, CmsRegistrationMapper registrations,
            CmsTopicMapper topics, GiftEntitlementMapper gifts, org.springframework.jdbc.core.JdbcTemplate currentReads,
            RefundApplicationMapper refunds, WechatVerifiedPaymentFactService facts) {
        this.orders = orders; this.registrations = registrations; this.topics = topics;
        this.gifts = gifts; this.currentReads = currentReads; this.refunds = refunds; this.facts = facts;
    }

    public record Original(CmsRegistration registration, MarketingProductOrder order,
            List<CmsRegistration> rows, WechatVerifiedPaymentFactService.Stored payment, long lineFen) {
        public String businessNo() { return payment.facts().businessOutTradeNo(); }
        public BigDecimal providerTotal() { return BigDecimal.valueOf(payment.facts().totalFen(), 2); }
    }

    /** Empty means an ordinary registration; malformed marketing bindings always fail closed. */
    @Transactional(rollbackFor = Exception.class)
    public Optional<Original> find(Long registrationId) {
        CmsRegistration observed = registrations.selectCmsRegistrationById(registrationId);
        if (observed == null || observed.getParentOrderNo() == null
                || !observed.getParentOrderNo().startsWith("MKO")) return Optional.empty();
        MarketingProductOrder observedOrder = orders.selectByOrderNo(observed.getParentOrderNo());
        if (observedOrder == null) {
            if (observed.getParentOrderNo().startsWith("MKO")) throw gap();
            return Optional.empty();
        }
        if (!Integer.valueOf(MarketingProductOrder.PAY_MODE_CASH).equals(observedOrder.getPayMode()))
            return Optional.empty();
        List<MarketingProductOrderLine> lines = orders.selectFrozenLines(observedOrder.getOrderNo());
        if (lines == null || lines.isEmpty()) throw gap();
        // The existing play fence locks its topic before registrations; retain that exclusion per topic.
        List<Long> lockedTopics = lines.stream().map(MarketingProductOrderLine::getTopicId)
                .filter(Objects::nonNull).distinct().sorted().toList();
        for (Long topicId : lockedTopics) {
            CmsTopic topic = topics.selectCmsTopicByIdForUpdate(topicId);
            if (topic == null || !topicId.equals(topic.getId())) throw gap();
        }
        List<CmsRegistration> rows = registrations.selectByParentOrderNoForUpdate(observedOrder.getOrderNo(), null);
        MarketingProductOrder order = orders.selectByOrderNoForUpdate(observedOrder.getOrderNo());
        if (order == null || !Objects.equals(order.getId(), observedOrder.getId())
                || !Integer.valueOf(MarketingProductOrder.PAY_MODE_CASH).equals(order.getPayMode())
                || !Integer.valueOf(MarketingProductOrder.PAYMENT_FACT_WECHAT).equals(order.getPaymentFactType())
                || (!Integer.valueOf(MarketingProductOrder.STATUS_PAID).equals(order.getStatus())
                    && !Integer.valueOf(MarketingProductOrder.STATUS_REFUNDED).equals(order.getStatus()))
                || !"CNY".equals(order.getCurrency()) || order.getMemberId() == null
                || order.getMemberId() <= 0 || order.getPaidAt() == null) throw gap();
        WechatVerifiedPaymentFactService.Stored paid = facts.findPaymentSuccess(order.getOrderNo());
        if (paid == null || paid.facts() == null || paid.reference() == null
                || !Objects.equals(order.getPaymentFactId(), paid.reference().id())) throw gap();
        WechatVerifiedPaymentFact payment = paid.facts();
        payment.requireValid();
        if (payment.kind() != WechatVerifiedPaymentFact.Kind.PAYMENT
                || !paid.reference().hash().equals(payment.factHash())
                || !order.getOrderNo().equals(payment.businessOutTradeNo())
                || !"CNY".equals(payment.currency()) || fen(order.getTotalCashAmount()) != payment.totalFen()
                || payment.amountFen() != payment.totalFen()) throw gap();
        lines = orders.selectFrozenLines(order.getOrderNo());
        if (lines == null || !lockedTopics.equals(lines.stream().map(MarketingProductOrderLine::getTopicId)
                .filter(Objects::nonNull).distinct().sorted().toList())) throw gap();
        Map<Long, MarketingProductOrderLine> byTopic = new HashMap<>();
        long allocated = 0;
        for (MarketingProductOrderLine line : lines) {
            if (line == null || line.getTopicId() == null || !order.getId().equals(line.getOrderId())
                    || byTopic.put(line.getTopicId(), line) != null || fen(line.getPayableAmount()) < 0) throw gap();
            allocated = Math.addExact(allocated, fen(line.getPayableAmount()));
        }
        if (allocated != payment.totalFen() || rows == null || rows.isEmpty()) throw gap();
        Set<Long> rowIds = new HashSet<>();
        Set<Long> rowTopics = new HashSet<>();
        CmsRegistration target = null;
        for (CmsRegistration row : rows) {
            MarketingProductOrderLine line = byTopic.get(row.getTopicId());
            if (row.getId() == null || row.getId() <= 0 || row.getRegistrationNo() == null || row.getRegistrationNo().isBlank()
                    || !rowIds.add(row.getId()) || !rowTopics.add(row.getTopicId())
                    || !order.getOrderNo().equals(row.getParentOrderNo())
                    || !order.getMemberId().equals(row.getMemberId()) || !Long.valueOf(1).equals(row.getOwnerType())
                    || !Objects.equals(row.getTopicId(), row.getOwnerId()) || line == null
                    || fen(row.getPayableAmount()) != fen(line.getPayableAmount())) throw gap();
            if (row.getId().equals(order.getPrimaryRegistrationId())
                    && !Objects.equals(row.getRegistrationNo(), order.getPrimaryRegistrationNo())) throw gap();
            if (row.getId().equals(registrationId)) target = row;
        }
        List<Long> bound = JSON.parseArray(order.getRegistrationIdsJson(), Long.class);
        if (bound == null || bound.size() != rowIds.size() || new HashSet<>(bound).size() != bound.size()
                || !rowIds.equals(new HashSet<>(bound)) || !rowIds.contains(order.getPrimaryRegistrationId())
                || target == null || fen(target.getPayableAmount()) <= 0) throw gap();
        return Optional.of(new Original(target, order, List.copyOf(rows), paid, fen(target.getPayableAmount())));
    }

    /** Called inside the original application/review/dispatch/inbox transaction, never around provider HTTP. */
    public void requireRefund(Original original, RefundApplication refund, boolean approved) {
        CmsRegistration target = original.registration();
        if (!"registration".equals(refund.getSourceType()) || !target.getId().equals(refund.getSourceId())
                || (approved && (!Integer.valueOf(1).equals(refund.getStatus()) || refund.getId() == null
                    || !Integer.valueOf(4).equals(target.getPaymentStatus())
                    || !Integer.valueOf(3).equals(target.getRegistrationStatus())))
                || !Objects.equals(target.getMemberId(), refund.getMemberId())
                || !Objects.equals(target.getOwnerType(), refund.getOwnerType())
                || !Objects.equals(target.getOwnerId(), refund.getOwnerId())
                || fen(refund.getPaidAmount()) != original.lineFen()) throw gap();
        long amount = fen(refund.getRefundAmount());
        long reserved = 0;
        long settledTarget = 0;
        boolean found = refund.getId() == null;
        for (CmsRegistration row : original.rows().stream().sorted(Comparator.comparing(CmsRegistration::getId)).toList()) {
            long lineReserved = 0;
            List<RefundApplication> history = refunds.selectRegistrationHistoryForUpdate(row.getId());
            if (history == null) throw gap();
            for (RefundApplication prior : history) {
                if (!"registration".equals(prior.getSourceType()) || !row.getId().equals(prior.getSourceId())) throw gap();
                if (refund.getId() != null && refund.getId().equals(prior.getId())) {
                    if (!target.getId().equals(row.getId()) || !Objects.equals(refund.getRefundNo(), prior.getRefundNo())
                            || !Objects.equals(refund.getMemberId(), prior.getMemberId())
                            || !Objects.equals(refund.getStatus(), prior.getStatus())
                            || fen(refund.getRefundAmount()) != fen(prior.getRefundAmount())
                            || fen(prior.getPaidAmount()) != original.lineFen()) throw gap();
                    found = true;
                    continue;
                }
                if (!Integer.valueOf(0).equals(prior.getStatus()) && !Integer.valueOf(1).equals(prior.getStatus())) continue;
                long priorFen = fen(prior.getRefundAmount());
                if (priorFen <= 0) throw gap();
                lineReserved = Math.addExact(lineReserved, priorFen);
                if (row.getId().equals(target.getId())) {
                    if (!Integer.valueOf(1).equals(prior.getStatus())
                            || !Integer.valueOf(RefundApplication.PAYOUT_SUCCESS).equals(prior.getPayoutStatus())) throw gap();
                    settledTarget = Math.addExact(settledTarget, priorFen);
                }
            }
            if (lineReserved > fen(row.getPayableAmount())) throw gap();
            reserved = Math.addExact(reserved, lineReserved);
        }
        if (!found || amount <= 0 || amount != original.lineFen() - settledTarget
                || Math.addExact(reserved, amount) > original.payment().facts().totalFen()) throw gap();
        requireUnused(original);
    }

    private void requireUnused(Original original) {
        MarketingProductOrder order = original.order();
        CmsRegistration row = original.registration();
        Long beneficiary = order.getMemberId();
        if (Integer.valueOf(MarketingProductOrder.FULFILLMENT_GIFT).equals(order.getFulfillmentKind())) {
            GiftEntitlement observed = gifts.selectByOrderId(order.getId());
            GiftEntitlement gift = observed == null ? null : gifts.selectByTokenHashForUpdate(observed.getClaimTokenHash());
            if (gift == null || !order.getId().equals(gift.getOrderId())
                    || !order.getMemberId().equals(gift.getBuyerMemberId())
                    || !Objects.equals(order.getProductId(), gift.getProductId())
                    || !Objects.equals(order.getGiftRecipientMemberId(), gift.getRecipientMemberId())
                    || (gift.getRecipientMemberId() != null && gift.getClaimedMemberId() != null
                        && !gift.getRecipientMemberId().equals(gift.getClaimedMemberId()))
                    || !order.getPrimaryRegistrationId().equals(gift.getSourceRegistrationId())
                    || (gift.getClaimedMemberId() != null && (!gift.getSourceRegistrationId().equals(gift.getClaimedRegistrationId())
                        || gift.getClaimedMemberId() <= 0))
                    || (Integer.valueOf(GiftEntitlement.STATUS_CLAIMED).equals(gift.getStatus())
                        && gift.getClaimedMemberId() == null)) throw gap();
            if (gift.getClaimedMemberId() != null) beneficiary = gift.getClaimedMemberId();
        } else if (!Integer.valueOf(MarketingProductOrder.FULFILLMENT_SELF).equals(order.getFulfillmentKind())) throw gap();
        if (Integer.valueOf(1).equals(row.getVerificationStatus()) || row.getFirstSuccessfulScanAt() != null) throw gap();
        // A plain RR SELECT can predate the topic lock and miss a just-committed old run.
        if (!currentReads.queryForList("SELECT id FROM player_node_progress WHERE member_id=? AND topic_id=? LIMIT 1 FOR SHARE",
                Long.class, beneficiary, row.getTopicId()).isEmpty()) throw gap();
    }

    public boolean matches(Original original, RefundApplication refund, WechatVerifiedPaymentFact actual) {
        WechatVerifiedPaymentFact paid = original.payment().facts();
        return actual.kind() == WechatVerifiedPaymentFact.Kind.REFUND
                && paid.mchid().equals(actual.mchid()) && paid.appid().equals(actual.appid())
                && paid.tradeType().equals(actual.tradeType()) && Objects.equals(paid.intentRequestHash(), actual.intentRequestHash())
                && paid.businessOutTradeNo().equals(actual.businessOutTradeNo()) && paid.wxOutTradeNo().equals(actual.wxOutTradeNo())
                && paid.transactionId().equals(actual.transactionId()) && "CNY".equals(actual.currency())
                && refund.getRefundNo().equals(actual.merchantEventNo())
                && paid.totalFen() == actual.totalFen() && fen(refund.getRefundAmount()) == actual.amountFen();
    }

    private static long fen(BigDecimal amount) {
        if (amount == null) throw gap();
        return amount.movePointRight(2).longValueExact();
    }

    private static IllegalStateException gap() {
        return new IllegalStateException("营销现金退款原付款/交付/冻结额度或使用事实不完整，请核对原单后重试");
    }
}
