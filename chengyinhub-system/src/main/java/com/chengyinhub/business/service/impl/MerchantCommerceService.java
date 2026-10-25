package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.MerchantSubscription;
import com.chengyinhub.business.domain.OmsOrder;
import com.chengyinhub.business.domain.SettlementRule;
import com.chengyinhub.business.domain.UserPermission;
import com.chengyinhub.business.mapper.MerchantSubscriptionMapper;
import com.chengyinhub.business.mapper.OmsOrderMapper;
import com.chengyinhub.business.service.IMerchantCommerceService;
import com.chengyinhub.business.service.ISettlementRuleService;
import com.chengyinhub.business.service.IUserPermissionService;
import com.chengyinhub.business.service.WechatPaymentOperations;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** E4 商业化能力层。收费入口默认关闭；开启后金额和权益参数也只认后台规则。 */
@Service
public class MerchantCommerceService implements IMerchantCommerceService {
    public static final String BIZ_PREMIUM_TEMPLATE = "premium_template";
    public static final String BIZ_PROMOTION_SLOT = "promotion_slot";
    public static final String BIZ_CUSTOM_EVENT = "custom_event";
    public static final String ENTITLEMENT_CITY_NODE = "city_node_quota";

    @Autowired private MerchantSubscriptionMapper subscriptionMapper;
    @Autowired private OmsOrderMapper orderMapper;
    @Autowired private ISettlementRuleService settlementRuleService;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("wechatPaymentSdkGateway")
    private WechatPaymentOperations wechatPayService;
    @Autowired private IUserPermissionService permissionService;
    @Autowired private org.springframework.transaction.PlatformTransactionManager paymentTransactions;

    @Autowired private WechatVerifiedPaymentFactService verifiedPaymentFacts;

    private final boolean selfCheckoutEnabled;

    public MerchantCommerceService(@Value("${merchant.commerce.self-checkout-enabled:false}") boolean selfCheckoutEnabled) {
        this.selfCheckoutEnabled = selfCheckoutEnabled;
    }

    @Override
    public boolean isSelfCheckoutEnabled() {
        return selfCheckoutEnabled;
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER)
    public Map<String, String> createOrder(Long memberId, Long merchantId, String openid, String bizType,
                                           String requestId) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        requirePaymentOutsideTransaction();
        if (!selfCheckoutEnabled) {
            throw new IllegalStateException("自助开通暂未开放，请联系官方人工开通");
        }
        OmsOrder committed = paymentTransaction().execute(status -> {
            requireBizType(bizType);
            requireRequestId(requestId);
            OmsOrder replay = orderMapper.selectCommerceOrderByRequest(memberId, merchantId, bizType, requestId);
            if (replay != null) return replay;
            SettlementRule rule = settlementRuleService.getActiveRule(0L, bizType);
            if (rule == null || rule.getUnitPrice() == null || rule.getUnitPrice().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalStateException("该权益尚未配置价格，请联系官方");
            }
            if (rule.getEntitlementMaxUsage() == null || rule.getEntitlementMaxUsage() <= 0
                    || rule.getEntitlementDays() == null || rule.getEntitlementDays() <= 0) {
                throw new IllegalStateException("该权益尚未配置用量或有效期，请联系官方");
            }
            // Canonical order_sn is VARCHAR(32): preserve the entire UUID value in <=25 lowercase base36 digits.
            String orderSn = prefixOf(bizType) + new java.math.BigInteger(
                    UUID.randomUUID().toString().replace("-", ""), 16).toString(36);
            OmsOrder order = new OmsOrder();
            order.setOrderSn(orderSn);
            order.setMemberId(memberId);
            order.setMmsMerchantId(merchantId);
            order.setBizType(bizType);
            order.setPayAmount(rule.getUnitPrice());
            order.setOrderTotalAmount(rule.getUnitPrice());
            order.setStatus(0L);
            order.setPayType(0L);
            order.setProductQuantity(1L);
            order.setEntitlementMaxUsage(rule.getEntitlementMaxUsage());
            order.setEntitlementDays(rule.getEntitlementDays());
            order.setCommerceRequestId(requestId);
            order.setCreateTime(DateUtils.getNowDate());
            try {
                if (orderMapper.insertOmsOrder(order) != 1) {
                    throw new IllegalStateException("商业化订单创建失败");
                }
            } catch (DuplicateKeyException duplicate) {
                OmsOrder existing = orderMapper.selectCommerceOrderByRequest(memberId, merchantId, bizType, requestId);
                if (existing == null) throw duplicate;
                return existing;
            }
            return order;
        });
        if (committed == null) throw new IllegalStateException("商业化订单意图未提交");
        return paymentResponse(committed, openid);
    }

    @Override
    public Map<String, String> orderStatus(Long memberId, Long merchantId, String orderSn) {
        if (orderSn == null || orderSn.trim().isEmpty()) throw new IllegalArgumentException("订单编号不合法");
        OmsOrder order = orderMapper.selectOmsOrderByOrderSn(orderSn.trim());
        if (order == null || order.getBizType() == null
                || !memberId.equals(order.getMemberId()) || !merchantId.equals(order.getMmsMerchantId())) {
            throw new IllegalArgumentException("无权查看该订单");
        }
        Map<String, String> result = new LinkedHashMap<>();
        result.put("orderSn", order.getOrderSn());
        result.put("paymentStatus", statusOf(order));
        return result;
    }

    @Override
    public boolean supportsOutTradeNo(String outTradeNo) {
        return outTradeNo != null && (outTradeNo.startsWith("TPLTM_")
                || outTradeNo.startsWith("PROMOT_") || outTradeNo.startsWith("CUSTOM_"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean handlePaidOrder(String outTradeNo, boolean paid, Integer paidAmountFen, String transactionId,
            Reference reference) {
        if (reference == null) throw new IllegalArgumentException("原成功支付事实引用必填");
        if (!paid || !supportsOutTradeNo(outTradeNo) || paidAmountFen == null
                || transactionId == null || transactionId.trim().isEmpty()) return false;
        WechatVerifiedPaymentFactService.Stored verified = checkedPayment(reference, outTradeNo, paidAmountFen, transactionId);
        // REQUIRED joins original callback/DONE; programmatic boundary also covers self/direct calls.
        var application = new org.springframework.transaction.support.TransactionTemplate(paymentTransactions);
        return Boolean.TRUE.equals(application.execute(status -> applyVerifiedPayment(
                outTradeNo, paid, paidAmountFen, transactionId, verified)));
    }

    private boolean applyVerifiedPayment(String outTradeNo, boolean paid, Integer paidAmountFen, String transactionId,
            WechatVerifiedPaymentFactService.Stored verified) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        if (!paid || !supportsOutTradeNo(outTradeNo) || paidAmountFen == null
                || transactionId == null || transactionId.trim().isEmpty()) return false;
        OmsOrder order = orderMapper.selectCommerceOrderForUpdate(outTradeNo);
        if (order == null || order.getId() == null || order.getMemberId() == null || order.getMmsMerchantId() == null
                || !bizMatchesPrefix(order.getBizType(), outTradeNo)) return false;
        if (order.getPayAmount() == null
                || order.getPayAmount().movePointRight(2).intValueExact() != paidAmountFen) return false;
        if (Long.valueOf(1L).equals(order.getStatus())) {
            return completeCommerceProof(order, verified);
        }
        if (!Long.valueOf(0L).equals(order.getStatus()) || order.getVerifiedPaymentFactId() != null
                || order.getVerifiedPaymentFactHash() != null) return false;
        if (!currentCommercePayment(verified)) return false;
        if (orderMapper.markCommerceOrderPaid(order.getId(), transactionId) == 0) {
            OmsOrder persisted = orderMapper.selectCommerceOrderForUpdate(outTradeNo);
            return completeCommerceProof(persisted, verified);
        }

        MerchantSubscription grant = new MerchantSubscription();
        grant.setMerchantId(order.getMemberId());
        grant.setSubscriptionType(order.getBizType());
        grant.setStartDate(new Date());
        if (order.getEntitlementDays() != null) {
            grant.setEndDate(addDays(new Date(), order.getEntitlementDays()));
        }
        grant.setMaxUsage(order.getEntitlementMaxUsage());
        grant.setUsedCount(0);
        grant.setIsActive(1);
        // MySQL INSERT=1，ON DUPLICATE KEY UPDATE 实际续订=2；两者都是真实成功(移植自 PR #8 mini-foundation-a)。
        int grantRows = subscriptionMapper.grantMerchantSubscription(grant);
        if (grantRows != 1 && grantRows != 2) {
            throw new IllegalStateException("商业化权益授予失败");
        }
        if (orderMapper.bindCommercePaymentProof(order, transactionId, verified.reference().id(), verified.reference().hash()) != 1)
            throw new IllegalStateException("商业化原支付消费凭据绑定失败");
        OmsOrder readback = orderMapper.selectCommerceOrderForUpdate(outTradeNo);
        if (readback == null || !order.getId().equals(readback.getId())
                || !java.util.Objects.equals(order.getMemberId(), readback.getMemberId())
                || !java.util.Objects.equals(order.getMmsMerchantId(), readback.getMmsMerchantId())
                || !java.util.Objects.equals(order.getBizType(), readback.getBizType())
                || !completeCommerceProof(readback, verified))
            throw new IllegalStateException("商业化原支付消费凭据回读不一致");
        return true;
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER)
    public void reconcileOrder(String outTradeNo) {
        requirePaymentOutsideTransaction();
        OmsOrder order = orderMapper.selectOmsOrderByOrderSn(outTradeNo);
        if (order == null || !Long.valueOf(0L).equals(order.getStatus())) return;
        requireBizType(order.getBizType());
        if (!supportsOutTradeNo(outTradeNo) || !bizMatchesPrefix(order.getBizType(), outTradeNo)) {
            throw new IllegalStateException("商业化订单类型与单号不一致");
        }
        boolean abandoned = isAbandoned(order);
        Map<String, String> observed;
        try {
            observed = wechatPayService.queryOrderStatus(outTradeNo);
        } catch (com.chengyinhub.business.service.WechatOrderNotExistException missing) {
            // 短暂 NOT_FOUND 不能证明原请求未受理；保留原单与权益，不据此本地关闭。
            return;
        }
        if (observed == null || !outTradeNo.equals(observed.get("out_trade_no"))) {
            throw new IllegalStateException("商业化查单结果无法确认");
        }
        String state = observed.get("trade_state");
        if ("SUCCESS".equals(state)) {
            if (!"CNY".equals(observed.get("currency")) || observed.get("total") == null
                    || !observed.get("total").matches("[0-9]{1,10}")) {
                throw new IllegalStateException("商业化支付金额或币种无法确认");
            }
            if (!Boolean.TRUE.equals(paymentTransaction().execute(status -> handlePaidOrder(outTradeNo, true,
                    Integer.valueOf(observed.get("total")), observed.get("transaction_id"), paymentReference(observed))))) {
                throw new IllegalStateException("商业化已支付订单补记失败");
            }
        } else if ("CLOSED".equals(state)) {
            closeObservedOrder(order, observed.get("wx_out_trade_no"));
        } else if (("NOTPAY".equals(state) || "PAYERROR".equals(state)) && abandoned) {
            // 放弃的待支付单不能每 5 分钟查一辈子：先在微信侧关单(已付会被微信拒绝并抛出，
            // 下一轮查到 SUCCESS 照常补记)，关单成功后才本地终结。
            if (!wechatPayService.closePaymentAttempt(outTradeNo, observed.get("wx_out_trade_no"))) {
                throw new IllegalStateException("商业化超时订单关单未确认");
            }
            closeObservedOrder(order, observed.get("wx_out_trade_no"));
        } else if (!"NOTPAY".equals(state) && !"USERPAYING".equals(state) && !"PAYERROR".equals(state)) {
            throw new IllegalStateException("商业化支付状态无法确认");
        }
    }

    private boolean closeObservedOrder(OmsOrder order, String observedWxOutTradeNo) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        return Boolean.TRUE.equals(paymentTransaction().execute(status -> {
            if (!wechatPayService.lockClosablePaymentAttemptForUpdate(order.getOrderSn(), observedWxOutTradeNo)) return false;
            return orderMapper.markCommerceOrderClosed(order.getId()) == 1;
        }));
    }

    private org.springframework.transaction.support.TransactionTemplate paymentTransaction() {
        var transaction = new org.springframework.transaction.support.TransactionTemplate(paymentTransactions);
        transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction;
    }

    /** 待支付超过放弃窗口(24h)的商业化订单不再等待用户回来支付。 */
    static final long ABANDON_AFTER_MILLIS = 24L * 60 * 60 * 1000;

    private boolean isAbandoned(OmsOrder order) {
        Date created = order.getCreateTime();
        return created != null && System.currentTimeMillis() - created.getTime() >= ABANDON_AFTER_MILLIS;
    }

    public Entitlement entitlement(Long memberId, String role, String entitlementType, int currentUsage) {
        int base = 0;
        UserPermission permission = permissionService.getByRole(role);
        if (permission != null) {
            if (ENTITLEMENT_CITY_NODE.equals(entitlementType) && permission.getMaxCityNodes() != null) {
                base = permission.getMaxCityNodes();
            } else if (BIZ_PREMIUM_TEMPLATE.equals(entitlementType) && permission.getMaxThemes() != null) {
                base = permission.getMaxThemes();
            }
        }
        MerchantSubscription probe = new MerchantSubscription();
        probe.setMerchantId(memberId);
        probe.setSubscriptionType(entitlementType);
        probe.setIsActive(1);
        List<MerchantSubscription> subscriptions = subscriptionMapper.selectMerchantSubscriptionList(probe);
        if (subscriptions == null) subscriptions = Collections.emptyList();
        int extra = 0;
        for (MerchantSubscription subscription : subscriptions) {
            if (subscription.getMaxUsage() != null && subscription.getMaxUsage() > 0) {
                extra += subscription.getMaxUsage();
            }
        }
        return new Entitlement(base + extra, Math.max(0, currentUsage));
    }

    private void requireBizType(String bizType) {
        if (!BIZ_PREMIUM_TEMPLATE.equals(bizType) && !BIZ_PROMOTION_SLOT.equals(bizType)
                && !BIZ_CUSTOM_EVENT.equals(bizType)) {
            throw new IllegalArgumentException("不支持的商业化权益类型");
        }
    }

    private void requireRequestId(String requestId) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{16,64}")) {
            throw new IllegalArgumentException("请求标识不合法");
        }
    }

    private Map<String, String> paymentResponse(OmsOrder order, String openid) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        String status = statusOf(order);
        Map<String, String> result = new LinkedHashMap<>();
        if ("pending".equals(status)) {
            result.putAll(wechatPayService.createGenericOrder(order.getOrderSn(), order.getPayAmount(),
                    descriptionOf(order.getBizType()), openid));
        }
        result.put("orderSn", order.getOrderSn());
        result.put("paymentStatus", status);
        return result;
    }

    private String statusOf(OmsOrder order) {
        if (Long.valueOf(1L).equals(order.getStatus())) return "success";
        if (Long.valueOf(5L).equals(order.getStatus()) || Long.valueOf(6L).equals(order.getStatus())) return "failed";
        return "pending";
    }

    private String prefixOf(String bizType) {
        if (BIZ_PREMIUM_TEMPLATE.equals(bizType)) return "TPLTM_";
        if (BIZ_PROMOTION_SLOT.equals(bizType)) return "PROMOT_";
        return "CUSTOM_";
    }

    private String descriptionOf(String bizType) {
        if (BIZ_PREMIUM_TEMPLATE.equals(bizType)) return "城瘾高级模板权益";
        if (BIZ_PROMOTION_SLOT.equals(bizType)) return "城瘾推广位权益";
        return "城瘾活动定制权益";
    }

    private boolean bizMatchesPrefix(String bizType, String outTradeNo) {
        return bizType != null && outTradeNo.startsWith(prefixOf(bizType));
    }

    private Date addDays(Date base, int days) {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.setTime(base);
        calendar.add(java.util.Calendar.DAY_OF_YEAR, days);
        return calendar.getTime();
    }

    public static final class Entitlement {
        private final int limit;
        private final int used;
        private final int remaining;

        public Entitlement(int limit, int used) {
            this.limit = limit;
            this.used = used;
            this.remaining = Math.max(0, limit - used);
        }
        public int getLimit() { return limit; }
        public int getUsed() { return used; }
        public int getRemaining() { return remaining; }
    }
    /** NEVER rejects proxied outer transactions; this also protects self/direct invocation before any local intent. */
    private static void requirePaymentOutsideTransaction() {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("支付外呼必须在原业务事务外，不能挂起持锁事务继续执行");
        }
    }

    /** Known current-attempt denial remains false; DB/read failures must propagate. No local intent policy. */
    private boolean currentCommercePayment(WechatVerifiedPaymentFactService.Stored verified) {
        try {
            var consumable = verifiedPaymentFacts.requireConsumablePayment(verified.reference());
            if (!verified.equals(consumable)) throw new IllegalStateException("原成功支付事实回读不一致");
            return true;
        } catch (WechatVerifiedPaymentFactService.ConflictException rejected) {
            return false;
        }
    }

    private boolean completeCommerceProof(OmsOrder order, WechatVerifiedPaymentFactService.Stored verified) {
        if (order == null || !Long.valueOf(1L).equals(order.getStatus())
                || !verified.facts().businessOutTradeNo().equals(order.getOrderSn())
                || !bizMatchesPrefix(order.getBizType(), order.getOrderSn()) || order.getPayAmount() == null
                || order.getPayAmount().movePointRight(2).longValueExact() != verified.facts().totalFen()
                || !verified.facts().transactionId().equals(order.getPaymentTransactionId())
                || !Long.valueOf(verified.reference().id()).equals(order.getVerifiedPaymentFactId())
                || !verified.reference().hash().equals(order.getVerifiedPaymentFactHash())) return false;
        if (!currentCommercePayment(verified)) return false;
        verifiedPaymentFacts.completePaymentRecovery(verified.reference(), order.getId());
        return true;
    }

    private WechatVerifiedPaymentFactService.Stored checkedPayment(Reference reference, String outTradeNo,
            Integer amountFen, String transactionId) {
        WechatVerifiedPaymentFactService.Stored verified = verifiedPaymentFacts.requireVerified(reference);
        WechatVerifiedPaymentFact fact = verified.facts();
        fact.requireValid();
        if (!reference.equals(verified.reference()) || !reference.hash().equals(fact.factHash())
                || fact.kind() != WechatVerifiedPaymentFact.Kind.PAYMENT || !"CNY".equals(fact.currency())
                || !outTradeNo.equals(fact.businessOutTradeNo()) || fact.totalFen() != amountFen.longValue()
                || fact.amountFen() != fact.totalFen() || !transactionId.trim().equals(fact.transactionId()))
            throw new IllegalStateException("原支付事实与业务单号、金额或交易身份不符");
        // Original merchant/app/channel/intent hash are immutable B facts, never current-config defaults.
        return verified;
    }

    private Reference paymentReference(Map<String, String> observed) {
        if (observed == null || observed.get("verified_fact_id") == null || observed.get("verified_fact_hash") == null)
            throw new IllegalStateException("微信成功观察缺少原支付事实引用");
        try { return new Reference(Long.parseLong(observed.get("verified_fact_id")), observed.get("verified_fact_hash")); }
        catch (IllegalArgumentException invalid) { throw new IllegalStateException("微信成功观察的原支付事实引用无效", invalid); }
    }

}
