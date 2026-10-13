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
import com.chengyinhub.business.service.IWechatPayService;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    @Autowired private IWechatPayService wechatPayService;
    @Autowired private IUserPermissionService permissionService;

    private final boolean selfCheckoutEnabled;

    public MerchantCommerceService(@Value("${merchant.commerce.self-checkout-enabled:false}") boolean selfCheckoutEnabled) {
        this.selfCheckoutEnabled = selfCheckoutEnabled;
    }

    @Override
    public boolean isSelfCheckoutEnabled() {
        return selfCheckoutEnabled;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, String> createOrder(Long memberId, Long merchantId, String openid, String bizType,
                                           String requestId) {
        if (!selfCheckoutEnabled) {
            throw new IllegalStateException("自助开通暂未开放，请联系官方人工开通");
        }
        requireBizType(bizType);
        requireRequestId(requestId);
        OmsOrder replay = orderMapper.selectCommerceOrderByRequest(memberId, merchantId, bizType, requestId);
        if (replay != null) return paymentResponse(replay, openid);
        SettlementRule rule = settlementRuleService.getActiveRule(0L, bizType);
        if (rule == null || rule.getUnitPrice() == null || rule.getUnitPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("该权益尚未配置价格，请联系官方");
        }
        if (rule.getEntitlementMaxUsage() == null || rule.getEntitlementMaxUsage() <= 0
                || rule.getEntitlementDays() == null || rule.getEntitlementDays() <= 0) {
            throw new IllegalStateException("该权益尚未配置用量或有效期，请联系官方");
        }
        String orderSn = prefixOf(bizType) + UUID.randomUUID().toString().replace("-", "");
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
            return paymentResponse(existing, openid);
        }
        return paymentResponse(order, openid);
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
    public boolean handlePaidOrder(String outTradeNo, boolean paid, Integer paidAmountFen, String transactionId) {
        if (!paid || !supportsOutTradeNo(outTradeNo) || paidAmountFen == null
                || transactionId == null || transactionId.trim().isEmpty()) return false;
        OmsOrder order = orderMapper.selectOmsOrderByOrderSn(outTradeNo);
        if (order == null || !bizMatchesPrefix(order.getBizType(), outTradeNo)) return false;
        if (order.getPayAmount() == null
                || order.getPayAmount().movePointRight(2).intValueExact() != paidAmountFen) return false;
        if (Long.valueOf(1L).equals(order.getStatus())) {
            return transactionId.equals(order.getPaymentTransactionId());
        }
        if (orderMapper.markCommerceOrderPaid(order.getId(), transactionId) == 0) {
            OmsOrder persisted = orderMapper.selectOmsOrderByOrderSn(outTradeNo);
            return persisted != null && Long.valueOf(1L).equals(persisted.getStatus())
                    && transactionId.equals(persisted.getPaymentTransactionId());
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
        if (subscriptionMapper.grantMerchantSubscription(grant) != 1) {
            throw new IllegalStateException("商业化权益授予失败");
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reconcileOrder(String outTradeNo) {
        OmsOrder order = orderMapper.selectOmsOrderByOrderSn(outTradeNo);
        if (order == null || !Long.valueOf(0L).equals(order.getStatus())) return;
        requireBizType(order.getBizType());
        if (!supportsOutTradeNo(outTradeNo) || !bizMatchesPrefix(order.getBizType(), outTradeNo)) {
            throw new IllegalStateException("商业化订单类型与单号不一致");
        }
        Map<String, String> observed = wechatPayService.queryOrderStatus(outTradeNo);
        if (observed == null || !outTradeNo.equals(observed.get("out_trade_no"))) {
            throw new IllegalStateException("商业化查单结果无法确认");
        }
        String state = observed.get("trade_state");
        if ("SUCCESS".equals(state)) {
            if (!"CNY".equals(observed.get("currency")) || observed.get("total") == null
                    || !observed.get("total").matches("[0-9]{1,10}")) {
                throw new IllegalStateException("商业化支付金额或币种无法确认");
            }
            if (!handlePaidOrder(outTradeNo, true, Integer.valueOf(observed.get("total")),
                    observed.get("transaction_id"))) {
                throw new IllegalStateException("商业化已支付订单补记失败");
            }
        } else if ("CLOSED".equals(state) || "REVOKED".equals(state)) {
            orderMapper.markCommerceOrderClosed(order.getId());
        } else if (!"NOTPAY".equals(state) && !"USERPAYING".equals(state) && !"PAYERROR".equals(state)) {
            throw new IllegalStateException("商业化支付状态无法确认");
        }
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
}
