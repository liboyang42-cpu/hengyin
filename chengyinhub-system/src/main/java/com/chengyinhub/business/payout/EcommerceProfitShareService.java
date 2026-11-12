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
            "PLATFORM_RECEIVER_MISSING", "NO_LEDGER_COMPONENTS", "LEDGER_CONSERVATION",
            ProfitSharePlanner.SUB_TO_SUB_UNSUPPORTED, "PERSONAL_BINDING_REQUIRED", "PERSONAL_BINDING_APPID_INVALID");

    private static final String PERSONAL_RETURN_UNSUPPORTED = "PERSONAL_PROFITSHARING_RETURN_UNSUPPORTED";

    public enum ReturnOutcome { NOT_APPLICABLE, ALL_RETURNED, PENDING, FAILED, SWITCH_OFF }

    private final EcomPayoutMapper mapper;
    private final EcommercePayGateway gateway;
    private final PayoutSwitches switches;
    private final TransactionOperations tx;
    /** l-payout3：合单 / 供应商应付表；未装配时转付行不落应付（合单开关关闭时也不会出现转付行）。 */
    private com.chengyinhub.business.mapper.EcomCombineMapper combineMapper;
    private EcomPayoutMapper personalReceiverMapper;
    private EcomPayoutMapper sessionPayoutMapper;

    @Autowired(required = false)
    public void setSessionPayoutMapper(EcomPayoutMapper mapper) {
        this.sessionPayoutMapper = mapper;
    }

    private com.chengyinhub.business.mapper.SettlementDetailMapper settlementDetailMapper;
    private com.chengyinhub.business.mapper.CmsActivityMapper activityMapper;

    @Autowired(required = false)
    public void setSessionWriteMappers(com.chengyinhub.business.mapper.SettlementDetailMapper details,
                                       com.chengyinhub.business.mapper.CmsActivityMapper activities) {
        this.settlementDetailMapper = details;
        this.activityMapper = activities;
    }

    @Autowired(required = false)
    public void setPersonalReceiverMapper(EcomPayoutMapper mapper) {
        this.personalReceiverMapper = mapper;
    }

    @Autowired(required = false)
    public void setCombineMapper(com.chengyinhub.business.mapper.EcomCombineMapper combineMapper) {
        this.combineMapper = combineMapper;
    }

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

    static String outReturnNo(long shareOrderId, String outRefundNo, ProfitSharePlanner.Receiver receiver) {
        return outReturnNo(shareOrderId, outRefundNo,
                "MERCHANT_ID".equals(receiver.type()) ? receiver.account() : receiver.identityKey());
    }

    private ProfitSharePlanner.Component component(EcomLedgerComponent c) {
        String openid = null;
        String appid = null;
        if (switches.personalClubEnabled() && c.getClubId() != null && c.getReceiverMemberId() != null
                && "MEMBER".equals(c.getReceiverKind()) && personalReceiverMapper != null) {
            var binding = personalReceiverMapper.selectByMemberAndAppid(c.getReceiverMemberId(), switches.spAppid());
            if (binding != null && "VERIFIED".equals(binding.getState())
                    && c.getReceiverMemberId().equals(binding.getMemberId())) {
                openid = binding.getOpenid();
                appid = binding.getAppid();
            }
        }
        return new ProfitSharePlanner.Component(c.getSettlementId(), c.getLineType(), c.getReceiverKind(),
                c.getReceiverMemberId(), c.getOriginalAmount(), c.getClubId(), c.getClubName(), openid, appid);
    }

    private void insertLine(long shareOrderId, ProfitSharePlanner.Line l) {
        EcomProfitShareLine line = new EcomProfitShareLine();
        line.setShareOrderId(shareOrderId);
        line.setSettlementId(l.settlementId());
        line.setLineType(l.lineType());
        line.setReceiverKind(l.kind().name());
        line.setReceiverMchid(l.receiverMchid());
        line.setReceiverType(l.receiverType());
        line.setReceiverAppid(l.receiverAppid());
        line.setReceiverMemberId(l.receiverMemberId());
        line.setClubId(l.clubId());
        line.setClubName(l.clubName());
        line.setAmountFen(l.amountFen());
        mapper.insertShareLine(line);
    }

    /** 为主题下以二级商户为卖家的已付单落分账计划。返回新建计划数。 */
    public int planTopic(long topicId) {
        if (!switches.profitSharingEnabled()) return 0;
        if (topicId < 0 && (!switches.personalClubEnabled() || sessionPayoutMapper == null)) return 0;
        int created = 0;
        for (EcomOrderSeller trade : paidTrades(topicId)) {
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
            for (EcomLedgerComponent c : ledgerComponents(topicId, trade.getRegistrationId())) {
                components.add(component(c));
            }
            Long sellerMember = mapper.selectSellerMemberId(trade.getSellerType(), trade.getSellerId());
            ProfitSharePlanner.Order order = new ProfitSharePlanner.Order(trade.getOutTradeNo(), trade.getTransactionId(),
                    trade.getSubMchid(), trade.getTotalFen(), sellerMember == null ? Set.of() : Set.of(sellerMember));
            ProfitSharePlanner.Plan plan = ProfitSharePlanner.plan(order, components, switches.platformReceiverMchid(),
                    switches.maxSharePercent(), this::memberSubMchid, switches.combinedEnabled(),
                    switches.personalClubEnabled(), switches.spAppid());
            Boolean inserted = tx.execute(status -> persist(topicId, trade, plan));
            if (Boolean.TRUE.equals(inserted)) created++;
        }
        return created;
    }

    /** Session ids and their settlement-detail ids occupy the negative namespace in shared payout tables. */
    public int planActivity(long activityId) {
        if (activityId <= 0 || !switches.personalClubEnabled() || sessionPayoutMapper == null) return 0;
        return planTopic(-activityId);
    }

    private List<EcomOrderSeller> paidTrades(long sourceId) {
        return sourceId < 0 ? sessionPayoutMapper.selectPaidTrades(-sourceId)
                : mapper.selectPaidSubMerchantTradesForTopic(sourceId);
    }

    private List<EcomLedgerComponent> ledgerComponents(long sourceId, Long registrationId) {
        return sourceId < 0 ? sessionPayoutMapper.selectComponents(-sourceId, registrationId)
                : mapper.selectLedgerComponents(sourceId, registrationId);
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
            insertLine(row.getId(), l);
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
            if (order.getTopicId() < 0 && sessionPayoutMapper == null) continue;
            if (!switches.personalClubEnabled() && hasPersonalReceiver(order.getId())) continue;
            if (!switches.combinedEnabled() && hasSubMerchantReceiver(order.getId())) {
                // f-money 待决定 2：本次改动前落下的计划可能含二级商户接收方。合单关时提交前失败关闭：整单转人工，不调微信。
                mapper.updateShareOrderState(order.getId(), PLANNED, MANUAL, null, ProfitSharePlanner.SUB_TO_SUB_UNSUPPORTED);
                continue;
            }
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
                            receivers(order.getId()), !hasPersonalReceiver(order.getId()));
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

    private boolean hasSubMerchantReceiver(long shareOrderId) {
        for (EcomProfitShareLine l : mapper.selectShareLines(shareOrderId))
            if (ProfitSharePlanner.ReceiverKind.SUB_MERCHANT.name().equals(l.getReceiverKind())) return true;
        return false;
    }

    private List<ProfitSharePlanner.Receiver> receivers(long shareOrderId) {
        Map<String, ProfitSharePlanner.Receiver> merged = new LinkedHashMap<>();
        for (EcomProfitShareLine l : mapper.selectShareLines(shareOrderId)) {
            if (ProfitSharePlanner.ReceiverKind.RETAINED.name().equals(l.getReceiverKind())) continue;
            ProfitSharePlanner.Receiver receiver = new ProfitSharePlanner.Receiver(l.getReceiverType(),
                    l.getReceiverMchid(), normalizedAppid(l.getReceiverAppid()), l.getAmountFen());
            merged.merge(receiver.identityKey(), receiver,
                    (a, b) -> new ProfitSharePlanner.Receiver(a.type(), a.account(), a.appid(), a.amountFen() + b.amountFen()));
        }
        return merged.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).toList();
    }

    private boolean hasPersonalReceiver(long shareOrderId) {
        return mapper.selectShareLines(shareOrderId).stream()
                .anyMatch(l -> "PERSONAL_OPENID".equals(l.getReceiverType()));
    }

    private static String normalizedAppid(String appid) { return appid == null ? "" : appid; }

    private boolean matchesReceivers(long shareOrderId, List<EcommercePayGateway.ReceiverResult> actual) {
        List<ProfitSharePlanner.Receiver> planned = receivers(shareOrderId);
        if (actual == null) return planned.isEmpty();
        if (actual.size() != planned.size()) return false;
        Map<String, Long> expected = new LinkedHashMap<>();
        for (ProfitSharePlanner.Receiver r : planned) expected.put(r.identityKey(), r.amountFen());
        for (EcommercePayGateway.ReceiverResult r : actual) {
            if (r == null) return false;
            String key = new ProfitSharePlanner.Receiver(r.receiverType(), r.account(), normalizedAppid(r.appid()), 0).identityKey();
            Long amount = expected.remove(key);
            if (amount == null || amount != r.amountFen()) return false;
        }
        return expected.isEmpty();
    }

    void applyResult(long shareOrderId, EcommercePayGateway.ShareResult result) {
        if (result == null || result.state() == null) return;
        String s = result.state();
        boolean anyFailed = result.receivers() != null && result.receivers().stream()
                .anyMatch(r -> r != null && ("CLOSED".equals(r.result()) || "FAILED".equals(r.result())));
        if (anyFailed || "CLOSED".equals(s)) {
            mapper.updateShareOrderState(shareOrderId, PROCESSING, MANUAL, result.wxOrderId(), "WECHAT_SHARE_CLOSED");
        } else if ("FINISHED".equals(s) || "SUCCESS".equals(s)) {
            if (!matchesReceivers(shareOrderId, result.receivers())) {
                mapper.updateShareOrderState(shareOrderId, PROCESSING, MANUAL, result.wxOrderId(), "WECHAT_SHARE_RECEIVERS_MISMATCH");
                return;
            }
            boolean allDone = result.receivers() == null || result.receivers().stream()
                    .allMatch(r -> "SUCCESS".equals(r.result()));
            if (!allDone) return;
            EcomProfitShareOrder order = mapper.selectShareOrderById(shareOrderId);
            if (order == null || !PROCESSING.equals(order.getState())) return;
            if (order.getTopicId() < 0 && sessionPayoutMapper == null)
                throw new IllegalStateException("SESSION_PAYOUT_MAPPER_REQUIRED");
            if (hasPersonalReceiver(shareOrderId)) {
                // Personal sharing cannot finish in the create request. Retry this distinct finish idempotently
                // after querying the original share until the remainder release is acknowledged.
                gateway.finishProfitSharing(order.getSubMchid(), order.getTransactionId(),
                        "CYF" + sha(order.getOutOrderNo()).substring(0, 29), "城瘾结算完结");
            }
            tx.execute(status -> {
                if (mapper.updateShareOrderState(shareOrderId, PROCESSING, SUCCESS, result.wxOrderId(), null) == 1) {
                    if (order.getTopicId() < 0) {
                        if (settlementDetailMapper == null || activityMapper == null) {
                            throw new IllegalStateException("SESSION_PAYOUT_WRITERS_MISSING");
                        }
                        settlementDetailMapper.markShareDetailsReceived(shareOrderId);
                        activityMapper.completeActivityIfReceived(shareOrderId);
                    }
                    recordRelayPayables(shareOrderId);
                }
                return null;
            });
        }
        // PROCESSING / PENDING：保持，等下一次 sync。
    }

    /**
     * l-payout3：分账成功后，分给平台转付的份额（SUPPLIER_RELAY）落供应商应付，由平台按供应商结算付给对方。
     * 唯一键保证重复调用只落一次；没有转付行或合单组件未装配时什么都不做。
     */
    private void recordRelayPayables(long shareOrderId) {
        if (combineMapper == null) return;
        EcomProfitShareOrder order = mapper.selectShareOrderById(shareOrderId);
        if (order == null) return;
        for (EcomProfitShareLine l : mapper.selectShareLines(shareOrderId)) {
            if (!ProfitSharePlanner.ReceiverKind.SUPPLIER_RELAY.name().equals(l.getReceiverKind())
                    || l.getReceiverMemberId() == null || l.getAmountFen() == null || l.getAmountFen() <= 0) continue;
            com.chengyinhub.business.domain.EcomSupplierPayable p = new com.chengyinhub.business.domain.EcomSupplierPayable();
            p.setSource("RELAY");
            p.setBusinessOutTradeNo(order.getOutTradeNo());
            p.setTopicId(order.getTopicId());
            p.setSettlementId(l.getSettlementId());
            p.setLineType(l.getLineType());
            p.setPayeeMemberId(l.getReceiverMemberId());
            p.setAmountFen(l.getAmountFen());
            try {
                combineMapper.insertPayable(p);
            } catch (DuplicateKeyException already) {
                // 已落过。
            }
        }
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
                if (order.getWxOrderId() != null || "WECHAT_SHARE_RECEIVERS_MISMATCH".equals(order.getManualReason())
                        || "WECHAT_SHARE_CLOSED".equals(order.getManualReason())) { pending = true; continue; }
                // l-payout2：从未提交微信的人工单发生退款后不能再按原账本重新计划。
                if (order.getWxOrderId() == null && !REFUNDED_BEFORE_SHARE.equals(order.getManualReason()))
                    mapper.updateShareOrderState(order.getId(), MANUAL, MANUAL, null, REFUNDED_BEFORE_SHARE);
                continue;
            }
            if (PROCESSING.equals(order.getState())) { pending = true; continue; }
            Map<String, ProfitSharePlanner.Receiver> identities = new LinkedHashMap<>();
            Map<String, long[]> shared = new LinkedHashMap<>();
            for (ProfitSharePlanner.Receiver receiver : receivers(order.getId())) {
                identities.put(receiver.identityKey(), receiver);
                shared.put(receiver.identityKey(), new long[] {receiver.amountFen(), 0});
            }
            List<EcomProfitShareReturn> existing = mapper.selectReturnsByShareOrder(order.getId());
            for (EcomProfitShareReturn r : existing) {
                if (outRefundNo.equals(r.getOutRefundNo()) || "FAILED".equals(r.getState())) continue;
                long[] v = shared.get(returnReceiver(r).identityKey());
                if (v != null) v[1] += r.getAmountFen();
            }
            Map<String, Long> due = ProfitSharePlanner.returnsForRefund(shared, order.getTotalFen(), refundFen, refundedBeforeFen);
            for (Map.Entry<String, Long> e : due.entrySet()) {
                ReturnOutcome one = returnOne(order, outRefundNo, identities.get(e.getKey()), e.getValue());
                if (one == ReturnOutcome.PENDING) pending = true;
                if (one == ReturnOutcome.FAILED) failed = true;
            }
        }
        if (failed) return ReturnOutcome.FAILED;
        return pending ? ReturnOutcome.PENDING : ReturnOutcome.ALL_RETURNED;
    }

    private ReturnOutcome returnOne(EcomProfitShareOrder order, String outRefundNo, ProfitSharePlanner.Receiver receiver, long amountFen) {
        String no = outReturnNo(order.getId(), outRefundNo, receiver);
        EcomProfitShareReturn row = mapper.selectReturn(no);
        if (row == null) {
            row = new EcomProfitShareReturn();
            row.setOutReturnNo(no);
            row.setShareOrderId(order.getId());
            row.setOutRefundNo(outRefundNo);
            row.setReturnMchid(receiver.account());
            row.setReturnType(receiver.type());
            row.setReturnAppid(normalizedAppid(receiver.appid()));
            row.setAmountFen(amountFen);
            try {
                mapper.insertReturn(row);
            } catch (DuplicateKeyException concurrent) {
                row = mapper.selectReturn(no);
            }
            row = mapper.selectReturn(no);
        }
        if (row.getAmountFen() != amountFen) return ReturnOutcome.FAILED; // 同号不同额：账本被改过，转人工
        if (!returnReceiver(row).identityKey().equals(receiver.identityKey())) return ReturnOutcome.FAILED;
        if ("SUCCESS".equals(row.getState())) return ReturnOutcome.ALL_RETURNED;
        if ("FAILED".equals(row.getState())) return ReturnOutcome.FAILED;
        if ("PERSONAL_OPENID".equals(row.getReturnType())) {
            mapper.updateReturnState(row.getId(), PROCESSING, "FAILED", null, PERSONAL_RETURN_UNSUPPORTED);
            return ReturnOutcome.FAILED;
        }
        try {
            EcommercePayGateway.ReturnResult result = gateway.createReturn(order.getSubMchid(), order.getOutOrderNo(), no,
                    returnReceiver(row), row.getAmountFen(), "城瘾退款回退分账");
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

    private static ProfitSharePlanner.Receiver returnReceiver(EcomProfitShareReturn row) {
        return new ProfitSharePlanner.Receiver(row.getReturnType(), row.getReturnMchid(),
                normalizedAppid(row.getReturnAppid()), row.getAmountFen());
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
        if (switches.personalClubEnabled() && sessionPayoutMapper != null) {
            for (Long activityId : sessionPayoutMapper.selectMissingActivityIds(clamp(limit))) {
                try {
                    created += planActivity(activityId);
                } catch (RuntimeException e) {
                    log.warn("ecom session share plan missing failed activityId={} type={}", activityId, e.getClass().getSimpleName());
                }
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
            if ("PERSONAL_OPENID".equals(r.getReturnType())) {
                n += mapper.updateReturnState(r.getId(), PROCESSING, "FAILED", null, PERSONAL_RETURN_UNSUPPORTED);
                continue;
            }
            try {
                EcommercePayGateway.ReturnResult result = gateway.queryReturn(order.getSubMchid(), order.getOutOrderNo(),
                        r.getOutReturnNo(), returnReceiver(r));
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
        if ("PERSONAL_OPENID".equals(r.getReturnType())) {
            mapper.updateReturnState(r.getId(), "FAILED", "FAILED", null, PERSONAL_RETURN_UNSUPPORTED);
            return ReturnOutcome.FAILED;
        }
        if (mapper.updateReturnState(r.getId(), "FAILED", PROCESSING, null, null) != 1) return ReturnOutcome.PENDING;
        return returnOne(order, r.getOutRefundNo(), returnReceiver(r), r.getAmountFen());
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
        if (!switches.personalClubEnabled() && (order.getManualReason().startsWith("PERSONAL_BINDING_")
                || hasPersonalReceiver(order.getId()))) return "SWITCH_OFF";
        if (order.getTopicId() < 0 && (!switches.personalClubEnabled() || sessionPayoutMapper == null)) return "SWITCH_OFF";
        if (!mapper.selectRefundsByTrade(order.getOutTradeNo()).isEmpty()) return "REFUNDED";
        EcomOrderSeller trade = null;
        for (EcomOrderSeller t : paidTrades(order.getTopicId())) {
            if (t.getOutTradeNo().equals(order.getOutTradeNo())) { trade = t; break; }
        }
        if (trade == null || !order.getTransactionId().equals(trade.getTransactionId())) return "TRADE_NOT_FOUND";
        List<ProfitSharePlanner.Component> components = new ArrayList<>();
        for (EcomLedgerComponent c : ledgerComponents(order.getTopicId(), trade.getRegistrationId())) {
            components.add(component(c));
        }
        Long sellerMember = mapper.selectSellerMemberId(trade.getSellerType(), trade.getSellerId());
        ProfitSharePlanner.Order o = new ProfitSharePlanner.Order(trade.getOutTradeNo(), trade.getTransactionId(),
                trade.getSubMchid(), trade.getTotalFen(), sellerMember == null ? Set.of() : Set.of(sellerMember));
        ProfitSharePlanner.Plan plan = ProfitSharePlanner.plan(o, components, switches.platformReceiverMchid(),
                switches.maxSharePercent(), this::memberSubMchid, switches.combinedEnabled(),
                switches.personalClubEnabled(), switches.spAppid());
        String result = tx.execute(status -> {
            boolean manual = "MANUAL".equals(plan.state());
            String hash = plan.planHash() == null ? sha("MANUAL|" + plan.manualReason() + "|" + order.getOutTradeNo()) : plan.planHash();
            if (mapper.replanShareOrder(order.getId(), manual ? MANUAL : PLANNED, plan.sharedFen(), hash, plan.manualReason()) != 1)
                return "CONCURRENT";
            mapper.deleteShareLines(order.getId());
            for (ProfitSharePlanner.Line l : plan.lines()) {
                insertLine(order.getId(), l);
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
