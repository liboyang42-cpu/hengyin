package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.TopicEditionFunds;
import com.chengyinhub.business.domain.TopicEditionFundsRawFacts;
import com.chengyinhub.business.domain.TopicEditionFundsAttribution;
import com.chengyinhub.business.domain.TopicEditionCost;
import com.chengyinhub.business.domain.ClubEditionHours;
import com.chengyinhub.business.domain.vo.TopicEditionFinanceOverviewVO;
import com.chengyinhub.business.mapper.TopicEditionFundsMapper;
import com.chengyinhub.business.service.ITopicEditionFundsService;
import com.chengyinhub.business.service.TopicEditionFundsPhase;
import com.chengyinhub.business.service.support.TopicEditionFundsCalculator;
import com.chengyinhub.business.service.support.EditionBreakEvenCalculator;
import com.chengyinhub.business.service.support.EditionContributionMarginCalculator;
import com.chengyinhub.common.constant.ExploreEditionFinancePolicy;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.constant.PurchaseKind;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.Collections;
import java.util.List;

@Service
public class TopicEditionFundsServiceImpl implements ITopicEditionFundsService {
    private final TopicEditionFundsMapper mapper;
    private final TopicEditionFundsCalculator calculator = new TopicEditionFundsCalculator();
    private final EditionBreakEvenCalculator breakEvenCalculator = new EditionBreakEvenCalculator();
    private final EditionContributionMarginCalculator contributionMarginCalculator =
            new EditionContributionMarginCalculator();

    public TopicEditionFundsServiceImpl(TopicEditionFundsMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TopicEditionFunds recomputeProvisional(Long topicId, Date asOf,
                                                  String idempotencyKey, String operator) {
        return recompute(topicId, asOf, idempotencyKey, operator, TopicEditionFundsPhase.PROVISIONAL);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TopicEditionFunds recompute(Long topicId, Date asOf, String idempotencyKey, String operator,
                                       TopicEditionFundsPhase phase) {
        if (phase != TopicEditionFundsPhase.PROVISIONAL && phase != TopicEditionFundsPhase.FINAL) {
            throw new IllegalArgumentException("EDITION_FUNDS_PHASE_REQUIRED");
        }
        if (topicId == null || asOf == null || isBlank(idempotencyKey) || isBlank(operator)) {
            throw new IllegalArgumentException("EDITION_FUNDS_ARGUMENT_REQUIRED");
        }
        TopicEditionFunds priorRequest = mapper.selectByIdempotencyKey(idempotencyKey);
        String snapshotKind = phase == TopicEditionFundsPhase.FINAL
                ? TopicEditionFunds.KIND_FINAL : TopicEditionFunds.KIND_PROVISIONAL;
        if (priorRequest != null) {
            if (!topicId.equals(priorRequest.getTopicId())
                    || !snapshotKind.equals(priorRequest.getSnapshotKind())) {
                throw new IllegalStateException("EDITION_FUNDS_IDEMPOTENCY_CONFLICT");
            }
            if (phase == TopicEditionFundsPhase.FINAL) {
                validatePersistedFinalAttributions(mapper.selectAttributionsBySnapshotId(priorRequest.getId()),
                        priorRequest.getNCash(), priorRequest.getGEarned());
            }
            return priorRequest;
        }

        TopicEditionFunds current = mapper.selectCurrentForUpdate(topicId, snapshotKind);
        if (phase == TopicEditionFundsPhase.FINAL && current != null) {
            if (idempotencyKey.equals(current.getIdempotencyKey())) {
                validatePersistedFinalAttributions(mapper.selectAttributionsBySnapshotId(current.getId()),
                        current.getNCash(), current.getGEarned());
                return current;
            }
            throw new IllegalStateException("EDITION_FUNDS_FINAL_ALREADY_EXISTS");
        }
        TopicEditionFundsRawFacts raw = mapper.selectProvisionalRawFacts(
                topicId, asOf, ProductType.FREE_EXPLORE, PurchaseKind.EXPLORE_PASS);
        validateProvisionalRaw(raw);
        if (phase == TopicEditionFundsPhase.FINAL
                && (raw.getUOut().signum() != 0 || raw.getUPending().signum() != 0)) {
            throw new IllegalStateException("EDITION_FUNDS_FINAL_UNSETTLED");
        }
        TopicEditionFundsCalculator.Calculation value = calculator.calculate(
                raw.getGCash(), BigDecimal.ZERO, raw.getUOut(), raw.getUPending(), raw.getRRefund());
        List<TopicEditionFundsAttribution> attributions = Collections.emptyList();
        if (phase == TopicEditionFundsPhase.FINAL) {
            attributions = mapper.selectFinalAttributionFacts(
                    topicId, ProductType.FREE_EXPLORE, PurchaseKind.EXPLORE_PASS);
            validateProducedFinalAttributions(attributions, raw.getNCash(), value.getGEarned());
        }

        TopicEditionFunds snapshot = new TopicEditionFunds();
        snapshot.setTopicId(topicId);
        snapshot.setSnapshotKind(snapshotKind);
        snapshot.setVersion(current == null ? 1L : current.getVersion() + 1L);
        snapshot.setCurrent(1);
        snapshot.setNCash(raw.getNCash());
        snapshot.setGCash(value.getGCash());
        snapshot.setUIn(value.getUIn());
        snapshot.setGEarned(value.getGEarned());
        snapshot.setUOut(value.getUOut());
        snapshot.setUPending(value.getUPending());
        snapshot.setRRefund(value.getRRefund());
        snapshot.setInputHash(inputHash(raw, attributions));
        snapshot.setIdempotencyKey(idempotencyKey);
        snapshot.setComputedBy(operator);
        snapshot.setComputedAt(new Date());

        if (current != null && mapper.supersedeCurrentProvisional(
                current.getId(), operator, "PROVISIONAL_RECOMPUTED") != 1) {
            throw new IllegalStateException("EDITION_FUNDS_PROVISIONAL_CONFLICT");
        }
        if (mapper.insertSnapshot(snapshot) != 1) {
            throw new IllegalStateException("EDITION_FUNDS_SNAPSHOT_WRITE_FAILED");
        }
        if (phase == TopicEditionFundsPhase.FINAL) {
            for (TopicEditionFundsAttribution attribution : attributions) {
                attribution.setId(null);
                attribution.setFundsSnapshotId(snapshot.getId());
                if (mapper.insertFinalAttribution(attribution) != 1) {
                    throw new IllegalStateException("EDITION_FUNDS_ATTRIBUTION_WRITE_FAILED");
                }
            }
            validatePersistedFinalAttributions(mapper.selectAttributionsBySnapshotId(snapshot.getId()),
                    raw.getNCash(), value.getGEarned());
        }
        return snapshot;
    }

    @Override
    @Transactional(readOnly = true)
    public TopicEditionFunds currentProvisional(Long topicId) {
        if (topicId == null) throw new IllegalArgumentException("EDITION_FUNDS_ARGUMENT_REQUIRED");
        return mapper.selectCurrent(topicId, TopicEditionFunds.KIND_PROVISIONAL);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int repairUnknownExplorePurchases(Long topicId) {
        if (topicId == null) throw new IllegalArgumentException("EDITION_FUNDS_ARGUMENT_REQUIRED");
        return mapper.classifyUnknownExplorePurchases(
                topicId, ProductType.FREE_EXPLORE, PurchaseKind.EXPLORE_PASS);
    }

    @Override
    @Transactional(readOnly = true)
    public TopicEditionFinanceOverviewVO currentFinanceOverview(Long topicId) {
        if (topicId == null) throw new IllegalArgumentException("EDITION_FUNDS_ARGUMENT_REQUIRED");
        TopicEditionFinanceOverviewVO result = new TopicEditionFinanceOverviewVO();
        TopicEditionFunds funds = mapper.selectCurrent(topicId, TopicEditionFunds.KIND_FINAL);
        if (funds == null) {
            funds = mapper.selectCurrent(topicId, TopicEditionFunds.KIND_PROVISIONAL);
        }
        TopicEditionCost cost = mapper.selectCurrentCost(topicId);
        result.setFunds(funds);
        result.setSnapshotKind(funds == null ? null : funds.getSnapshotKind());
        result.setCost(cost);
        result.setHours(mapper.selectHours(topicId));
        if (cost == null) {
            result.setContributionMarginReason("EDITION_COST_UNKNOWN");
            result.setBreakEvenReason("EDITION_COST_UNKNOWN");
            return result;
        }
        BigDecimal platformCosts = cost.platformCostTotal();
        result.setPlatformCostTotal(platformCosts);
        if (funds == null) {
            result.setContributionMarginReason("EDITION_FUNDS_UNKNOWN");
        } else {
            result.setContributionMargin(contributionMarginCalculator.calculate(
                    funds.getGEarned(), platformCosts, cost.getFixedCompensation(), cost.getBonuses()));
        }
        BigDecimal fixed = cost.getBreakEvenFixedCost();
        if (fixed != null && cost.getFixedCompensation() != null && cost.getBonuses() != null) {
            fixed = fixed.add(cost.getFixedCompensation()).add(cost.getBonuses());
        }
        EditionBreakEvenCalculator.Result breakEven = breakEvenCalculator.calculate(
                fixed, ExploreEditionFinancePolicy.TICKET_PRICE, cost.getVariableCostPerTicket());
        result.setBreakEvenTickets(breakEven.getRequiredTickets());
        result.setBreakEvenReason(breakEven.getReason());
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TopicEditionCost saveCost(Long topicId, TopicEditionCost cost, String operator) {
        if (topicId == null || cost == null || isBlank(operator) || isBlank(cost.getNote())) {
            throw new IllegalArgumentException("EDITION_COST_ARGUMENT_REQUIRED");
        }
        validateCost(cost);
        TopicEditionCost current = mapper.selectCurrentCostForUpdate(topicId);
        cost.setId(null);
        cost.setTopicId(topicId);
        cost.setVersion(current == null ? 1L : current.getVersion() + 1L);
        cost.setCurrent(1);
        if (current != null && mapper.supersedeCurrentCost(current.getId()) != 1) {
            throw new IllegalStateException("EDITION_COST_CONFLICT");
        }
        if (mapper.insertCost(cost) != 1) throw new IllegalStateException("EDITION_COST_WRITE_FAILED");
        return cost;
    }

    private void validateCost(TopicEditionCost cost) {
        BigDecimal[] required = {cost.getTaxCost(), cost.getPaymentCost(), cost.getMaterialCost(), cost.getKitCost(),
                cost.getInsuranceRiskCost(), cost.getAcquisitionCost(), cost.getPlatformLaborCost(),
                cost.getOtherCost(), cost.getFixedCompensation(), cost.getBonuses()};
        for (BigDecimal value : required) {
            if (value == null || value.signum() < 0) throw new IllegalArgumentException("EDITION_COST_AMOUNT_INVALID");
        }
        if ((cost.getPlatformOnsiteHours() != null && cost.getPlatformOnsiteHours().signum() < 0)
                || (cost.getBreakEvenFixedCost() != null && cost.getBreakEvenFixedCost().signum() < 0)
                || (cost.getVariableCostPerTicket() != null && cost.getVariableCostPerTicket().signum() < 0)
                || (cost.getHeadcountRate() != null && cost.getHeadcountRate().signum() < 0)
                || (cost.getHeadcountCap() != null && cost.getHeadcountCap().signum() < 0)) {
            throw new IllegalArgumentException("EDITION_COST_AMOUNT_INVALID");
        }
    }

    private void validateProvisionalRaw(TopicEditionFundsRawFacts raw) {
        if (raw == null || raw.getNCash() == null || raw.getUnknownCount() == null
                || raw.getInvalidRefundCount() == null || raw.getUOut() == null
                || raw.getUPending() == null) {
            throw new IllegalStateException("EDITION_FUNDS_UNKNOWN:rawFacts");
        }
        if (raw.getUnknownCount() > 0L) {
            throw new IllegalStateException("EDITION_FUNDS_UNKNOWN:paidFacts=" + raw.getUnknownCount());
        }
        if (raw.getInvalidRefundCount() > 0L) {
            throw new IllegalStateException("EDITION_FUNDS_UNBALANCED:refund>cash");
        }
        if (raw.getUPending().compareTo(raw.getUOut()) > 0) {
            throw new IllegalStateException("EDITION_FUNDS_PROVISIONAL_CONTRACT:uPending>uOut");
        }
    }

    private void validateProducedFinalAttributions(List<TopicEditionFundsAttribution> rows,
                                                   Long expectedTickets, BigDecimal expectedEarned) {
        if (rows == null) throw new IllegalStateException("EDITION_FUNDS_ATTRIBUTION_UNKNOWN");
        for (TopicEditionFundsAttribution row : rows) {
            if (row == null || row.getUnfrozenCount() == null || row.getUnfrozenCount() != 0L) {
                throw new IllegalStateException("EDITION_FUNDS_ATTRIBUTION_INVALID");
            }
            // ★净票数必须落在 [0, 毛计数] 之间。它是承接费 HEADCOUNT 的唯一计酬基数，
            //   查询写错（比如退款 join 漏掉、或误抄成 count(*)）在这里就要红，
            //   不能等到 FINAL 之后拿一个多算的票数去付钱 —— 那时快照已不可变。
            //   ⚠️ 只校验【新生产】的行；已落库的历史快照该列为 NULL，由消费方判 PENDING。
            if (row.getNetTicketCount() == null || row.getNetTicketCount() < 0L
                    || row.getTicketCount() == null
                    || row.getNetTicketCount() > row.getTicketCount()) {
                throw new IllegalStateException("EDITION_FUNDS_ATTRIBUTION_INVALID");
            }
        }
        validatePersistedFinalAttributions(rows, expectedTickets, expectedEarned);
    }

    /** 已落库的 FINAL 只校验不可变快照自身；未冻结票硬闸只在生产写入前读取报名事实。 */
    private void validatePersistedFinalAttributions(List<TopicEditionFundsAttribution> rows,
                                                    Long expectedTickets, BigDecimal expectedEarned) {
        if (rows == null) throw new IllegalStateException("EDITION_FUNDS_ATTRIBUTION_UNKNOWN");
        long tickets = 0L;
        BigDecimal earned = BigDecimal.ZERO;
        for (TopicEditionFundsAttribution row : rows) {
            if (row == null || row.getTicketCount() == null || row.getTicketCount() <= 0L
                    || row.getEarnedAmount() == null || row.getEarnedAmount().signum() < 0
                    || (row.getSourceClubId() != null && row.getSourceClubId() <= 0L)) {
                throw new IllegalStateException("EDITION_FUNDS_ATTRIBUTION_INVALID");
            }
            tickets += row.getTicketCount();
            earned = earned.add(row.getEarnedAmount());
        }
        if (expectedTickets == null || tickets != expectedTickets.longValue()
                || earned.compareTo(expectedEarned) != 0) {
            throw new IllegalStateException("EDITION_FUNDS_ATTRIBUTION_UNBALANCED");
        }
    }

    private String inputHash(TopicEditionFundsRawFacts raw, List<TopicEditionFundsAttribution> attributions) {
        String canonical = raw.getNCash() + "|" + plain(raw.getGCash()) + "|" + plain(raw.getUOut())
                + "|" + plain(raw.getUPending()) + "|" + plain(raw.getRRefund());
        for (TopicEditionFundsAttribution row : attributions) {
            canonical += "|" + (row.getSourceClubId() == null ? "PLATFORM" : row.getSourceClubId())
                    + ":" + row.getTicketCount() + ":" + plain(row.getEarnedAmount());
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String plain(BigDecimal value) {
        return value == null ? "UNKNOWN" : value.stripTrailingZeros().toPlainString();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
