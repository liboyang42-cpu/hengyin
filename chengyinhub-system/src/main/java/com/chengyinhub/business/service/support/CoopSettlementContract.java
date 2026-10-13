package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.dto.CoopSettlementCandidate;
import com.chengyinhub.business.domain.dto.CoopSettlementOriginLine;
import com.chengyinhub.business.domain.dto.CoopSettlementSnapshot;

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
                lines.add(line(snapshot, candidate.getRegistrationId(), snapshot.getFixedFee().setScale(2),
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
        if (c == null || c.getRegistrationId() == null || !Long.valueOf(1L).equals(c.getOwnerType())
                || !snapshot.getCommercialContextId().equals(c.getOwnerId())
                || !Integer.valueOf(2).equals(c.getRegistrationStatus())
                || !Integer.valueOf(2).equals(c.getPaymentStatus())
                || !Integer.valueOf(1).equals(c.getVerificationStatus())
                || c.getPurchaseKind() == null || !snapshot.getEligiblePurchaseKinds().contains(c.getPurchaseKind())
                || !c.isScopeCovered()) {
            return false;
        }
        if ("MERCHANT_PARTICIPATION".equals(snapshot.getServiceUnit())) {
            if (GUIDED_TICKET.equals(c.getPurchaseKind()) && c.isOrganizerScopeCovered()) {
                return c.isGroupEvidenceMatchesOrder()
                        && before(c.getGroupVerifyTime(), cutoff)
                        && before(c.getGroupMemberVerifyTime(), cutoff);
            }
            return c.isMerchantScanPresent() && before(c.getMerchantScanTime(), cutoff);
        }
        if ("ORGANIZER_LEAD".equals(snapshot.getServiceUnit())) {
            return GUIDED_TICKET.equals(c.getPurchaseKind()) && c.isOrganizerScopeCovered()
                    && before(c.getVerificationTime(), cutoff);
        }
        throw manualRepair("serviceUnit 快照非法:" + snapshot.getServiceUnit());
    }

    private static void validateSnapshot(CoopSettlementSnapshot s, Date cutoff) {
        if (s == null || s.getOrderId() == null || blank(s.getCommercialContextType())
                || s.getCommercialContextId() == null || blank(s.getBeneficiaryRole())
                || s.getBeneficiaryId() == null || blank(s.getServiceUnit())
                || blank(s.getScopeType()) || s.getScopeId() == null
                || s.getEligiblePurchaseKinds() == null || s.getEligiblePurchaseKinds().isEmpty()
                || blank(s.getFulfillmentEvidenceRule()) || blank(s.getCompensationMode())
                || s.getSettlementSnapshotAt() == null || cutoff == null) {
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
