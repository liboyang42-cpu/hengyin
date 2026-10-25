package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.CoopOrderStationFulfillmentMapper;
import com.chengyinhub.business.domain.CoopOrderStationFulfillment;
import com.chengyinhub.business.service.support.CoopOrderStationScope;
import com.chengyinhub.business.service.support.CoopSettlementContract;
import com.chengyinhub.business.domain.dto.CoopGroupSettlementBasis;
import com.chengyinhub.business.domain.dto.CoopSettlementCandidate;
import com.chengyinhub.business.domain.dto.CoopSettlementSnapshot;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CoopGroupVerifyMapper;
import com.chengyinhub.business.mapper.CoopSettlementEvidenceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * 活动壳只读结算证据 seam。它与旧现金结算基数刻意分离，调用方不能把本服务结果传回旧分润链。
 */
@Service
public class TopicSettlementEvidenceService {
    private final CmsActivityMapper activityMapper;
    private final CmsRegistrationMapper registrationMapper;
    private final CoopGroupVerifyMapper groupVerifyMapper;
    private final CoopSettlementEvidenceMapper evidenceMapper;
    @org.springframework.beans.factory.annotation.Autowired
    private CoopOrderStationFulfillmentMapper stationFulfillments;
    @org.springframework.beans.factory.annotation.Autowired
    private com.chengyinhub.business.service.RegistrationSeatSettlementReader registrationSeats;

    public TopicSettlementEvidenceService(CmsActivityMapper activityMapper,
            CmsRegistrationMapper registrationMapper, CoopGroupVerifyMapper groupVerifyMapper,
            CoopSettlementEvidenceMapper evidenceMapper) {
        this.activityMapper = activityMapper;
        this.registrationMapper = registrationMapper;
        this.groupVerifyMapper = groupVerifyMapper;
        this.evidenceMapper = evidenceMapper;
    }

    /** 主题工作台履约展示；只读取三项主题级证据，不触发逐商家或候选锁查询。 */
    @Transactional(readOnly = true)
    public Summary readTopic(Long topicId) {
        if (topicId == null) {
            throw new IllegalArgumentException("topicId 不能为空");
        }
        Summary result = new Summary();
        result.totalSales = registrationMapper
                .selectTotalSalesEvidenceByTopicIdIncludingActivityShell(topicId);
        result.verifiedSales = registrationMapper
                .selectVerifiedSalesEvidenceByTopicIdIncludingActivityShell(topicId);
        result.verifiedHeads = registrationMapper
                .selectVerifiedHeadsEvidenceByTopicIdIncludingActivityShell(topicId);
        return result;
    }

    @Transactional(readOnly = true)
    public Summary read(Long topicId, Long merchantId) {
        if (merchantId == null) {
            throw new IllegalArgumentException("merchantId 不能为空");
        }
        Summary result = readTopic(topicId);
        result.merchantVerifiedHeads = registrationMapper
                .selectVerifiedHeadsEvidenceByTopicAndMerchantIncludingActivityShell(topicId, merchantId);
        result.merchantVerifiedSales = registrationMapper
                .selectVerifiedSalesEvidenceByTopicAndMerchantIncludingActivityShell(topicId, merchantId);
        result.verifiedRegistrationIds = registrationMapper
                .selectVerifiedRegistrationIdsEvidenceByTopicIdIncludingActivityShell(topicId);
        result.groupBasis = groupVerifyMapper
                .selectSettlementEvidenceBasisByTopicAndMerchantIncludingActivityShell(topicId, merchantId);
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public List<CoopSettlementCandidate> lockCandidates(CoopSettlementSnapshot snapshot, Date settlementCutoff) {
        if (snapshot == null || snapshot.getTopicId() == null) {
            throw new IllegalArgumentException("snapshot.topicId 不能为空，不能将 economic context id 当 topic id");
        }
        if (snapshot.getBillingStationId() != null || snapshot.getOrderTermsSha256() != null)
            return lockStationCandidates(snapshot, settlementCutoff);
        List<Long> activityIds = activityMapper
                .selectActiveActivityIdsByTopicId(snapshot.getTopicId());
        List<CoopSettlementCandidate> candidates = evidenceMapper.selectTopicEvidenceCandidatesForUpdate(snapshot,
                activityIds == null ? Collections.<Long>emptyList() : activityIds, settlementCutoff);
        if ("FIXED_PER_FULFILLMENT".equals(snapshot.getCompensationMode()) && candidates != null
                && candidates.stream().anyMatch(c -> c != null && c.isOrganizerScopeCovered())) {
            // All original registrations are already locked. Reuse the canonical mapper-only current-read owner.
            java.util.Map<Long, java.util.Map<Long, com.chengyinhub.business.domain.RegistrationSeat>> byRegistration = new java.util.HashMap<>();
            if (activityIds != null) for (Long activityId : new java.util.TreeSet<>(activityIds)) {
                var verified = registrationSeats.verifiedSeatsForGroup(snapshot.getTopicId(), activityId, settlementCutoff);
                if (verified == null) throw new IllegalStateException("MANUAL_REPAIR:CURRENT_SEAT_FACTS_REQUIRED");
                for (var seat : verified) {
                    if (seat == null || seat.getId() == null || seat.getRegistrationId() == null
                            || !snapshot.getTopicId().equals(seat.getTopicId()) || !activityId.equals(seat.getActivityId())
                            || !Integer.valueOf(1).equals(seat.getStatus()) || seat.getVerifiedAt() == null)
                        throw new IllegalStateException("MANUAL_REPAIR:SEAT_SOURCE_IDENTITY_CONFLICT");
                    byRegistration.computeIfAbsent(seat.getRegistrationId(), ignored -> new java.util.TreeMap<>()).put(seat.getId(), seat);
                }
            }
            for (CoopSettlementCandidate candidate : candidates) {
                if (candidate == null || !candidate.isOrganizerScopeCovered()) continue;
                var seats = byRegistration.getOrDefault(candidate.getRegistrationId(), java.util.Map.of());
                List<Long> ids = new java.util.ArrayList<>();
                for (var seat : seats.values()) {
                    if (candidate.getFulfillmentStartInclusive() != null && candidate.getFulfillmentEndExclusive() != null
                            && !seat.getVerifiedAt().before(candidate.getFulfillmentStartInclusive())
                            && seat.getVerifiedAt().before(candidate.getFulfillmentEndExclusive())
                            && seat.getVerifiedAt().before(settlementCutoff)) ids.add(seat.getId());
                }
                java.util.Collections.sort(ids);
                candidate.setVerifiedSeatIds(seats.isEmpty() && Long.valueOf(1L).equals(candidate.getOrderQuantity()) ? null : ids);
            }
        }
        return candidates;
    }

    /** Exact V1 proof selection. No merchant aggregate, live station attribution or seat expansion. */
    private List<CoopSettlementCandidate> lockStationCandidates(CoopSettlementSnapshot snapshot, Date cutoff) {
        CoopSettlementContract.requireSupportedCashPath(snapshot);
        if (snapshot.getBillingStationId() == null || snapshot.getBillingStationId() <= 0
                || snapshot.getOrderTermsSha256() == null || !snapshot.getOrderTermsSha256().matches("[0-9a-f]{64}")
                || cutoff == null || snapshot.getFulfillmentStartInclusive() == null)
            throw CoopOrderStationScope.fail("FROZEN_SCOPE_REQUIRED");
        // Current registration locks precede proof/audit locks, including direct prepare callers.
        if (evidenceMapper.selectTopicCashRowsForUpdate(snapshot.getTopicId()) == null
                || evidenceMapper.selectTopicActivityCashRowsForUpdate(snapshot.getTopicId()) == null)
            throw CoopOrderStationScope.fail("CURRENT_REGISTRATIONS_REQUIRED");
        var orders = stationFulfillments.selectEffectiveOrdersForUpdate(snapshot.getTopicId());
        CoopOrderStationScope.requireDistinctStations(orders);
        var matches = orders.stream().filter(order -> snapshot.getOrderId().equals(order.getId())).toList();
        if (matches.size() != 1) throw CoopOrderStationScope.fail("CURRENT_ORDER_REQUIRED");
        var order = matches.get(0);
        var scope = CoopOrderStationScope.read(order)
                .orElseThrow(() -> CoopOrderStationScope.fail("CONFIRMED_SCOPE_REQUIRED"));
        if (!snapshot.getTopicId().equals(order.getTopicId()) || !snapshot.getBeneficiaryId().equals(order.getToId())
                || !snapshot.getBillingStationId().equals(scope.getStationId()) || !snapshot.getOrderTermsSha256().equals(scope.getTermsHash()))
            throw CoopOrderStationScope.fail("FROZEN_SCOPE_DRIFT");
        var proofs = stationFulfillments.selectByOrderForUpdate(order.getId());
        if (proofs == null) throw CoopOrderStationScope.fail("CURRENT_PROOFS_REQUIRED");
        java.util.Map<Long, CoopOrderStationFulfillment> valid = new java.util.HashMap<>();
        java.util.Set<Long> registrations = new java.util.HashSet<>();
        for (var proof : proofs) {
            if (proof == null || proof.getRegistrationId() == null || proof.getVerificationAuditId() == null)
                throw CoopOrderStationScope.fail("PROOF_IDENTITY_REQUIRED");
            var registration = stationFulfillments.selectRegistrationForUpdate(proof.getRegistrationId());
            var events = stationFulfillments.selectVerificationEventsForUpdate(proof.getRegistrationId());
            if (events == null || events.isEmpty())
                throw CoopOrderStationScope.fail("ORIGINAL_AUDIT_MISSING");
            // Retain old immutable generations, but never charge them after a later verify/reverse.
            if (!proof.getVerificationAuditId().equals(events.get(events.size() - 1).getId())) continue;
            var audit = stationFulfillments.selectAuditForUpdate(proof.getVerificationAuditId());
            CoopOrderStationScope.requireCurrentAudit(audit, events);
            var expected = CoopOrderStationScope.expected(order, registration, audit);
            CoopOrderStationScope.requireSame(proof, expected);
            if (!registrations.add(proof.getRegistrationId()))
                throw CoopOrderStationScope.fail("CURRENT_PROOF_OVERLAP");
            valid.put(proof.getId(), proof);
        }
        var candidates = stationFulfillments.selectV1CandidatesForUpdate(snapshot, cutoff);
        if (candidates == null) throw CoopOrderStationScope.fail("CURRENT_CANDIDATES_REQUIRED");
        List<CoopSettlementCandidate> selected = new java.util.ArrayList<>();
        for (var candidate : candidates) {
            var proof = candidate == null ? null : valid.get(candidate.getStationFulfillmentId());
            if (proof == null) continue; // an old generation is not a current fulfillment
            if (!proof.getRegistrationId().equals(candidate.getRegistrationId()) || candidate.getEligibleSalesSnapshot() == null
                    || proof.getSalesBasisAmount().compareTo(candidate.getEligibleSalesSnapshot()) != 0)
                throw CoopOrderStationScope.fail("CANDIDATE_SOURCE_CONFLICT");
            selected.add(candidate);
        }
        return selected;
    }

    /** 在报名锁之后当前读退款事实；已批准退款的净额/调整链尚未接通，也须拒结。 */
    public void requireUnrefundedCashBasis(Long topicId) {
        List<Long> pending = evidenceMapper.selectCashBasisBlockingRefundIdsForUpdate(topicId);
        if (pending == null || !pending.isEmpty()) {
            throw new IllegalStateException("MANUAL_REPAIR:REFUND_NET_BASIS_UNPROVEN");
        }
    }

    public static class Summary {
        private BigDecimal totalSales;
        private BigDecimal verifiedSales;
        private int verifiedHeads;
        private int merchantVerifiedHeads;
        private BigDecimal merchantVerifiedSales;
        private List<Long> verifiedRegistrationIds;
        private CoopGroupSettlementBasis groupBasis;

        public BigDecimal getTotalSales() { return totalSales; }
        public BigDecimal getVerifiedSales() { return verifiedSales; }
        public int getVerifiedHeads() { return verifiedHeads; }
        public int getMerchantVerifiedHeads() { return merchantVerifiedHeads; }
        public BigDecimal getMerchantVerifiedSales() { return merchantVerifiedSales; }
        public List<Long> getVerifiedRegistrationIds() { return verifiedRegistrationIds; }
        public CoopGroupSettlementBasis getGroupBasis() { return groupBasis; }
    }
}
