package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.EcomOrderSeller;
import com.chengyinhub.business.domain.EcomRefund;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.mapper.EcomPayoutMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.payout.EcommercePayGateway;
import com.chengyinhub.business.payout.EcommerceProfitShareService;
import com.chengyinhub.business.payout.EcommerceSellerResolver;
import com.chengyinhub.business.payout.PayoutSwitches;
import com.chengyinhub.business.payout.SellerOfRecordPolicy;
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
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * l-payout2：把「以二级商户为卖家」的报名单接进现有支付事实链路。
 *
 * <p>{@link WechatPaymentSdkGateway} 只在 {@link #enabled()}（{@code payout.ecommerce.enabled} 且配置了 sp_mchid）为真时
 * 才调用本类；开关关闭时网关一行新代码都不会执行到，现有平台商户号的下单 / 查单 / 关单 / 退款结果逐字不变。
 *
 * <p>与平台商户号路径共用同一套不可变证据：
 * <ul>
 *   <li>支付意图仍由 {@link WechatPaymentIntentStore} 冻结，商户号列记二级商户号 sub_mchid；</li>
 *   <li>支付成功仍写 {@link WechatVerifiedPaymentFactService} 的 PAYMENT 事实（mchid = sub_mchid），领域回调与退款照旧只认事实引用；</li>
 *   <li>回调、主动查单都先向微信查原单（服务商查单接口）逐字段核对，再交给现有 {@link WechatPaymentCallbackRouter}。</li>
 * </ul>
 *
 * <p>退款：先按退款比例回退已分出去的分账（{@link EcommerceProfitShareService#returnForRefund}），全部回退成功后
 * 才向微信申请电商退款；回退未完成时退款保持「处理中」（UNKNOWN），由原退款单号的恢复查证继续推进，绝不换号重发。
 */
@Component
public class EcommercePartnerPaymentAdapter {
    private static final Logger log = LoggerFactory.getLogger(EcommercePartnerPaymentAdapter.class);

    static final String RETURNING = "RETURNING";
    static final String RETURN_FAILED = "RETURN_FAILED";
    static final String SUBMITTING = "SUBMITTING";
    static final String SUBMITTED = "SUBMITTED";
    private static final Set<String> PROVIDER_TERMINAL = Set.of("SUCCESS", "CLOSED", "ABNORMAL");
    private static final String ID = "[A-Za-z0-9_-]{1,64}";

    private final PayoutSwitches switches;
    private final EcomPayoutMapper mapper;
    private final EcommercePayGateway gateway;
    private final EcommerceSellerResolver sellers;
    private final EcommerceProfitShareService profitSharing;
    private final WechatPaymentIntentStore intents;
    private final WechatVerifiedPaymentFactService verifiedFacts;
    private final UmsMemberMapper members;

    public EcommercePartnerPaymentAdapter(PayoutSwitches switches, EcomPayoutMapper mapper, EcommercePayGateway gateway,
                                          EcommerceSellerResolver sellers, EcommerceProfitShareService profitSharing,
                                          WechatPaymentIntentStore intents, WechatVerifiedPaymentFactService verifiedFacts,
                                          UmsMemberMapper members) {
        this.switches = switches;
        this.mapper = mapper;
        this.gateway = gateway;
        this.sellers = sellers;
        this.profitSharing = profitSharing;
        this.intents = intents;
        this.verifiedFacts = verifiedFacts;
        this.members = members;
    }

    /** 电商收付通总开关。为假时网关不调用本类任何其它方法。 */
    public boolean enabled() { return switches.ecommerceEnabled(); }

    /** 业务单若以二级商户为卖家下过单，返回其卖家记录；否则 null（平台商户号单）。 */
    EcomOrderSeller sellerFor(String businessNo) {
        if (!enabled() || businessNo == null || businessNo.isBlank()) return null;
        return mapper.selectOrderSeller(businessNo);
    }

    /** 已付事实是否属于二级商户为卖家的单：商户号必须等于登记的 sub_mchid。 */
    EcomOrderSeller sellerForPaidFact(WechatVerifiedPaymentFact paid) {
        if (paid == null) return null;
        EcomOrderSeller seller = sellerFor(paid.businessOutTradeNo());
        return seller != null && seller.getSubMchid().equals(paid.mchid()) ? seller : null;
    }

    /** 锁支付尝试时用的商户号：二级商户单用 sub_mchid，其余 null（调用方用平台商户号）。 */
    String partnerMchid(String businessNo) {
        EcomOrderSeller seller = sellerFor(businessNo);
        return seller == null ? null : seller.getSubMchid();
    }

    // ------------------------------------------------------------------ 下单

    /**
     * 报名单下单。返回 null 表示走现有平台商户号路径（不改变任何东西）。
     * <ul>
     *   <li>已有卖家记录：继续用原 sub_mchid（已开始的支付不换收款主体）；</li>
     *   <li>平台商户号下已冻结过意图：继续走平台；</li>
     *   <li>否则只有路由开关打开、是主题门票、卖家已完成进件时才以 sub_mchid 下单。</li>
     * </ul>
     * 活动场次票与俱乐部会费的结算仍直接记会员余额，未接入分账，因此本期一律走平台。
     */
    Map<String, String> createOrder(CmsRegistration registration, String platformMchid) {
        if (!enabled() || registration == null || registration.getRegistrationNo() == null) return null;
        String businessNo = registration.getRegistrationNo();
        EcomOrderSeller seller = mapper.selectOrderSeller(businessNo);
        if (seller == null) {
            if (!switches.sellerRoutingEnabled() || !Long.valueOf(1L).equals(registration.getOwnerType())) return null;
            if (platformMchid != null && !platformMchid.isBlank() && intents.hasAnyAttempt(platformMchid, businessNo)) return null;
            SellerOfRecordPolicy.Decision decision = sellers.decide(registration);
            if (decision == null || !decision.subMerchant()) return null;
            EcomOrderSeller row = new EcomOrderSeller();
            row.setOutTradeNo(businessNo);
            row.setSubMchid(decision.subMchid());
            row.setSellerType(decision.sellerType().name());
            row.setSellerId(decision.sellerId());
            row.setTotalFen(fen(registration.getWechatPaymentAmount()));
            try {
                mapper.insertOrderSeller(row);
            } catch (DuplicateKeyException concurrent) {
                // 并发下单：以先写入的那条为准。
            }
            seller = mapper.selectOrderSeller(businessNo);
            if (seller == null) throw PaymentPreorderException.definiteFailure("卖家收款单写入失败", null);
        }
        if (!switches.sellerRoutingEnabled() && seller.getTransactionId() == null && !intents.hasAnyAttempt(seller.getSubMchid(), businessNo)) {
            // 路由开关已关、该单也从未向卖家商户号下过单：回到平台商户号前先确认没有遗留卖家意图。
            throw PaymentPreorderException.definiteFailure("卖家收款已暂停，请稍后再试", null);
        }
        long totalFen;
        String openid;
        String description;
        String notifyUrl;
        try {
            totalFen = fen(registration.getWechatPaymentAmount());
            if (seller.getTotalFen() == null || seller.getTotalFen() != totalFen)
                throw new IllegalStateException("支付金额与卖家收款单不符");
            UmsMember member = members.selectUmsMemberById(registration.getMemberId());
            if (member == null || member.getWxOpenId() == null || member.getWxOpenId().isBlank())
                throw new IllegalStateException("用户不存在");
            openid = member.getWxOpenId();
            description = description(registration.getActivityTitle() + "-" + registration.getTicketName());
            notifyUrl = gateway.paymentNotifyUrl();
        } catch (RuntimeException invalid) {
            throw PaymentPreorderException.definiteFailure("创建微信支付订单参数异常：" + invalid.getMessage(), invalid);
        }
        String subMchid = seller.getSubMchid();
        String wxNo = intents.freezePaymentIntent(businessNo, subMchid, switches.spAppid(), "JSAPI", "CNY",
                Math.toIntExact(totalFen), openid, description, notifyUrl, () -> {
                    try {
                        gateway.queryPartnerOrder(subMchid, businessNo);
                        throw new IllegalStateException("历史卖家原单缺少冻结请求，须保留原单查证");
                    } catch (EcommercePayGateway.PartnerOrderNotFound absent) {
                        // 微信侧不存在同号原单，可以首次提交。
                    }
                });
        try {
            RestoreDispatchGuard.requireDispatchAllowed();
            return gateway.prepayJsapi(subMchid, wxNo, totalFen, description, openid);
        } catch (RestoreDispatchGuard.Blocked paused) {
            throw paused;
        } catch (RuntimeException e) {
            throw PaymentPreorderException.resultUncertain("创建微信支付订单结果未知：" + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ 查单 / 关单

    Map<String, String> queryOrderStatus(String businessNo, EcomOrderSeller seller) {
        requireOutsideBusinessTransaction();
        String subMchid = seller.getSubMchid();
        String wxNo = intents.currentWechatPaymentNo(businessNo, subMchid);
        EcommercePayGateway.PartnerOrder order;
        try {
            order = gateway.queryPartnerOrder(subMchid, wxNo);
        } catch (EcommercePayGateway.PartnerOrderNotFound missing) {
            throw new WechatOrderNotExistException("微信侧订单不存在：" + businessNo, missing);
        } catch (RuntimeException e) {
            throw new RuntimeException("查询订单状态异常：" + e.getMessage(), e);
        }
        try {
            requireOriginal(order, subMchid, wxNo);
            if ("CLOSED".equals(order.tradeState())) intents.recordConfirmedClosedAttempt(subMchid, wxNo);
            return facts(businessNo, order, "SUCCESS".equals(order.tradeState()) ? recordPayment(businessNo, order) : null);
        } catch (RuntimeException e) {
            throw new RuntimeException("查询订单状态异常：" + e.getMessage(), e);
        }
    }

    boolean closePaymentAttempt(String businessNo, String observedWxNo, EcomOrderSeller seller) {
        RestoreDispatchGuard.requireDispatchAllowed();
        requireOutsideBusinessTransaction();
        String subMchid = seller.getSubMchid();
        if (businessNo == null || observedWxNo == null || !businessNo.equals(intents.businessPaymentOrderNo(observedWxNo, subMchid)))
            throw new IllegalArgumentException("关单必须携带原业务及所观察的微信尝试身份");
        try {
            if (!intents.currentAttemptClosable(businessNo, observedWxNo, subMchid)) return false;
            gateway.closePartnerOrder(subMchid, observedWxNo);
            EcommercePayGateway.PartnerOrder observed = gateway.queryPartnerOrder(subMchid, observedWxNo);
            requireOriginal(observed, subMchid, observedWxNo);
            if (!"CLOSED".equals(observed.tradeState())) return false;
            intents.recordConfirmedClosedAttempt(subMchid, observedWxNo);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("关闭原支付尝试未确认", e);
        }
    }

    String currentWechatPaymentNo(String businessNo, EcomOrderSeller seller) {
        return intents.currentWechatPaymentNo(businessNo, seller.getSubMchid());
    }

    // ------------------------------------------------------------------ 支付回调

    /**
     * 已验签通知 → 按原商户 / 原单号向微信查原单逐字段核对 → 写不可变支付事实。任何不符返回 null（回 FAIL 让微信重试）。
     */
    Map<String, String> verifiedNotificationFacts(EcommercePayGateway.PartnerOrder notice) {
        requireOutsideBusinessTransaction();
        if (notice == null || !switches.spMchid().equals(notice.spMchid()) || !matches(notice.subMchid(), "[0-9]{6,32}")
                || !matches(notice.outTradeNo(), ID) || !matches(notice.transactionId(), ID)) return null;
        String subMchid = notice.subMchid();
        String wxNo = notice.outTradeNo();
        String businessNo = intents.businessPaymentOrderNo(wxNo, subMchid);
        EcomOrderSeller seller = mapper.selectOrderSeller(businessNo);
        if (seller == null || !subMchid.equals(seller.getSubMchid())) return null;
        if ("SUCCESS".equals(notice.tradeState())) {
            WechatVerifiedPaymentFactService.Stored stored = verifiedFacts.findPaymentSuccess(subMchid, wxNo, notice.transactionId());
            if (stored != null) {
                WechatVerifiedPaymentFact f = stored.facts();
                if (!f.appid().equals(notice.spAppid()) || !f.tradeType().equals(notice.tradeType())
                        || notice.totalFen() == null || f.totalFen() != notice.totalFen() || !"CNY".equals(notice.currency())
                        || !sameInstant(f.successAt(), notice.successTime())) return null;
                bindTransaction(businessNo, f.transactionId());
                return replayFacts(f, stored.reference());
            }
        }
        EcommercePayGateway.PartnerOrder original = gateway.queryPartnerOrder(subMchid, wxNo);
        requireOriginal(original, subMchid, wxNo);
        boolean same = Objects.equals(notice.tradeState(), original.tradeState())
                && Objects.equals(notice.transactionId(), original.transactionId())
                && Objects.equals(notice.spAppid(), original.spAppid())
                && Objects.equals(notice.tradeType(), original.tradeType())
                && Objects.equals(notice.totalFen(), original.totalFen())
                && "CNY".equals(notice.currency());
        if (same && "SUCCESS".equals(original.tradeState())) same = sameInstant(instant(original.successTime()), notice.successTime());
        if (!same || !Set.of("SUCCESS", "CLOSED", "REFUND").contains(original.tradeState())) return null;
        if ("CLOSED".equals(original.tradeState()) && verifiedFacts.hasConfirmedExternalSuccess(subMchid, businessNo)) return null;
        return facts(businessNo, original, "SUCCESS".equals(original.tradeState()) ? recordPayment(businessNo, original) : null);
    }

    // ------------------------------------------------------------------ 退款

    /**
     * 先回退分账，再向微信电商退款。同一退款单号幂等：重复调用只会推进同一条 ecom_refund，不会换号重发。
     * 返回 UNKNOWN 时调用方保留原退款单「处理中」，之后按原单号恢复查证（{@link #queryRefundFacts}）。
     */
    RefundRequestResult submitRefund(WechatVerifiedPaymentFact paid, EcomOrderSeller seller, String outRefundNo,
                                     long refundFen, long totalFen, String reason) {
        RestoreDispatchGuard.requireDispatchAllowed();
        requireOutsideBusinessTransaction();
        if (paid.kind() != WechatVerifiedPaymentFact.Kind.PAYMENT || !paid.mchid().equals(seller.getSubMchid())
                || paid.totalFen() != totalFen || paid.amountFen() != paid.totalFen() || !"CNY".equals(paid.currency())
                || seller.getTotalFen() == null || seller.getTotalFen() != totalFen
                || (seller.getTransactionId() != null && !seller.getTransactionId().equals(paid.transactionId())))
            return RefundRequestResult.UNKNOWN;
        EcomRefund row = mapper.selectRefund(outRefundNo);
        if (row == null) {
            EcomRefund created = new EcomRefund();
            created.setOutRefundNo(outRefundNo);
            created.setOutTradeNo(paid.businessOutTradeNo());
            created.setSubMchid(seller.getSubMchid());
            created.setTransactionId(paid.transactionId());
            created.setRefundFen(refundFen);
            created.setTotalFen(totalFen);
            try {
                mapper.insertRefund(created);
            } catch (DuplicateKeyException concurrent) {
                // 并发：以已存在的那条为准，下面逐字段核对。
            }
            row = mapper.selectRefund(outRefundNo);
        }
        if (row == null || !paid.businessOutTradeNo().equals(row.getOutTradeNo()) || !seller.getSubMchid().equals(row.getSubMchid())
                || !paid.transactionId().equals(row.getTransactionId()) || row.getRefundFen() == null || row.getRefundFen() != refundFen
                || row.getTotalFen() == null || row.getTotalFen() != totalFen) {
            log.warn("ecom refund identity conflict outRefundNo={}", safe(outRefundNo));
            return RefundRequestResult.UNKNOWN;
        }
        switch (row.getState()) {
            case SUBMITTED: case "SUCCESS": return RefundRequestResult.ACCEPTED;
            case "CLOSED": case "ABNORMAL": return RefundRequestResult.DEFINITIVE_FAILED;
            case SUBMITTING: return createProviderRefund(row, reason);
            case RETURNING: case RETURN_FAILED: break;
            default: return RefundRequestResult.UNKNOWN;
        }
        long refundedBefore = 0;
        for (EcomRefund other : mapper.selectRefundsByTrade(row.getOutTradeNo())) {
            if (other.getOutRefundNo().equals(outRefundNo)) continue;
            if ("CLOSED".equals(other.getState()) || "ABNORMAL".equals(other.getState())) continue;
            refundedBefore += other.getRefundFen();
        }
        if (refundedBefore + refundFen > totalFen) return RefundRequestResult.DEFINITIVE_FAILED;
        EcommerceProfitShareService.ReturnOutcome outcome =
                profitSharing.returnForRefund(row.getOutTradeNo(), outRefundNo, refundFen, refundedBefore);
        switch (outcome) {
            case NOT_APPLICABLE: case ALL_RETURNED: case SWITCH_OFF: break;
            case FAILED:
                mapper.updateRefundState(outRefundNo, row.getState(), RETURN_FAILED, null, "PROFIT_SHARE_RETURN_FAILED");
                return RefundRequestResult.UNKNOWN;
            default:
                return RefundRequestResult.UNKNOWN; // 回退处理中：保持原退款单处理中，等恢复查证推进。
        }
        if (mapper.updateRefundState(outRefundNo, row.getState(), SUBMITTING, null, null) != 1) return RefundRequestResult.UNKNOWN;
        row.setState(SUBMITTING);
        return createProviderRefund(row, reason);
    }

    private RefundRequestResult createProviderRefund(EcomRefund row, String reason) {
        RestoreDispatchGuard.requireDispatchAllowed();
        try {
            EcommercePayGateway.RefundAccepted accepted = gateway.createRefund(row.getSubMchid(), row.getTransactionId(),
                    row.getOutRefundNo(), row.getRefundFen(), row.getTotalFen(), reason);
            if (accepted == null || !row.getOutRefundNo().equals(accepted.outRefundNo())
                    || !matches(accepted.refundId(), ID)) return RefundRequestResult.UNKNOWN;
            mapper.updateRefundState(row.getOutRefundNo(), SUBMITTING, SUBMITTED, accepted.refundId(), null);
            return RefundRequestResult.ACCEPTED;
        } catch (RestoreDispatchGuard.Blocked paused) {
            throw paused;
        } catch (RuntimeException uncertain) {
            log.warn("ecom refund submit uncertain outRefundNo={} type={}", safe(row.getOutRefundNo()),
                    uncertain.getClass().getSimpleName());
            return RefundRequestResult.UNKNOWN;
        }
    }

    /**
     * 按原退款单号恢复 / 查证。还没提交给微信（回退未完成）时先继续回退并提交；已提交则查电商退款单并逐字段核对。
     * 返回 null 表示未知（调用方保持处理中）；SUCCESS 时写不可变 REFUND 事实。
     */
    Map<String, String> queryRefundFacts(WechatVerifiedPaymentFact paid, EcomOrderSeller seller, String outRefundNo) {
        requireOutsideBusinessTransaction();
        if (outRefundNo == null || !outRefundNo.matches(ID)) return null;
        EcomRefund row = mapper.selectRefund(outRefundNo);
        if (row == null || !paid.businessOutTradeNo().equals(row.getOutTradeNo()) || !seller.getSubMchid().equals(row.getSubMchid())
                || !paid.transactionId().equals(row.getTransactionId())) return null;
        if (RETURNING.equals(row.getState()) || RETURN_FAILED.equals(row.getState())) {
            if (submitRefund(paid, seller, outRefundNo, row.getRefundFen(), row.getTotalFen(), null) != RefundRequestResult.ACCEPTED)
                return null;
            row = mapper.selectRefund(outRefundNo);
        }
        EcommercePayGateway.RefundQuery q;
        try {
            q = gateway.queryRefund(row.getSubMchid(), outRefundNo);
        } catch (EcommercePayGateway.RefundNotFound missing) {
            Map<String, String> notFound = new HashMap<>();
            notFound.put("status", "NOT_FOUND");
            return notFound;
        } catch (RuntimeException unavailable) {
            log.warn("ecom refund query unknown type={}", unavailable.getClass().getSimpleName());
            return null;
        }
        if (q == null || !outRefundNo.equals(q.outRefundNo()) || !matches(q.refundId(), ID) || q.status() == null
                || !row.getTransactionId().equals(q.transactionId()) || !paid.wxOutTradeNo().equals(q.outTradeNo())
                || q.refundFen() == null || q.refundFen().longValue() != row.getRefundFen()
                || (q.currency() != null && !"CNY".equals(q.currency()))) return null;
        if (SUBMITTING.equals(row.getState())) {
            mapper.updateRefundState(outRefundNo, SUBMITTING, SUBMITTED, q.refundId(), null);
            row.setState(SUBMITTED);
        }
        if (PROVIDER_TERMINAL.contains(q.status()) && SUBMITTED.equals(row.getState())) {
            mapper.updateRefundState(outRefundNo, SUBMITTED, q.status(), q.refundId(),
                    "SUCCESS".equals(q.status()) ? null : "PROVIDER_" + q.status());
        }
        Map<String, String> facts = new HashMap<>();
        facts.put("outRefundNo", outRefundNo);
        facts.put("outTradeNo", paid.businessOutTradeNo());
        facts.put("wxOutTradeNo", paid.wxOutTradeNo());
        facts.put("status", q.status());
        facts.put("refund", Long.toString(row.getRefundFen()));
        facts.put("total", Long.toString(row.getTotalFen()));
        facts.put("currency", "CNY");
        facts.put("mchid", paid.mchid());
        facts.put("appid", paid.appid());
        facts.put("tradeType", paid.tradeType());
        facts.put("transactionId", q.transactionId());
        facts.put("refundId", q.refundId());
        facts.put("successTime", q.successTime());
        if ("SUCCESS".equals(q.status())) {
            WechatVerifiedPaymentFact fact = new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.REFUND,
                    paid.mchid(), paid.appid(), paid.businessOutTradeNo(), paid.wxOutTradeNo(), outRefundNo, q.refundId(),
                    q.transactionId(), paid.tradeType(), "CNY", row.getTotalFen(), row.getRefundFen(),
                    instant(q.successTime()), paid.intentRequestHash(), "REFUND");
            WechatVerifiedPaymentFact.Reference ref = verifiedFacts.recordVerified(fact);
            facts.put("verified_fact_id", Long.toString(ref.id()));
            facts.put("verified_fact_hash", ref.hash());
        }
        return facts;
    }

    /** 已验签的电商退款通知 → 以原支付事实为界查证退款单。返回 null 表示未知 / 不属于本系统。 */
    Map<String, String> verifiedRefundNoticeFacts(EcommercePayGateway.RefundNotice notice) {
        if (notice == null || !switches.spMchid().equals(notice.spMchid()) || !matches(notice.outRefundNo(), ID)) return null;
        EcomRefund row = mapper.selectRefund(notice.outRefundNo());
        if (row == null || !row.getSubMchid().equals(notice.subMchid())) return null;
        EcomOrderSeller seller = mapper.selectOrderSeller(row.getOutTradeNo());
        WechatVerifiedPaymentFactService.Stored paid = verifiedFacts.findPaymentSuccess(row.getOutTradeNo());
        if (seller == null || paid == null || !paid.facts().mchid().equals(row.getSubMchid())
                || !paid.facts().transactionId().equals(row.getTransactionId())) return null;
        return queryRefundFacts(paid.facts(), seller, notice.outRefundNo());
    }

    // ------------------------------------------------------------------ 内部

    private void requireOriginal(EcommercePayGateway.PartnerOrder order, String subMchid, String wxNo) {
        if (order == null || !switches.spMchid().equals(order.spMchid()) || !subMchid.equals(order.subMchid())
                || !wxNo.equals(order.outTradeNo()) || order.spAppid() == null || order.spAppid().isBlank()
                || order.tradeState() == null || order.totalFen() == null || order.totalFen() <= 0
                || !"CNY".equals(order.currency()))
            throw new IllegalStateException("微信原单身份或金额事实缺失/不符");
        if (("SUCCESS".equals(order.tradeState()) || "REFUND".equals(order.tradeState()))
                && (!matches(order.transactionId(), ID) || !"JSAPI".equals(order.tradeType())))
            throw new IllegalStateException("微信已付原单交易身份缺失");
        List<Map<String, Object>> frozen = intents.frozenFacts(subMchid, wxNo);
        if (frozen.size() != 1) throw new IllegalStateException("卖家原单缺少唯一冻结意图");
        Map<String, Object> row = frozen.get(0);
        if (!order.spAppid().equals(row.get("appid"))
                || (order.tradeType() != null && !order.tradeType().equals(row.get("trade_type")))
                || !"CNY".equals(row.get("currency")) || !(row.get("total_fen") instanceof Number total)
                || total.longValue() != order.totalFen())
            throw new IllegalStateException("微信原单与永久冻结意图不一致");
    }

    private WechatVerifiedPaymentFact.Reference recordPayment(String businessNo, EcommercePayGateway.PartnerOrder order) {
        WechatVerifiedPaymentFact.Reference ref = verifiedFacts.recordVerified(new WechatVerifiedPaymentFact(
                WechatVerifiedPaymentFact.Kind.PAYMENT, order.subMchid(), order.spAppid(), businessNo, order.outTradeNo(),
                order.outTradeNo(), order.transactionId(), order.transactionId(), order.tradeType(), "CNY",
                order.totalFen(), order.totalFen(), instant(order.successTime()),
                intents.frozenRequestHash(order.subMchid(), order.outTradeNo()), "SUCCESS"));
        bindTransaction(businessNo, order.transactionId());
        return ref;
    }

    private void bindTransaction(String businessNo, String transactionId) {
        mapper.bindOrderSellerTransaction(businessNo, transactionId);
        EcomOrderSeller bound = mapper.selectOrderSeller(businessNo);
        if (bound == null || !transactionId.equals(bound.getTransactionId()))
            throw new IllegalStateException("卖家收款单已绑定其它微信交易");
    }

    private static Map<String, String> facts(String businessNo, EcommercePayGateway.PartnerOrder o,
                                             WechatVerifiedPaymentFact.Reference ref) {
        Map<String, String> r = new HashMap<>();
        r.put("trade_state", o.tradeState());
        r.put("transaction_id", o.transactionId());
        r.put("out_trade_no", businessNo);
        r.put("wx_out_trade_no", o.outTradeNo());
        r.put("appid", o.spAppid());
        r.put("mchid", o.subMchid());
        r.put("trade_type", o.tradeType());
        r.put("total", String.valueOf(o.totalFen()));
        r.put("currency", o.currency());
        if (ref != null) {
            r.put("verified_fact_id", Long.toString(ref.id()));
            r.put("verified_fact_hash", ref.hash());
        }
        return r;
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

    static long fen(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("支付金额必须为正数");
        return amount.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).intValueExact();
    }

    private static String description(String value) {
        if (value.length() <= 127) return value;
        int end = Character.isHighSurrogate(value.charAt(123)) ? 123 : 124;
        return value.substring(0, end) + "...";
    }

    private static Instant instant(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalStateException("微信成功时间缺失");
        return OffsetDateTime.parse(raw).toInstant();
    }

    private static boolean sameInstant(Instant expected, String raw) {
        try { return expected.equals(instant(raw)); } catch (RuntimeException invalid) { return false; }
    }

    private static boolean matches(String value, String pattern) { return value != null && value.matches(pattern); }

    private static String safe(String value) { return value != null && value.matches(ID) ? value : "UNKNOWN"; }

    private static void requireOutsideBusinessTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("官方SDK查证及独立事实写入必须在原业务事务外执行");
    }
}
