package com.chengyinhub.business.payout;

import com.chengyinhub.business.domain.EcomCombineOrder;
import com.chengyinhub.business.domain.EcomCombineReturn;
import com.chengyinhub.business.domain.EcomCombineSettlement;
import com.chengyinhub.business.domain.EcomLedgerComponent;
import com.chengyinhub.business.domain.EcomSupplierPayable;
import com.chengyinhub.business.mapper.EcomCombineMapper;
import com.chengyinhub.business.mapper.EcomPayoutMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * l-payout3：合单结算。份额只读内部账本（{@link CombinedSettlementPlanner}），本服务只做三件事：
 * <ol>
 *   <li>{@link #planTopic}：主题结算冻结后，每笔已付合单落一条结算计划和供应商应付（幂等：每单每主题一条）；</li>
 *   <li>{@link #submitDue}：到期（与 T+14 同口径）后，卖家子单不需要补差时「完结分账」解冻给卖家，需要补差时向平台分账；</li>
 *   <li>{@link #returnForRefund}：卖家子单退款前，按比例回退已经分给平台的补差。</li>
 * </ol>
 * 需要 {@code payout.ecommerce.combined-enabled} 与 {@code profit-sharing-enabled} 同时打开；否则所有入口直接返回，不读不写。
 */
@Service
public class EcommerceCombinedSettlementService {
    private static final Logger log = LoggerFactory.getLogger(EcommerceCombinedSettlementService.class);

    public static final String PLANNED = "PLANNED";
    public static final String PROCESSING = "PROCESSING";
    public static final String SUCCESS = "SUCCESS";
    public static final String MANUAL = "MANUAL";

    private final EcomCombineMapper mapper;
    private final EcomPayoutMapper payoutMapper;
    private final EcommercePayGateway gateway;
    private final PayoutSwitches switches;
    private final TransactionOperations tx;

    @Autowired
    public EcommerceCombinedSettlementService(EcomCombineMapper mapper, EcomPayoutMapper payoutMapper, EcommercePayGateway gateway,
                                              PayoutSwitches switches, PlatformTransactionManager transactionManager) {
        this(mapper, payoutMapper, gateway, switches, new TransactionTemplate(transactionManager));
    }

    public EcommerceCombinedSettlementService(EcomCombineMapper mapper, EcomPayoutMapper payoutMapper, EcommercePayGateway gateway,
                                              PayoutSwitches switches, TransactionOperations tx) {
        this.mapper = mapper;
        this.payoutMapper = payoutMapper;
        this.gateway = gateway;
        this.switches = switches;
        this.tx = tx;
    }

    public boolean active() { return switches.combinedEnabled() && switches.profitSharingEnabled(); }

    static String outOrderNo(String businessOutTradeNo, long topicId) {
        return "CYCS" + CombinedCheckoutPolicy.sha(businessOutTradeNo + "|" + topicId).substring(0, 28);
    }

    static String outReturnNo(long settlementId, String parentOutRefundNo) {
        return "CYCT" + CombinedCheckoutPolicy.sha(settlementId + "|" + parentOutRefundNo).substring(0, 28);
    }

    /** 为主题下每笔已付合单落结算计划。返回新建计划数。 */
    public int planTopic(long topicId) {
        if (!active()) return 0;
        int created = 0;
        for (EcomCombineOrder order : mapper.selectPaidCombineOrdersForTopic(topicId)) {
            if (mapper.selectSettlement(order.getOutTradeNo(), topicId) != null) continue;
            CombinedSettlementPlanner.Plan plan;
            if (!mapper.selectRefundsByTrade(order.getOutTradeNo()).isEmpty()) {
                // 结算前已退款：子单实收已不等于账本份额，不能按原份额结算，转人工。
                plan = new CombinedSettlementPlanner.Plan("MANUAL", "REFUNDED_BEFORE_SHARE", 0, 0, 0, List.of(), null);
            } else {
                List<ProfitSharePlanner.Component> components = new ArrayList<>();
                for (EcomLedgerComponent c : payoutMapper.selectLedgerComponents(topicId, order.getRegistrationId())) {
                    components.add(new ProfitSharePlanner.Component(c.getSettlementId(), c.getLineType(), c.getReceiverKind(),
                            c.getReceiverMemberId(), c.getOriginalAmount()));
                }
                Long sellerMember = payoutMapper.selectSellerMemberId(order.getSellerType(), order.getSellerId());
                plan = CombinedSettlementPlanner.plan(new CombinedSettlementPlanner.Order(order.getOutTradeNo(),
                        order.getSellerTransactionId(), order.getSellerFen(), order.getHeldFen(), sellerMember),
                        components, switches.maxSharePercent());
            }
            CombinedSettlementPlanner.Plan p = plan;
            Boolean inserted = tx.execute(status -> persist(topicId, order, p));
            if (Boolean.TRUE.equals(inserted)) created++;
        }
        return created;
    }

    private Boolean persist(long topicId, EcomCombineOrder order, CombinedSettlementPlanner.Plan plan) {
        EcomCombineSettlement row = new EcomCombineSettlement();
        row.setOutOrderNo(outOrderNo(order.getOutTradeNo(), topicId));
        row.setBusinessOutTradeNo(order.getOutTradeNo());
        row.setTopicId(topicId);
        row.setSellerSubMchid(order.getSellerSubMchid());
        row.setSellerTransactionId(order.getSellerTransactionId());
        row.setSellerFen(order.getSellerFen());
        row.setHeldFen(order.getHeldFen());
        row.setPlatformFen(plan.platformFen());
        row.setShortfallFen(plan.shortfallFen());
        row.setSurplusFen(plan.surplusFen());
        row.setPlanHash(plan.planHash() == null
                ? CombinedCheckoutPolicy.sha("MANUAL|" + plan.manualReason() + "|" + order.getOutTradeNo()) : plan.planHash());
        row.setState(plan.ready() ? PLANNED : MANUAL);
        row.setManualReason(plan.manualReason());
        try {
            mapper.insertSettlement(row);
        } catch (DuplicateKeyException concurrent) {
            return false;
        }
        for (CombinedSettlementPlanner.Payable p : plan.payables()) {
            EcomSupplierPayable payable = new EcomSupplierPayable();
            payable.setSource("COMBINED");
            payable.setBusinessOutTradeNo(order.getOutTradeNo());
            payable.setTopicId(topicId);
            payable.setSettlementId(p.settlementId());
            payable.setLineType(p.lineType());
            payable.setPayeeMemberId(p.payeeMemberId());
            payable.setAmountFen(p.amountFen());
            mapper.insertPayable(payable);
        }
        return true;
    }

    /** 补齐结算后落计划失败的主题（定时任务兜底）。 */
    public int planMissing(int limit) {
        if (!active()) return 0;
        int created = 0;
        for (Long topicId : mapper.selectSettledTopicsMissingCombineSettlements(clamp(limit))) {
            try {
                created += planTopic(topicId);
            } catch (RuntimeException e) {
                log.warn("combine settlement plan missing failed topicId={} type={}", topicId, e.getClass().getSimpleName());
            }
        }
        return created;
    }

    /** 到期计划：先 PLANNED→PROCESSING 抢占，再在事务外调微信；结果未知保持 PROCESSING，由 sync 按单号查证。 */
    public int submitDue(int limit) {
        if (!active()) return 0;
        int submitted = 0;
        for (EcomCombineSettlement s : mapper.selectDuePlannedSettlements(new Date(), clamp(limit))) {
            if (mapper.updateSettlementState(s.getId(), PLANNED, PROCESSING, null, null) != 1) continue;
            submitted++;
            try {
                if (s.getShortfallFen() == 0) {
                    gateway.finishProfitSharing(s.getSellerSubMchid(), s.getSellerTransactionId(), s.getOutOrderNo(), "城瘾结算完结");
                    mapper.updateSettlementState(s.getId(), PROCESSING, SUCCESS, null, null);
                } else {
                    apply(s.getId(), gateway.createProfitSharing(s.getSellerSubMchid(), s.getSellerTransactionId(), s.getOutOrderNo(),
                            List.of(new ProfitSharePlanner.Receiver(switches.platformReceiverMchid(), s.getShortfallFen())), true));
                }
            } catch (RuntimeException uncertain) {
                log.warn("combine settlement submit uncertain id={} type={}", s.getId(), uncertain.getClass().getSimpleName());
            }
        }
        return submitted;
    }

    public int syncProcessing(int limit) {
        if (!active()) return 0;
        int n = 0;
        for (EcomCombineSettlement s : mapper.selectSettlementsByState(PROCESSING, clamp(limit))) {
            try {
                apply(s.getId(), gateway.queryProfitSharing(s.getSellerSubMchid(), s.getSellerTransactionId(), s.getOutOrderNo()));
                n++;
            } catch (RuntimeException e) {
                log.warn("combine settlement query failed id={} type={}", s.getId(), e.getClass().getSimpleName());
            }
        }
        return n;
    }

    void apply(long id, EcommercePayGateway.ShareResult result) {
        if (result == null || result.state() == null) return;
        boolean anyFailed = result.receivers() != null && result.receivers().stream()
                .anyMatch(r -> "CLOSED".equals(r.result()) || "FAILED".equals(r.result()));
        if (anyFailed || "CLOSED".equals(result.state())) {
            mapper.updateSettlementState(id, PROCESSING, MANUAL, result.wxOrderId(), "WECHAT_SHARE_CLOSED");
        } else if ("FINISHED".equals(result.state()) || "SUCCESS".equals(result.state())) {
            boolean done = result.receivers() == null || result.receivers().stream()
                    .allMatch(r -> r.result() == null || "SUCCESS".equals(r.result()));
            if (done) mapper.updateSettlementState(id, PROCESSING, SUCCESS, result.wxOrderId(), null);
        }
    }

    /**
     * 卖家子单退款前回退补差分账（幂等：同一业务退款单号对同一结算只建一笔回退）。
     * 只有返回 NOT_APPLICABLE / ALL_RETURNED 时才能向微信申请卖家子单退款。
     */
    public EcommerceProfitShareService.ReturnOutcome returnForRefund(EcomCombineOrder order, String parentOutRefundNo,
                                                                     long sellerRefundFen, long sellerRefundedBeforeFen) {
        if (!switches.combinedEnabled()) return EcommerceProfitShareService.ReturnOutcome.PENDING;
        boolean pending = false, failed = false;
        for (EcomCombineSettlement s : mapper.selectSettlementsByTrade(order.getOutTradeNo())) {
            if (PLANNED.equals(s.getState())) {
                // 尚未提交：冻住计划，避免把正在退的钱分出去或完结；由人工按退款后的账本处理。
                mapper.updateSettlementState(s.getId(), PLANNED, MANUAL, null, "REFUNDED_BEFORE_SHARE");
                continue;
            }
            if (PROCESSING.equals(s.getState())) { pending = true; continue; }
            if (!SUCCESS.equals(s.getState()) || s.getShortfallFen() == 0) continue;
            Map<String, long[]> shared = new LinkedHashMap<>();
            long[] v = new long[] { s.getShortfallFen(), 0 };
            shared.put(switches.platformReceiverMchid(), v);
            for (EcomCombineReturn r : mapper.selectReturnsBySettlement(s.getId())) {
                if (parentOutRefundNo.equals(r.getParentOutRefundNo()) || "FAILED".equals(r.getState())) continue;
                v[1] += r.getAmountFen();
            }
            Long due = ProfitSharePlanner.returnsForRefund(shared, s.getSellerFen(), sellerRefundFen, sellerRefundedBeforeFen)
                    .get(switches.platformReceiverMchid());
            if (due == null || due <= 0) continue;
            EcommerceProfitShareService.ReturnOutcome one = returnOne(s, parentOutRefundNo, due);
            if (one == EcommerceProfitShareService.ReturnOutcome.PENDING) pending = true;
            if (one == EcommerceProfitShareService.ReturnOutcome.FAILED) failed = true;
        }
        if (failed) return EcommerceProfitShareService.ReturnOutcome.FAILED;
        return pending ? EcommerceProfitShareService.ReturnOutcome.PENDING : EcommerceProfitShareService.ReturnOutcome.ALL_RETURNED;
    }

    private EcommerceProfitShareService.ReturnOutcome returnOne(EcomCombineSettlement s, String parentOutRefundNo, long amountFen) {
        String no = outReturnNo(s.getId(), parentOutRefundNo);
        EcomCombineReturn row = mapper.selectReturn(no);
        if (row == null) {
            EcomCombineReturn created = new EcomCombineReturn();
            created.setOutReturnNo(no);
            created.setCombineSettlementId(s.getId());
            created.setParentOutRefundNo(parentOutRefundNo);
            created.setReturnMchid(switches.platformReceiverMchid());
            created.setAmountFen(amountFen);
            created.setState(PROCESSING);
            try {
                mapper.insertReturn(created);
            } catch (DuplicateKeyException concurrent) {
                // 以已存在的为准。
            }
            row = mapper.selectReturn(no);
        }
        if (row == null || row.getAmountFen() != amountFen) return EcommerceProfitShareService.ReturnOutcome.FAILED;
        if (SUCCESS.equals(row.getState())) return EcommerceProfitShareService.ReturnOutcome.ALL_RETURNED;
        if ("FAILED".equals(row.getState())) return EcommerceProfitShareService.ReturnOutcome.FAILED;
        try {
            EcommercePayGateway.ReturnResult result = gateway.createReturn(s.getSellerSubMchid(), s.getOutOrderNo(), no,
                    row.getReturnMchid(), amountFen, "城瘾退款回退分账");
            String r = result == null ? null : result.result();
            if (SUCCESS.equals(r)) {
                mapper.updateReturnState(no, PROCESSING, SUCCESS, result.wxReturnNo(), null);
                return EcommerceProfitShareService.ReturnOutcome.ALL_RETURNED;
            }
            if ("FAILED".equals(r)) {
                mapper.updateReturnState(no, PROCESSING, "FAILED", result.wxReturnNo(), "WECHAT_RETURN_FAILED");
                return EcommerceProfitShareService.ReturnOutcome.FAILED;
            }
            return EcommerceProfitShareService.ReturnOutcome.PENDING;
        } catch (RuntimeException uncertain) {
            log.warn("combine return uncertain no={} type={}", no, uncertain.getClass().getSimpleName());
            return EcommerceProfitShareService.ReturnOutcome.PENDING;
        }
    }

    // ---- 运营端：供应商应付

    public List<EcomSupplierPayable> payables(String state, int limit) {
        if (!switches.combinedEnabled()) return List.of();
        return mapper.selectPayablesForAdmin(state, clamp(limit));
    }

    /** 运营录入线下对公付款 / 灵活用工打款单号后标记已付；只能从 PENDING 推进一次。 */
    public boolean markPaid(long payableId, String reference, String operator) {
        if (!switches.combinedEnabled() || reference == null || reference.isBlank() || reference.length() > 128) return false;
        return mapper.markPayablePaid(payableId, reference.trim(), operator) == 1;
    }

    private static int clamp(int limit) { return Math.max(1, Math.min(limit, 200)); }
}
