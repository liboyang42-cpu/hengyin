package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.CoopSettlement;
import com.chengyinhub.business.domain.CoopSettlementDetail;
import com.chengyinhub.business.domain.CoopSettlementAdjustment;
import com.chengyinhub.business.domain.SettlementClaim;
import com.chengyinhub.business.domain.dto.CoopSettlementCandidate;
import com.chengyinhub.business.domain.dto.CoopSettlementOriginLine;
import com.chengyinhub.business.domain.dto.CoopSettlementPlan;
import com.chengyinhub.business.domain.dto.CoopSettlementSnapshot;
import com.chengyinhub.business.mapper.CoopSettlementDetailMapper;
import com.chengyinhub.business.mapper.CoopSettlementAdjustmentMapper;
import com.chengyinhub.business.mapper.CoopSettlementMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.FulfillmentWindowResolver;
import com.chengyinhub.business.service.ICoopOrderSettlementSnapshotReader;
import com.chengyinhub.business.service.ICoopSettlementClaimService;
import com.chengyinhub.business.service.ISettlementClaimService;
import com.chengyinhub.business.service.support.CoopSettlementContract;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

@Service
public class CoopSettlementClaimServiceImpl implements ICoopSettlementClaimService {
    private final ICoopOrderSettlementSnapshotReader snapshotReader;
    private final FulfillmentWindowResolver windowResolver;
    private final TopicSettlementEvidenceService evidenceService;
    private final ISettlementClaimService claimService;
    private final CoopSettlementMapper settlementMapper;
    private final CoopSettlementDetailMapper detailMapper;
    private final CoopSettlementAdjustmentMapper adjustmentMapper;

    @Autowired
    private CmsTopicMapper topicMapper;

    @Autowired
    public CoopSettlementClaimServiceImpl(ICoopOrderSettlementSnapshotReader snapshotReader,
            FulfillmentWindowResolver windowResolver, TopicSettlementEvidenceService evidenceService,
            ISettlementClaimService claimService, CoopSettlementMapper settlementMapper,
            CoopSettlementDetailMapper detailMapper, CoopSettlementAdjustmentMapper adjustmentMapper) {
        this.snapshotReader = snapshotReader;
        this.windowResolver = windowResolver;
        this.evidenceService = evidenceService;
        this.claimService = claimService;
        this.settlementMapper = settlementMapper;
        this.detailMapper = detailMapper;
        this.adjustmentMapper = adjustmentMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void appendExecutedAdjustment(Long claimId, String eventKey, BigDecimal signedAmount) {
        if (claimId == null || eventKey == null || eventKey.trim().isEmpty()
                || signedAmount == null || signedAmount.compareTo(BigDecimal.ZERO) == 0) {
            throw new IllegalArgumentException("claim 调整必须有 claimId/不可变 eventKey/非零 signedAmount");
        }
        CoopSettlement observed = settlementMapper.selectByClaimId(claimId);
        if (observed == null || observed.getId() == null) {
            throw new IllegalStateException(CoopSettlementContract.LEDGER_CONFLICT
                    + ":调整找不到原始 coop claimId=" + claimId);
        }
        lockTopic(observed.getTopicId());
        CoopSettlement head = settlementMapper.selectByIdForUpdate(observed.getId());
        if (head == null || !same(head.getId(), observed.getId()) || !same(head.getTopicId(), observed.getTopicId())) {
            throw new IllegalStateException(CoopSettlementContract.LEDGER_CONFLICT + ":调整原头主题变化 claimId=" + claimId);
        }
        CoopSettlementDetail origin = detailMapper.selectByClaimIdForUpdate(claimId);
        if (origin == null || !same(origin.getSettlementId(), head.getId()) || !same(origin.getClaimId(), claimId)) {
            throw new IllegalStateException(CoopSettlementContract.LEDGER_CONFLICT + ":调整原明细归属变化 claimId=" + claimId);
        }
        CoopSettlementAdjustment adjustment = new CoopSettlementAdjustment();
        adjustment.setSettlementId(origin.getSettlementId());
        adjustment.setClaimId(claimId);
        adjustment.setEntryKind("ADJUSTMENT");
        adjustment.setEventKey(eventKey);
        adjustment.setAmount(signedAmount);
        adjustment.setStatus("EXECUTED");
        adjustment.setCreateTime(new Date());
        if (adjustmentMapper.insertIgnoreAdjustment(adjustment) == 1) return;
        CoopSettlementAdjustment current = adjustmentMapper.selectByClaimEventForUpdate(
                claimId, "ADJUSTMENT", eventKey);
        if (current == null || !same(current.getSettlementId(), origin.getSettlementId())
                || current.getAmount() == null || current.getAmount().compareTo(signedAmount) != 0
                || !"EXECUTED".equals(current.getStatus())) {
            throw new IllegalStateException(CoopSettlementContract.LEDGER_CONFLICT
                    + ":调整事件幂等终态不一致 claimId=" + claimId + ",eventKey=" + eventKey);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public CoopSettlementPlan prepare(CoopOrder order, Date settlementSnapshotAt) {
        if (order == null || order.getId() == null || !Integer.valueOf(0).equals(order.getStatus())
                || !Integer.valueOf(0).equals(order.getDelFlag())) {
            throw manualRepair("coop_order 非有效状态");
        }
        CoopSettlementSnapshot snapshot = snapshotReader.read(order);
        if (snapshot == null) throw manualRepair("coop_order 冻结快照尚未接入");
        CoopSettlementContract.requireSupportedCashPath(snapshot);
        lockTopic(snapshot.getTopicId());
        snapshot.setSettlementSnapshotAt(settlementSnapshotAt);
        Date cutoff = windowResolver.endExclusive(snapshot);
        if (cutoff == null || !cutoff.equals(snapshot.getFulfillmentEndExclusive())
                || snapshot.getFulfillmentStartInclusive() == null
                || !snapshot.getFulfillmentStartInclusive().before(cutoff)
                || settlementSnapshotAt == null || settlementSnapshotAt.before(cutoff)) {
            throw manualRepair("FROZEN_FULFILLMENT_WINDOW_NOT_CLOSED");
        }
        List<CoopSettlementCandidate> candidates = evidenceService.lockCandidates(snapshot, cutoff);
        evidenceService.requireUnrefundedCashBasis(snapshot.getTopicId());
        List<CoopSettlementOriginLine> lines = CoopSettlementContract.originLines(snapshot, candidates, cutoff);
        if (!"NONE".equals(snapshot.getCompensationMode()) && lines.isEmpty()) {
            throw manualRepair("NO_PROVEN_FULFILLMENT:不得以零元终态消耗合作单");
        }
        String route = "NONE".equals(snapshot.getCompensationMode()) ? "NONE" : "COOP_ORDER";
        return new CoopSettlementPlan(snapshot, route, lines);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void persist(CoopSettlementPlan plan, CoopSettlement head) {
        if (plan == null) throw new IllegalArgumentException("plan 不能为空");
        lockTopic(plan.getSnapshot() == null ? null : plan.getSnapshot().getTopicId());
        if ("NONE".equals(plan.getRoute())) {
            for (CoopSettlementOriginLine line : plan.getOriginLines()) claim(plan, line);
            return;
        }
        if (head == null) throw new IllegalArgumentException("COOP_ORDER 聚合头不能为空");
        if (!same(head.getTopicId(), plan.getSnapshot().getTopicId())) {
            throw new IllegalStateException(CoopSettlementContract.LEDGER_CONFLICT + ":聚合头与原计划主题不一致");
        }
        applyHeaderAttribution(head, plan.getSnapshot());
        BigDecimal expectedAmount = plan.getOriginAmount();
        head.setAmount(BigDecimal.ZERO.setScale(2));
        head.setVerifiedHeads(0);
        if (settlementMapper.insertCoopSettlement(head) != 1 || head.getId() == null) {
            throw new IllegalStateException(CoopSettlementContract.LEDGER_CONFLICT + ":coop 聚合头写入失败");
        }
        for (CoopSettlementOriginLine line : plan.getOriginLines()) {
            SettlementClaim claim = claim(plan, line);
            CoopSettlementDetail detail = new CoopSettlementDetail();
            detail.setSettlementId(head.getId());
            detail.setClaimId(claim.getId());
            detail.setRegistrationId(line.getRegistrationId());
            detail.setOriginAmount(line.getOriginAmount());
            detail.setCreateTime(new Date());
            if (detailMapper.insertIgnoreDetail(detail) != 1) {
                CoopSettlementDetail current = detailMapper.selectByClaimIdForUpdate(claim.getId());
                if (!sameDetail(current, detail)) {
                    throw new IllegalStateException(CoopSettlementContract.LEDGER_CONFLICT
                            + ":claim 已挂到其他 coop 聚合头 claimId=" + claim.getId());
                }
            }
        }
        if (detailMapper.refreshOriginAggregate(head.getId()) != 1) {
            throw new IllegalStateException(CoopSettlementContract.LEDGER_CONFLICT + ":coop 明细聚合头刷新失败");
        }
        head.setAmount(expectedAmount);
        head.setVerifiedHeads(plan.getVerifiedHeads());
    }

    private SettlementClaim claim(CoopSettlementPlan plan, CoopSettlementOriginLine line) {
        CoopSettlementSnapshot s = plan.getSnapshot();
        return claimService.claim(SettlementClaim.request(s.getCommercialContextType(),
                s.getCommercialContextId(), "REGISTRATION", line.getRegistrationId(),
                s.getBeneficiaryRole(), s.getBeneficiaryId(), s.getServiceUnit(), plan.getRoute(),
                "COOP_ORDER", s.getOrderId()));
    }

    private void lockTopic(Long topicId) {
        if (topicId == null) throw manualRepair("SETTLEMENT_TOPIC_REQUIRED");
        com.chengyinhub.business.domain.CmsTopic topic = topicMapper.selectCmsTopicByIdForUpdate(topicId);
        if (topic == null || !topicId.equals(topic.getId())) throw manualRepair("SETTLEMENT_TOPIC_NOT_FOUND");
    }

    private static void applyHeaderAttribution(CoopSettlement head, CoopSettlementSnapshot s) {
        head.setSettlementRoute("COOP_ORDER");
        head.setSourceContractType("COOP_ORDER");
        head.setSourceContractId(s.getOrderId());
        head.setCommercialContextType(s.getCommercialContextType());
        head.setCommercialContextId(s.getCommercialContextId());
        head.setBeneficiaryRole(s.getBeneficiaryRole());
        head.setBeneficiaryId(s.getBeneficiaryId());
        head.setServiceUnit(s.getServiceUnit());
    }

    private static boolean sameDetail(CoopSettlementDetail a, CoopSettlementDetail b) {
        return a != null && same(a.getSettlementId(), b.getSettlementId())
                && same(a.getClaimId(), b.getClaimId()) && same(a.getRegistrationId(), b.getRegistrationId())
                && a.getOriginAmount() != null && b.getOriginAmount() != null
                && a.getOriginAmount().compareTo(b.getOriginAmount()) == 0;
    }
    private static boolean same(Object a, Object b) { return a == null ? b == null : a.equals(b); }
    private static IllegalStateException manualRepair(String reason) {
        return new IllegalStateException(CoopSettlementContract.MANUAL_REPAIR + ":" + reason);
    }
}
