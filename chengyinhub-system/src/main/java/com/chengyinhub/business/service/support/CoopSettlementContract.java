package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.dto.CoopSettlementCandidate;
import com.chengyinhub.business.domain.dto.CoopSettlementOriginLine;
import com.chengyinhub.business.domain.dto.CoopSettlementPlan;
import com.chengyinhub.business.domain.dto.CoopSettlementSnapshot;
import com.chengyinhub.common.constant.ProductType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 合同四的唯一选集与金额算法；不读 live 条款。 */
public final class CoopSettlementContract {
    public static final String LEDGER_CONFLICT = "LEDGER_CONFLICT";
    public static final String MANUAL_REPAIR = "MANUAL_REPAIR";
    private static final String GUIDED_TICKET = "GUIDED_TICKET";

    private CoopSettlementContract() {}

    /** 本批只接通已批准且可证的经典主题票、主题级商家逐票扫码；其他路径明确拒结。 */
    public static void requireSupportedCashPath(CoopSettlementSnapshot s) {
        if (s == null || !Integer.valueOf(ProductType.ORIENTEERING).equals(s.getProductType())
                || s.getInitiatorMemberId() == null || s.getInitiatorMemberId() <= 0
                || !"v1".equals(s.getTermsVersion()) || s.getCatalogFrozenAt() == null
                || !"TOPIC".equals(s.getScopeType()) || !same(s.getTopicId(), s.getScopeId())
                || !"TOPIC_EDITION".equals(s.getCommercialContextType())
                || !same(s.getTopicId(), s.getCommercialContextId())
                || !"MERCHANT".equals(s.getBeneficiaryRole()) || s.getBeneficiaryId() == null
                || s.getBeneficiaryId() <= 0 || !"MERCHANT_PARTICIPATION".equals(s.getServiceUnit())
                || !("REGISTRATION_SCAN".equals(s.getFulfillmentEvidenceRule()) || "COOP_GROUP_VERIFY".equals(s.getFulfillmentEvidenceRule()))
                || s.getEligiblePurchaseKinds() == null
                || !s.getEligiblePurchaseKinds().equals(Collections.singletonList(GUIDED_TICKET))) {
            throw manualRepair("UNSUPPORTED_CASH_PATH:NODE/ACTIVITY/CLUB/非冻结受款或证据路径不得进入经典主题聚合");
        }
    }

    public static List<CoopSettlementOriginLine> originLines(CoopSettlementSnapshot snapshot,
            List<CoopSettlementCandidate> candidates, Date settlementCutoff) {
        validateSnapshot(snapshot, settlementCutoff);
        Map<Long, CoopSettlementCandidate> distinct = new TreeMap<>();
        if (candidates != null) {
            for (CoopSettlementCandidate candidate : candidates) {
                if (eligible(snapshot, candidate, settlementCutoff)) {
                    distinct.put(candidate.getRegistrationId(), candidate);
                }
            }
        }

        List<CoopSettlementOriginLine> lines = new ArrayList<>();
        if ("FIXED_PER_FULFILLMENT".equals(snapshot.getCompensationMode())) {
            requirePositiveOrZero(snapshot.getFixedFee(), "fixedFeeSnapshot");
            for (CoopSettlementCandidate candidate : distinct.values()) {
                int verifiedSeats = candidate.getVerifiedSeatIds() == null ? 1 : candidate.getVerifiedSeatIds().size();
                lines.add(line(snapshot, candidate.getRegistrationId(), snapshot.getFixedFee().multiply(BigDecimal.valueOf(verifiedSeats)).setScale(2),
                        candidate.getEligibleSalesSnapshot()));
            }
            return lines;
        }
        if ("SALES_PERCENT".equals(snapshot.getCompensationMode())) {
            requirePositiveOrZero(snapshot.getShareRate(), "shareRateSnapshot");
            allocateLargestRemainder(snapshot, distinct, lines);
            return lines;
        }
        if ("NONE".equals(snapshot.getCompensationMode())) {
            for (CoopSettlementCandidate candidate : distinct.values()) {
                lines.add(line(snapshot, candidate.getRegistrationId(), BigDecimal.ZERO.setScale(2),
                        candidate.getEligibleSalesSnapshot()));
            }
            return lines;
        }
        throw manualRepair("compensationMode 快照非法:" + snapshot.getCompensationMode());
    }

    /**
     * Every plan comes from prepare's closed frozen window and current evidence read.
     * A registration is both the existing claim fulfillment identity and the original
     * sale range. Its scan/group-member/seat sources all belong to that registration;
     * no amount or winner is chosen to hide overlapping originals for one beneficiary.
     */
    public static void requireIndependentOrderScopes(List<CoopSettlementPlan> plans) {
        if (plans == null) throw manualRepair("ORDER_SCOPE_PLANS_REQUIRED");
        java.util.Set<Long> orders = new java.util.HashSet<>();
        Long topicId = null;
        Long initiatorId = null;
        Map<String, Map<Long, Long>> registrationsByBeneficiary = new LinkedHashMap<>();
        for (CoopSettlementPlan plan : plans) {
            CoopSettlementSnapshot snapshot = plan == null ? null : plan.getSnapshot();
            // Scope invariants are separate from prepare's mandatory cash-path admission.
            // A legitimate CLUB/ORGANIZER plan can be checked here without enabling its cash route.
            if (snapshot == null || snapshot.getOrderId() == null || snapshot.getOrderId() <= 0
                    || snapshot.getTopicId() == null || snapshot.getTopicId() <= 0
                    || snapshot.getInitiatorMemberId() == null || snapshot.getInitiatorMemberId() <= 0
                    || snapshot.getBeneficiaryId() == null || snapshot.getBeneficiaryId() <= 0
                    || !("MERCHANT".equals(snapshot.getBeneficiaryRole()) && "MERCHANT_PARTICIPATION".equals(snapshot.getServiceUnit())
                        || "CLUB".equals(snapshot.getBeneficiaryRole()) && "ORGANIZER_LEAD".equals(snapshot.getServiceUnit()))
                    || snapshot.getCommercialContextId() == null || snapshot.getCommercialContextId() <= 0
                    || !("TOPIC_EDITION".equals(snapshot.getCommercialContextType()) || "ACTIVITY".equals(snapshot.getCommercialContextType()))
                    || "TOPIC_EDITION".equals(snapshot.getCommercialContextType()) && !same(snapshot.getTopicId(), snapshot.getCommercialContextId())
                    || snapshot.getScopeId() == null || snapshot.getScopeId() <= 0
                    || !("TOPIC".equals(snapshot.getScopeType()) || "NODE".equals(snapshot.getScopeType()) || "ACTIVITY".equals(snapshot.getScopeType()))
                    || "TOPIC".equals(snapshot.getScopeType()) && !same(snapshot.getTopicId(), snapshot.getScopeId())
                    || !orders.add(snapshot.getOrderId()) || plan.getOriginLines() == null
                    || !("NONE".equals(plan.getRoute()) && "NONE".equals(snapshot.getCompensationMode())
                        || "COOP_ORDER".equals(plan.getRoute())
                            && ("SALES_PERCENT".equals(snapshot.getCompensationMode()) || "FIXED_PER_FULFILLMENT".equals(snapshot.getCompensationMode()))
                            && !plan.getOriginLines().isEmpty())
                    || topicId != null && !same(topicId, snapshot.getTopicId())
                    || initiatorId != null && !same(initiatorId, snapshot.getInitiatorMemberId()))
                throw manualRepair("ORIGINAL_ORDER_SCOPE_IDENTITY_UNPROVEN");
            topicId = snapshot.getTopicId();
            initiatorId = snapshot.getInitiatorMemberId();
            String beneficiary = snapshot.getBeneficiaryRole() + ":" + snapshot.getBeneficiaryId();
            Map<Long, Long> occupied = registrationsByBeneficiary.computeIfAbsent(beneficiary, ignored -> new TreeMap<>());
            java.util.Set<Long> local = new java.util.HashSet<>();
            for (CoopSettlementOriginLine line : plan.getOriginLines()) {
                if (line == null || line.getRegistrationId() == null || line.getRegistrationId() <= 0
                        || !local.add(line.getRegistrationId()) || line.getOriginAmount() == null
                        || line.getOriginAmount().signum() < 0 || line.getEligibleSalesSnapshot() == null
                        || line.getEligibleSalesSnapshot().signum() < 0
                        || "NONE".equals(plan.getRoute()) && line.getOriginAmount().signum() != 0)
                    throw manualRepair("ORIGINAL_ORDER_SALES_OR_FULFILLMENT_SCOPE_UNPROVEN");
                requireHeaderEquality(snapshot, line);
                Long previous = occupied.putIfAbsent(line.getRegistrationId(), snapshot.getOrderId());
                if (previous != null)
                    throw manualRepair("ORIGINAL_ORDER_SCOPE_OVERLAP:orders=" + previous + "," + snapshot.getOrderId()
                            + ":registration=" + line.getRegistrationId());
            }
        }
    }

    public static BigDecimal sumOrigin(List<CoopSettlementOriginLine> lines) {
        BigDecimal sum = BigDecimal.ZERO.setScale(2);
        for (CoopSettlementOriginLine line : lines) sum = sum.add(line.getOriginAmount());
        return sum;
    }

    public static void requireHeaderEquality(CoopSettlementSnapshot header, CoopSettlementOriginLine detail) {
        if (!"COOP_ORDER".equals(detail.getSettlementRoute())
                || !"COOP_ORDER".equals(detail.getSourceContractType())
                || !same(header.getOrderId(), detail.getSourceContractId())
                || !same(header.getCommercialContextType(), detail.getCommercialContextType())
                || !same(header.getCommercialContextId(), detail.getCommercialContextId())
                || !same(header.getBeneficiaryRole(), detail.getBeneficiaryRole())
                || !same(header.getBeneficiaryId(), detail.getBeneficiaryId())
                || !same(header.getServiceUnit(), detail.getServiceUnit())) {
            throw new IllegalStateException(LEDGER_CONFLICT + ":coop detail/header 归属字段不全等 registrationId="
                    + detail.getRegistrationId());
        }
    }

    private static void allocateLargestRemainder(CoopSettlementSnapshot snapshot,
            Map<Long, CoopSettlementCandidate> distinct, List<CoopSettlementOriginLine> out) {
        BigDecimal rate = snapshot.getShareRate().movePointLeft(2);
        BigDecimal eligibleTotal = BigDecimal.ZERO;
        List<Share> shares = new ArrayList<>();
        BigDecimal baseSum = BigDecimal.ZERO.setScale(2);
        for (CoopSettlementCandidate candidate : distinct.values()) {
            if (candidate.getEligibleSalesSnapshot() == null
                    || candidate.getEligibleSalesSnapshot().compareTo(BigDecimal.ZERO) < 0) {
                throw manualRepair("registration " + candidate.getRegistrationId() + " 缺 eligibleSalesSnapshot");
            }
            eligibleTotal = eligibleTotal.add(candidate.getEligibleSalesSnapshot());
            BigDecimal raw = candidate.getEligibleSalesSnapshot().multiply(rate);
            BigDecimal base = raw.setScale(2, RoundingMode.DOWN);
            shares.add(new Share(candidate.getRegistrationId(), base, raw.subtract(base)));
            baseSum = baseSum.add(base);
        }
        BigDecimal target = eligibleTotal.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        int cents = target.subtract(baseSum).movePointRight(2).intValueExact();
        Collections.sort(shares, Comparator.comparing(Share::getRemainder).reversed()
                .thenComparing(Share::getRegistrationId));
        for (int i = 0; i < cents; i++) shares.get(i).amount = shares.get(i).amount.add(new BigDecimal("0.01"));
        Collections.sort(shares, Comparator.comparing(Share::getRegistrationId));
        for (Share share : shares) out.add(line(snapshot, share.registrationId, share.amount,
                distinct.get(share.registrationId).getEligibleSalesSnapshot()));
        if (sumOrigin(out).compareTo(target) != 0) {
            throw new IllegalStateException(LEDGER_CONFLICT + ":比例分摊明细和不等于头目标额");
        }
    }

    private static boolean eligible(CoopSettlementSnapshot snapshot, CoopSettlementCandidate c, Date cutoff) {
        if (c == null || c.getRegistrationId() == null
                || !Integer.valueOf(2).equals(c.getRegistrationStatus())
                || !Integer.valueOf(2).equals(c.getPaymentStatus())
                || !Integer.valueOf(1).equals(c.getVerificationStatus())
                || c.getPurchaseKind() == null || !snapshot.getEligiblePurchaseKinds().contains(c.getPurchaseKind())
                || !c.isScopeCovered()) {
            return false;
        }
        String contextType = Long.valueOf(1L).equals(c.getOwnerType()) ? "TOPIC_EDITION"
                : Long.valueOf(2L).equals(c.getOwnerType()) ? "ACTIVITY" : null;
        if (contextType == null || !same(snapshot.getTopicId(), c.getSourceTopicId())) return false;
        if (!contextType.equals(snapshot.getCommercialContextType()) || !same(c.getOwnerId(), snapshot.getCommercialContextId())) {
            throw manualRepair("ECONOMIC_CONTEXT_SPLIT_REQUIRED:registration=" + c.getRegistrationId());
        }
        if (!c.isTicketOwnerMatches() || c.getFulfillmentStartInclusive() == null
                || c.getFulfillmentEndExclusive() == null
                || !c.getFulfillmentStartInclusive().before(c.getFulfillmentEndExclusive())
                || c.getFulfillmentStartInclusive().before(snapshot.getFulfillmentStartInclusive())
                || c.getFulfillmentEndExclusive().after(cutoff)) {
            throw manualRepair("REGISTRATION_FROZEN_WINDOW_UNPROVEN:registration=" + c.getRegistrationId());
        }
        if (c.getEligibleSalesSnapshot() == null || c.getEligibleSalesSnapshot().signum() < 0) {
            throw manualRepair("ELIGIBLE_SALES_UNPROVEN:registration=" + c.getRegistrationId());
        }
        if ("FIXED_PER_FULFILLMENT".equals(snapshot.getCompensationMode())) {
            if (c.getVerifiedSeatIds() == null) {
                // Existing individual scan is exactly one proven seat, never order_num expansion.
                if (!Long.valueOf(1L).equals(c.getOrderQuantity()))
                    throw manualRepair("FIXED_FEE_QUANTITY_UNPROVEN:registration=" + c.getRegistrationId());
            } else {
                if (c.getOrderQuantity() == null || c.getOrderQuantity() <= 0
                        || c.getVerifiedSeatIds().stream().anyMatch(id -> id == null || id <= 0)
                        || new java.util.HashSet<>(c.getVerifiedSeatIds()).size() != c.getVerifiedSeatIds().size()
                        || c.getVerifiedSeatIds().size() > c.getOrderQuantity())
                    throw manualRepair("FIXED_FEE_SEAT_IDENTITY_CONFLICT:registration=" + c.getRegistrationId());
                if (c.getVerifiedSeatIds().isEmpty()) return false;
            }
        }
        if ("REGISTRATION_SCAN".equals(snapshot.getFulfillmentEvidenceRule())) {
            return "MERCHANT_PARTICIPATION".equals(snapshot.getServiceUnit())
                    && c.isMerchantScanPresent() && inWindow(c.getMerchantScanTime(), c, cutoff);
        }
        if ("COOP_GROUP_VERIFY".equals(snapshot.getFulfillmentEvidenceRule())) {
            if (!GUIDED_TICKET.equals(c.getPurchaseKind()) || !c.isOrganizerScopeCovered()) return false;
            if ("MERCHANT_PARTICIPATION".equals(snapshot.getServiceUnit())) {
                return c.isGroupEvidenceMatchesOrder() && inWindow(c.getGroupVerifyTime(), c, cutoff)
                        && inWindow(c.getGroupMemberVerifyTime(), c, cutoff);
            }
            return "ORGANIZER_LEAD".equals(snapshot.getServiceUnit())
                    && inWindow(c.getVerificationTime(), c, cutoff) && inWindow(c.getGroupMemberVerifyTime(), c, cutoff);
        }
        // 原合同纯算法入口已明确命名的逐报名规则；运行时 reader/prepare 不接受这个旧 seam 枚举。
        if (!"V1_PER_REGISTRATION".equals(snapshot.getFulfillmentEvidenceRule())) {
            throw manualRepair("FULFILLMENT_EVIDENCE_RULE_UNSUPPORTED");
        }
        if ("MERCHANT_PARTICIPATION".equals(snapshot.getServiceUnit())) {
            if (GUIDED_TICKET.equals(c.getPurchaseKind()) && c.isOrganizerScopeCovered()) {
                return c.isGroupEvidenceMatchesOrder()
                        && inWindow(c.getGroupVerifyTime(), c, cutoff)
                        && inWindow(c.getGroupMemberVerifyTime(), c, cutoff);
            }
            return c.isMerchantScanPresent() && inWindow(c.getMerchantScanTime(), c, cutoff);
        }
        if ("ORGANIZER_LEAD".equals(snapshot.getServiceUnit())) {
            return GUIDED_TICKET.equals(c.getPurchaseKind()) && c.isOrganizerScopeCovered()
                    && inWindow(c.getVerificationTime(), c, cutoff);
        }
        throw manualRepair("serviceUnit 快照非法:" + snapshot.getServiceUnit());
    }

    private static void validateSnapshot(CoopSettlementSnapshot s, Date cutoff) {
        if (s == null || s.getOrderId() == null || s.getTopicId() == null || blank(s.getCommercialContextType())
                || s.getCommercialContextId() == null || blank(s.getBeneficiaryRole())
                || s.getBeneficiaryId() == null || blank(s.getServiceUnit())
                || blank(s.getScopeType()) || s.getScopeId() == null
                || s.getEligiblePurchaseKinds() == null || s.getEligiblePurchaseKinds().isEmpty()
                || blank(s.getFulfillmentEvidenceRule()) || blank(s.getCompensationMode())
                || s.getSettlementSnapshotAt() == null || cutoff == null
                || s.getFulfillmentStartInclusive() == null || !s.getFulfillmentStartInclusive().before(cutoff)) {
            throw manualRepair("coop_order 冻结快照不完整");
        }
        if (s.getSettlementSnapshotAt().before(cutoff)) {
            throw manualRepair("settlementSnapshotAt 早于 settlementCutoff");
        }
    }

    private static CoopSettlementOriginLine line(CoopSettlementSnapshot s, Long registrationId,
            BigDecimal amount, BigDecimal eligibleSalesSnapshot) {
        CoopSettlementOriginLine line = new CoopSettlementOriginLine(registrationId, amount);
        line.setEligibleSalesSnapshot(eligibleSalesSnapshot);
        line.setSettlementRoute("COOP_ORDER");
        line.setSourceContractType("COOP_ORDER");
        line.setSourceContractId(s.getOrderId());
        line.setCommercialContextType(s.getCommercialContextType());
        line.setCommercialContextId(s.getCommercialContextId());
        line.setBeneficiaryRole(s.getBeneficiaryRole());
        line.setBeneficiaryId(s.getBeneficiaryId());
        line.setServiceUnit(s.getServiceUnit());
        requireHeaderEquality(s, line);
        return line;
    }

    private static void requirePositiveOrZero(BigDecimal value, String field) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) throw manualRepair("缺/非法 " + field);
    }
    private static boolean before(Date value, Date cutoff) { return value != null && value.before(cutoff); }
    private static boolean inWindow(Date value, CoopSettlementCandidate c, Date cutoff) {
        return value != null && !value.before(c.getFulfillmentStartInclusive())
                && before(value, c.getFulfillmentEndExclusive()) && before(value, cutoff);
    }
    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
    private static boolean same(Object a, Object b) { return a == null ? b == null : a.equals(b); }
    private static IllegalStateException manualRepair(String reason) {
        return new IllegalStateException(MANUAL_REPAIR + ":" + reason);
    }

    private static final class Share {
        private final Long registrationId;
        private BigDecimal amount;
        private final BigDecimal remainder;
        private Share(Long registrationId, BigDecimal amount, BigDecimal remainder) {
            this.registrationId = registrationId;
            this.amount = amount;
            this.remainder = remainder;
        }
        private Long getRegistrationId() { return registrationId; }
        private BigDecimal getRemainder() { return remainder; }
    }
}
