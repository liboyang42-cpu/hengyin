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
import com.chengyinhub.business.domain.vo.ClubSettlementSummaryRow;
import com.chengyinhub.business.domain.vo.MerchantRedemptionSummary;
import com.chengyinhub.business.domain.vo.MerchantRedemptionView;
import com.chengyinhub.business.domain.vo.MerchantSettlementEntry;
import com.chengyinhub.business.domain.vo.MerchantSettlementOverview;
import com.chengyinhub.business.domain.vo.PublicTransferBatch;
import com.chengyinhub.business.domain.vo.PublicTransferBatchDetail;
import com.chengyinhub.business.domain.vo.RedemptionSignupName;
import com.chengyinhub.business.security.PiiCryptoService;
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
    @Autowired private PiiCryptoService piiCryptoService;
    /** 第十二轮 k5:俱乐部场次结算付给本商家的核销费(按场次一行)。 */
    @Autowired(required = false) private com.chengyinhub.business.mapper.ClubMapper clubMapper;
    @Autowired(required = false) private com.chengyinhub.business.mapper.SettlementDetailMapper sessionFeeMapper;
    /** 个人账户「待结算」与资产页同源(/api/wallet/stages),见 personalPending。 */
    @Autowired(required = false) private MemberFundsStageService fundsStageService;

    public MerchantSettlementOverview overview(Long memberId) {
        BigDecimal[] personal = personalThisMonth(memberId);
        BigDecimal gross = personal[0];
        BigDecimal adjustments = personal[1];
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

    static final String RECORD_ACTIVITY_TICKET = "activity_ticket";
    static final String RECORD_SESSION_FEE = "session_fee";

    /**
     * 第十二轮 k5:俱乐部场次结算时付给本商家的核销费,按场次合并成一行(商家对一场只扫一次团码)。
     * 金额 = 已入账核销费 + 之后退款冲回(负数);全部冲回时显示「已调整」,部分冲回仍是已入个人账户。
     */
    private List<MerchantRedemptionView> sessionFeeViews(Long memberId, Long activityId, Long topicId, String storeName) {
        List<MerchantRedemptionView> out = new ArrayList<MerchantRedemptionView>();
        if (sessionFeeMapper == null || memberId == null) return out;
        Map<Long, List<com.chengyinhub.business.domain.vo.MerchantSessionFeeLine>> byActivity =
                new java.util.LinkedHashMap<Long, List<com.chengyinhub.business.domain.vo.MerchantSessionFeeLine>>();
        for (com.chengyinhub.business.domain.vo.MerchantSessionFeeLine line
                : safeList(sessionFeeMapper.selectSessionFeeLinesByMerchant(memberId, activityId))) {
            if (line == null || line.getActivityId() == null || line.getFeeAmount() == null) continue;
            if (topicId != null && !topicId.equals(line.getTopicId())) continue;
            List<com.chengyinhub.business.domain.vo.MerchantSessionFeeLine> items = byActivity.get(line.getActivityId());
            if (items == null) { items = new ArrayList<com.chengyinhub.business.domain.vo.MerchantSessionFeeLine>(); byActivity.put(line.getActivityId(), items); }
            items.add(line);
        }
        for (Map.Entry<Long, List<com.chengyinhub.business.domain.vo.MerchantSessionFeeLine>> e : byActivity.entrySet()) {
            BigDecimal fee = BigDecimal.ZERO;
            BigDecimal clawback = BigDecimal.ZERO;
            BigDecimal unit = null;
            boolean sameUnit = true;
            Date occurredAt = null;
            com.chengyinhub.business.domain.vo.MerchantSessionFeeLine first = e.getValue().get(0);
            for (com.chengyinhub.business.domain.vo.MerchantSessionFeeLine line : e.getValue()) {
                fee = fee.add(line.getFeeAmount());
                if (line.getClawbackAmount() != null) clawback = clawback.add(line.getClawbackAmount());
                if (unit == null) unit = line.getUnitFee();
                else if (line.getUnitFee() == null || unit.compareTo(line.getUnitFee()) != 0) sameUnit = false;
                if (line.getOccurredAt() != null && (occurredAt == null || line.getOccurredAt().after(occurredAt))) occurredAt = line.getOccurredAt();
            }
            MerchantRedemptionView row = new MerchantRedemptionView();
            row.setRecordKey(RECORD_SESSION_FEE + ":" + e.getKey());
            row.setRecordType(RECORD_SESSION_FEE);
            row.setRecordId(String.valueOf(e.getKey()));
            row.setOccurredAt(occurredAt);
            row.setFulfillmentState("ACTIVE");
            row.setStoreName(storeName);
            row.setTopicName(first.getActivityTitle());
            row.setChapterName(first.getTicketName());
            row.setSettlementRoute("COOP_ORDER");
            row.setDestination("PERSONAL_ACCOUNT"); // 场次结算直接入商家余额,不是平台对公打款
            BigDecimal net = fee.add(clawback);
            row.setSettlementAmount(money(net));
            row.setRefundState(clawback.signum() == 0 ? null : "REFUNDED");
            row.setSettlementState(net.signum() == 0 ? "ADJUSTED" : "SETTLED");
            if (sameUnit && unit != null && unit.signum() > 0) {
                row.setUnitFee(money(unit));
                BigDecimal[] heads = fee.divideAndRemainder(unit);
                if (heads[1].signum() == 0) row.setHeadCount(heads[0].intValue());
            }
            row.setDisplayState(MerchantFinanceStateResolver.resolve(row.getFulfillmentState(), row.getSettlementState()));
            out.add(row);
        }
        return out;
    }

    /** 本月个人账户 [到账收入, 已执行调整(负数)];「结算」概览与「核销与退款」汇总共用同一份。 */
    private BigDecimal[] personalThisMonth(Long memberId) {
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
        return new BigDecimal[]{gross, adjustments};
    }

    /**
     * 个人账户还没到账的钱,与资产页「待结算」同一真源(MemberFundsStageService:未结束活动 + 客诉期,
     * 含 50% 退款后留给主办方的那部分,金额按结算同一公式估算)。审不出金额时返回 null,不给确定的 0。
     */
    private BigDecimal personalPending(Long memberId) {
        if (fundsStageService == null || memberId == null) return null;
        Map<String, Object> stages;
        try {
            stages = fundsStageService.stages(memberId, new Date());
        } catch (com.chengyinhub.common.exception.ServiceException unknownAccount) {
            return null; // 账户查不到:待结算未知,汇总条不出,不给确定的 0
        }
        if (stages == null || !Boolean.TRUE.equals(stages.get("amountsKnown"))) return null;
        BigDecimal pending = stages.get("pendingSettlement") instanceof BigDecimal
                ? (BigDecimal) stages.get("pendingSettlement") : BigDecimal.ZERO;
        Object period = stages.get("complaintPeriod");
        if (period instanceof List) for (Object item : (List<?>) period) {
            Object amount = item instanceof Map ? ((Map<?, ?>) item).get("amount") : null;
            if (amount instanceof BigDecimal) pending = pending.add((BigDecimal) amount);
        }
        return pending;
    }

    /**
     * check-1104 j1 F13 / F12:全店「核销与退款」汇总的待结算 / 已入账要与资产页、结算概览同源。
     * 个人账户部分(活动分润、coop 分润)直接取账户口径,章节计酬(对公)仍按核销行累加。
     */
    private void addPersonalAccount(MerchantRedemptionSummary summary, Long memberId) {
        BigDecimal pending = personalPending(memberId);
        BigDecimal rowsPending = parseAmount(summary.getPendingAmount());
        summary.setPendingAmount(pending == null || rowsPending == null ? null : money(rowsPending.add(pending)));
        BigDecimal[] personal = personalThisMonth(memberId);
        BigDecimal rowsArrived = parseAmount(summary.getArrivedAmount());
        summary.setArrivedAmount(rowsArrived == null ? null : money(rowsArrived.add(personal[0]).add(personal[1])));
    }

    private MerchantRedemptionView toActivityTicketView(com.chengyinhub.business.domain.vo.MerchantActivityTicketScan scan,
                                                        String storeName) {
        if (scan == null || scan.getRegistrationId() == null) return null;
        MerchantRedemptionView row = new MerchantRedemptionView();
        row.setRecordKey(RECORD_ACTIVITY_TICKET + ":" + scan.getRegistrationId());
        row.setRecordType(RECORD_ACTIVITY_TICKET);
        row.setRecordId(String.valueOf(scan.getRegistrationId()));
        row.setOccurredAt(scan.getVerifiedAt());
        row.setFulfillmentState("ACTIVE");
        row.setStoreName(storeName);
        row.setRefundState(null);
        row.setTopicName(scan.getActivityTitle());
        row.setChapterName(scan.getTicketName());
        if (!blank(scan.getRealName()) && piiCryptoService != null) {
            String name = piiCryptoService.decrypt(scan.getRealName());
            if (!blank(name)) row.setCustomerDisplayName(name.trim());
        }
        row.setSettlementRoute("ACTIVITY");
        // 第十二轮 k6(j1 N4):活动票由 settleActivity 结进商家 App 内余额(可提现),不是平台打款。
        row.setDestination("PERSONAL_ACCOUNT");
        if (scan.getSettlementAmount() == null) {
            // 支付时没落商家账本(老单/免费票):金额未成立,显示「待定」,不伪造 0。
            row.setSettlementState("PENDING");
        } else if (scan.getSettlementAmount().signum() == 0) {
            row.setSettlementAmount(money(scan.getSettlementAmount()));
            row.setSettlementState("NOT_APPLICABLE");
            row.setNoCashReason("免费票 · 无现金结算");
        } else {
            row.setSettlementAmount(money(scan.getSettlementAmount()));
            row.setSettlementState(Integer.valueOf(1).equals(scan.getSettlementStatus()) ? "SETTLED" : "PENDING");
        }
        row.setDisplayState(MerchantFinanceStateResolver.resolve(row.getFulfillmentState(), row.getSettlementState()));
        return row;
    }

    public MerchantFinancePage<MerchantRedemptionView> redemptions(Long memberId, String filter, int pageNum, int pageSize) {
        return redemptions(memberId, filter, pageNum, pageSize, null);
    }

    /**
     * 2026-11-01 业主:主题工作台「台账」只看这个主题的核销。topicId 只在本商家自己的核销集合里收窄
     * (selectByMerchant 已按商家归属取),汇总也按收窄后的集合算 —— 回答「这个主题我接待了多少」。
     */
    public MerchantFinancePage<MerchantRedemptionView> redemptions(Long memberId, String filter, int pageNum, int pageSize, Long topicId) {
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
        Map<Long, String> signupNames = signupNames(memberId, null);
        for (Redemption redemption : safeList(redemptionMapper.selectByMerchant(memberId))) {
            if (topicId != null && (redemption == null || !topicId.equals(redemption.getTopicId()))) continue;
            MerchantRedemptionView row = toRedemptionView(redemption, byRedemption.get(redemption == null ? null : redemption.getId()),
                    batches, merchant == null ? null : merchant.getName());
            if (row == null) continue;
            row.setCustomerDisplayName(signupNames.get(redemption.getId()));
            unfiltered.add(row);
            if (matchesRedemptionFilter(row, filter)) all.add(row);
        }
        // check-1104 j1 F13:商家自有活动的验票同列(原先只有章节核销,活动验票后这里恒为 0)。
        for (com.chengyinhub.business.domain.vo.MerchantActivityTicketScan scan
                : safeList(redemptionMapper.selectActivityTicketScansByMerchant(memberId, null))) {
            if (topicId != null && (scan == null || !topicId.equals(scan.getTopicId()))) continue;
            MerchantRedemptionView row = toActivityTicketView(scan, merchant == null ? null : merchant.getName());
            if (row == null) continue;
            unfiltered.add(row);
            if (matchesRedemptionFilter(row, filter)) all.add(row);
        }
        // 第十二轮 k5:俱乐部场次按核销付费的核销费(场次结算后才成立)。
        for (MerchantRedemptionView row : sessionFeeViews(memberId, null, topicId, merchant == null ? null : merchant.getName())) {
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
        MerchantRedemptionSummary summary = summarizeThisMonth(unfiltered, topicId == null);
        if (topicId == null) addPersonalAccount(summary, memberId);
        page.setSummary(summary);
        page.setTotal(all.size()); page.setPageNum(safePageNum); page.setPageSize(safePageSize);
        int from = Math.min((safePageNum - 1) * safePageSize, all.size());
        page.setRows(new ArrayList<MerchantRedemptionView>(all.subList(from, Math.min(from + safePageSize, all.size()))));
        return page;
    }

    public MerchantRedemptionView redemptionDetail(Long memberId, String recordType, String recordId) {
        if (RECORD_SESSION_FEE.equals(recordType)) {
            Long activityId = parseId(recordId);
            if (memberId == null || activityId == null) return null;
            MmsMerchant merchant = merchantService == null ? null : merchantService.selectMmsMerchantByMemberId(memberId);
            List<MerchantRedemptionView> rows = sessionFeeViews(memberId, activityId, null, merchant == null ? null : merchant.getName());
            return rows.isEmpty() ? null : rows.get(0);
        }
        if (RECORD_ACTIVITY_TICKET.equals(recordType)) {
            Long registrationId = parseId(recordId);
            if (memberId == null || registrationId == null) return null;
            List<com.chengyinhub.business.domain.vo.MerchantActivityTicketScan> scans =
                    safeList(redemptionMapper.selectActivityTicketScansByMerchant(memberId, registrationId));
            if (scans.isEmpty()) return null;
            MmsMerchant merchant = merchantService == null ? null : merchantService.selectMmsMerchantByMemberId(memberId);
            return toActivityTicketView(scans.get(0), merchant == null ? null : merchant.getName());
        }
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
        MerchantRedemptionView row = toRedemptionView(redemption, facts, batchesByPeriod(memberId), merchant == null ? null : merchant.getName());
        if (row != null) row.setCustomerDisplayName(signupNames(memberId, redemptionId).get(redemptionId));
        return row;
    }

    /**
     * M-27(2026-11-03 业主):核销对应报名的报名姓名,服务端解密(同 C-05 ClubCrmAppServiceImpl)。
     * SQL 已按本商家归属收窄;为空就不放进来,页面那一格不出,不写占位。姓名不写日志。
     */
    private Map<Long, String> signupNames(Long memberId, Long redemptionId) {
        Map<Long, String> out = new HashMap<Long, String>();
        if (memberId == null || redemptionMapper == null) return out;
        for (RedemptionSignupName raw : safeList(redemptionMapper.selectSignupNamesByMerchant(memberId, redemptionId))) {
            if (raw == null || raw.getRedemptionId() == null || blank(raw.getRealName())) continue;
            String name = piiCryptoService == null ? null : piiCryptoService.decrypt(raw.getRealName());
            if (!blank(name)) out.put(raw.getRedemptionId(), name.trim());
        }
        return out;
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
        addCoopSettlements(all, safeList(coopSettlementMapper.selectSummaryByMerchant(memberId)), memberId);
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

    private void addCoopSettlements(List<MerchantSettlementEntry> target, List<ClubSettlementSummaryRow> settlements, Long memberId) {
        for (ClubSettlementSummaryRow settlement : settlements) {
            if (settlement == null || settlement.getId() == null || settlement.getAmount() == null
                    || memberId == null || !memberId.equals(settlement.getMerchantId())) continue;
            BigDecimal net = settlement.getNetAmount();
            if (settlement.getAmount().signum() == 0 && net != null && net.signum() == 0) continue;
            MerchantSettlementEntry entry = base("coop:" + settlement.getId(), "COOP_ORDER", "EARNING", "COOP_SHARE",
                    String.valueOf(settlement.getId()), "PERSONAL_ACCOUNT", net, settlement.getCreateTime(),
                    settlement.getPayoutTime(), "coop_settlement", settlement.getId());
            if (net == null) entry.setSignedAmount(null);
            entry.setOriginalAmount(money(settlement.getOriginalAmount()));
            entry.setExecutedAdjustmentAmount(settlement.getExecutedAdjustmentAmount() == null
                    ? null : money(settlement.getExecutedAdjustmentAmount()));
            entry.setPayeeType(settlement.getPayeeType());
            entry.setTopicName(settlement.getTopicName());
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
            if (!isPersonalEarningRow(detail)) continue;
            BigDecimal signed = detail.getChangeBalance();
            MerchantSettlementEntry entry = base("balance:" + detail.getId(), "REGISTRATION_SPLIT",
                    signed.signum() < 0 ? "CLAWBACK" : "EARNING",
                    detail.getEventType().longValue() == 6L ? "REFUND_CLAWBACK"
                            : detail.getEventType().longValue() == 5L
                            || detail.getEventType().longValue() == com.chengyinhub.business.service.support.ClubSessionMerchantFee.EVENT_TYPE_SESSION_MERCHANT_FEE
                            ? "COOP_SHARE" : "ACTIVITY_SHARE",
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

    /** 全权益核销:票款归主办方(发布主题的俱乐部;商家发布的主题则是该商家),店铺与主办方线下结算。 */
    private String perkNoCashReason(CmsTopic topic) {
        String base = "票款由主办方收取；店铺如需结算，请联系主办方线下处理";
        String organizer = organizerName(topic);
        return organizer == null ? base : base + " · 主办方：" + organizer;
    }

    private String organizerName(CmsTopic topic) {
        if (topic == null) return null;
        String name = null;
        if (topic.getClubId() != null && topic.getClubId() > 0) {
            com.chengyinhub.business.domain.Club club = clubMapper == null ? null : clubMapper.selectClubById(topic.getClubId());
            name = club == null ? null : club.getName();
        } else if (topic.getMemberId() != null && merchantService != null) {
            MmsMerchant publisher = merchantService.selectMmsMerchantByMemberId(topic.getMemberId());
            name = publisher == null ? null : publisher.getName();
        }
        return name == null || name.trim().isEmpty() ? null : name.trim();
    }

    private MerchantRedemptionView toRedemptionView(Redemption redemption, List<MerchantPayable> payables,
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
            row.setNoCashReason(perkNoCashReason(topic));
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
        return row;
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
            if (isPersonalEarningRow(row)) result.add(row);
        }
        return result;
    }

    /**
     * 商家个人账户的经营收入流水:5 = 已落库 coop payout,2 = 既有活动分润口径,
     * 3 + 收入(change_type=1) = 活动结算给商户的分润(CmsActivityServiceImpl.settleActivity 写 eventType=3)。
     * eventType=3 同时是提现申请(change_type=2 支出),必须靠收入方向区分,不能把提现算成收入。
     * check-1104 j1 F13:原先漏掉 3,活动结算后「本月个人账户净入账」恒为 0,与资产页 ¥94.58 对不上。
     */
    static boolean isPersonalEarningRow(UmsMemberBalanceDetail row) {
        if (row == null || row.getEventType() == null) return false;
        long type = row.getEventType().longValue();
        if (type == 5L || type == 2L) return true;
        // 第十二轮 k5:95 = 俱乐部场次结算付的合作商家核销费(只有收入方向)。
        if (type == com.chengyinhub.business.service.support.ClubSessionMerchantFee.EVENT_TYPE_SESSION_MERCHANT_FEE) {
            return row.getChangeType() != null && row.getChangeType().longValue() == 1L;
        }
        // 第十二轮 k5:6 + 支出 = 结算后退款按比例从本账户扣回(RefundServiceImpl.debitReceiverBalance「退款冲销」)。
        // 不列它,财务里「已入账」就比余额多出被扣回的那部分。
        if (type == 6L) {
            return row.getChangeType() != null && row.getChangeType().longValue() == 2L;
        }
        return type == 3L && row.getChangeType() != null && row.getChangeType().longValue() == 1L;
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
    private static MerchantRedemptionSummary summarizeThisMonth(List<MerchantRedemptionView> rows, boolean personalFromAccount) {
        Date[] month = shanghaiMonth(new Date());
        int count = 0;
        BigDecimal pending = BigDecimal.ZERO;
        BigDecimal arrived = BigDecimal.ZERO;
        for (MerchantRedemptionView row : rows) {
            if (row == null || !within(row.getOccurredAt(), month)) continue;
            count++;
            // 全店汇总里活动验票的钱走个人账户口径(addPersonalAccount),这里只计笔数,避免同一笔钱算两遍。
            if (personalFromAccount && (RECORD_ACTIVITY_TICKET.equals(row.getRecordType())
                    || RECORD_SESSION_FEE.equals(row.getRecordType()))) continue;
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
