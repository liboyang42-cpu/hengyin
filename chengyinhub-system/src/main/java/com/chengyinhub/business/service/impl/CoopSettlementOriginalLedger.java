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
import java.math.BigInteger;
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
    private final com.chengyinhub.business.service.support.RegistrationPaymentConsumptionReadService paymentConsumptions;
    private final UmsMemberMapper members;
    private final IUmsMemberBalanceDetailService balanceLedger;
    private final SettlementDebtLedger debts;
    private final ObjectMapper json = new ObjectMapper();
    @org.springframework.beans.factory.annotation.Autowired
    private CoopSettlementEvidenceMapper originalCloseEvidence;


    public CoopSettlementOriginalLedger(CmsTopicMapper topics, CoopSettlementCashEvidenceMapper evidence,
            CoopSettlementOriginalMapper originals, CoopSettlementMapper heads, CoopSettlementAdjustmentMapper legacyAdjustments, WechatVerifiedPaymentFactService verifiedFacts,
            com.chengyinhub.business.service.support.RegistrationPaymentConsumptionReadService paymentConsumptions,
            UmsMemberMapper members, IUmsMemberBalanceDetailService balanceLedger, SettlementDebtLedger debts) {
        this.topics = topics; this.evidence = evidence; this.originals = originals; this.heads = heads;
        this.legacyAdjustments = legacyAdjustments;
        this.verifiedFacts = verifiedFacts; this.paymentConsumptions = paymentConsumptions; this.members = members; this.balanceLedger = balanceLedger; this.debts = debts;
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
        Set<Long> consumedPaymentPayers = new HashSet<>();
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
            if (previousPayment == null) {
                var consumed = paymentConsumptions.findConsumedPayment(component.getPayerRegistrationId());
                if (consumed.isPresent()) {
                    if (!paymentReference.equals(consumed.get().reference())) throw conflict("ORIGINAL_CONSUMED_PAYMENT_REFERENCE_CONFLICT");
                    if (!payer.getRegistrationNo().equals(consumed.get().registrationNo())
                            || !payment.transactionId().equals(consumed.get().transactionId()))
                        throw conflict("ORIGINAL_CONSUMED_PAYMENT_IDENTITY_CONFLICT");
                    consumedPaymentPayers.add(component.getPayerRegistrationId());
                }
            }
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
        for (var row : components) {
            int inserted = consumedPaymentPayers.contains(row.getPayerRegistrationId())
                    ? originals.insertConsumedPaymentComponent(row) : originals.insertComponent(row);
            if (inserted != 1 || row.getId() == null) throw conflict("ORIGINAL_COMPONENT_WRITE_CONFLICT");
        }
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
        RefundHistory history = refundHistory(previous, close, source, components, originalCash);
        CoopSettlementRefundReceipt prior = originals.selectReceiptForUpdate(refundId);
        CoopSettlementRefundReceipt retained = history.receipts().get(refundId);
        if (prior != null || retained != null) {
            if (prior == null || retained == null || !Objects.equals(prior.getId(),retained.getId()))
                throw conflict("REFUND_RECEIPT_HISTORY_CONFLICT");
            requireSame(retained, prior);
            if (!verifiedFactId.equals(prior.getVerifiedFactId()) || !topicId.equals(prior.getTopicId()) || !fingerprint.equals(prior.getSourceFingerprint())
                    || !proof.kind().equals(prior.getVerifiedFactKind()) || !proof.factHash().equals(prior.getVerifiedFactHash())
                    || !Objects.equals(close.getId(), prior.getCashCloseId())
                    || !Objects.equals(refund.getSourceId(), prior.getSourceRegistrationId())
                    || !Objects.equals(source.getPayerRegistrationId(), prior.getPayerRegistrationId())) throw conflict("REFUND_RECEIPT_PAYLOAD_CONFLICT");
            equal(prior.getRefundAmount(), proof.refundAmount(), "REFUND_RECEIPT_AMOUNT_CONFLICT");
            return Optional.of(prior);
        }
        BigDecimal before = history.refunded();
        BigDecimal after = before.add(cents(proof.refundAmount()));
        if (proof.refundAmount().signum() <= 0 || after.compareTo(originalCash) > 0)
            throw conflict("ORIGINAL_ORDER_OVER_REFUND");
        Map<Long, BigDecimal> reversals = cumulativeReversals(components, originalCash, before, after, history.reversed());
        BigDecimal reversing = reversals.values().stream().reduce(ZERO, BigDecimal::add);
        equal(reversing, proof.refundAmount(), "REFUND_COMPONENT_PLAN_CONFLICT");
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
                    .map(row -> reversals.get(row.getId())).reduce(ZERO,BigDecimal::add).negate();
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
        receipt.setReversedAmount(reversing); receipt.setCalculationVersion(close.getOriginCalculationVersion()); receipt.setSourceFingerprint(fingerprint);
        if (originals.insertReceipt(receipt) != 1 || receipt.getId() == null) throw conflict("REFUND_RECEIPT_WRITE_CONFLICT");
        components.sort(Comparator.comparing((CoopSettlementOriginalComponent row) -> row.getReceiverMemberId() == null ? 0L : row.getReceiverMemberId())
                .thenComparing(CoopSettlementOriginalComponent::getSettlementId).thenComparing(CoopSettlementOriginalComponent::getId));
        for (var row : components) {
            BigDecimal amount = reversals.get(row.getId()); if (amount.signum() == 0) continue;
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
        verifyReplay(receipt, components, reversals);
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
    private record RefundHistory(BigDecimal refunded, Map<Long, BigDecimal> reversed,
            Map<Long, CoopSettlementRefundReceipt> receipts) { }

    /** Only committed success receipts/effects form the cumulative basis; topic locking serializes arrival order. */
    private RefundHistory refundHistory(List<CoopSettlementRefundReceipt> receipts, CoopSettlementCashClose close,
            CoopSettlementCashContribution payer, List<CoopSettlementOriginalComponent> components, BigDecimal originalCash) {
        List<CoopSettlementRefundReceipt> ordered = new ArrayList<>(receipts);
        for (var receipt : ordered) if (receipt == null || receipt.getId() == null || receipt.getId() <= 0)
            throw conflict("REFUND_HISTORY_IDENTITY_REQUIRED");
        ordered.sort(Comparator.comparing(CoopSettlementRefundReceipt::getId));
        Set<Long> receiptIds = new HashSet<>();
        Set<String> factIds = new HashSet<>();
        Map<Long, CoopSettlementRefundReceipt> byRefund = new HashMap<>();
        Map<Long, BigDecimal> reversed = new HashMap<>();
        BigDecimal refunded = ZERO;
        for (var receipt : ordered) {
            String kind = receipt.getVerifiedFactKind();
            if (!receiptIds.add(receipt.getId()) || receipt.getRefundId() == null || receipt.getRefundId() <= 0
                    || byRefund.put(receipt.getRefundId(),receipt) != null || receipt.getVerifiedFactId() == null
                    || receipt.getVerifiedFactId() <= 0 || !("WX".equals(kind) || "MANUAL".equals(kind))
                    || ("MANUAL".equals(kind) && !receipt.getRefundId().equals(receipt.getVerifiedFactId()))
                    || receipt.getSourceRegistrationId() == null || receipt.getSourceRegistrationId() <= 0
                    || receipt.getSourceFingerprint() == null || !receipt.getSourceFingerprint().matches("[0-9a-f]{64}")
                    || !factIds.add(kind+":"+receipt.getVerifiedFactId())
                    || receipt.getVerifiedFactHash() == null || !receipt.getVerifiedFactHash().matches("[0-9a-f]{64}")
                    || !close.getId().equals(receipt.getCashCloseId()) || !close.getTopicId().equals(receipt.getTopicId())
                    || !payer.getPayerRegistrationId().equals(receipt.getPayerRegistrationId())
                    || !"CNY".equals(receipt.getCurrency())
                    || !close.getOriginCalculationVersion().equals(receipt.getCalculationVersion()))
                throw conflict("REFUND_HISTORY_IDENTITY_CONFLICT");
            equal(receipt.getTotalAmount(), originalCash, "REFUND_HISTORY_TOTAL_CONFLICT");
            equal(receipt.getCumulativeBefore(), refunded, "REFUND_HISTORY_CUMULATIVE_CONFLICT");
            BigDecimal amount = cents(receipt.getRefundAmount());
            BigDecimal after = refunded.add(amount);
            if (amount.signum() <= 0 || after.compareTo(originalCash) > 0) throw conflict("ORIGINAL_ORDER_OVER_REFUND");
            equal(receipt.getCumulativeAfter(), after, "REFUND_HISTORY_CUMULATIVE_CONFLICT");
            equal(receipt.getReversedAmount(), amount, "REFUND_RECEIPT_EFFECT_CONFLICT");
            Map<Long, BigDecimal> plan = cumulativeReversals(components, originalCash, refunded, after, reversed);
            for (var entry : verifyReplay(receipt, components, plan).entrySet())
                reversed.merge(entry.getKey(), entry.getValue(), BigDecimal::add);
            refunded = after;
        }
        return new RefundHistory(refunded, Map.copyOf(reversed), Map.copyOf(byRefund));
    }

    /** Project cumulative proportions onto prior reversals, then distribute only this order's remaining cents. */
    private Map<Long, BigDecimal> cumulativeReversals(List<CoopSettlementOriginalComponent> components,
            BigDecimal originalCash, BigDecimal before, BigDecimal after, Map<Long, BigDecimal> reversed) {
        record Share(CoopSettlementOriginalComponent component, long capacity, BigInteger gap) { }
        record Tail(Share share, BigInteger remainder) { }
        long denominator = cents(originalCash).movePointRight(2).longValueExact();
        long beforeFen = cents(before).movePointRight(2).longValueExact();
        long afterFen = cents(after).movePointRight(2).longValueExact();
        if (denominator <= 0 || beforeFen < 0 || afterFen <= beforeFen || afterFen > denominator)
            throw conflict("ORIGINAL_ORDER_OVER_REFUND");
        long required = afterFen-beforeFen;
        BigInteger base = BigInteger.valueOf(denominator);
        Set<Long> ids = new HashSet<>();
        Map<Long, Long> allocated = new HashMap<>();
        List<Share> active = new ArrayList<>();
        long originalSum = 0, alreadySum = 0;
        for (var component : components) {
            if (component.getId() == null || component.getId() <= 0 || !ids.add(component.getId())
                    || component.getSourceRegistrationId() == null || component.getSettlementId() == null)
                throw conflict("ORIGINAL_COMPONENT_IDENTITY_CONFLICT");
            long original = cents(component.getOriginalAmount()).movePointRight(2).longValueExact();
            long already = cents(reversed.getOrDefault(component.getId(),ZERO)).movePointRight(2).longValueExact();
            if (original < 0 || already < 0 || already > original) throw conflict("ORIGINAL_COMPONENT_OVER_REVERSED");
            originalSum = Math.addExact(originalSum,original); alreadySum = Math.addExact(alreadySum,already);
            allocated.put(component.getId(),afterFen == denominator ? original-already : 0L);
            BigInteger gap = BigInteger.valueOf(original).multiply(BigInteger.valueOf(afterFen))
                    .subtract(BigInteger.valueOf(already).multiply(base));
            if (gap.signum() > 0) active.add(new Share(component,original-already,gap));
        }
        if (originalSum != denominator || alreadySum != beforeFen || !ids.containsAll(reversed.keySet()))
            throw conflict("ORIGINAL_COMPONENT_BASIS_CONFLICT");
        if (afterFen != denominator) {
            // Earlier tail cents are lower bounds. Never take them back when cumulative remainder ranks change.
            active.sort(Comparator.comparing(Share::gap));
            BigInteger sum = active.stream().map(Share::gap).reduce(BigInteger.ZERO,BigInteger::add);
            BigInteger needed = base.multiply(BigInteger.valueOf(required));
            int first = 0;
            while (first < active.size()) {
                int count = active.size()-first;
                BigInteger excess = sum.subtract(needed);
                if (excess.signum() < 0) throw conflict("ORIGINAL_COMPONENT_CAPACITY_CONFLICT");
                if (active.get(first).gap().multiply(BigInteger.valueOf(count)).compareTo(excess) > 0) break;
                sum = sum.subtract(active.get(first++).gap());
            }
            int count = active.size()-first;
            if (count == 0) throw conflict("ORIGINAL_COMPONENT_CAPACITY_CONFLICT");
            BigInteger excess = sum.subtract(needed);
            BigInteger divisor = base.multiply(BigInteger.valueOf(count));
            List<Tail> tails = new ArrayList<>();
            long assigned = 0;
            for (int i=first; i<active.size(); i++) {
                Share share = active.get(i);
                BigInteger numerator = share.gap().multiply(BigInteger.valueOf(count)).subtract(excess);
                BigInteger[] division = numerator.divideAndRemainder(divisor);
                long amount = division[0].longValueExact();
                if (amount < 0 || amount > share.capacity()) throw conflict("ORIGINAL_COMPONENT_CAPACITY_CONFLICT");
                allocated.put(share.component().getId(),amount); assigned = Math.addExact(assigned,amount);
                tails.add(new Tail(share,division[1]));
            }
            tails.sort((left,right) -> {
                int rank = right.remainder().compareTo(left.remainder());
                return rank != 0 ? rank : compareOriginalIdentity(left.share().component(),right.share().component());
            });
            int remaining = Math.toIntExact(required-assigned);
            if (remaining < 0 || remaining > tails.size()) throw conflict("REFUND_COMPONENT_PLAN_CONFLICT");
            for (int i=0; i<remaining; i++) {
                Tail tail = tails.get(i); Long id = tail.share().component().getId();
                long amount = allocated.get(id)+1;
                if (tail.remainder().signum() <= 0 || amount > tail.share().capacity())
                    throw conflict("ORIGINAL_COMPONENT_CAPACITY_CONFLICT");
                allocated.put(id,amount);
            }
        }
        Map<Long, BigDecimal> result = new HashMap<>();
        allocated.forEach((id,amount) -> result.put(id,BigDecimal.valueOf(amount,2)));
        equal(result.values().stream().reduce(ZERO,BigDecimal::add),after.subtract(before),"REFUND_COMPONENT_PLAN_CONFLICT");
        return result;
    }

    private static int compareOriginalIdentity(CoopSettlementOriginalComponent left, CoopSettlementOriginalComponent right) {
        int order = left.getSourceRegistrationId().compareTo(right.getSourceRegistrationId());
        if (order == 0) order = left.getSettlementId().compareTo(right.getSettlementId());
        return order != 0 ? order : left.getId().compareTo(right.getId());
    }

    private Map<Long, BigDecimal> verifyReplay(CoopSettlementRefundReceipt receipt,
            List<CoopSettlementOriginalComponent> components, Map<Long, BigDecimal> expected) {
        List<CoopSettlementOriginalAdjustment> rows = originals.selectAdjustmentsForReceiptForUpdate(receipt.getId());
        if (rows == null) throw conflict("REFUND_RECEIPT_EFFECT_REQUIRED");
        Map<Long, CoopSettlementOriginalComponent> byId = new HashMap<>();
        for (var component : components) byId.put(component.getId(),component);
        Map<Long, BigDecimal> actual = new HashMap<>();
        BigDecimal total = ZERO;
        for (var row : rows) {
            var component = row == null ? null : byId.get(row.getComponentId());
            if (component == null || actual.containsKey(row.getComponentId())
                    || !Objects.equals(receipt.getId(), row.getReceiptId())
                    || !Objects.equals(component.getSettlementId(), row.getSettlementId())
                    || !Objects.equals(receipt.getCashCloseId(), row.getCashCloseId())
                    || !Objects.equals(receipt.getPayerRegistrationId(), row.getPayerRegistrationId()))
                throw conflict("REFUND_RECEIPT_COMPONENT_CONFLICT");
            BigDecimal amount = cents(row.getAmount()).negate();
            BigDecimal debit = cents(row.getWalletDebit()), debt = cents(row.getDebtAmount());
            if (amount.signum() <= 0 || debit.signum() < 0 || debt.signum() < 0 || row.getHeadWasPaid() == null)
                throw conflict("REFUND_RECEIPT_COMPONENT_AMOUNT_CONFLICT");
            equal(amount,expected.getOrDefault(row.getComponentId(),ZERO),"REFUND_RECEIPT_COMPONENT_AMOUNT_CONFLICT");
            equal(debit.add(debt),Boolean.TRUE.equals(row.getHeadWasPaid()) ? amount : ZERO,"REFUND_RECEIPT_MONEY_EFFECT_CONFLICT");
            actual.put(component.getId(),amount); total=total.add(amount);
        }
        for (var component : components)
            equal(actual.getOrDefault(component.getId(),ZERO),expected.get(component.getId()),"REFUND_RECEIPT_COMPONENTS_INCOMPLETE");
        equal(total, receipt.getReversedAmount(), "REFUND_RECEIPT_EFFECT_CONFLICT");
        return actual;
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw conflict("ORIGINAL_FUNDS_TRANSACTION_REQUIRED");
    }
    private static IllegalStateException conflict(String reason) { return new IllegalStateException(reason); }
}
