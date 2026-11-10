package com.chengyinhub.business.payout;

import com.chengyinhub.business.domain.EcomLedgerComponent;
import com.chengyinhub.business.domain.EcomOrderSeller;
import com.chengyinhub.business.domain.EcomProfitShareLine;
import com.chengyinhub.business.domain.EcomProfitShareOrder;
import com.chengyinhub.business.domain.EcomProfitShareReturn;
import com.chengyinhub.business.mapper.EcomPayoutMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 电商收付通分账执行。份额只读内部账本，本服务只把账本已经确定的份额搬到微信：
 * <ol>
 *   <li>{@link #planTopic}：主题结算冻结后，为每笔以二级商户为卖家的已付报名单落一条分账计划（幂等：每单每主题一条）。</li>
 *   <li>{@link #submitPlanned}：先在库里 PLANNED→PROCESSING 抢占，再调微信（事务外），按 out_order_no 幂等。</li>
 *   <li>{@link #syncProcessing}：查询微信结果推进状态。</li>
 *   <li>{@link #returnForRefund}：退款前按比例回退已分出的资金，全部回退成功才允许退款。</li>
 * </ol>
 * 开关 {@code payout.ecommerce.profit-sharing-enabled} 关闭时所有入口直接返回，不读不写。
 */
@Service
public class EcommerceProfitShareService {
    private static final Logger log = LoggerFactory.getLogger(EcommerceProfitShareService.class);

    public static final String PLANNED = "PLANNED";
    public static final String PROCESSING = "PROCESSING";
    public static final String SUCCESS = "SUCCESS";
    public static final String MANUAL = "MANUAL";
    static final String REFUNDED_BEFORE_SHARE = "REFUNDED_BEFORE_SHARE";
    /** 只有这些「计划阶段」转人工的原因可以在补齐条件后重新计划；微信侧已关闭 / 已退款的单不能。 */
    static final Set<String> REPLANNABLE = Set.of("RECEIVER_NOT_ONBOARDED", "SHARE_RATIO_EXCEEDED",
            "PLATFORM_RECEIVER_MISSING", "NO_LEDGER_COMPONENTS", "LEDGER_CONSERVATION");

    public enum ReturnOutcome { NOT_APPLICABLE, ALL_RETURNED, PENDING, FAILED, SWITCH_OFF }

    private final EcomPayoutMapper mapper;
    private final EcommercePayGateway gateway;
    private final PayoutSwitches switches;
    private final TransactionOperations tx;

    @Autowired
    public EcommerceProfitShareService(EcomPayoutMapper mapper, EcommercePayGateway gateway, PayoutSwitches switches,
                                       PlatformTransactionManager transactionManager) {
        this(mapper, gateway, switches, new TransactionTemplate(transactionManager));
    }

    public EcommerceProfitShareService(EcomPayoutMapper mapper, EcommercePayGateway gateway, PayoutSwitches switches,
                                TransactionOperations tx) {
        this.mapper = mapper;
        this.gateway = gateway;
        this.switches = switches;
        this.tx = tx;
    }

    static String outOrderNo(String outTradeNo, long topicId) {
        return "CYPS" + sha(outTradeNo + "|" + topicId).substring(0, 28);
    }

    static String outReturnNo(long shareOrderId, String outRefundNo, String mchid) {
        return "CYPR" + sha(shareOrderId + "|" + outRefundNo + "|" + mchid).substring(0, 28);
    }

    /** 为主题下以二级商户为卖家的已付单落分账计划。返回新建计划数。 */
    public int planTopic(long topicId) {
        if (!switches.profitSharingEnabled()) return 0;
        int created = 0;
        for (EcomOrderSeller trade : mapper.selectPaidSubMerchantTradesForTopic(topicId)) {
            if (mapper.selectShareOrder(trade.getOutTradeNo(), topicId) != null) continue;
            if (!mapper.selectRefundsByTrade(trade.getOutTradeNo()).isEmpty()) {
                // l-payout2：结算前已发生退款：微信侧冻结资金已不等于账本份额之和，不能按原份额分账，转人工。
                ProfitSharePlanner.Plan refunded = new ProfitSharePlanner.Plan("MANUAL", REFUNDED_BEFORE_SHARE,
                        List.of(), List.of(), 0, null);
                Boolean inserted = tx.execute(status -> persist(topicId, trade, refunded));
                if (Boolean.TRUE.equals(inserted)) created++;
                continue;
            }
            List<ProfitSharePlanner.Component> components = new ArrayList<>();
            for (EcomLedgerComponent c : mapper.selectLedgerComponents(topicId, trade.getRegistrationId())) {
                components.add(new ProfitSharePlanner.Component(c.getSettlementId(), c.getLineType(), c.getReceiverKind(),
                        c.getReceiverMemberId(), c.getOriginalAmount()));
            }
            Long sellerMember = mapper.selectSellerMemberId(trade.getSellerType(), trade.getSellerId());
            ProfitSharePlanner.Order order = new ProfitSharePlanner.Order(trade.getOutTradeNo(), trade.getTransactionId(),
                    trade.getSubMchid(), trade.getTotalFen(), sellerMember == null ? Set.of() : Set.of(sellerMember));
            ProfitSharePlanner.Plan plan = ProfitSharePlanner.plan(order, components, switches.platformReceiverMchid(),
                    switches.maxSharePercent(), this::memberSubMchid);
            Boolean inserted = tx.execute(status -> persist(topicId, trade, plan));
            if (Boolean.TRUE.equals(inserted)) created++;
        }
        return created;
    }

    private Optional<String> memberSubMchid(Long memberId) {
        List<String> found = mapper.selectFinishedSubMchidsForMember(memberId);
        // 同一会员既是商家又是俱乐部主理人且两边都进件时，账本行无法判断该打给哪个主体：歧义转人工。
        return found != null && found.size() == 1 ? Optional.of(found.get(0)) : Optional.empty();
    }

    private Boolean persist(long topicId, EcomOrderSeller trade, ProfitSharePlanner.Plan plan) {
        EcomProfitShareOrder row = new EcomProfitShareOrder();
        row.setOutOrderNo(outOrderNo(trade.getOutTradeNo(), topicId));
        row.setOutTradeNo(trade.getOutTradeNo());
        row.setTopicId(topicId);
        row.setSubMchid(trade.getSubMchid());
        row.setTransactionId(trade.getTransactionId());
        row.setTotalFen(trade.getTotalFen());
        row.setSharedFen(plan.sharedFen());
        row.setPlanHash(plan.planHash() == null ? sha("MANUAL|" + plan.manualReason() + "|" + trade.getOutTradeNo()) : plan.planHash());
        row.setState("MANUAL".equals(plan.state()) ? MANUAL : PLANNED);
        row.setManualReason(plan.manualReason());
        try {
            mapper.insertShareOrder(row);
        } catch (DuplicateKeyException concurrent) {
            return false;
        }
        for (ProfitSharePlanner.Line l : plan.lines()) {
            EcomProfitShareLine line = new EcomProfitShareLine();
            line.setShareOrderId(row.getId());
            line.setSettlementId(l.settlementId());
            line.setLineType(l.lineType());
            line.setReceiverKind(l.kind().name());
            line.setReceiverMchid(l.receiverMchid());
            line.setReceiverMemberId(l.receiverMemberId());
            line.setAmountFen(l.amountFen());
            mapper.insertShareLine(line);
        }
        return true;
    }

    /**
     * 提交已到期的分账计划。到期口径与 T+14 入余额相同：分账单涉及的每张结算头都已到 payable_time、主题无未结客诉。
     * 每单先抢占再调微信；调用异常保持 PROCESSING，由 sync 按单号查证。
     */
    public int submitPlanned(int limit) {
        if (!switches.profitSharingEnabled()) return 0;
        int submitted = 0;
        for (EcomProfitShareOrder order : mapper.selectDuePlannedShareOrders(new java.util.Date(), clamp(limit))) {
            if (mapper.updateShareOrderState(order.getId(), PLANNED, PROCESSING, null, null) != 1) continue;
            submitted++;
            try {
                EcommercePayGateway.ShareResult result;
                if (order.getSharedFen() == 0) {
                    // 没有需要分出的份额：完结分账，把冻结资金解冻给卖家。
                    gateway.finishProfitSharing(order.getSubMchid(), order.getTransactionId(), order.getOutOrderNo(), "城瘾结算完结");
                    result = new EcommercePayGateway.ShareResult("FINISHED", null, List.of());
                } else {
                    result = gateway.createProfitSharing(order.getSubMchid(), order.getTransactionId(), order.getOutOrderNo(),
                            receivers(order.getId()), true);
                }
                applyResult(order.getId(), result);
            } catch (RuntimeException uncertain) {
                log.warn("ecom profit sharing submit uncertain id={} type={}", order.getId(), uncertain.getClass().getSimpleName());
            }
        }
        return submitted;
    }

    /** 查询 PROCESSING 的分账单并推进状态。 */
    public int syncProcessing(int limit) {
        if (!switches.profitSharingEnabled()) return 0;
        int n = 0;
        for (EcomProfitShareOrder order : mapper.selectShareOrdersByState(PROCESSING, clamp(limit))) {
            try {
                applyResult(order.getId(), gateway.queryProfitSharing(order.getSubMchid(), order.getTransactionId(), order.getOutOrderNo()));
                n++;
            } catch (RuntimeException e) {
                log.warn("ecom profit sharing query failed id={} type={}", order.getId(), e.getClass().getSimpleName());
            }
        }
        return n;
    }

    private List<ProfitSharePlanner.Receiver> receivers(long shareOrderId) {
        Map<String, Long> merged = new LinkedHashMap<>();
        for (EcomProfitShareLine l : mapper.selectShareLines(shareOrderId)) {
            if (ProfitSharePlanner.ReceiverKind.RETAINED.name().equals(l.getReceiverKind())) continue;
            merged.merge(l.getReceiverMchid(), l.getAmountFen(), Long::sum);
        }
        List<ProfitSharePlanner.Receiver> out = new ArrayList<>();
        merged.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> out.add(new ProfitSharePlanner.Receiver(e.getKey(), e.getValue())));
        return out;
    }

    void applyResult(long shareOrderId, EcommercePayGateway.ShareResult result) {
        if (result == null || result.state() == null) return;
        String s = result.state();
        boolean anyFailed = result.receivers() != null && result.receivers().stream()
                .anyMatch(r -> "CLOSED".equals(r.result()) || "FAILED".equals(r.result()));
        if (anyFailed || "CLOSED".equals(s)) {
            mapper.updateShareOrderState(shareOrderId, PROCESSING, MANUAL, result.wxOrderId(), "WECHAT_SHARE_CLOSED");
        } else if ("FINISHED".equals(s) || "SUCCESS".equals(s)) {
            boolean allDone = result.receivers() == null || result.receivers().stream()
                    .allMatch(r -> r.result() == null || "SUCCESS".equals(r.result()));
            if (allDone) mapper.updateShareOrderState(shareOrderId, PROCESSING, SUCCESS, result.wxOrderId(), null);
        }
        // PROCESSING / PENDING：保持，等下一次 sync。
    }

    /**
     * 退款前的分账回退（幂等：同一退款单号对同一接收方只建一笔回退）。
     * 只有返回 {@link ReturnOutcome#NOT_APPLICABLE} 或 {@link ReturnOutcome#ALL_RETURNED} 时调用方才能向微信申请退款。
     */
    public ReturnOutcome returnForRefund(String outTradeNo, String outRefundNo, long refundFen, long refundedBeforeFen) {
        if (!switches.profitSharingEnabled()) {
            return mapper.selectShareOrdersByTrade(outTradeNo).isEmpty() ? ReturnOutcome.SWITCH_OFF : ReturnOutcome.PENDING;
        }
        List<EcomProfitShareOrder> orders = mapper.selectShareOrdersByTrade(outTradeNo);
        if (orders.isEmpty()) return ReturnOutcome.NOT_APPLICABLE;
        boolean pending = false;
        boolean failed = false;
        for (EcomProfitShareOrder order : orders) {
            if (PLANNED.equals(order.getState())) {
                // 尚未分出：先冻住计划，避免把正在退的钱分出去；后续由人工按退款后的账本重新计划。
                mapper.updateShareOrderState(order.getId(), PLANNED, MANUAL, null, "REFUNDED_BEFORE_SHARE");
                continue;
            }
            if (MANUAL.equals(order.getState())) {
                // l-payout2：从未提交微信的人工单发生退款后不能再按原账本重新计划。
                if (order.getWxOrderId() == null && !REFUNDED_BEFORE_SHARE.equals(order.getManualReason()))
                    mapper.updateShareOrderState(order.getId(), MANUAL, MANUAL, null, REFUNDED_BEFORE_SHARE);
                continue;
            }
            if (PROCESSING.equals(order.getState())) { pending = true; continue; }
            Map<String, long[]> shared = new LinkedHashMap<>();
            for (EcomProfitShareLine l : mapper.selectShareLines(order.getId())) {
                if (ProfitSharePlanner.ReceiverKind.RETAINED.name().equals(l.getReceiverKind())) continue;
                shared.computeIfAbsent(l.getReceiverMchid(), k -> new long[2])[0] += l.getAmountFen();
            }
            List<EcomProfitShareReturn> existing = mapper.selectReturnsByShareOrder(order.getId());
            for (EcomProfitShareReturn r : existing) {
                if (outRefundNo.equals(r.getOutRefundNo()) || "FAILED".equals(r.getState())) continue;
                long[] v = shared.get(r.getReturnMchid());
                if (v != null) v[1] += r.getAmountFen();
            }
            Map<String, Long> due = ProfitSharePlanner.returnsForRefund(shared, order.getTotalFen(), refundFen, refundedBeforeFen);
            for (Map.Entry<String, Long> e : due.entrySet()) {
                ReturnOutcome one = returnOne(order, outRefundNo, e.getKey(), e.getValue());
                if (one == ReturnOutcome.PENDING) pending = true;
                if (one == ReturnOutcome.FAILED) failed = true;
            }
        }
        if (failed) return ReturnOutcome.FAILED;
        return pending ? ReturnOutcome.PENDING : ReturnOutcome.ALL_RETURNED;
    }

    private ReturnOutcome returnOne(EcomProfitShareOrder order, String outRefundNo, String mchid, long amountFen) {
        String no = outReturnNo(order.getId(), outRefundNo, mchid);
        EcomProfitShareReturn row = mapper.selectReturn(no);
        if (row == null) {
            row = new EcomProfitShareReturn();
            row.setOutReturnNo(no);
            row.setShareOrderId(order.getId());
            row.setOutRefundNo(outRefundNo);
            row.setReturnMchid(mchid);
            row.setAmountFen(amountFen);
            try {
                mapper.insertReturn(row);
            } catch (DuplicateKeyException concurrent) {
                row = mapper.selectReturn(no);
            }
            row = mapper.selectReturn(no);
        }
        if (row.getAmountFen() != amountFen) return ReturnOutcome.FAILED; // 同号不同额：账本被改过，转人工
        if ("SUCCESS".equals(row.getState())) return ReturnOutcome.ALL_RETURNED;
        if ("FAILED".equals(row.getState())) return ReturnOutcome.FAILED;
        try {
            EcommercePayGateway.ReturnResult result = gateway.createReturn(order.getSubMchid(), order.getOutOrderNo(), no,
                    mchid, amountFen, "城瘾退款回退分账");
            String r = result == null ? null : result.result();
            if ("SUCCESS".equals(r)) {
                mapper.updateReturnState(row.getId(), "PROCESSING", "SUCCESS", result.wxReturnNo(), null);
                return ReturnOutcome.ALL_RETURNED;
            }
            if ("FAILED".equals(r)) {
                mapper.updateReturnState(row.getId(), "PROCESSING", "FAILED", result.wxReturnNo(), safeReason(result.failReason()));
                return ReturnOutcome.FAILED;
            }
            return ReturnOutcome.PENDING;
        } catch (RuntimeException uncertain) {
            log.warn("ecom profit sharing return uncertain id={} type={}", row.getId(), uncertain.getClass().getSimpleName());
            return ReturnOutcome.PENDING;
        }
    }

    // ---- l-payout2：定时任务与运营端 ----

    /** 补齐：已结算主题里有二级商户已付单却还没有分账计划的（结算后落计划失败时由此兜底）。 */
    public int planMissing(int limit) {
        if (!switches.profitSharingEnabled()) return 0;
        int created = 0;
        for (Long topicId : mapper.selectSettledTopicsMissingSharePlans(clamp(limit))) {
            try {
                created += planTopic(topicId);
            } catch (RuntimeException e) {
                log.warn("ecom profit share plan missing failed topicId={} type={}", topicId, e.getClass().getSimpleName());
            }
        }
        return created;
    }

    /** 查询处理中的分账回退并推进状态（只向前推进）。 */
    public int syncReturns(int limit) {
        if (!switches.profitSharingEnabled()) return 0;
        int n = 0;
        for (EcomProfitShareReturn r : mapper.selectReturnsByState(PROCESSING, clamp(limit))) {
            EcomProfitShareOrder order = mapper.selectShareOrderById(r.getShareOrderId());
            if (order == null) continue;
            try {
                EcommercePayGateway.ReturnResult result = gateway.queryReturn(order.getSubMchid(), order.getOutOrderNo(), r.getOutReturnNo());
                String state = result == null ? null : result.result();
                if ("SUCCESS".equals(state)) {
                    n += mapper.updateReturnState(r.getId(), PROCESSING, SUCCESS, result.wxReturnNo(), null);
                } else if ("FAILED".equals(state)) {
                    n += mapper.updateReturnState(r.getId(), PROCESSING, "FAILED", result.wxReturnNo(), safeReason(result.failReason()));
                }
            } catch (RuntimeException e) {
                log.warn("ecom profit sharing return query failed id={} type={}", r.getId(), e.getClass().getSimpleName());
            }
        }
        return n;
    }

    /** 运营端重试失败的分账回退：用原回退单号重新发起（微信按单号幂等），不换号、不改金额。 */
    public ReturnOutcome retryReturn(long returnId) {
        if (!switches.profitSharingEnabled()) return ReturnOutcome.SWITCH_OFF;
        EcomProfitShareReturn r = mapper.selectReturnById(returnId);
        if (r == null || !"FAILED".equals(r.getState())) return ReturnOutcome.NOT_APPLICABLE;
        EcomProfitShareOrder order = mapper.selectShareOrderById(r.getShareOrderId());
        if (order == null) return ReturnOutcome.NOT_APPLICABLE;
        if (mapper.updateReturnState(r.getId(), "FAILED", PROCESSING, null, null) != 1) return ReturnOutcome.PENDING;
        return returnOne(order, r.getOutRefundNo(), r.getReturnMchid(), r.getAmountFen());
    }

    /**
     * 运营端重新计划一张转人工的分账单：仅限从未提交微信、原因属于计划阶段、且该支付单没有任何退款。
     * 份额仍只读账本；重新计划后仍转人工则保持人工并更新原因。
     */
    public String replan(long shareOrderId) {
        if (!switches.profitSharingEnabled()) return "SWITCH_OFF";
        EcomProfitShareOrder order = mapper.selectShareOrderById(shareOrderId);
        if (order == null || !MANUAL.equals(order.getState()) || order.getWxOrderId() != null
                || !REPLANNABLE.contains(order.getManualReason())) return "NOT_REPLANNABLE";
        if (!mapper.selectRefundsByTrade(order.getOutTradeNo()).isEmpty()) return "REFUNDED";
        EcomOrderSeller trade = null;
        for (EcomOrderSeller t : mapper.selectPaidSubMerchantTradesForTopic(order.getTopicId())) {
            if (t.getOutTradeNo().equals(order.getOutTradeNo())) { trade = t; break; }
        }
        if (trade == null || !order.getTransactionId().equals(trade.getTransactionId())) return "TRADE_NOT_FOUND";
        List<ProfitSharePlanner.Component> components = new ArrayList<>();
        for (EcomLedgerComponent c : mapper.selectLedgerComponents(order.getTopicId(), trade.getRegistrationId())) {
            components.add(new ProfitSharePlanner.Component(c.getSettlementId(), c.getLineType(), c.getReceiverKind(),
                    c.getReceiverMemberId(), c.getOriginalAmount()));
        }
        Long sellerMember = mapper.selectSellerMemberId(trade.getSellerType(), trade.getSellerId());
        ProfitSharePlanner.Order o = new ProfitSharePlanner.Order(trade.getOutTradeNo(), trade.getTransactionId(),
                trade.getSubMchid(), trade.getTotalFen(), sellerMember == null ? Set.of() : Set.of(sellerMember));
        ProfitSharePlanner.Plan plan = ProfitSharePlanner.plan(o, components, switches.platformReceiverMchid(),
                switches.maxSharePercent(), this::memberSubMchid);
        String result = tx.execute(status -> {
            boolean manual = "MANUAL".equals(plan.state());
            String hash = plan.planHash() == null ? sha("MANUAL|" + plan.manualReason() + "|" + order.getOutTradeNo()) : plan.planHash();
            if (mapper.replanShareOrder(order.getId(), manual ? MANUAL : PLANNED, plan.sharedFen(), hash, plan.manualReason()) != 1)
                return "CONCURRENT";
            mapper.deleteShareLines(order.getId());
            for (ProfitSharePlanner.Line l : plan.lines()) {
                EcomProfitShareLine line = new EcomProfitShareLine();
                line.setShareOrderId(order.getId());
                line.setSettlementId(l.settlementId());
                line.setLineType(l.lineType());
                line.setReceiverKind(l.kind().name());
                line.setReceiverMchid(l.receiverMchid());
                line.setReceiverMemberId(l.receiverMemberId());
                line.setAmountFen(l.amountFen());
                mapper.insertShareLine(line);
            }
            return manual ? "MANUAL:" + plan.manualReason() : PLANNED;
        });
        return result == null ? "CONCURRENT" : result;
    }

    private static String safeReason(String reason) {
        if (reason == null) return null;
        String r = reason.replaceAll("[^A-Za-z0-9_]", "");
        return r.length() > 64 ? r.substring(0, 64) : r;
    }

    private static int clamp(int limit) { return Math.max(1, Math.min(limit, 200)); }

    private static String sha(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
