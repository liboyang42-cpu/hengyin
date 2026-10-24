package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.domain.dto.CoopOriginalRefundScope;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.IUmsMemberBalanceDetailService;
import com.chengyinhub.business.service.support.SettlementDebtLedger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.util.*;

/** 原订单不可变分项的存储/成功冲回owner。不开新事务、不调用SDK、不计算当前费率、不改原head金额。 */
@Service
public class CoopSettlementOriginalLedger {
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private final CmsTopicMapper topics;
    private final CoopSettlementCashEvidenceMapper evidence;
    private final CoopSettlementOriginalMapper originals;
    private final CoopSettlementMapper heads;
    private final CoopSettlementAdjustmentMapper legacyAdjustments;
    private final WechatVerifiedPaymentFactService verifiedFacts;
    private final UmsMemberMapper members;
    private final IUmsMemberBalanceDetailService balanceLedger;
    private final SettlementDebtLedger debts;
    private final ObjectMapper json = new ObjectMapper();
    @org.springframework.beans.factory.annotation.Autowired
    private CoopSettlementEvidenceMapper originalCloseEvidence;


    public CoopSettlementOriginalLedger(CmsTopicMapper topics, CoopSettlementCashEvidenceMapper evidence,
            CoopSettlementOriginalMapper originals, CoopSettlementMapper heads, CoopSettlementAdjustmentMapper legacyAdjustments, WechatVerifiedPaymentFactService verifiedFacts,
            UmsMemberMapper members, IUmsMemberBalanceDetailService balanceLedger, SettlementDebtLedger debts) {
        this.topics = topics; this.evidence = evidence; this.originals = originals; this.heads = heads;
        this.legacyAdjustments = legacyAdjustments;
        this.verifiedFacts = verifiedFacts; this.members = members; this.balanceLedger = balanceLedger; this.debts = debts;
    }

    /** 输入为已经按批准分币合同准备的原计划，本方法只校验/冻结，不替调用者选择分币算法。
     * 所有head先在同一原结算事务产生；平台必须为真实ACCOUNTING种类，无伪造claim/member。 */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void freezeOriginals(CoopSettlementCashClose proposed, List<CoopSettlementCashContribution> contributions,
            List<CoopSettlementOriginalComponent> components) {
        requireTransaction();
        if (proposed == null || contributions == null || components == null) throw conflict("ORIGINAL_PLAN_REQUIRED");
        lockTopic(proposed.getTopicId());
        // Direct producer callers must use the same topic -> current registrations -> refund history order.
        if (originalCloseEvidence.selectTopicCashRowsForUpdate(proposed.getTopicId()) == null
                || originalCloseEvidence.selectTopicActivityCashRowsForUpdate(proposed.getTopicId()) == null) {
            throw conflict("CURRENT_ORIGINAL_CLOSE_ROWS_REQUIRED");
        }
        requireNoApprovedRefundsForOriginalClose(originals, proposed.getTopicId());
        if (!Objects.equals(proposed.getOriginalContributionCount(), contributions.size())) throw conflict("CONTRIBUTION_COUNT_CONFLICT");
        for (var row : contributions) if (row == null || row.getRegistrationId() == null || row.getPayerRegistrationId() == null) throw conflict("CONTRIBUTION_IDENTITY_REQUIRED");
        CoopSettlementCashClose close = evidence.recordClose(proposed);
        if (close == null || close.getId() == null) throw conflict("CLOSE_RECEIPT_REQUIRED");
        List<CoopSettlementCashContribution> ordered = new ArrayList<>(contributions);
        ordered.sort(Comparator.comparing((CoopSettlementCashContribution c) -> !Objects.equals(c.getRegistrationId(), c.getPayerRegistrationId()))
                .thenComparing(CoopSettlementCashContribution::getRegistrationId));
        Map<Long, CoopSettlementCashContribution> byRegistration = new HashMap<>();
        Map<Long, BigDecimal> cashByPayer = new TreeMap<>();
        BigDecimal sales = ZERO;
        for (CoopSettlementCashContribution row : ordered) {
            if (row == null || row.getRegistrationId() == null || byRegistration.put(row.getRegistrationId(), row) != null
                    || !close.getTopicId().equals(row.getSourceTopicId())) throw conflict("CONTRIBUTION_IDENTITY_CONFLICT");
            row.setCashCloseId(close.getId());
            evidence.recordContribution(row);
            BigDecimal basis = cents(row.getSalesBasisAmount());
            sales = sales.add(basis); cashByPayer.merge(row.getPayerRegistrationId(), basis, BigDecimal::add);
        }
        equal(sales, proposed.getOriginalSalesBasis(), "CLOSE_SALES_CONSERVATION");
        Map<Long, BigDecimal> componentByHead = new TreeMap<>();
        Map<Long, BigDecimal> componentByPayer = new TreeMap<>();
        Map<Long, CoopSettlement> lockedHeads = new HashMap<>();
        Map<Long, WechatVerifiedPaymentFact.Reference> paymentByPayer = new HashMap<>();
        Set<String> componentKeys = new HashSet<>();
        for (CoopSettlementOriginalComponent component : components) {
            if (component == null || component.getSettlementId() == null || component.getSourceRegistrationId() == null
                    || component.getPayerRegistrationId() == null || component.getPaymentFactId() == null || component.getPaymentFactId() <= 0
                    || component.getPaymentFactHash() == null) throw conflict("COMPONENT_IDENTITY_REQUIRED");
            componentByHead.putIfAbsent(component.getSettlementId(), ZERO);
        }
        List<CoopSettlement> currentHeads = originals.selectTopicHeadsForUpdate(close.getTopicId());
        if (currentHeads == null) throw conflict("CURRENT_TOPIC_HEADS_REQUIRED");
        Set<Long> actualCashHeads = new HashSet<>();
        for (var head : currentHeads) {
            if (head == null || !close.getTopicId().equals(head.getTopicId())) throw conflict("HEAD_TOPIC_CONFLICT");
            if (head.getLineType() == null) throw conflict("ORIGINAL_HEAD_LINE_REQUIRED");
            if (Set.of("PLATFORM","INITIATOR_GUARANTEE","INITIATOR_MARGIN","MERCHANT_VERIFY","LEAD_FEE").contains(head.getLineType())) {
                actualCashHeads.add(head.getId());
            }
        }
        if (!actualCashHeads.equals(componentByHead.keySet())) throw conflict("ORIGINAL_HEAD_SET_INCOMPLETE");
        for (Long id : componentByHead.keySet()) {
            CoopSettlement head = heads.selectByIdForUpdate(id);
            if (head == null || !close.getTopicId().equals(head.getTopicId())) throw conflict("HEAD_TOPIC_CONFLICT");
            lockedHeads.put(id, head);
        }
        for (CoopSettlementOriginalComponent component : components) {
            CoopSettlement head = lockedHeads.get(component.getSettlementId());
            CoopSettlementCashContribution source = byRegistration.get(component.getSourceRegistrationId());
            CoopSettlementCashContribution payer = byRegistration.get(component.getPayerRegistrationId());
            if (source == null || payer == null || !Objects.equals(source.getPayerRegistrationId(), payer.getRegistrationId())
                    || !Objects.equals(payer.getPayerRegistrationId(), payer.getRegistrationId())
                    || !componentKeys.add(component.getSourceRegistrationId() + ":" + component.getSettlementId())) throw conflict("COMPONENT_SOURCE_CONFLICT");
            component.setCashCloseId(close.getId()); component.setTopicId(close.getTopicId());
            BigDecimal amount = cents(component.getOriginalAmount());
            if (amount.signum() < 0 && !"INITIATOR_MARGIN".equals(component.getLineType())) throw conflict("NEGATIVE_NON_MARGIN_ORIGIN");
            component.setOriginalAmount(amount); component.setOriginalOrderCash(cents(component.getOriginalOrderCash()));
            equal(component.getOriginalOrderCash(), cashByPayer.get(component.getPayerRegistrationId()), "ORIGINAL_ORDER_CASH_CONFLICT");
            WechatVerifiedPaymentFact.Reference paymentReference = new WechatVerifiedPaymentFact.Reference(component.getPaymentFactId(), component.getPaymentFactHash());
            WechatVerifiedPaymentFact.Reference previousPayment = paymentByPayer.putIfAbsent(component.getPayerRegistrationId(), paymentReference);
            if (previousPayment != null && !previousPayment.equals(paymentReference)) throw conflict("ORIGINAL_PAYMENT_IDENTITY_CONFLICT");
            WechatVerifiedPaymentFact payment = verifiedFacts.requireVerified(paymentReference).facts();
            requirePayment(payment, payer.getRegistrationNo(), component.getOriginalOrderCash());
            if (!Objects.equals(head.getLineType(), component.getLineType())
                    || !Objects.equals(head.getMerchantId(), component.getReceiverMemberId())
                    || !("ACCOUNTING".equals(component.getReceiverKind()) && "PLATFORM".equals(head.getLineType()) && head.getMerchantId() == null
                        || "MEMBER".equals(component.getReceiverKind()) && head.getMerchantId() != null && head.getMerchantId() > 0)) throw conflict("ORIGINAL_RECEIVER_CONFLICT");
            if (component.getCalculationVersion() == null || component.getCalculationVersion().isBlank()
                    || !component.getCalculationVersion().equals(close.getOriginCalculationVersion())) throw conflict("ORIGINAL_ROUNDING_VERSION_REQUIRED");
            component.setSourceFingerprint(fingerprint(component));
            componentByHead.merge(head.getId(), amount, BigDecimal::add);
            componentByPayer.merge(component.getPayerRegistrationId(), amount, BigDecimal::add);
        }
        for (var entry : componentByHead.entrySet()) equal(entry.getValue(), lockedHeads.get(entry.getKey()).getAmount(), "ORIGINAL_HEAD_CONSERVATION");
        for (var entry : cashByPayer.entrySet()) equal(entry.getValue(), componentByPayer.getOrDefault(entry.getKey(), ZERO), "ORIGINAL_ORDER_CONSERVATION");
        BigDecimal total = componentByHead.values().stream().reduce(ZERO, BigDecimal::add);
        equal(total, close.getOriginalSalesBasis(), "ORIGINAL_CLOSE_CONSERVATION");
        List<CoopSettlementOriginalComponent> prior = originals.selectComponentsForCloseForUpdate(close.getId());
        if (prior == null) throw conflict("ORIGINAL_COMPONENT_READ_REQUIRED");
        if (!prior.isEmpty()) {
            if (prior.size() != components.size()) throw conflict("ORIGINAL_COMPONENT_SET_CONFLICT");
            Map<String, CoopSettlementOriginalComponent> retained = new HashMap<>();
            for (var row : prior) retained.put(row.getSourceRegistrationId() + ":" + row.getSettlementId(), row);
            for (var row : components) requireSame(retained.get(row.getSourceRegistrationId() + ":" + row.getSettlementId()), row);
            return;
        }
        for (Long id : componentByHead.keySet()) {
            CoopSettlement head = lockedHeads.get(id);
            String kind = head.getMerchantId() == null ? "ACCOUNTING" : "MEMBER";
            if (originals.bindHeadToClose(id, close.getId(), close.getTopicId(), kind) != 1) throw conflict("HEAD_CLOSE_BINDING_CONFLICT");
        }
        for (var row : components) if (originals.insertComponent(row) != 1 || row.getId() == null) throw conflict("ORIGINAL_COMPONENT_WRITE_CONFLICT");
    }

    /** A批准阶段仅选择追收owner：有原close必须由本owner处理，不得并行扣旧settlement_detail。
     * 这里只读当前归属，不改变退款资格、金额、报名或资金。 */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public boolean hasFrozenOriginalRefundComponents(Long refundId) {
        requireTransaction();
        CoopOriginalRefundScope observed = originals.selectRefundScope(refundId);
        if (observed == null) throw conflict("ORIGINAL_REFUND_SCOPE_MISSING");
        if (!"registration".equals(observed.getSourceType())) return false;
        if (!Boolean.TRUE.equals(observed.getRelationPresent())) throw conflict("ORIGINAL_REFUND_RELATION_UNREADABLE");
        if (observed.getTopicId() == null) return false;
        CmsTopic topic = lockTopic(observed.getTopicId());
        CoopOriginalRefundScope current = originals.selectRefundScopeForUpdate(refundId);
        if (current == null || !Boolean.TRUE.equals(current.getRelationPresent()) || !topic.getId().equals(current.getTopicId())
                || !Objects.equals(observed.getSourceId(), current.getSourceId())) throw conflict("ORIGINAL_REFUND_SCOPE_CHANGED");
        CoopSettlementCashClose close = evidence.selectCloseForUpdate(topic.getId());
        CoopSettlementCashContribution source = evidence.selectContributionForUpdate(current.getSourceId());
        // 未写过本原分项close的历史路由仍属于原owner，不把旧聚合头伪装成新原分项。
        if (close == null && source == null) return false;
        if (close == null || source == null || !Objects.equals(close.getId(), source.getCashCloseId())
                || !topic.getId().equals(source.getSourceTopicId())) throw conflict("ORIGINAL_REFUND_SOURCE_NOT_FROZEN");
        List<CoopSettlementOriginalComponent> rows = originals.selectComponentsForPayerForUpdate(close.getId(),source.getPayerRegistrationId());
        if (rows == null || rows.isEmpty()) throw conflict("ORIGINAL_COMPONENTS_MISSING");
        return true;
    }

    /** 对齐A ae160684的ICoopSettlementClaimService终态hook，非报名退款与真实独立活动不伪造topic。 */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void reverseCompletedRegistrationRefund(Long refundId) {
        requireTransaction();
        CoopOriginalRefundScope scope = originals.selectRefundScope(refundId);
        if (scope == null) throw conflict("ORIGINAL_REFUND_SCOPE_MISSING");
        if (!"registration".equals(scope.getSourceType())) return;
        if (!Boolean.TRUE.equals(scope.getRelationPresent())) throw conflict("ORIGINAL_REFUND_RELATION_UNREADABLE");
        if (scope.getTopicId() == null) return; // Only an existing standalone activity has this valid shape.
        applySucceededRefund(scope.getTopicId(), refundId);
    }

    /** A先在同一事务写SUCCESS CAS，再调用本方法；任一异常必须回滚CAS、调整、钱包和回执。
     * B的完整原SUCCESS事实独立保留；本方法不发起退款、不改变资格/实际退款额。 */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public Optional<CoopSettlementRefundReceipt> applySucceededRefund(Long topicId, Long refundId) {
        requireTransaction(); lockTopic(topicId);
        if (refundId == null) throw conflict("VERIFIED_REFUND_FACT_REQUIRED");
        CoopOriginalRefundScope currentScope = originals.selectRefundScopeForUpdate(refundId);
        if (currentScope == null || !Boolean.TRUE.equals(currentScope.getRelationPresent())
                || !topicId.equals(currentScope.getTopicId())) throw conflict("ORIGINAL_REFUND_SCOPE_CHANGED");
        RefundApplication refund = originals.selectRefundForUpdate(refundId);
        if (refund == null || !Integer.valueOf(1).equals(refund.getStatus())
                || !(Integer.valueOf(RefundApplication.PAYOUT_SUCCESS).equals(refund.getPayoutStatus())
                    || Integer.valueOf(RefundApplication.PAYOUT_MANUAL_VERIFIED).equals(refund.getPayoutStatus()))
                || !"registration".equals(refund.getSourceType()) || !Objects.equals(currentScope.getSourceId(), refund.getSourceId())) throw conflict("REFUND_SUCCESS_CAS_REQUIRED");
        CoopSettlementCashClose close = evidence.selectCloseForUpdate(topicId);
        CoopSettlementCashContribution source = evidence.selectContributionForUpdate(refund.getSourceId());
        if (close == null && source == null) return Optional.empty(); // Legacy owner: no fabricated backfill or duplicate clawback.
        if (close == null || source == null || !Objects.equals(close.getId(), source.getCashCloseId())
                || !topicId.equals(source.getSourceTopicId())) throw conflict("ORIGINAL_REFUND_SOURCE_NOT_FROZEN");
        List<CoopSettlementOriginalComponent> components = originals.selectComponentsForPayerForUpdate(close.getId(), source.getPayerRegistrationId());
        if (components == null || components.isEmpty()) throw conflict("ORIGINAL_COMPONENTS_MISSING");
        for (var component : components) {
            if (component == null || !Objects.equals(close.getId(),component.getCashCloseId())
                    || !topicId.equals(component.getTopicId()) || !Objects.equals(source.getPayerRegistrationId(),component.getPayerRegistrationId())
                    || !Objects.equals(close.getOriginCalculationVersion(),component.getCalculationVersion())
                    || !Objects.equals(component.getSourceFingerprint(),fingerprint(component))) throw conflict("ORIGINAL_COMPONENT_FINGERPRINT_CONFLICT");
        }
        BigDecimal originalCash = cents(components.get(0).getOriginalOrderCash());
        CompletedProof proof = completedProof(refund, source, originalCash, components.get(0));
        Long verifiedFactId = proof.id(); String fingerprint = proof.fingerprint();
        CoopSettlementRefundReceipt prior = originals.selectReceiptForUpdate(refundId);
        if (prior != null) {
            if (!verifiedFactId.equals(prior.getVerifiedFactId()) || !topicId.equals(prior.getTopicId()) || !fingerprint.equals(prior.getSourceFingerprint())
                    || !proof.kind().equals(prior.getVerifiedFactKind()) || !proof.factHash().equals(prior.getVerifiedFactHash())
                    || !Objects.equals(close.getId(), prior.getCashCloseId())
                    || !Objects.equals(refund.getSourceId(), prior.getSourceRegistrationId())
                    || !Objects.equals(source.getPayerRegistrationId(), prior.getPayerRegistrationId())) throw conflict("REFUND_RECEIPT_PAYLOAD_CONFLICT");
            equal(prior.getRefundAmount(), proof.refundAmount(), "REFUND_RECEIPT_AMOUNT_CONFLICT");
            verifyReplay(prior, components);
            return Optional.of(prior);
        }
        equal(originalCash, proof.totalAmount(), "ORIGINAL_PAYMENT_TOTAL_CONFLICT");
        BigDecimal originalSum = ZERO;
        for (var row : components) {
            if (!close.getId().equals(row.getCashCloseId()) || !source.getPayerRegistrationId().equals(row.getPayerRegistrationId())) throw conflict("ORIGINAL_COMPONENT_SCOPE_CONFLICT");
            if (cents(row.getOriginalAmount()).signum() < 0) throw conflict("NEGATIVE_ORDER_MARGIN_POLICY_REQUIRED");
            equal(row.getOriginalOrderCash(), originalCash, "ORIGINAL_ORDER_TOTAL_CONFLICT"); originalSum = originalSum.add(row.getOriginalAmount());
            if (!Objects.equals(row.getPaymentFactId(), components.get(0).getPaymentFactId())
                    || !Objects.equals(row.getPaymentFactHash(), components.get(0).getPaymentFactHash())) throw conflict("ORIGINAL_PAYMENT_IDENTITY_CONFLICT");
        }
        equal(originalSum, originalCash, "ORIGINAL_ORDER_CONSERVATION");
        List<CoopSettlementRefundReceipt> previous = originals.selectReceiptsForPayerForUpdate(close.getId(), source.getPayerRegistrationId());
        if (previous == null) throw conflict("REFUND_RECEIPT_SET_REQUIRED");
        BigDecimal before = previous.stream().map(CoopSettlementRefundReceipt::getRefundAmount).map(CoopSettlementOriginalLedger::cents).reduce(ZERO, BigDecimal::add);
        BigDecimal after = before.add(proof.refundAmount());
        if (after.compareTo(originalCash) > 0) throw conflict("ORIGINAL_ORDER_OVER_REFUND");
        // 部分退款/分币新决策尚未答复；只执行不依赖该选择的首次精确全额原件冲回。
        if (before.signum() != 0 || proof.refundAmount().compareTo(originalCash) != 0) throw conflict("PARTIAL_REFUND_ROUNDING_DECISION_PENDING");
        Map<Long, CoopSettlement> lockedHeads = new TreeMap<>();
        for (var row : components) lockedHeads.put(row.getSettlementId(), null);
        for (Long id : new ArrayList<>(lockedHeads.keySet())) {
            CoopSettlement head = heads.selectByIdForUpdate(id);
            if (head == null || !topicId.equals(head.getTopicId()) || !(Integer.valueOf(0).equals(head.getStatus()) || Integer.valueOf(1).equals(head.getStatus()))) throw conflict("ORIGINAL_REFUND_HEAD_UNPROVEN");
            lockedHeads.put(id, head);
        }
        for (var head : lockedHeads.values()) {
            List<CoopSettlementAdjustment> priorAdjustments = legacyAdjustments.selectExecutedBySettlementForUpdate(head.getId());
            if (priorAdjustments == null) throw conflict("CURRENT_LEGACY_ADJUSTMENTS_REQUIRED");
            BigDecimal delta = components.stream().filter(row -> head.getId().equals(row.getSettlementId()))
                    .map(CoopSettlementOriginalComponent::getOriginalAmount).reduce(ZERO,BigDecimal::add).negate();
            BigDecimal net = cents(head.getAmount()).add(delta).add(sum(originals.selectAdjustmentsForSettlementForUpdate(head.getId())));
            for (var adjustment : priorAdjustments) {
                if (adjustment == null || !head.getId().equals(adjustment.getSettlementId()) || !"EXECUTED".equals(adjustment.getStatus())) throw conflict("LEGACY_ADJUSTMENT_SCOPE_CONFLICT");
                net = net.add(cents(adjustment.getAmount()));
            }
            if (net.signum() < 0) throw conflict("NEGATIVE_SETTLEMENT_HEAD_POLICY_REQUIRED");
        }
        CoopSettlementRefundReceipt receipt = new CoopSettlementRefundReceipt();
        receipt.setCashCloseId(close.getId()); receipt.setTopicId(topicId); receipt.setRefundId(refundId); receipt.setVerifiedFactId(verifiedFactId);
        receipt.setVerifiedFactKind(proof.kind());
        receipt.setVerifiedFactHash(proof.factHash());
        receipt.setSourceRegistrationId(refund.getSourceId()); receipt.setPayerRegistrationId(source.getPayerRegistrationId()); receipt.setCurrency("CNY");
        receipt.setRefundAmount(cents(proof.refundAmount())); receipt.setTotalAmount(originalCash); receipt.setCumulativeBefore(before); receipt.setCumulativeAfter(after);
        receipt.setReversedAmount(proof.refundAmount()); receipt.setCalculationVersion(close.getOriginCalculationVersion()); receipt.setSourceFingerprint(fingerprint);
        if (originals.insertReceipt(receipt) != 1 || receipt.getId() == null) throw conflict("REFUND_RECEIPT_WRITE_CONFLICT");
        components.sort(Comparator.comparing((CoopSettlementOriginalComponent row) -> row.getReceiverMemberId() == null ? 0L : row.getReceiverMemberId())
                .thenComparing(CoopSettlementOriginalComponent::getSettlementId).thenComparing(CoopSettlementOriginalComponent::getId));
        for (var row : components) {
            BigDecimal amount = cents(row.getOriginalAmount()); if (amount.signum() == 0) continue;
            CoopSettlement head = lockedHeads.get(row.getSettlementId());
            if (!Objects.equals(head.getMerchantId(), row.getReceiverMemberId()) || !Objects.equals(head.getLineType(), row.getLineType())) throw conflict("ORIGINAL_RECEIVER_CHANGED");
            boolean paid = "MEMBER".equals(row.getReceiverKind()) && Integer.valueOf(1).equals(head.getStatus());
            BigDecimal balance = paid ? members.selectBalanceForUpdate(row.getReceiverMemberId()) : ZERO;
            if (balance == null) throw conflict("ORIGINAL_RECEIVER_BALANCE_MISSING");
            if (paid) {
                List<BigDecimal> credits = originals.selectHeadCreditAmountsForUpdate(head.getId(),row.getReceiverMemberId(),head.getTopicId());
                if (credits == null || credits.size() > 1) throw conflict("ORIGINAL_PAYOUT_CREDIT_RECEIPT_CONFLICT");
                if (credits.isEmpty()) {
                    if (cents(head.getAmount()).signum() == 0) paid = false;
                    else throw conflict("ORIGINAL_PAYOUT_CREDIT_RECEIPT_MISSING");
                } else {
                    BigDecimal recovered = ZERO;
                    for (var previousAdjustment : originals.selectAdjustmentsForSettlementForUpdate(head.getId())) {
                        if (Boolean.TRUE.equals(previousAdjustment.getHeadWasPaid())) recovered = recovered.subtract(cents(previousAdjustment.getAmount()));
                    }
                    if (cents(credits.get(0)).subtract(recovered).compareTo(amount) < 0) throw conflict("ORIGINAL_PAYOUT_PRINCIPAL_INSUFFICIENT");
                }
            }
            BigDecimal debit = paid ? balance.max(ZERO).min(amount) : ZERO;
            BigDecimal debt = paid ? amount.subtract(debit) : ZERO;
            CoopSettlementOriginalAdjustment adjustment = new CoopSettlementOriginalAdjustment();
            adjustment.setCashCloseId(close.getId()); adjustment.setComponentId(row.getId()); adjustment.setReceiptId(receipt.getId());
            adjustment.setSettlementId(row.getSettlementId()); adjustment.setPayerRegistrationId(row.getPayerRegistrationId());
            adjustment.setAmount(amount.negate()); adjustment.setHeadWasPaid(paid); adjustment.setWalletDebit(debit); adjustment.setDebtAmount(debt);
            if (originals.insertAdjustment(adjustment) != 1 || adjustment.getId() == null) throw conflict("ORIGINAL_ADJUSTMENT_WRITE_CONFLICT");
            if (debit.signum() > 0) {
                if (members.deductBalance(row.getReceiverMemberId(), debit) != 1) throw conflict("ORIGINAL_REFUND_DEBIT_FAILED");
                UmsMemberBalanceDetail flow = new UmsMemberBalanceDetail(); flow.setMemberId(row.getReceiverMemberId());
                flow.setCurrentBalance(balance); flow.setAfterBalance(balance.subtract(debit)); flow.setChangeBalance(debit.negate());
                flow.setChangeReason("Coop原订单退款分项冲回"); flow.setEventType(94L); flow.setEventId(adjustment.getId()); flow.setChangeType(2L); flow.setDelFlag(0L); flow.setCreateTime(new Date());
                if (balanceLedger.insertUmsMemberBalanceDetail(flow) != 1) throw conflict("ORIGINAL_REFUND_BALANCE_RECEIPT_FAILED");
            }
            if (debt.signum() > 0) debts.recordOriginalRefund(row.getReceiverMemberId(), adjustment.getId(), debt);
        }
        equal(sum(originals.selectAdjustmentsForReceiptForUpdate(receipt.getId())).negate(), receipt.getReversedAmount(), "REFUND_COMPONENT_CONSERVATION");
        return Optional.of(receipt);
    }

    /** Caller holds the original topic fence and current registration locks, including zero/refunded/activity rows. */
    static void requireNoApprovedRefundsForOriginalClose(CoopSettlementOriginalMapper mapper, Long topicId) {
        List<Long> approved = mapper.selectApprovedRegistrationRefundIdsForOriginalCloseForUpdate(topicId);
        if (approved == null || !approved.isEmpty()) {
            throw conflict("MANUAL_REPAIR:REFUND_NET_BASIS_UNPROVEN:APPROVED_REFUND_PRECEDES_ORIGINAL_CLOSE");
        }
    }

    private CmsTopic lockTopic(Long id) {
        if (id == null || id <= 0) throw conflict("TOPIC_REQUIRED");
        CmsTopic topic = topics.selectCmsTopicByIdForUpdate(id);
        if (topic == null || !id.equals(topic.getId())) throw conflict("TOPIC_NOT_FOUND"); return topic;
    }
    private void requirePayment(WechatVerifiedPaymentFact fact, String payerOrderNo, BigDecimal originalCash) {
        if (fact == null) throw conflict("VERIFIED_ORIGINAL_PAYMENT_REQUIRED");
        fact.requireValid();
        if (fact.kind() != WechatVerifiedPaymentFact.Kind.PAYMENT || !Objects.equals(payerOrderNo, fact.businessOutTradeNo())
                || fact.amountFen() != cents(originalCash).movePointRight(2).longValueExact()) throw conflict("VERIFIED_ORIGINAL_PAYMENT_REQUIRED");
    }
    private record CompletedProof(Long id, String kind, String factHash, BigDecimal refundAmount, BigDecimal totalAmount, String fingerprint) { }

    private CompletedProof completedProof(RefundApplication refund, CoopSettlementCashContribution source, BigDecimal originalCash, CoopSettlementOriginalComponent origin) {
        var payment = verifiedFacts.requireVerified(new WechatVerifiedPaymentFact.Reference(origin.getPaymentFactId(), origin.getPaymentFactHash())).facts();
        requirePayment(payment, source.getPayerOrderNo(), originalCash);
        if (Integer.valueOf(RefundApplication.PAYOUT_SUCCESS).equals(refund.getPayoutStatus())) {
            var reference = originals.selectRefundVerifiedReferenceForUpdate(refund.getId());
            if (reference == null) throw conflict("VERIFIED_REFUND_INBOX_LINK_REQUIRED");
            var stored = verifiedFacts.requireVerified(reference); var fact = stored.facts(); fact.requireValid();
            if (fact.kind() != WechatVerifiedPaymentFact.Kind.REFUND || !Objects.equals(refund.getRefundNo(), fact.merchantEventNo())
                    || !Objects.equals(source.getPayerOrderNo(), fact.businessOutTradeNo())
                    || !payment.mchid().equals(fact.mchid()) || !payment.appid().equals(fact.appid())
                    || !payment.wxOutTradeNo().equals(fact.wxOutTradeNo()) || !payment.transactionId().equals(fact.transactionId())
                    || !payment.tradeType().equals(fact.tradeType()) || !payment.currency().equals(fact.currency())
                    || payment.totalFen() != fact.totalFen()) throw conflict("VERIFIED_REFUND_ORIGINAL_IDENTITY_CONFLICT");
            BigDecimal refunded = BigDecimal.valueOf(fact.amountFen(), 2); equal(refund.getRefundAmount(), refunded, "REFUND_AMOUNT_CONFLICT");
            String boundHash = fingerprint(Map.of("refundFactId",reference.id(),"refundFactHash",reference.hash(),
                    "paymentFactId",origin.getPaymentFactId(),"paymentFactHash",origin.getPaymentFactHash(),
                    "refundId",refund.getId(),"sourceRegistrationId",refund.getSourceId()));
            return new CompletedProof(reference.id(), "WX", reference.hash(), refunded, BigDecimal.valueOf(fact.totalFen(), 2), boundHash);
        }
        if (refund.getPayoutHandlerId() == null || refund.getPayoutHandlerId() <= 0 || refund.getPayoutReviewer2Id() == null
                || refund.getPayoutReviewer2Id() <= 0 || refund.getPayoutHandlerId().equals(refund.getPayoutReviewer2Id())
                || blank(refund.getPayoutReceiptUrl()) || blank(refund.getPayoutMethod())
                || refund.getPayoutHandledAt() == null || refund.getPayoutReviewed2At() == null) throw conflict("MANUAL_ORIGINAL_TWO_PERSON_RECEIPT_REQUIRED");
        Map<String, Object> original = new TreeMap<>();
        original.put("refundId", refund.getId()); original.put("sourceId", refund.getSourceId()); original.put("payerOrderNo", source.getPayerOrderNo());
        original.put("handler", refund.getPayoutHandlerId()); original.put("reviewer", refund.getPayoutReviewer2Id());
        original.put("handledAt", refund.getPayoutHandledAt().getTime()); original.put("reviewedAt", refund.getPayoutReviewed2At().getTime());
        original.put("method", refund.getPayoutMethod()); original.put("receiptHash", fingerprint(Map.of("receipt", refund.getPayoutReceiptUrl())));
        original.put("refundAmount", cents(refund.getRefundAmount()).toPlainString()); original.put("totalAmount", originalCash.toPlainString());
        String hash = fingerprint(original);
        return new CompletedProof(refund.getId(), "MANUAL", hash, cents(refund.getRefundAmount()), originalCash, hash);
    }
    private static BigDecimal cents(BigDecimal amount) {
        if (amount == null) throw conflict("EXACT_MONEY_REQUIRED"); return amount.setScale(2, RoundingMode.UNNECESSARY);
    }
    private static void equal(BigDecimal a, BigDecimal b, String reason) { if (cents(a).compareTo(cents(b)) != 0) throw conflict(reason); }
    private static BigDecimal sum(List<CoopSettlementOriginalAdjustment> rows) {
        if (rows == null) throw conflict("CURRENT_ADJUSTMENTS_REQUIRED");
        return rows.stream().map(CoopSettlementOriginalAdjustment::getAmount).map(CoopSettlementOriginalLedger::cents).reduce(ZERO, BigDecimal::add);
    }
    private String fingerprint(Object value) {
        try {
            ObjectNode node = payload(value);
            Map<String, Object> canonical = new TreeMap<>();
            node.fields().forEachRemaining(entry -> canonical.put(entry.getKey(), entry.getValue().isNumber()
                    ? entry.getValue().decimalValue().stripTrailingZeros().toPlainString() : entry.getValue()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(canonical)));
        }
        catch (Exception e) { throw new IllegalStateException("ORIGINAL_PAYLOAD_FINGERPRINT_FAILED", e); }
    }
    private ObjectNode payload(Object value) {
        ObjectNode payload = json.valueToTree(value); payload.remove(List.of("id", "createdAt", "sourceFingerprint")); return payload;
    }
    private void requireSame(Object stored, Object requested) {
        if (stored == null || !payload(stored).equals(payload(requested))) throw conflict("ORIGINAL_COMPONENT_PAYLOAD_CONFLICT");
        if (stored instanceof CoopSettlementOriginalComponent row && requested instanceof CoopSettlementOriginalComponent expected
                && !Objects.equals(row.getSourceFingerprint(), expected.getSourceFingerprint())) throw conflict("ORIGINAL_COMPONENT_FINGERPRINT_CONFLICT");
    }
    private void verifyReplay(CoopSettlementRefundReceipt receipt, List<CoopSettlementOriginalComponent> components) {
        List<CoopSettlementOriginalAdjustment> rows = originals.selectAdjustmentsForReceiptForUpdate(receipt.getId());
        if (rows == null) throw conflict("REFUND_RECEIPT_EFFECT_REQUIRED");
        Map<Long, CoopSettlementOriginalComponent> expected = new HashMap<>();
        for (var component : components) if (cents(component.getOriginalAmount()).signum() != 0) expected.put(component.getId(), component);
        for (var row : rows) {
            var component = expected.remove(row.getComponentId());
            if (component == null || !Objects.equals(receipt.getId(), row.getReceiptId())
                    || !Objects.equals(component.getSettlementId(), row.getSettlementId())
                    || !Objects.equals(receipt.getCashCloseId(), row.getCashCloseId())
                    || !Objects.equals(receipt.getPayerRegistrationId(), row.getPayerRegistrationId())) throw conflict("REFUND_RECEIPT_COMPONENT_CONFLICT");
            equal(row.getAmount(), component.getOriginalAmount().negate(), "REFUND_RECEIPT_COMPONENT_AMOUNT_CONFLICT");
        }
        if (!expected.isEmpty()) throw conflict("REFUND_RECEIPT_COMPONENTS_INCOMPLETE");
        equal(sum(rows).negate(), receipt.getReversedAmount(), "REFUND_RECEIPT_EFFECT_CONFLICT");
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw conflict("ORIGINAL_FUNDS_TRANSACTION_REQUIRED");
    }
    private static IllegalStateException conflict(String reason) { return new IllegalStateException(reason); }
}
