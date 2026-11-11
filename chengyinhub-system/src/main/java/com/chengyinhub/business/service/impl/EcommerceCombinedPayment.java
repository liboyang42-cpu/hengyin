package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.EcomCombineOrder;
import com.chengyinhub.business.domain.EcomCombineRefund;
import com.chengyinhub.business.domain.EcomCombineSubOrder;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.mapper.EcomCombineMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.payout.CombinedCheckoutPolicy;
import com.chengyinhub.business.payout.EcommerceCombinedSettlementService;
import com.chengyinhub.business.payout.EcommercePayGateway;
import com.chengyinhub.business.payout.EcommerceProfitShareService;
import com.chengyinhub.business.payout.EcommerceSellerResolver;
import com.chengyinhub.business.payout.PayoutSwitches;
import com.chengyinhub.business.payout.SellerOfRecordPolicy;
import com.chengyinhub.business.service.IPricingService;
import com.chengyinhub.business.service.PaymentPreorderException;
import com.chengyinhub.business.service.RefundRequestResult;
import com.chengyinhub.business.service.WechatOrderNotExistException;
import com.chengyinhub.common.config.RestoreDispatchGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * l-payout3：多方收款主题门票的「合单支付」接入现有支付事实链路。
 *
 * <p>只由 {@link EcommercePartnerPaymentAdapter} 在 {@code payout.ecommerce.combined-enabled} 打开时调用；
 * 开关关闭时本类不读任何表、不调微信。与 l-payout2 共用同一套不可变证据：
 * <ul>
 *   <li>支付意图仍由 {@link WechatPaymentIntentStore} 冻结：商户号列记电商平台商户号（合单商户号 combine_mchid），
 *       微信单号即合单号 combine_out_trade_no，金额 = 各子单之和；</li>
 *   <li>支付成功写一条 PAYMENT 事实（mchid = combine_mchid，wx_out_trade_no = 合单号，交易号 = 卖家子单交易号），
 *       领域回调、结算、退款照旧只认事实引用；</li>
 *   <li>每笔子单的交易号与金额另存 ecom_combine_sub_order，退款按子单分别发起（微信不支持按合单号退款）。</li>
 * </ul>
 */
@Component
public class EcommerceCombinedPayment {
    private static final Logger log = LoggerFactory.getLogger(EcommerceCombinedPayment.class);

    public enum Route { COMBINED, PLATFORM, SINGLE }

    /** createOrder 的结果：COMBINED 时带调起参数；PLATFORM = 整单走平台商户号；SINGLE = 交给 l-payout2 单商户路径。 */
    public record Placed(Route route, Map<String, String> params) { }

    static final String RETURNING = "RETURNING";
    static final String RETURN_FAILED = "RETURN_FAILED";
    static final String SUBMITTING = "SUBMITTING";
    static final String SUBMITTED = "SUBMITTED";
    private static final Set<String> PROVIDER_FAILED = Set.of("CLOSED", "ABNORMAL");
    private static final String ID = "[A-Za-z0-9_-]{1,64}";

    private final PayoutSwitches switches;
    private final EcomCombineMapper mapper;
    private final EcommercePayGateway gateway;
    private final EcommerceSellerResolver sellers;
    private final EcommerceCombinedSettlementService settlement;
    private final WechatPaymentIntentStore intents;
    private final WechatVerifiedPaymentFactService verifiedFacts;
    private final UmsMemberMapper members;
    private final CmsTopicMapper topics;
    private final CoopOrderMapper coopOrders;
    private final IPricingService pricing;

    public EcommerceCombinedPayment(PayoutSwitches switches, EcomCombineMapper mapper, EcommercePayGateway gateway,
                                    EcommerceSellerResolver sellers, EcommerceCombinedSettlementService settlement,
                                    WechatPaymentIntentStore intents, WechatVerifiedPaymentFactService verifiedFacts,
                                    UmsMemberMapper members, CmsTopicMapper topics, CoopOrderMapper coopOrders,
                                    IPricingService pricing) {
        this.switches = switches;
        this.mapper = mapper;
        this.gateway = gateway;
        this.sellers = sellers;
        this.settlement = settlement;
        this.intents = intents;
        this.verifiedFacts = verifiedFacts;
        this.members = members;
        this.topics = topics;
        this.coopOrders = coopOrders;
        this.pricing = pricing;
    }

    public boolean enabled() { return switches.combinedEnabled(); }

    /** 业务单的合单记录；开关关闭时不读库。 */
    EcomCombineOrder find(String businessNo) {
        if (!enabled() || businessNo == null || businessNo.isBlank()) return null;
        return mapper.selectCombineOrder(businessNo);
    }

    // ------------------------------------------------------------------ 下单

    /**
     * 只在没有 l-payout2 卖家记录时由适配器调用。已有合单记录就继续合单（已开始的支付不换收款主体）；
     * 平台商户号下已冻结过意图的单继续走平台。
     */
    Placed createOrder(CmsRegistration registration, String platformMchid) {
        if (!enabled() || registration == null || registration.getRegistrationNo() == null) return new Placed(Route.SINGLE, null);
        String businessNo = registration.getRegistrationNo();
        EcomCombineOrder row = mapper.selectCombineOrder(businessNo);
        if (row == null) {
            if (!Long.valueOf(1L).equals(registration.getOwnerType())) return new Placed(Route.SINGLE, null);
            if (platformMchid != null && !platformMchid.isBlank() && intents.hasAnyAttempt(platformMchid, businessNo))
                return new Placed(Route.SINGLE, null);
            SellerOfRecordPolicy.Decision seller = sellers.decide(registration);
            if (seller == null || !seller.subMerchant()) return new Placed(Route.SINGLE, null);
            long totalFen;
            CombinedCheckoutPolicy.Decision decision;
            try {
                totalFen = EcommercePartnerPaymentAdapter.fen(registration.getWechatPaymentAmount());
                decision = decide(registration, seller, totalFen);
            } catch (RuntimeException invalid) {
                throw PaymentPreorderException.definiteFailure("创建微信支付订单参数异常：" + invalid.getMessage(), invalid);
            }
            if (decision.path() == CombinedCheckoutPolicy.Path.SINGLE) return new Placed(Route.SINGLE, null);
            if (decision.path() == CombinedCheckoutPolicy.Path.PLATFORM) {
                log.info("combine checkout falls back to platform reason={}", decision.reason());
                return new Placed(Route.PLATFORM, null);
            }
            CombinedCheckoutPolicy.SubOrder sellerSub = decision.subOrders().get(0);
            CombinedCheckoutPolicy.SubOrder heldSub = decision.subOrders().get(1);
            EcomCombineOrder created = new EcomCombineOrder();
            created.setOutTradeNo(businessNo);
            created.setSellerSubMchid(sellerSub.subMchid());
            created.setSellerType(seller.sellerType().name());
            created.setSellerId(seller.sellerId());
            created.setPlatformSubMchid(heldSub.subMchid());
            created.setTotalFen(totalFen);
            created.setSellerFen(sellerSub.amountFen());
            created.setHeldFen(heldSub.amountFen());
            created.setCommissionFen(decision.commissionFen());
            created.setReserveFen(decision.reserveFen());
            try {
                mapper.insertCombineOrder(created);
            } catch (DuplicateKeyException concurrent) {
                // 并发下单：以先写入的那条为准。
            }
            row = mapper.selectCombineOrder(businessNo);
            if (row == null) throw PaymentPreorderException.definiteFailure("合单收款单写入失败", null);
        }
        return new Placed(Route.COMBINED, pay(registration, row));
    }

    private CombinedCheckoutPolicy.Decision decide(CmsRegistration registration, SellerOfRecordPolicy.Decision seller, long totalFen) {
        CmsTopic topic = topics.selectCmsTopicById(registration.getOwnerId());
        if (topic == null) return new CombinedCheckoutPolicy.Decision(CombinedCheckoutPolicy.Path.PLATFORM, "TOPIC_MISSING", List.of(), 0, 0);
        List<CombinedCheckoutPolicy.Contract> contracts = new ArrayList<>();
        List<CoopOrder> effective = coopOrders.selectEffectiveOrdersByTopicId(topic.getId());
        if (effective != null) {
            for (CoopOrder o : effective) contracts.add(new CombinedCheckoutPolicy.Contract(o.getShareMode(), o.getShareRate(), o.getFixedFee()));
        }
        BigDecimal rate = contracts.isEmpty() ? BigDecimal.ZERO : pricing.platformRateOf(topic);
        return CombinedCheckoutPolicy.decide(seller, totalFen, rate, contracts, registration.getOrderNum(), switches);
    }

    private Map<String, String> pay(CmsRegistration registration, EcomCombineOrder row) {
        String businessNo = row.getOutTradeNo();
        long totalFen;
        String openid;
        String description;
        String notifyUrl;
        try {
            totalFen = EcommercePartnerPaymentAdapter.fen(registration.getWechatPaymentAmount());
            if (row.getTotalFen() == null || row.getTotalFen() != totalFen) throw new IllegalStateException("支付金额与合单收款单不符");
            UmsMember member = members.selectUmsMemberById(registration.getMemberId());
            if (member == null || member.getWxOpenId() == null || member.getWxOpenId().isBlank())
                throw new IllegalStateException("用户不存在");
            openid = member.getWxOpenId();
            description = EcommercePartnerPaymentAdapter.description(registration.getActivityTitle() + "-" + registration.getTicketName());
            notifyUrl = gateway.combinedNotifyUrl();
        } catch (RuntimeException invalid) {
            throw PaymentPreorderException.definiteFailure("创建微信支付订单参数异常：" + invalid.getMessage(), invalid);
        }
        String spMchid = switches.spMchid();
        String combineNo = intents.freezePaymentIntent(businessNo, spMchid, switches.spAppid(), "JSAPI", "CNY",
                Math.toIntExact(totalFen), openid, description, notifyUrl, () -> {
                    try {
                        gateway.queryCombinedOrder(businessNo);
                        throw new IllegalStateException("历史合单原单缺少冻结请求，须保留原单查证");
                    } catch (EcommercePayGateway.CombinedOrderNotFound absent) {
                        // 微信侧不存在同号合单，可以首次提交。
                    }
                });
        List<EcomCombineSubOrder> subs = ensureSubOrders(row, combineNo);
        List<EcommercePayGateway.CombinedSubRequest> requests = new ArrayList<>();
        for (EcomCombineSubOrder s : subs) {
            requests.add(new EcommercePayGateway.CombinedSubRequest(s.getSubMchid(), s.getOutTradeNo(), s.getAmountFen(),
                    description, Integer.valueOf(1).equals(s.getProfitSharing())));
        }
        try {
            RestoreDispatchGuard.requireDispatchAllowed();
            return gateway.prepayCombinedJsapi(combineNo, requests, openid);
        } catch (RestoreDispatchGuard.Blocked paused) {
            throw paused;
        } catch (RuntimeException e) {
            throw PaymentPreorderException.resultUncertain("创建微信合单支付结果未知：" + e.getMessage(), e);
        }
    }

    /** 每个合单号的子单确定性生成（同一合单号重复调用只写一次），并核对与合单记录一致。 */
    List<EcomCombineSubOrder> ensureSubOrders(EcomCombineOrder row, String combineNo) {
        List<EcomCombineSubOrder> subs = mapper.selectSubOrders(combineNo);
        if (subs.isEmpty()) {
            insertSub(row, combineNo, CombinedCheckoutPolicy.Role.SELLER, row.getSellerSubMchid(), row.getSellerFen(), 1);
            insertSub(row, combineNo, CombinedCheckoutPolicy.Role.PLATFORM_HELD, row.getPlatformSubMchid(), row.getHeldFen(), 0);
            subs = mapper.selectSubOrders(combineNo);
        }
        requireSubOrders(row, combineNo, subs);
        return subs;
    }

    private void insertSub(EcomCombineOrder row, String combineNo, CombinedCheckoutPolicy.Role role, String subMchid, long amount, int sharing) {
        EcomCombineSubOrder s = new EcomCombineSubOrder();
        s.setCombineOutTradeNo(combineNo);
        s.setRole(role.name());
        s.setOutTradeNo(CombinedCheckoutPolicy.subOutTradeNo(combineNo, role));
        s.setBusinessOutTradeNo(row.getOutTradeNo());
        s.setSubMchid(subMchid);
        s.setAmountFen(amount);
        s.setProfitSharing(sharing);
        try {
            mapper.insertSubOrder(s);
        } catch (DuplicateKeyException concurrent) {
            // 已写入。
        }
    }

    private static void requireSubOrders(EcomCombineOrder row, String combineNo, List<EcomCombineSubOrder> subs) {
        if (subs == null || subs.size() != 2) throw new IllegalStateException("合单子单缺失");
        EcomCombineSubOrder seller = subs.get(0), held = subs.get(1);
        if (!"SELLER".equals(seller.getRole()) || !"PLATFORM_HELD".equals(held.getRole())
                || !row.getOutTradeNo().equals(seller.getBusinessOutTradeNo()) || !row.getOutTradeNo().equals(held.getBusinessOutTradeNo())
                || !row.getSellerSubMchid().equals(seller.getSubMchid()) || !row.getPlatformSubMchid().equals(held.getSubMchid())
                || !Objects.equals(row.getSellerFen(), seller.getAmountFen()) || !Objects.equals(row.getHeldFen(), held.getAmountFen())
                || !CombinedCheckoutPolicy.subOutTradeNo(combineNo, CombinedCheckoutPolicy.Role.SELLER).equals(seller.getOutTradeNo())
                || !CombinedCheckoutPolicy.subOutTradeNo(combineNo, CombinedCheckoutPolicy.Role.PLATFORM_HELD).equals(held.getOutTradeNo()))
            throw new IllegalStateException("合单子单与合单收款单不一致");
    }

    // ------------------------------------------------------------------ 查单 / 关单 / 通知

    Map<String, String> queryOrderStatus(String businessNo, EcomCombineOrder row) {
        requireOutsideBusinessTransaction();
        String combineNo = intents.currentWechatPaymentNo(businessNo, switches.spMchid());
        EcommercePayGateway.CombinedOrder order;
        try {
            order = gateway.queryCombinedOrder(combineNo);
        } catch (EcommercePayGateway.CombinedOrderNotFound missing) {
            throw new WechatOrderNotExistException("微信侧合单不存在：" + businessNo, missing);
        } catch (RuntimeException e) {
            throw new RuntimeException("查询合单状态异常：" + e.getMessage(), e);
        }
        try {
            return verifiedFacts(businessNo, row, combineNo, order);
        } catch (RuntimeException e) {
            throw new RuntimeException("查询合单状态异常：" + e.getMessage(), e);
        }
    }

    boolean closePaymentAttempt(String businessNo, String observedCombineNo, EcomCombineOrder row) {
        RestoreDispatchGuard.requireDispatchAllowed();
        requireOutsideBusinessTransaction();
        String spMchid = switches.spMchid();
        if (businessNo == null || observedCombineNo == null || !businessNo.equals(intents.businessPaymentOrderNo(observedCombineNo, spMchid)))
            throw new IllegalArgumentException("关单必须携带原业务及所观察的微信尝试身份");
        try {
            if (!intents.currentAttemptClosable(businessNo, observedCombineNo, spMchid)) return false;
            List<EcomCombineSubOrder> subs = ensureSubOrders(row, observedCombineNo);
            List<EcommercePayGateway.CombinedSubRequest> requests = new ArrayList<>();
            for (EcomCombineSubOrder s : subs)
                requests.add(new EcommercePayGateway.CombinedSubRequest(s.getSubMchid(), s.getOutTradeNo(), s.getAmountFen(), null, false));
            gateway.closeCombinedOrder(observedCombineNo, requests);
            EcommercePayGateway.CombinedOrder observed = gateway.queryCombinedOrder(observedCombineNo);
            if (!"CLOSED".equals(aggregateState(row, observedCombineNo, observed, subs))) return false;
            intents.recordConfirmedClosedAttempt(spMchid, observedCombineNo);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("关闭原合单尝试未确认", e);
        }
    }

    /**
     * 已验签的合单通知只用于定位合单号；一律按原合单号查单逐字段核对后写事实。已写过事实的重放直接返回同一事实引用。
     * 返回 null 表示不属于本系统或事实不符（回 FAIL 让微信重试）。
     */
    Map<String, String> verifiedNotificationFacts(String combineNo) {
        requireOutsideBusinessTransaction();
        if (!enabled() || combineNo == null || !combineNo.matches("[A-Za-z0-9_-]{1,32}")) return null;
        String businessNo = intents.businessPaymentOrderNo(combineNo, switches.spMchid());
        if (businessNo == null) return null;
        EcomCombineOrder row = mapper.selectCombineOrder(businessNo);
        if (row == null) return null;
        WechatVerifiedPaymentFactService.Stored stored = verifiedFacts.findPaymentSuccess(businessNo);
        if (stored != null && switches.spMchid().equals(stored.facts().mchid()) && combineNo.equals(stored.facts().wxOutTradeNo())) {
            bindPaid(row, combineNo, stored.facts().transactionId());
            return replayFacts(stored.facts(), stored.reference());
        }
        EcommercePayGateway.CombinedOrder order = gateway.queryCombinedOrder(combineNo);
        Map<String, String> facts = verifiedFacts(businessNo, row, combineNo, order);
        String state = facts.get("trade_state");
        if ("CLOSED".equals(state) && verifiedFacts.hasConfirmedExternalSuccess(switches.spMchid(), businessNo)) return null;
        return Set.of("SUCCESS", "CLOSED").contains(state) ? facts : null;
    }

    private Map<String, String> verifiedFacts(String businessNo, EcomCombineOrder row, String combineNo,
                                              EcommercePayGateway.CombinedOrder order) {
        List<EcomCombineSubOrder> subs = ensureSubOrders(row, combineNo);
        String state = aggregateState(row, combineNo, order, subs);
        if ("CLOSED".equals(state)) intents.recordConfirmedClosedAttempt(switches.spMchid(), combineNo);
        WechatVerifiedPaymentFact.Reference ref = null;
        EcommercePayGateway.CombinedSub sellerSub = sub(order, subs.get(0).getOutTradeNo());
        if ("SUCCESS".equals(state)) ref = recordPayment(businessNo, row, combineNo, order, subs);
        Map<String, String> r = new HashMap<>();
        r.put("trade_state", state);
        r.put("transaction_id", sellerSub == null ? null : sellerSub.transactionId());
        r.put("out_trade_no", businessNo);
        r.put("wx_out_trade_no", combineNo);
        r.put("appid", order.combineAppid());
        r.put("mchid", switches.spMchid());
        r.put("trade_type", sellerSub == null ? null : sellerSub.tradeType());
        r.put("total", String.valueOf(row.getTotalFen()));
        r.put("currency", "CNY");
        if (ref != null) {
            r.put("verified_fact_id", Long.toString(ref.id()));
            r.put("verified_fact_hash", ref.hash());
        }
        return r;
    }

    /**
     * 合单整体状态：所有子单都已付（SUCCESS / REFUND）→ SUCCESS；全部 CLOSED → CLOSED；其余 → NOTPAY。
     * 合单身份、每笔子单的二级商户号 / 金额 / 币种必须与本地冻结的子单逐一相符，否则抛异常（不写事实）。
     */
    String aggregateState(EcomCombineOrder row, String combineNo, EcommercePayGateway.CombinedOrder order, List<EcomCombineSubOrder> subs) {
        if (order == null || !switches.spAppid().equals(order.combineAppid()) || !switches.spMchid().equals(order.combineMchid())
                || !combineNo.equals(order.combineOutTradeNo()) || order.subOrders() == null || order.subOrders().size() != subs.size())
            throw new IllegalStateException("微信合单身份事实缺失/不符");
        List<Map<String, Object>> frozen = intents.frozenFacts(switches.spMchid(), combineNo);
        if (frozen.size() != 1 || !(frozen.get(0).get("total_fen") instanceof Number total) || total.longValue() != row.getTotalFen()
                || !switches.spAppid().equals(frozen.get(0).get("appid")))
            throw new IllegalStateException("合单与永久冻结意图不一致");
        int paid = 0, closed = 0;
        for (EcomCombineSubOrder local : subs) {
            EcommercePayGateway.CombinedSub remote = sub(order, local.getOutTradeNo());
            if (remote == null || !switches.spMchid().equals(remote.mchid()) || !local.getSubMchid().equals(remote.subMchid())
                    || remote.totalFen() == null || remote.totalFen().longValue() != local.getAmountFen()
                    || !"CNY".equals(remote.currency()) || remote.tradeState() == null)
                throw new IllegalStateException("微信合单子单与冻结子单不符");
            if ("SUCCESS".equals(remote.tradeState()) || "REFUND".equals(remote.tradeState())) {
                if (!matches(remote.transactionId(), ID) || !"JSAPI".equals(remote.tradeType()))
                    throw new IllegalStateException("微信已付子单交易身份缺失");
                paid++;
            } else if ("CLOSED".equals(remote.tradeState())) {
                closed++;
            }
        }
        if (paid == subs.size()) return "SUCCESS";
        if (closed == subs.size()) return "CLOSED";
        if (paid > 0) throw new IllegalStateException("合单子单部分已付，须人工核查");
        return "NOTPAY";
    }

    private WechatVerifiedPaymentFact.Reference recordPayment(String businessNo, EcomCombineOrder row, String combineNo,
                                                               EcommercePayGateway.CombinedOrder order, List<EcomCombineSubOrder> subs) {
        EcommercePayGateway.CombinedSub seller = sub(order, subs.get(0).getOutTradeNo());
        WechatVerifiedPaymentFact.Reference ref = verifiedFacts.recordVerified(new WechatVerifiedPaymentFact(
                WechatVerifiedPaymentFact.Kind.PAYMENT, switches.spMchid(), order.combineAppid(), businessNo, combineNo,
                combineNo, seller.transactionId(), seller.transactionId(), seller.tradeType(), "CNY",
                row.getTotalFen(), row.getTotalFen(), instant(seller.successTime()),
                intents.frozenRequestHash(switches.spMchid(), combineNo), "SUCCESS"));
        for (EcomCombineSubOrder local : subs) {
            EcommercePayGateway.CombinedSub remote = sub(order, local.getOutTradeNo());
            mapper.bindSubOrderTransaction(combineNo, local.getRole(), remote.transactionId());
        }
        List<EcomCombineSubOrder> bound = mapper.selectSubOrders(combineNo);
        for (EcomCombineSubOrder local : bound) {
            if (!sub(order, local.getOutTradeNo()).transactionId().equals(local.getTransactionId()))
                throw new IllegalStateException("合单子单已绑定其它微信交易");
        }
        bindPaid(row, combineNo, seller.transactionId());
        return ref;
    }

    private void bindPaid(EcomCombineOrder row, String combineNo, String sellerTransactionId) {
        mapper.bindCombinePaid(row.getOutTradeNo(), combineNo, sellerTransactionId);
        EcomCombineOrder bound = mapper.selectCombineOrder(row.getOutTradeNo());
        if (bound == null || !combineNo.equals(bound.getCombineOutTradeNo()) || !sellerTransactionId.equals(bound.getSellerTransactionId()))
            throw new IllegalStateException("合单收款单已绑定其它微信交易");
        row.setCombineOutTradeNo(combineNo);
        row.setSellerTransactionId(sellerTransactionId);
    }

    // ------------------------------------------------------------------ 退款

    /**
     * 一笔业务退款按子单金额比例拆成每个子单各一笔退款（子单退款单号由业务退款单号确定性生成，幂等）。
     * 卖家子单若已向平台补差分账，先回退再退款。全部子单受理才返回 ACCEPTED；任一处理中返回 UNKNOWN，
     * 由原业务退款单号的恢复查证继续推进，绝不换号重发。
     */
    RefundRequestResult submitRefund(WechatVerifiedPaymentFact paid, EcomCombineOrder row, String outRefundNo,
                                     long refundFen, long totalFen, String reason) {
        RestoreDispatchGuard.requireDispatchAllowed();
        requireOutsideBusinessTransaction();
        if (!isCombinedPayment(paid, row) || paid.totalFen() != totalFen || row.getTotalFen() != totalFen)
            return RefundRequestResult.UNKNOWN;
        List<EcomCombineSubOrder> subs = mapper.selectSubOrders(row.getCombineOutTradeNo());
        if (subs.size() != 2 || subs.stream().anyMatch(s -> s.getTransactionId() == null)) return RefundRequestResult.UNKNOWN;
        List<EcomCombineRefund> children = mapper.selectRefundsByParent(outRefundNo);
        if (children.isEmpty()) {
            long[] amounts = new long[subs.size()];
            long[] before = new long[subs.size()];
            for (int i = 0; i < subs.size(); i++) amounts[i] = subs.get(i).getAmountFen();
            for (EcomCombineRefund other : mapper.selectRefundsByTrade(row.getOutTradeNo())) {
                if (PROVIDER_FAILED.contains(other.getState())) continue;
                before["SELLER".equals(other.getRole()) ? 0 : 1] += other.getRefundFen();
            }
            long[] split = CombinedCheckoutPolicy.splitRefund(amounts, before, refundFen);
            if (split == null) return RefundRequestResult.DEFINITIVE_FAILED;
            for (int i = 0; i < subs.size(); i++) {
                if (split[i] <= 0) continue;
                EcomCombineSubOrder s = subs.get(i);
                CombinedCheckoutPolicy.Role role = CombinedCheckoutPolicy.Role.valueOf(s.getRole());
                EcomCombineRefund child = new EcomCombineRefund();
                child.setOutRefundNo(CombinedCheckoutPolicy.subOutRefundNo(outRefundNo, role));
                child.setParentOutRefundNo(outRefundNo);
                child.setBusinessOutTradeNo(row.getOutTradeNo());
                child.setRole(role.name());
                child.setSubMchid(s.getSubMchid());
                child.setSubOutTradeNo(s.getOutTradeNo());
                child.setTransactionId(s.getTransactionId());
                child.setRefundFen(split[i]);
                child.setTotalFen(s.getAmountFen());
                child.setState(role == CombinedCheckoutPolicy.Role.SELLER ? RETURNING : SUBMITTING);
                try {
                    mapper.insertRefund(child);
                } catch (DuplicateKeyException concurrent) {
                    // 并发：以已存在的为准，下面核对。
                }
            }
            children = mapper.selectRefundsByParent(outRefundNo);
            // 发生退款后，平台待付的供应商应付先挂起，由运营按退款后的账本复核。
            mapper.holdPayablesForRefund(row.getOutTradeNo());
        }
        long sum = 0;
        for (EcomCombineRefund c : children) {
            EcomCombineSubOrder s = "SELLER".equals(c.getRole()) ? subs.get(0) : subs.get(1);
            if (!row.getOutTradeNo().equals(c.getBusinessOutTradeNo()) || !s.getSubMchid().equals(c.getSubMchid())
                    || !s.getTransactionId().equals(c.getTransactionId()) || !s.getOutTradeNo().equals(c.getSubOutTradeNo())
                    || !Objects.equals(s.getAmountFen(), c.getTotalFen())) {
                log.warn("combine refund identity conflict outRefundNo={}", safe(outRefundNo));
                return RefundRequestResult.UNKNOWN;
            }
            sum += c.getRefundFen();
        }
        if (children.isEmpty() || sum != refundFen) return RefundRequestResult.UNKNOWN;
        boolean allAccepted = true;
        int failed = 0;
        for (EcomCombineRefund c : children) {
            String state = advance(c, row, reason);
            if (PROVIDER_FAILED.contains(state)) failed++;
            if (!SUBMITTED.equals(state) && !"SUCCESS".equals(state)) allAccepted = false;
        }
        if (failed == children.size()) return RefundRequestResult.DEFINITIVE_FAILED;
        return allAccepted ? RefundRequestResult.ACCEPTED : RefundRequestResult.UNKNOWN;
    }

    /** 推进一笔子单退款，返回推进后的状态。 */
    private String advance(EcomCombineRefund c, EcomCombineOrder row, String reason) {
        String state = c.getState();
        if (RETURNING.equals(state) || RETURN_FAILED.equals(state)) {
            long sellerRefundedBefore = 0;
            for (EcomCombineRefund other : mapper.selectRefundsByTrade(row.getOutTradeNo())) {
                if (!"SELLER".equals(other.getRole()) || other.getOutRefundNo().equals(c.getOutRefundNo())
                        || PROVIDER_FAILED.contains(other.getState())) continue;
                sellerRefundedBefore += other.getRefundFen();
            }
            EcommerceProfitShareService.ReturnOutcome outcome = settlement.returnForRefund(row, c.getParentOutRefundNo(),
                    c.getRefundFen(), sellerRefundedBefore);
            switch (outcome) {
                case NOT_APPLICABLE: case ALL_RETURNED: break;
                case FAILED:
                    mapper.updateRefundState(c.getOutRefundNo(), state, RETURN_FAILED, null, "PROFIT_SHARE_RETURN_FAILED");
                    return RETURN_FAILED;
                default:
                    return state;
            }
            if (mapper.updateRefundState(c.getOutRefundNo(), state, SUBMITTING, null, null) != 1) return state;
            state = SUBMITTING;
        }
        if (!SUBMITTING.equals(state)) return state;
        RestoreDispatchGuard.requireDispatchAllowed();
        try {
            EcommercePayGateway.RefundAccepted accepted = gateway.createRefund(c.getSubMchid(), c.getTransactionId(),
                    c.getOutRefundNo(), c.getRefundFen(), c.getTotalFen(), reason);
            if (accepted == null || !c.getOutRefundNo().equals(accepted.outRefundNo()) || !matches(accepted.refundId(), ID)) return state;
            mapper.updateRefundState(c.getOutRefundNo(), SUBMITTING, SUBMITTED, accepted.refundId(), null);
            return SUBMITTED;
        } catch (RestoreDispatchGuard.Blocked paused) {
            throw paused;
        } catch (RuntimeException uncertain) {
            log.warn("combine refund submit uncertain outRefundNo={} type={}", safe(c.getOutRefundNo()), uncertain.getClass().getSimpleName());
            return state;
        }
    }

    /**
     * 按业务退款单号恢复 / 查证：未提交的子单先继续推进，已提交的逐笔查电商退款单核对。
     * 全部子单 SUCCESS 才写一条不可变 REFUND 事实（金额 = 各子单之和）。返回 null 表示未知。
     */
    Map<String, String> queryRefundFacts(WechatVerifiedPaymentFact paid, EcomCombineOrder row, String outRefundNo) {
        requireOutsideBusinessTransaction();
        if (outRefundNo == null || !outRefundNo.matches(ID) || !isCombinedPayment(paid, row)) return null;
        List<EcomCombineRefund> children = mapper.selectRefundsByParent(outRefundNo);
        if (children.isEmpty()) return null;
        long refundFen = 0;
        for (EcomCombineRefund c : children) refundFen += c.getRefundFen();
        if (children.stream().anyMatch(c -> Set.of(RETURNING, RETURN_FAILED, SUBMITTING).contains(c.getState()))) {
            if (submitRefund(paid, row, outRefundNo, refundFen, row.getTotalFen(), null) != RefundRequestResult.ACCEPTED) return null;
            children = mapper.selectRefundsByParent(outRefundNo);
        }
        int success = 0, failed = 0;
        String refundId = null;
        Instant successAt = null;
        for (EcomCombineRefund c : children) {
            EcommercePayGateway.RefundQuery q;
            try {
                q = gateway.queryRefund(c.getSubMchid(), c.getOutRefundNo());
            } catch (EcommercePayGateway.RefundNotFound missing) {
                return null;
            } catch (RuntimeException unavailable) {
                log.warn("combine refund query unknown type={}", unavailable.getClass().getSimpleName());
                return null;
            }
            if (q == null || !c.getOutRefundNo().equals(q.outRefundNo()) || !matches(q.refundId(), ID) || q.status() == null
                    || !c.getTransactionId().equals(q.transactionId()) || !c.getSubOutTradeNo().equals(q.outTradeNo())
                    || q.refundFen() == null || q.refundFen().longValue() != c.getRefundFen()
                    || (q.currency() != null && !"CNY".equals(q.currency()))) return null;
            if (SUBMITTING.equals(c.getState())) {
                mapper.updateRefundState(c.getOutRefundNo(), SUBMITTING, SUBMITTED, q.refundId(), null);
                c.setState(SUBMITTED);
            }
            if (("SUCCESS".equals(q.status()) || PROVIDER_FAILED.contains(q.status())) && SUBMITTED.equals(c.getState())) {
                mapper.updateRefundState(c.getOutRefundNo(), SUBMITTED, q.status(), q.refundId(),
                        "SUCCESS".equals(q.status()) ? null : "PROVIDER_" + q.status());
            }
            if ("SUCCESS".equals(q.status())) {
                success++;
                if (refundId == null) refundId = q.refundId();
                Instant at = instant(q.successTime());
                if (successAt == null || at.isAfter(successAt)) successAt = at;
            } else if (PROVIDER_FAILED.contains(q.status())) {
                failed++;
            }
        }
        String status;
        if (success == children.size()) status = "SUCCESS";
        else if (failed == children.size()) status = "CLOSED";
        else if (success + failed == children.size()) status = "ABNORMAL"; // 部分子单退款失败：退款待补偿，人工处理
        else status = "PROCESSING";
        Map<String, String> facts = new HashMap<>();
        facts.put("outRefundNo", outRefundNo);
        facts.put("outTradeNo", paid.businessOutTradeNo());
        facts.put("wxOutTradeNo", paid.wxOutTradeNo());
        facts.put("status", status);
        facts.put("refund", Long.toString(refundFen));
        facts.put("total", Long.toString(row.getTotalFen()));
        facts.put("currency", "CNY");
        facts.put("mchid", paid.mchid());
        facts.put("appid", paid.appid());
        facts.put("tradeType", paid.tradeType());
        facts.put("transactionId", paid.transactionId());
        facts.put("refundId", refundId);
        facts.put("successTime", successAt == null ? null : successAt.toString());
        if ("SUCCESS".equals(status)) {
            WechatVerifiedPaymentFact fact = new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.REFUND,
                    paid.mchid(), paid.appid(), paid.businessOutTradeNo(), paid.wxOutTradeNo(), outRefundNo, refundId,
                    paid.transactionId(), paid.tradeType(), "CNY", row.getTotalFen(), refundFen,
                    successAt, paid.intentRequestHash(), "REFUND");
            WechatVerifiedPaymentFact.Reference ref = verifiedFacts.recordVerified(fact);
            facts.put("verified_fact_id", Long.toString(ref.id()));
            facts.put("verified_fact_hash", ref.hash());
        }
        return facts;
    }

    /** 已验签的电商退款通知（某一子单）→ 以原支付事实为界查证整笔业务退款。null 表示不属于合单或未知。 */
    Map<String, String> verifiedRefundNoticeFacts(EcommercePayGateway.RefundNotice notice) {
        if (!enabled() || notice == null || !switches.spMchid().equals(notice.spMchid()) || !matches(notice.outRefundNo(), ID)) return null;
        EcomCombineRefund child = mapper.selectRefund(notice.outRefundNo());
        if (child == null || !child.getSubMchid().equals(notice.subMchid())) return null;
        EcomCombineOrder row = mapper.selectCombineOrder(child.getBusinessOutTradeNo());
        WechatVerifiedPaymentFactService.Stored paid = verifiedFacts.findPaymentSuccess(child.getBusinessOutTradeNo());
        if (row == null || paid == null || !isCombinedPayment(paid.facts(), row)) return null;
        return queryRefundFacts(paid.facts(), row, child.getParentOutRefundNo());
    }

    /** 已付事实是否就是这笔合单：商户号 = 合单商户号、微信单号 = 已付合单号、交易号 = 卖家子单交易号。 */
    boolean isCombinedPayment(WechatVerifiedPaymentFact paid, EcomCombineOrder row) {
        return paid != null && row != null && paid.kind() == WechatVerifiedPaymentFact.Kind.PAYMENT
                && switches.spMchid().equals(paid.mchid()) && row.getOutTradeNo().equals(paid.businessOutTradeNo())
                && row.getCombineOutTradeNo() != null && row.getCombineOutTradeNo().equals(paid.wxOutTradeNo())
                && row.getSellerTransactionId() != null && row.getSellerTransactionId().equals(paid.transactionId())
                && paid.amountFen() == paid.totalFen() && "CNY".equals(paid.currency());
    }

    // ------------------------------------------------------------------ 内部

    private static EcommercePayGateway.CombinedSub sub(EcommercePayGateway.CombinedOrder order, String outTradeNo) {
        if (order == null || order.subOrders() == null) return null;
        EcommercePayGateway.CombinedSub found = null;
        for (EcommercePayGateway.CombinedSub s : order.subOrders()) {
            if (outTradeNo.equals(s.outTradeNo())) {
                if (found != null) return null;
                found = s;
            }
        }
        return found;
    }

    private static Map<String, String> replayFacts(WechatVerifiedPaymentFact f, WechatVerifiedPaymentFact.Reference ref) {
        Map<String, String> r = new HashMap<>();
        r.put("out_trade_no", f.businessOutTradeNo());
        r.put("wx_out_trade_no", f.wxOutTradeNo());
        r.put("transaction_id", f.transactionId());
        r.put("trade_state", "SUCCESS");
        r.put("appid", f.appid());
        r.put("mchid", f.mchid());
        r.put("trade_type", f.tradeType());
        r.put("total", Long.toString(f.totalFen()));
        r.put("currency", f.currency());
        r.put("verified_fact_id", Long.toString(ref.id()));
        r.put("verified_fact_hash", ref.hash());
        return r;
    }

    private static Instant instant(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalStateException("微信成功时间缺失");
        return OffsetDateTime.parse(raw).toInstant();
    }

    private static boolean matches(String value, String pattern) { return value != null && value.matches(pattern); }

    private static String safe(String value) { return value != null && value.matches(ID) ? value : "UNKNOWN"; }

    private static void requireOutsideBusinessTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("官方SDK查证及独立事实写入必须在原业务事务外执行");
    }
}
