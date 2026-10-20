package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CoopSettlement;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.MerchantClawbackApplication;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.MerchantPayable;
import com.chengyinhub.business.domain.MerchantPayableBatch;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.Redemption;
import com.chengyinhub.business.domain.UmsMemberBalanceDetail;
import com.chengyinhub.business.domain.vo.MerchantFinancePage;
import com.chengyinhub.business.domain.vo.MerchantRedemptionSummary;
import com.chengyinhub.business.domain.vo.MerchantRedemptionView;
import com.chengyinhub.business.domain.vo.MerchantSettlementEntry;
import com.chengyinhub.business.domain.vo.MerchantSettlementOverview;
import com.chengyinhub.business.domain.vo.PublicTransferBatch;
import com.chengyinhub.business.domain.vo.PublicTransferBatchDetail;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CoopSettlementMapper;
import com.chengyinhub.business.mapper.MerchantClawbackApplicationMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.mapper.MerchantPayableBatchMapper;
import com.chengyinhub.business.mapper.MerchantPayableMapper;
import com.chengyinhub.business.mapper.RedemptionMapper;
import com.chengyinhub.business.mapper.UmsMemberBalanceDetailMapper;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.support.MerchantFinanceStateResolver;
import com.chengyinhub.business.service.support.MerchantAccrualBasis;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 商家资金的只读聚合层。
 *
 * <p>此层只读已存在账表；P0 的 claim/coop_settlement_detail 尚未落地时，绝不由
 * verifiedRegIds 伪造 claim 或商家级报名清单。</p>
 */
@Service
public class MerchantFinanceQueryService {
    @Autowired private MerchantPayableMapper payableMapper;
    @Autowired private MerchantPayableBatchMapper batchMapper;
    @Autowired private CoopSettlementMapper coopSettlementMapper;
    @Autowired private UmsMemberBalanceDetailMapper balanceDetailMapper;
    @Autowired private MerchantClawbackApplicationMapper clawbackMapper;
    @Autowired private RedemptionMapper redemptionMapper;
    @Autowired private MerchantChapterOfferMapper offerMapper;
    @Autowired private CmsTopicMapper topicMapper;
    @Autowired private CmsTopicChapterMapper chapterMapper;
    @Autowired private IMmsMerchantService merchantService;

    public MerchantSettlementOverview overview(Long memberId) {
        List<UmsMemberBalanceDetail> balanceRows = personalBalanceRows(memberId);
        Map<Long, CoopSettlement> paidCoop = paidCoopById(memberId);
        Date[] month = shanghaiMonth(new Date());
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal adjustments = BigDecimal.ZERO;
        for (UmsMemberBalanceDetail row : balanceRows) {
            if (row.getCreateTime() == null || row.getChangeBalance() == null || !within(row.getCreateTime(), month)) continue;
            if (row.getEventType() != null && row.getEventType().longValue() == 5L
                    && !paidCoop.containsKey(row.getEventId())) continue;
            if (row.getChangeBalance().signum() > 0) gross = gross.add(row.getChangeBalance());
            if (row.getChangeBalance().signum() < 0) adjustments = adjustments.add(row.getChangeBalance());
        }
        Map<String, BigDecimal> netByPeriod = new HashMap<String, BigDecimal>();
        for (MerchantPayable payable : safeList(payableMapper.selectByMerchant(memberId))) {
            if (payable == null || payable.getAmount() == null || payable.getCreateTime() == null) continue;
            String period = shanghaiPeriod(payable.getCreateTime());
            BigDecimal signed = MerchantPayable.ENTRY_REVERSAL.equals(payable.getEntryType())
                    ? payable.getAmount().negate() : payable.getAmount();
            netByPeriod.put(period, netByPeriod.containsKey(period) ? netByPeriod.get(period).add(signed) : signed);
        }
        Map<String, MerchantPayableBatch> batches = batchesByPeriod(memberId);
        BigDecimal publicPending = BigDecimal.ZERO;
        BigDecimal adjustmentPending = BigDecimal.ZERO;
        int adjustmentCount = 0;
        for (Map.Entry<String, BigDecimal> item : netByPeriod.entrySet()) {
            MerchantPayableBatch batch = batches.get(item.getKey());
            BigDecimal net = batch == null || batch.getAmountTotal() == null ? item.getValue() : batch.getAmountTotal();
            boolean paid = batch != null && MerchantPayableBatch.STATUS_PAID.equals(batch.getStatus());
            if (!paid && net.signum() > 0) publicPending = publicPending.add(net);
            if (!paid && net.signum() <= 0) { adjustmentPending = adjustmentPending.add(net.abs()); adjustmentCount++; }
        }
        for (MerchantClawbackApplication clawback : safeList(clawbackMapper.selectByMerchantMemberId(memberId))) {
            if (clawback != null && MerchantClawbackApplication.STATUS_PENDING_REVIEW.equals(clawback.getStatus())
                    && clawback.getAmount() != null) { adjustmentPending = adjustmentPending.add(clawback.getAmount()); adjustmentCount++; }
        }
        MerchantSettlementOverview result = new MerchantSettlementOverview();
        result.setPersonalArrivedThisMonthGross(money(gross));
        result.setPersonalExecutedAdjustmentsThisMonth(money(adjustments));
        result.setPersonalArrivedThisMonthNet(money(gross.add(adjustments)));
        result.setPublicPayablePending(money(publicPending));
        result.setAdjustmentPending(money(adjustmentPending));
        result.setAdjustmentPendingCount(adjustmentCount);
        return result;
    }

    public MerchantFinancePage<MerchantRedemptionView> redemptions(Long memberId, String filter, int pageNum, int pageSize) {
        int safePageNum = pageNum < 1 ? 1 : pageNum;
        int safePageSize = pageSize < 1 ? 20 : Math.min(pageSize, 50);
        List<MerchantPayable> payables = safeList(payableMapper.selectByMerchant(memberId));
        Map<Long, List<MerchantPayable>> byRedemption = new HashMap<Long, List<MerchantPayable>>();
        for (MerchantPayable payable : payables) {
            if (payable == null || payable.getRedemptionId() == null) continue;
            List<MerchantPayable> items = byRedemption.get(payable.getRedemptionId());
            if (items == null) { items = new ArrayList<MerchantPayable>(); byRedemption.put(payable.getRedemptionId(), items); }
            items.add(payable);
        }
        Map<String, MerchantPayableBatch> batches = batchesByPeriod(memberId);
        MmsMerchant merchant = merchantService == null ? null : merchantService.selectMmsMerchantByMemberId(memberId);
        List<MerchantRedemptionView> all = new ArrayList<MerchantRedemptionView>();
        // 汇总条按【筛选前】的全集算:它回答「我这个月接待了多少」,切筛选 chip 时不该跟着变。
        List<MerchantRedemptionView> unfiltered = new ArrayList<MerchantRedemptionView>();
        for (Redemption redemption : safeList(redemptionMapper.selectByMerchant(memberId))) {
            MerchantRedemptionView row = toRedemptionView(memberId, redemption, byRedemption.get(redemption == null ? null : redemption.getId()),
                    batches, merchant == null ? null : merchant.getName());
            if (row == null) continue;
            unfiltered.add(row);
            if (matchesRedemptionFilter(row, filter)) all.add(row);
        }
        Collections.sort(all, new Comparator<MerchantRedemptionView>() {
            @Override public int compare(MerchantRedemptionView left, MerchantRedemptionView right) {
                long l = left.getOccurredAt() == null ? 0L : left.getOccurredAt().getTime();
                long r = right.getOccurredAt() == null ? 0L : right.getOccurredAt().getTime();
                return Long.compare(r, l);
            }
        });
        MerchantFinancePage<MerchantRedemptionView> page = new MerchantFinancePage<MerchantRedemptionView>();
        page.setSummary(summarizeThisMonth(unfiltered));
        page.setTotal(all.size()); page.setPageNum(safePageNum); page.setPageSize(safePageSize);
        int from = Math.min((safePageNum - 1) * safePageSize, all.size());
        page.setRows(new ArrayList<MerchantRedemptionView>(all.subList(from, Math.min(from + safePageSize, all.size()))));
        return page;
    }

    public MerchantRedemptionView redemptionDetail(Long memberId, String recordType, String recordId) {
        if (!"redemption".equals(recordType) || blank(recordId)) return null;
        Long redemptionId = parseId(recordId);
        if (redemptionId == null) return null;
        Redemption redemption = redemptionMapper.selectByMerchantAndId(memberId, redemptionId);
        if (redemption == null) return null;
        List<MerchantPayable> facts = new ArrayList<MerchantPayable>();
        for (MerchantPayable payable : safeList(payableMapper.selectByMerchant(memberId))) {
            if (payable != null && redemptionId.equals(payable.getRedemptionId())) facts.add(payable);
        }
        MmsMerchant merchant = merchantService == null ? null : merchantService.selectMmsMerchantByMemberId(memberId);
        return toRedemptionView(memberId, redemption, facts, batchesByPeriod(memberId), merchant == null ? null : merchant.getName());
    }

    public MerchantFinancePage<PublicTransferBatch> publicTransferBatches(Long memberId, int pageNum, int pageSize) {
        int safePageNum = pageNum < 1 ? 1 : pageNum;
        int safePageSize = pageSize < 1 ? 20 : Math.min(pageSize, 50);
        List<PublicTransferBatch> all = new ArrayList<PublicTransferBatch>();
        for (MerchantPayableBatch batch : safeList(batchMapper.selectByMerchant(memberId))) if (batch != null) all.add(toPublicBatch(batch));
        MerchantFinancePage<PublicTransferBatch> page = new MerchantFinancePage<PublicTransferBatch>();
        page.setTotal(all.size()); page.setPageNum(safePageNum); page.setPageSize(safePageSize);
        int from = Math.min((safePageNum - 1) * safePageSize, all.size());
        page.setRows(new ArrayList<PublicTransferBatch>(all.subList(from, Math.min(from + safePageSize, all.size()))));
        return page;
    }

    public PublicTransferBatchDetail publicTransferBatchDetail(Long memberId, Long batchId) {
        if (batchId == null) return null;
        MerchantPayableBatch owned = batchMapper.selectByMerchantAndId(memberId, batchId);
        if (owned == null) return null;
        PublicTransferBatchDetail detail = new PublicTransferBatchDetail();
        detail.setBatch(toPublicBatch(owned));
        List<MerchantSettlementEntry> all = settlementEntriesAll(memberId, "public");
        List<MerchantSettlementEntry> earnings = new ArrayList<MerchantSettlementEntry>();
        List<MerchantSettlementEntry> adjustments = new ArrayList<MerchantSettlementEntry>();
        for (MerchantSettlementEntry entry : all) {
            if (!String.valueOf(batchId).equals(entry.getRefId())
                    && !String.valueOf(batchId).equals(entry.getTargetBatchId())) continue;
            if ("EARNING".equals(entry.getEntryKind())) earnings.add(entry); else adjustments.add(entry);
        }
        detail.setEarningEntries(earnings); detail.setAdjustments(adjustments);
        return detail;
    }

    public MerchantFinancePage<MerchantSettlementEntry> settlementEntries(Long memberId, String source,
                                                                            int pageNum, int pageSize) {
        int safePageNum = pageNum < 1 ? 1 : pageNum;
        int safePageSize = pageSize < 1 ? 20 : Math.min(pageSize, 50);
        List<MerchantSettlementEntry> all = settlementEntriesAll(memberId, source);
        MerchantFinancePage<MerchantSettlementEntry> page = new MerchantFinancePage<MerchantSettlementEntry>();
        page.setTotal(all.size());
        page.setPageNum(safePageNum);
        page.setPageSize(safePageSize);
        int from = Math.min((safePageNum - 1) * safePageSize, all.size());
        int to = Math.min(from + safePageSize, all.size());
        page.setRows(new ArrayList<MerchantSettlementEntry>(all.subList(from, to)));
        return page;
    }

    private List<MerchantSettlementEntry> settlementEntriesAll(Long memberId, String source) {
        Map<String, MerchantPayableBatch> batches = batchesByPeriod(memberId);
        List<MerchantSettlementEntry> all = new ArrayList<MerchantSettlementEntry>();
        addPayables(all, safeList(payableMapper.selectByMerchant(memberId)), batches);
        addCoopSettlements(all, safeList(coopSettlementMapper.selectByMerchant(memberId)));
        addExecutedClawbacks(all, safeList(clawbackMapper.selectByMerchantMemberId(memberId)), batches);
        addPersonalArrivals(all, memberId);
        filterBySource(all, source);
        Collections.sort(all, new Comparator<MerchantSettlementEntry>() {
            @Override public int compare(MerchantSettlementEntry left, MerchantSettlementEntry right) {
                long l = left.getOccurredAt() == null ? 0L : left.getOccurredAt().getTime();
                long r = right.getOccurredAt() == null ? 0L : right.getOccurredAt().getTime();
                int byTime = Long.compare(r, l);
                if (byTime != 0) return byTime;
                return right.getEntryKey().compareTo(left.getEntryKey());
            }
        });
        return all;
    }

    private void addPayables(List<MerchantSettlementEntry> target, List<MerchantPayable> payables,
                             Map<String, MerchantPayableBatch> batches) {
        for (MerchantPayable payable : payables) {
            if (payable == null || payable.getId() == null || payable.getAmount() == null) continue;
            String period = shanghaiPeriod(payable.getCreateTime());
            MerchantPayableBatch batch = period == null ? null : batches.get(period);
            BigDecimal signed = MerchantPayable.ENTRY_REVERSAL.equals(payable.getEntryType())
                    ? payable.getAmount().negate() : payable.getAmount();
            MerchantSettlementEntry entry = base("payable:" + payable.getId(), "CHAPTER_OFFER",
                    MerchantPayable.ENTRY_REVERSAL.equals(payable.getEntryType()) ? "REVERSAL" : "EARNING",
                    "REDEMPTION_FEE", String.valueOf(payable.getRedemptionId()), "PUBLIC_TRANSFER", signed,
                    payable.getCreateTime(), batch == null ? null : batch.getPaidAt(), "redemption",
                    payable.getRedemptionId());
            entry.setClaimRef(singleClaimRef());
            entry.setRefId(batch == null || batch.getId() == null ? null : String.valueOf(batch.getId()));
            if (batch != null && MerchantPayableBatch.STATUS_PAID.equals(batch.getStatus())
                    && (batch.getPaidAt() == null || blank(batch.getPayVoucherNo()))) {
                entry.setDisplayState(MerchantFinanceStateResolver.resolve("ACTIVE", "ERROR"));
            } else if (MerchantPayable.ENTRY_REVERSAL.equals(payable.getEntryType())) {
                entry.setDisplayState(MerchantFinanceStateResolver.resolve("REVERSED",
                        batch != null && MerchantPayableBatch.STATUS_PAID.equals(batch.getStatus())
                                ? "ADJUSTED" : "ADJUSTMENT_PENDING"));
            } else {
                entry.setDisplayState(MerchantFinanceStateResolver.resolve("ACTIVE",
                        batch != null && MerchantPayableBatch.STATUS_PAID.equals(batch.getStatus()) ? "SETTLED" : "PENDING"));
            }
            target.add(entry);
        }
    }

    private void addCoopSettlements(List<MerchantSettlementEntry> target, List<CoopSettlement> settlements) {
        for (CoopSettlement settlement : settlements) {
            if (settlement == null || settlement.getId() == null || settlement.getAmount() == null) continue;
            if (settlement.getAmount().compareTo(BigDecimal.ZERO) == 0) continue;
            MerchantSettlementEntry entry = base("coop:" + settlement.getId(), "COOP_ORDER", "EARNING", "COOP_SHARE",
                    String.valueOf(settlement.getId()), "PERSONAL_ACCOUNT", settlement.getAmount(), settlement.getCreateTime(),
                    settlement.getPayoutTime(), "coop_settlement", settlement.getId());
            entry.setHeadCount(settlement.getVerifiedHeads());
            Map<String, Object> group = new HashMap<String, Object>();
            group.put("type", "GROUP");
            group.put("groupType", "COOP_SETTLEMENT");
            group.put("groupId", settlement.getId());
            // P0 尚未建归属明细，不能用 topic 级 verifiedRegIds 编造 claimCount。
            group.put("claimCount", null);
            group.put("headCount", settlement.getVerifiedHeads());
            entry.setClaimRef(group);
            entry.setDisplayState(MerchantFinanceStateResolver.resolve("ACTIVE",
                    Integer.valueOf(1).equals(settlement.getStatus()) ? "SETTLED" : "PENDING"));
            target.add(entry);
        }
    }

    private void addExecutedClawbacks(List<MerchantSettlementEntry> target,
                                      List<MerchantClawbackApplication> clawbacks,
                                      Map<String, MerchantPayableBatch> batches) {
        for (MerchantClawbackApplication clawback : clawbacks) {
            if (clawback == null || !MerchantClawbackApplication.STATUS_EXECUTED.equals(clawback.getStatus())
                    || clawback.getId() == null || clawback.getAmount() == null) continue;
            Date occurredAt = clawback.getExecutedAt() == null ? clawback.getConfirmedAt() : clawback.getExecutedAt();
            MerchantSettlementEntry entry = base("clawback:" + clawback.getId(), "CHAPTER_OFFER", "CLAWBACK",
                    "MERCHANT_CLAWBACK", String.valueOf(clawback.getId()), "PUBLIC_TRANSFER",
                    clawback.getAmount().negate(), occurredAt, occurredAt, "safe_adjustment", clawback.getId());
            entry.setClaimRef(singleClaimRef());
            entry.setTargetBatchId(clawback.getTargetBatchId() == null ? null : String.valueOf(clawback.getTargetBatchId()));
            entry.setDisplayState(MerchantFinanceStateResolver.resolve("ACTIVE", "ADJUSTED"));
            target.add(entry);
        }
    }

    private void addPersonalArrivals(List<MerchantSettlementEntry> target, Long memberId) {
        UmsMemberBalanceDetail query = new UmsMemberBalanceDetail();
        query.setMemberId(memberId);
        for (UmsMemberBalanceDetail detail : safeList(balanceDetailMapper.selectUmsMemberBalanceDetailList(query))) {
            if (detail == null || detail.getId() == null || detail.getChangeBalance() == null || detail.getEventType() == null) continue;
            // 5 是已落库的 coop payout；活动收入仅认既有 eventType=2，避免把提现/邀请等奖励混入商家收入。
            if (detail.getEventType().longValue() != 5L && detail.getEventType().longValue() != 2L) continue;
            BigDecimal signed = detail.getChangeBalance();
            MerchantSettlementEntry entry = base("balance:" + detail.getId(), "REGISTRATION_SPLIT",
                    signed.signum() < 0 ? "CLAWBACK" : "EARNING",
                    detail.getEventType().longValue() == 5L ? "COOP_SHARE" : "ACTIVITY_SHARE",
                    String.valueOf(detail.getEventId()), "PERSONAL_ACCOUNT", signed, detail.getCreateTime(),
                    detail.getCreateTime(), "balance_detail", detail.getId());
            entry.setClaimRef(singleClaimRef());
            entry.setDisplayState(MerchantFinanceStateResolver.resolve("ACTIVE", signed.signum() < 0 ? "ADJUSTED" : "SETTLED"));
            target.add(entry);
        }
    }

    private MerchantSettlementEntry base(String key, String route, String kind, String source, String sourceId,
                                         String destination, BigDecimal amount, Date occurredAt, Date arrivalAt,
                                         String refKind, Long refId) {
        MerchantSettlementEntry entry = new MerchantSettlementEntry();
        entry.setEntryKey(key);
        entry.setSettlementRoute(route);
        entry.setEntryKind(kind);
        entry.setSource(source);
        entry.setSourceId(sourceId);
        entry.setDestination(destination);
        entry.setSignedAmount(money(amount));
        entry.setOccurredAt(occurredAt);
        entry.setArrivalAt(arrivalAt);
        entry.setRefKind(refKind);
        entry.setRefId(refId == null ? null : String.valueOf(refId));
        Map<String, String> ref = new HashMap<String, String>();
        ref.put("kind", refKind);
        ref.put("id", entry.getRefId());
        entry.setRef(ref);
        return entry;
    }

    private Map<String, MerchantPayableBatch> batchesByPeriod(Long memberId) {
        Map<String, MerchantPayableBatch> result = new HashMap<String, MerchantPayableBatch>();
        for (MerchantPayableBatch batch : safeList(batchMapper.selectByMerchant(memberId))) {
            if (batch != null && !blank(batch.getPeriodYm())) result.put(batch.getPeriodYm(), batch);
        }
        return result;
    }

    private MerchantRedemptionView toRedemptionView(Long ownerMemberId, Redemption redemption, List<MerchantPayable> payables,
                                                    Map<String, MerchantPayableBatch> batches, String storeName) {
        if (redemption == null || redemption.getId() == null) return null;
        MerchantRedemptionView row = new MerchantRedemptionView();
        row.setRecordKey("redemption:" + redemption.getId());
        row.setRecordType("redemption");
        row.setRecordId(String.valueOf(redemption.getId()));
        row.setOccurredAt(redemption.getCreateTime());
        row.setFulfillmentState("REVERSED".equals(redemption.getStatus()) ? "REVERSED" : "ACTIVE");
        row.setStoreName(storeName);
        row.setRefundState(null); // 当前退款表没有按商家核销安全关联；不得猜测 NONE。
        CmsTopic topic = topicMapper == null || redemption.getTopicId() == null ? null : topicMapper.selectCmsTopicById(redemption.getTopicId());
        CmsTopicChapter chapter = chapterMapper == null || redemption.getChapterId() == null ? null
                : chapterMapper.selectCmsTopicChapterById(redemption.getChapterId());
        row.setTopicName(topic == null ? null : topic.getName());
        row.setChapterName(chapter == null ? null : chapter.getName());
        MerchantChapterOffer offer = offerMapper == null || redemption.getOfferId() == null ? null
                : offerMapper.selectById(redemption.getOfferId());
        if (offer != null && "PERK".equals(offer.getTermsMode())) {
            row.setSettlementRoute("NONE");
            row.setSettlementState("NOT_APPLICABLE");
            row.setNoCashReason("权益已履约 · 无现金结算");
            row.setDisplayState(MerchantFinanceStateResolver.resolve(row.getFulfillmentState(), row.getSettlementState()));
            return row;
        }
        if (offer != null && "TRAFFIC".equals(offer.getTermsMode())) {
            row.setSettlementRoute("NONE");
            row.setSettlementState("NOT_APPLICABLE");
            row.setNoCashReason("本次接待不产生结算");
            row.setDisplayState(MerchantFinanceStateResolver.resolve(row.getFulfillmentState(), row.getSettlementState()));
            return row;
        }
        List<MerchantPayable> facts = payables == null ? Collections.<MerchantPayable>emptyList() : payables;
        if (offer != null && "REVSHARE".equals(offer.getTermsMode()) && facts.isEmpty()
                && "ACTIVE".equals(row.getFulfillmentState())) {
            row.setSettlementRoute("CHAPTER_OFFER");
            row.setSettlementState("ERROR");
            row.setDisplayState(MerchantFinanceStateResolver.resolve(row.getFulfillmentState(), row.getSettlementState()));
            return row;
        }
        if (facts.isEmpty()) {
            row.setSettlementRoute("COOP_ORDER");
            // ①/② 没有商家级原子结算事实时，只是待主题结算，金额绝不可伪造成 0。
            row.setSettlementState("PENDING");
            row.setDisplayState(MerchantFinanceStateResolver.resolve(row.getFulfillmentState(), row.getSettlementState()));
            return row;
        }
        BigDecimal net = BigDecimal.ZERO;
        row.setSettlementRoute("CHAPTER_OFFER");
        boolean paid = false;
        boolean pendingReversal = false;
        MerchantPayable latest = facts.get(facts.size() - 1);
        for (MerchantPayable fact : facts) {
            BigDecimal signed = MerchantPayable.ENTRY_REVERSAL.equals(fact.getEntryType()) ? fact.getAmount().negate() : fact.getAmount();
            net = net.add(signed);
            MerchantPayableBatch batch = batches.get(shanghaiPeriod(fact.getCreateTime()));
            if (batch != null && MerchantPayableBatch.STATUS_PAID.equals(batch.getStatus())) paid = true;
            if (MerchantPayable.ENTRY_REVERSAL.equals(fact.getEntryType())
                    && (batch == null || !MerchantPayableBatch.STATUS_PAID.equals(batch.getStatus()))) pendingReversal = true;
        }
        row.setSettlementAmount(money(net));
        if (net.signum() == 0 && offer == null) {
            row.setSettlementRoute("COOP_ORDER");
            row.setSettlementState("NOT_APPLICABLE");
            row.setNoCashReason("本次合作不产生现金分润");
            row.setDisplayState(MerchantFinanceStateResolver.resolve(row.getFulfillmentState(), row.getSettlementState()));
            return row;
        }
        if (pendingReversal) row.setSettlementState("ADJUSTMENT_PENDING");
        else if (paid && MerchantPayable.ENTRY_REVERSAL.equals(latest.getEntryType())) row.setSettlementState("ADJUSTED");
        else if (paid) row.setSettlementState("SETTLED");
        else row.setSettlementState("PENDING");
        row.setDisplayState(MerchantFinanceStateResolver.resolve(row.getFulfillmentState(), row.getSettlementState()));
        projectAccrualBasis(row, ownerMemberId, redemption, facts);
        return row;
    }

    private void projectAccrualBasis(MerchantRedemptionView row, Long ownerMemberId,
                                     Redemption redemption, List<MerchantPayable> payables) {
        if (!"CHAPTER_OFFER".equals(row.getSettlementRoute()) || ownerMemberId == null
                || !ownerMemberId.equals(redemption.getMerchantId())) return;
        List<MerchantAccrualBasis.Fact> facts = new ArrayList<MerchantAccrualBasis.Fact>();
        for (MerchantPayable payable : payables) {
            if (payable == null) return;
            facts.add(new MerchantAccrualBasis.Fact(payable.getId(), payable.getClaimId(),
                    payable.getMerchantMemberId(), payable.getRedemptionId(), payable.getEventKey(),
                    payable.getEntryType(), payable.getIdempotencyKey(),
                    payable.getPerHeadFeeSnapshot(), payable.getAmount()));
        }
        MerchantAccrualBasis basis = MerchantAccrualBasis.project(ownerMemberId, redemption.getId(), facts);
        if (basis != null) {
            row.setUnitFee(basis.getUnitFee());
            row.setHeadCount(basis.getHeadCount());
        }
    }

    private PublicTransferBatch toPublicBatch(MerchantPayableBatch batch) {
        PublicTransferBatch result = new PublicTransferBatch();
        BigDecimal amount = batch.getAmountTotal() == null ? BigDecimal.ZERO : batch.getAmountTotal();
        result.setBatchId(batch.getId() == null ? null : String.valueOf(batch.getId()));
        result.setPeriodYm(batch.getPeriodYm());
        result.setAmountTotal(money(amount));
        result.setNetDirection(amount.signum() > 0 ? "PLATFORM_PAYS_MERCHANT"
                : (amount.signum() == 0 ? "ZERO" : "MERCHANT_OWES_PLATFORM"));
        boolean paidInvalid = MerchantPayableBatch.STATUS_PAID.equals(batch.getStatus())
                && (amount.signum() <= 0 || batch.getPaidAt() == null || blank(batch.getPayVoucherNo()));
        result.setPaymentState(paidInvalid ? "PENDING" : (batch.getStatus() == null ? "PENDING" : batch.getStatus()));
        result.setDisplayState(paidInvalid ? "LEDGER_ERROR" : null);
        result.setInvoiceState(batch.getInvoiceStatus() == null ? "NONE" : batch.getInvoiceStatus());
        result.setHoldState(Integer.valueOf(1).equals(batch.getFrozen()) ? "FROZEN" : "NORMAL");
        result.setPaidAt(batch.getPaidAt());
        result.setPayVoucherNo(batch.getPayVoucherNo());
        return result;
    }

    private boolean matchesRedemptionFilter(MerchantRedemptionView row, String filter) {
        if (blank(filter) || "all".equals(filter)) return true;
        if ("pending".equals(filter)) return "PENDING_SETTLEMENT".equals(row.getDisplayState())
                || "ADJUSTMENT_PENDING".equals(row.getDisplayState()) || "LEDGER_ERROR".equals(row.getDisplayState());
        if ("handled".equals(filter)) return "SETTLED".equals(row.getDisplayState()) || "ADJUSTED".equals(row.getDisplayState())
                || "REVERSED_BEFORE_SETTLEMENT".equals(row.getDisplayState());
        return "no_cash".equals(filter) && "NO_CASH_SETTLEMENT".equals(row.getDisplayState());
    }

    private List<UmsMemberBalanceDetail> personalBalanceRows(Long memberId) {
        UmsMemberBalanceDetail query = new UmsMemberBalanceDetail();
        query.setMemberId(memberId);
        List<UmsMemberBalanceDetail> result = new ArrayList<UmsMemberBalanceDetail>();
        for (UmsMemberBalanceDetail row : safeList(balanceDetailMapper.selectUmsMemberBalanceDetailList(query))) {
            if (row == null || row.getEventType() == null) continue;
            if (row.getEventType().longValue() == 5L || row.getEventType().longValue() == 2L) result.add(row);
        }
        return result;
    }

    private Map<Long, CoopSettlement> paidCoopById(Long memberId) {
        Map<Long, CoopSettlement> result = new HashMap<Long, CoopSettlement>();
        for (CoopSettlement settlement : safeList(coopSettlementMapper.selectByMerchant(memberId))) {
            if (settlement != null && settlement.getId() != null && Integer.valueOf(1).equals(settlement.getStatus())) result.put(settlement.getId(), settlement);
        }
        return result;
    }

    /**
     * 核销记录页顶部汇总条(资金域重排草图 01)。
     *
     * <p>入参是【筛选前】的全集,窗口固定 Asia/Shanghai 当前自然月(复用 shanghaiMonth/within,
     * 与 overview 同一套口径)。⚠️ 这份聚合必须在服务端算:前端只拿得到当前页 rows,
     * 对它求和分页后必错,而且错得静默。
     */
    private static MerchantRedemptionSummary summarizeThisMonth(List<MerchantRedemptionView> rows) {
        Date[] month = shanghaiMonth(new Date());
        int count = 0;
        BigDecimal pending = BigDecimal.ZERO;
        BigDecimal arrived = BigDecimal.ZERO;
        for (MerchantRedemptionView row : rows) {
            if (row == null || !within(row.getOccurredAt(), month)) continue;
            count++;
            // settlementAmount 是两位小数字符串:null=金额尚未成立、0=已确认不产生现金,两者都不进金额合计。
            BigDecimal amount = parseAmount(row.getSettlementAmount());
            if (amount == null) continue;
            if ("PENDING_SETTLEMENT".equals(row.getDisplayState())) pending = pending.add(amount);
            else if ("SETTLED".equals(row.getDisplayState())) arrived = arrived.add(amount);
        }
        MerchantRedemptionSummary summary = new MerchantRedemptionSummary();
        summary.setCount(count);
        summary.setPendingAmount(money(pending));
        summary.setArrivedAmount(money(arrived));
        return summary;
    }

    private static BigDecimal parseAmount(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Date[] shanghaiMonth(Date now) {
        java.util.Calendar calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        calendar.setTime(now);
        calendar.set(java.util.Calendar.DAY_OF_MONTH, 1);
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0);
        calendar.set(java.util.Calendar.MINUTE, 0);
        calendar.set(java.util.Calendar.SECOND, 0);
        calendar.set(java.util.Calendar.MILLISECOND, 0);
        Date start = calendar.getTime();
        calendar.add(java.util.Calendar.MONTH, 1);
        return new Date[]{start, calendar.getTime()};
    }

    private static boolean within(Date value, Date[] range) {
        return value != null && !value.before(range[0]) && value.before(range[1]);
    }

    private void filterBySource(List<MerchantSettlementEntry> entries, String source) {
        if (blank(source) || "all".equals(source)) return;
        for (int i = entries.size() - 1; i >= 0; i--) {
            MerchantSettlementEntry entry = entries.get(i);
            boolean keep = "coop".equals(source) ? "COOP_SHARE".equals(entry.getSource())
                    : ("public".equals(source) ? "PUBLIC_TRANSFER".equals(entry.getDestination())
                    : ("personal".equals(source) ? "PERSONAL_ACCOUNT".equals(entry.getDestination()) : false));
            if (!keep) entries.remove(i);
        }
    }

    private static String shanghaiPeriod(Date value) {
        if (value == null) return null;
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM");
        format.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        return format.format(value);
    }

    private static String money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static Map<String, Object> singleClaimRef() {
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("type", "SINGLE");
        // P0 settlement_claim 尚未落地，显式传 null 而非拿来源 id 假冒 claimId。
        result.put("claimId", null);
        return result;
    }

    private static Long parseId(String value) {
        try { return Long.valueOf(value); } catch (NumberFormatException ignored) { return null; }
    }
    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
    private static <T> List<T> safeList(List<T> value) { return value == null ? Collections.<T>emptyList() : value; }
}
