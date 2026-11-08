package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.domain.GiftEntitlement;
import com.chengyinhub.business.domain.MarketingProduct;
import com.chengyinhub.business.domain.MarketingProductItem;
import com.chengyinhub.business.domain.MarketingProductOrder;
import com.chengyinhub.business.domain.MarketingProductOrderLine;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UmsMemberPointsDetail;
import com.chengyinhub.business.domain.dto.MarketingExtraSeatPlan;
import com.chengyinhub.business.domain.dto.MarketingProductOrderRequest;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.exception.MarketingProductException;
import com.chengyinhub.business.mapper.GiftEntitlementMapper;
import com.chengyinhub.business.mapper.MarketingProductMapper;
import com.chengyinhub.business.mapper.MarketingProductOrderMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.mapper.UmsMemberPointsDetailMapper;
import com.chengyinhub.business.service.FulfillmentCommand;
import com.chengyinhub.business.service.FulfillmentOutcome;
import com.chengyinhub.business.service.GrantLine;
import com.chengyinhub.business.service.IGiftEntitlementService;
import com.chengyinhub.business.service.IMarketingExtraSeatService;
import com.chengyinhub.business.service.IMarketingProductFulfillmentService;
import com.chengyinhub.business.service.IMarketingProductPurchaseService;
import com.chengyinhub.business.service.PaymentFacts;
import com.chengyinhub.business.service.WechatPaymentOperations;
import com.chengyinhub.business.service.support.MarketingAllocation;
import com.chengyinhub.business.service.support.MarketingCashChannel;
import com.chengyinhub.business.service.support.MarketingClaimTokenCodec;
import com.chengyinhub.business.service.support.MarketingProductRules;
import com.chengyinhub.business.service.support.MarketingQuotePayload;
import com.chengyinhub.business.service.support.MarketingQuoteSigner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商品下单与来源记录。
 *
 * <p><b>不建第二可玩真源</b>：权益/席位一律由 M2-F 的
 * {@link IMarketingProductFulfillmentService} 在 {@code cms_registration} 上产出；
 * 本服务只扣真实积分/校验真实微信事实，并记录购买来源与父子映射。
 */
@Service
public class MarketingProductPurchaseServiceImpl implements IMarketingProductPurchaseService {

    private static final char[] UPPER = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    /** 积分购买流水类型，与既有 CmsRegistrationServiceImpl 的 eventType=99 同口径。 */
    private static final long POINTS_EVENT_PURCHASE = 99L;

    private final MarketingProductMapper productMapper;
    private final MarketingProductOrderMapper orderMapper;
    private final GiftEntitlementMapper giftMapper;
    private final UmsMemberMapper memberMapper;
    private final UmsMemberPointsDetailMapper pointsDetailMapper;
    private final MarketingProductRules rules;
    private final com.chengyinhub.business.service.support.PointsValuePolicy pointsValuePolicy;
    private final MarketingQuoteSigner signer;
    private final MarketingCashChannel cashChannel;
    private final IGiftEntitlementService giftService;
    private final IMarketingProductFulfillmentService fulfillment;
    private final IMarketingExtraSeatService extraSeatService;
    private final WechatVerifiedPaymentFactService verifiedFacts;
    private final WechatPaymentOperations wechatPayService;

    public MarketingProductPurchaseServiceImpl(MarketingProductMapper productMapper,
                                               MarketingProductOrderMapper orderMapper,
                                               GiftEntitlementMapper giftMapper,
                                               UmsMemberMapper memberMapper,
                                               UmsMemberPointsDetailMapper pointsDetailMapper,
                                               MarketingProductRules rules,
                                               com.chengyinhub.business.service.support.PointsValuePolicy pointsValuePolicy,
                                               MarketingQuoteSigner signer,
                                               MarketingCashChannel cashChannel,
                                               IGiftEntitlementService giftService,
                                               IMarketingProductFulfillmentService fulfillment,
                                               IMarketingExtraSeatService extraSeatService,
                                               WechatVerifiedPaymentFactService verifiedFacts,
                                               @org.springframework.beans.factory.annotation.Qualifier("wechatPaymentSdkGateway")
                                               WechatPaymentOperations wechatPayService) {
        this.productMapper = productMapper;
        this.orderMapper = orderMapper;
        this.giftMapper = giftMapper;
        this.memberMapper = memberMapper;
        this.pointsDetailMapper = pointsDetailMapper;
        this.rules = rules;
        this.pointsValuePolicy = pointsValuePolicy;
        this.signer = signer;
        this.cashChannel = cashChannel;
        this.giftService = giftService;
        this.fulfillment = fulfillment;
        this.extraSeatService = extraSeatService;
        this.verifiedFacts = verifiedFacts;
        this.wechatPayService = wechatPayService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> purchase(long callerMemberId, MarketingProductOrderRequest request) {
        requireMember(callerMemberId);
        validateRequest(request);
        MarketingProductOrder replay = orderMapper.selectByMemberRequest(callerMemberId, request.getRequestId());
        if (replay != null) {
            if (!fingerprint(callerMemberId, request).equals(replay.getRequestFingerprint())) {
                throw MarketingProductException.conflict("REQUEST_CONFLICT", "同一请求号提交了不同商品参数");
            }
            return orderView(replay);
        }

        MarketingProduct product = productMapper.selectByIdForUpdate(request.getProductId());
        if (product == null) throw MarketingProductException.notFound("PRODUCT_NOT_FOUND", "商品不存在");
        Date now = new Date();
        rules.requireSellable(product, now);
        if (product.usesTicketRegistration()) {
            // 团体/学校：本域不建单，真实报名/席位走既有 ticket 结算链路。
            MarketingProductException rejected = MarketingProductException.conflict(
                    "USE_REGISTRATION_CHECKOUT", "该商品需在报名结算中使用对应票种购买");
            java.util.Map<String, Object> routeData = new java.util.LinkedHashMap<>();
            routeData.put("checkout", rules.ticketCheckout(product,
                    request.getQuantity() == null ? 1 : request.getQuantity()));
            throw rejected.withData(routeData);
        }
        List<MarketingProductItem> items = rules.items(product.getId());

        int fulfillment = request.getFulfillmentKind() == null
                ? MarketingProductOrder.FULFILLMENT_SELF : request.getFulfillmentKind();
        int quantity = request.getQuantity() == null ? 1 : request.getQuantity();
        rules.requireQuantity(quantity);
        Long originalOrderId = null;
        MarketingExtraSeatPlan extraSeatPlan = null;
        if (fulfillment == MarketingProductOrder.FULFILLMENT_EXTRA_SEAT) {
            // 裁C：加座 = 自玩独立增购一份一人准入。全部前置在原单行锁内完成
            // （归属/已付/非礼品/父商品匹配/原 registration 属本人且自玩域/内容与原单冻结行一致/
            //  同一受赠人未重复加座）。原单金额与数量一律不改。
            extraSeatPlan = extraSeatService.requirePurchasePlan(callerMemberId, product, request, items);
            originalOrderId = extraSeatPlan.getOriginalOrderId();
        } else if (product.isExtraSeatRoute()
                || (product.getProductKind() != null
                && product.getProductKind() == MarketingProduct.KIND_EXTRA_SEAT)) {
            throw MarketingProductException.badRequest("FULFILLMENT_INVALID", "加座商品必须走加座履约");
        }
        // 加座受赠人参与签名：报价签一次，下单不能悄悄换成别人。
        Long recipient = extraSeatPlan == null ? null : extraSeatPlan.getRecipientMemberId();
        if (fulfillment == MarketingProductOrder.FULFILLMENT_GIFT) {
            if (!Integer.valueOf(1).equals(product.getGiftAllowed())) {
                throw MarketingProductException.badRequest("GIFT_NOT_ALLOWED", "该商品不支持赠送");
            }
            if (quantity != 1) throw MarketingProductException.badRequest("GIFT_QUANTITY_INVALID", "礼品一次只能送一份");
            recipient = request.getRecipientMemberId();
            if (recipient != null && recipient.longValue() == callerMemberId) {
                throw MarketingProductException.badRequest("GIFT_SELF_RECIPIENT", "不能把礼品送给自己");
            }
        }

        BigDecimal totalCash = MarketingQuotePayload.cashTotal(product.getCashPrice(), quantity);
        Integer totalPoints = MarketingQuotePayload.pointsTotal(product.getPointsPrice(), quantity);
        int payMode = request.getPayMode();
        if (payMode == MarketingProductOrder.PAY_MODE_POINTS && totalPoints == null) {
            throw MarketingProductException.badRequest("PAY_MODE_UNAVAILABLE", "该商品不支持积分支付");
        }
        if (payMode == MarketingProductOrder.PAY_MODE_CASH && totalCash == null) {
            throw MarketingProductException.badRequest("PAY_MODE_UNAVAILABLE", "该商品不支持现金支付");
        }

        long quoteExpiresAt = request.getQuoteExpiresAt() == null ? 0L : request.getQuoteExpiresAt();
        if (request.getQuoteSign() == null || quoteExpiresAt <= 0) {
            throw MarketingProductException.badRequest("QUOTE_REQUIRED", "请先获取服务端报价");
        }
        if (quoteExpiresAt < now.getTime()) {
            throw MarketingProductException.conflict("QUOTE_EXPIRED", "报价已过期，请重新获取");
        }
        String canonical = MarketingQuotePayload.of(callerMemberId, product, fulfillment, quantity,
                originalOrderId, recipient, totalCash, totalPoints,
                MarketingQuotePayload.seatTotal(product, quantity), items, quoteExpiresAt);
        if (!signer.matches(canonical, request.getQuoteSign())) {
            throw MarketingProductException.conflict("QUOTE_STALE", "商品价格或内容已变化，请重新报价");
        }

        MarketingProductOrder order = new MarketingProductOrder();
        order.setOrderNo(orderNo());
        order.setMemberId(callerMemberId);
        order.setProductId(product.getId());
        order.setProductRevision(product.getRevision());
        order.setFulfillmentKind(fulfillment);
        order.setGiftRecipientMemberId(recipient);
        order.setOriginalOrderId(originalOrderId);
        order.setPayMode(payMode);
        order.setQuantity(quantity);
        order.setUnitCashPrice(product.getCashPrice());
        order.setUnitPointsPrice(product.getPointsPrice());
        order.setTotalCashAmount(totalCash);
        order.setTotalPointsAmount(totalPoints);
        order.setCurrency("CNY");
        order.setProductSnapshotJson(snapshot(product, items, fulfillment, quantity));
        order.setQuoteSign(request.getQuoteSign());
        order.setQuoteExpiresAt(new Date(quoteExpiresAt));
        order.setStatus(MarketingProductOrder.STATUS_PENDING_CASH);
        order.setFulfillmentState(MarketingProductOrder.GRANT_PENDING);
        order.setRequestId(request.getRequestId());
        order.setRequestFingerprint(fingerprint(callerMemberId, request));
        order.setCreateTime(now);
        order.setUpdateTime(now);
        orderMapper.insert(order);

        String giftToken = null;
        if (payMode == MarketingProductOrder.PAY_MODE_POINTS) {
            payWithPoints(order, now);
            grantOrDefer(order, product, items, now, null);
            if (needsGiftSource(fulfillment)) {
                giftToken = giftService.issue(order, now);
            }
        }
        return orderView(orderMapper.selectByOrderNo(order.getOrderNo()), giftToken);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> settleVerifiedCashPayment(String orderNo, long factId, String factHash) {
        if (orderNo == null || orderNo.trim().isEmpty()) {
            throw MarketingProductException.badRequest("ORDER_NO_REQUIRED", "订单号不能为空");
        }
        MarketingProductOrder order = orderMapper.selectByOrderNoForUpdate(orderNo.trim());
        if (order == null) throw MarketingProductException.notFound("ORDER_NOT_FOUND", "订单不存在");
        if (Integer.valueOf(MarketingProductOrder.STATUS_PAID).equals(order.getStatus())
                || Integer.valueOf(MarketingProductOrder.STATUS_REFUNDED).equals(order.getStatus())) {
            if (Integer.valueOf(MarketingProductOrder.PAYMENT_FACT_WECHAT).equals(order.getPaymentFactType())
                    && order.getPaymentFactId() != null && order.getPaymentFactId() == factId) {
                return orderView(order);
            }
            throw MarketingProductException.conflict("PAYMENT_ALREADY_SETTLED", "订单已用另一支付事实结算");
        }
        if (!Integer.valueOf(MarketingProductOrder.STATUS_PENDING_CASH).equals(order.getStatus())) {
            throw MarketingProductException.conflict("ORDER_NOT_PAYABLE", "订单当前不可支付");
        }
        WechatVerifiedPaymentFactService.Stored stored;
        try {
            stored = verifiedFacts.requireVerified(new WechatVerifiedPaymentFact.Reference(factId, factHash));
        } catch (WechatVerifiedPaymentFactService.ConflictException conflict) {
            throw MarketingProductException.conflict("PAYMENT_FACT_UNVERIFIED", "支付事实校验失败");
        } catch (IllegalStateException unavailable) {
            throw MarketingProductException.conflict("PAYMENT_FACT_UNVERIFIED", "支付事实不可用");
        }
        WechatVerifiedPaymentFact facts = stored.facts();
        if (facts.kind() != WechatVerifiedPaymentFact.Kind.PAYMENT) {
            throw MarketingProductException.conflict("PAYMENT_FACT_KIND_INVALID", "不是支付成功事实");
        }
        if (!orderNo.trim().equals(facts.businessOutTradeNo())) {
            throw MarketingProductException.conflict("PAYMENT_FACT_ORDER_MISMATCH", "支付事实不属于该订单");
        }
        if (!"CNY".equals(facts.currency()) || order.getTotalCashAmount() == null) {
            throw MarketingProductException.conflict("PAYMENT_FACT_AMOUNT_MISMATCH", "支付币种或金额不符");
        }
        long expectedFen = order.getTotalCashAmount().movePointRight(2).longValueExact();
        if (facts.totalFen() != expectedFen || facts.amountFen() != expectedFen) {
            throw MarketingProductException.conflict("PAYMENT_FACT_AMOUNT_MISMATCH", "支付金额与订单不符");
        }
        Date now = new Date();
        if (orderMapper.markCashPaid(order.getId(), factId, now, now) != 1) {
            throw MarketingProductException.conflict("PAYMENT_ALREADY_SETTLED", "支付事实已被使用");
        }
        MarketingProductOrder paid = orderMapper.selectByOrderNo(orderNo.trim());
        MarketingProduct product = productMapper.selectById(paid.getProductId());
        if (product == null) throw MarketingProductException.conflict("PRODUCT_MISSING", "商品不存在");
        String giftToken = null;
        grantOrDefer(paid, product, rules.items(product.getId()), now, factHash);
        if (needsGiftSource(paid.getFulfillmentKind())) {
            giftToken = giftService.issue(paid, now);
        }
        return orderView(orderMapper.selectByOrderNo(orderNo.trim()), giftToken);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean handlePaidOrder(String orderNo, boolean paid, Integer paidAmountFen,
                                   String transactionId, WechatVerifiedPaymentFact.Reference reference) {
        if (!paid || orderNo == null || orderNo.trim().isEmpty() || reference == null) return false;
        MarketingProductOrder order = orderMapper.selectByOrderNo(orderNo.trim());
        if (order == null) return false;
        if (order.getTotalCashAmount() == null || paidAmountFen == null) return false;
        long expectedFen;
        try {
            expectedFen = order.getTotalCashAmount().movePointRight(2).longValueExact();
        } catch (ArithmeticException invalidAmount) {
            return false;
        }
        if (paidAmountFen.longValue() != expectedFen) return false;
        // 异常必须向上抛：同事务回滚，原回调不 ACK 成功，由微信重投/原 inbox 保留失败态。
        // 这里吞掉异常会让已写入的半成品随外层事务提交（self-invocation 不新开事务）。
        settleVerifiedCashPayment(orderNo.trim(), reference.id(), reference.hash());
        return true;
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER)
    public Map<String, Object> prepareCashPayment(long callerMemberId, String orderNo, String payChannel) {
        requireMember(callerMemberId);
        if (!cashChannel.enabled()) {
            throw MarketingProductException.conflict(cashChannel.unavailableReason(),
                    "现金支付通道尚未接通");
        }
        if (orderNo == null || orderNo.trim().isEmpty()) {
            throw MarketingProductException.badRequest("ORDER_NO_REQUIRED", "订单号不能为空");
        }
        MarketingProductOrder order = orderMapper.selectByOrderNo(orderNo.trim());
        if (order == null || !Long.valueOf(callerMemberId).equals(order.getMemberId())) {
            throw MarketingProductException.notFound("ORDER_NOT_FOUND", "订单不存在");
        }
        if (!Integer.valueOf(MarketingProductOrder.STATUS_PENDING_CASH).equals(order.getStatus())
                || !Integer.valueOf(MarketingProductOrder.PAY_MODE_CASH).equals(order.getPayMode())) {
            throw MarketingProductException.conflict("ORDER_NOT_PAYABLE", "订单当前不可支付");
        }
        if (!wechatPayService.isReady()) {
            throw MarketingProductException.paymentRequired("PAYMENT_UNAVAILABLE", "支付服务暂不可用");
        }
        boolean appChannel = payChannel != null && "APP".equalsIgnoreCase(payChannel.trim());
        Map<String, String> payParams = appChannel
                ? wechatPayService.createGenericAppOrder(order.getOrderNo(), order.getTotalCashAmount(), "城瘾商品")
                : wechatPayService.createGenericOrder(order.getOrderNo(), order.getTotalCashAmount(),
                        "城瘾商品", requireWxOpenId(callerMemberId));
        Map<String, Object> out = orderView(order);
        out.put("payParams", payParams);
        return out;
    }

    private String requireWxOpenId(long memberId) {
        UmsMember member = memberMapper.selectUmsMemberById(memberId);
        if (member == null || member.getWxOpenId() == null || member.getWxOpenId().trim().isEmpty()) {
            throw MarketingProductException.paymentRequired("OPENID_MISSING", "当前账号缺少微信支付标识");
        }
        return member.getWxOpenId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> cancel(long callerMemberId, String orderNo) {
        requireMember(callerMemberId);
        MarketingProductOrder order = lockOwnedOrder(callerMemberId, orderNo);
        if (!Integer.valueOf(MarketingProductOrder.STATUS_PENDING_CASH).equals(order.getStatus())) {
            throw MarketingProductException.conflict("ORDER_NOT_CANCELLABLE", "只有待支付订单可取消");
        }
        if (orderMapper.markCancelled(order.getId(), new Date()) != 1) {
            throw MarketingProductException.conflict("ORDER_STATE_CHANGED", "订单状态已变化");
        }
        return orderView(orderMapper.selectByOrderNo(order.getOrderNo()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> revokeAfterRefund(String orderNo, String reason) {
        if (orderNo == null || orderNo.trim().isEmpty()) {
            throw MarketingProductException.badRequest("ORDER_NO_REQUIRED", "订单号不能为空");
        }
        MarketingProductOrder order = orderMapper.selectByOrderNoForUpdate(orderNo.trim());
        if (order == null) throw MarketingProductException.notFound("ORDER_NOT_FOUND", "订单不存在");
        if (Integer.valueOf(MarketingProductOrder.STATUS_REFUNDED).equals(order.getStatus())) {
            return orderView(order);
        }
        if (!Integer.valueOf(MarketingProductOrder.STATUS_PAID).equals(order.getStatus())) {
            throw MarketingProductException.conflict("ORDER_NOT_REFUNDABLE", "只有已支付订单可撤销");
        }
        Date now = new Date();
        String safeReason = reason == null ? "REFUND" : reason;
        if (Integer.valueOf(MarketingProductOrder.FULFILLMENT_GIFT).equals(order.getFulfillmentKind())) {
            giftService.revokeForOrder(order, safeReason, now);
        }
        if (orderMapper.markRefunded(order.getId(), safeReason, now, now) != 1) {
            throw MarketingProductException.conflict("ORDER_STATE_CHANGED", "订单状态已变化");
        }
        // 原 registration 的退款撤销/积分返还在原 C07 + 原注册/积分入口完成；
        // 本域不另建退款状态、不另建 event_type，避免第二资金真源。
        return orderView(orderMapper.selectByOrderNo(order.getOrderNo()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> myOrders(long callerMemberId, int limit) {
        requireMember(callerMemberId);
        List<MarketingProductOrder> orders = orderMapper.selectByMember(
                callerMemberId, limit <= 0 ? 20 : Math.min(limit, 100));
        List<Map<String, Object>> out = new ArrayList<>();
        if (orders == null) return out;
        for (MarketingProductOrder order : orders) out.add(orderView(order));
        return out;
    }

    @Override
    public Map<String, Object> activateOnUse(long callerMemberId, long topicId) {
        requireMember(callerMemberId);
        MarketingProductOrder pending = orderMapper.selectPendingActivation(callerMemberId, topicId);
        if (pending == null) {
            throw MarketingProductException.notFound("NO_PENDING_ACTIVATION", "没有待激活的通行证");
        }
        FulfillmentOutcome outcome = fulfillment.activateOnFirstUse(
                pending.getRequestId(), callerMemberId, topicId, new Date());
        return activationView(pending, outcome);
    }

    // ------------------------------------------------------------------ grant

    private void grantOrDefer(MarketingProductOrder order, MarketingProduct product,
                              List<MarketingProductItem> items, Date now, String cashFactHash) {
        // 加座同样是"买家名下的一行 + 礼品来源"，领取后由加入者持有；买家不再是可玩人。
        // 不建第二权益表、不改原单数量、不涉及 PlayTeam/party 绑定。
        BigDecimal total = Integer.valueOf(MarketingProductOrder.PAY_MODE_POINTS).equals(order.getPayMode())
                ? pointsAsCash(order) : order.getTotalCashAmount();
        List<GrantLine> lines = persistFrozenLines(order, product, items, total, now);

        FulfillmentCommand command = new FulfillmentCommand();
        command.requestId = order.getRequestId();
        command.requestFingerprint = order.getRequestFingerprint();
        command.parentOrderNo = order.getOrderNo();
        command.memberId = order.getMemberId();
        command.lines = lines;
        if (Integer.valueOf(MarketingProductOrder.PAY_MODE_POINTS).equals(order.getPayMode())) {
            command.payment = PaymentFacts.points(order.getTotalPointsAmount() == null
                    ? 0L : order.getTotalPointsAmount().longValue());
        } else {
            if (cashFactHash == null || cashFactHash.length() != 64) {
                throw MarketingProductException.conflict("PAYMENT_FACT_UNVERIFIED",
                        "现金成交缺少已验证支付事实哈希");
            }
            command.payment = PaymentFacts.cash(
                    order.getPaymentFactId() == null ? 0L : order.getPaymentFactId(),
                    cashFactHash, order.getTotalCashAmount(), null);
        }
        FulfillmentOutcome outcome = fulfillment.grant(command);
        if (outcome != null) {
            boolean pending = outcome.pendingActivation;
            Long primaryId = outcome.registrationIds.isEmpty() ? null : outcome.registrationIds.get(0);
            String idsJson = outcome.registrationIds.isEmpty()
                    ? null : JSON.toJSONString(outcome.registrationIds);
            orderMapper.bindRegistrations(order.getId(), primaryId, null, idsJson, now);
            // 回填内存态：礼品签发需要 sourceRegistrationId（买家已付行）。
            order.setPrimaryRegistrationId(primaryId);
            order.setRegistrationIdsJson(idsJson);
            markGrantState(order.getId(),
                    pending ? MarketingProductOrder.GRANT_AWAITING_ACTIVATION
                            : MarketingProductOrder.GRANT_DONE, now);
        }
    }

    /** 成交时冻结分摊行（首次即写；重放读同一份，不按当前价格重算）。 */
    private List<GrantLine> persistFrozenLines(MarketingProductOrder order, MarketingProduct product,
                                               List<MarketingProductItem> items, BigDecimal total, Date now) {
        List<MarketingProductOrderLine> stored = orderMapper.selectFrozenLines(order.getOrderNo());
        if (stored != null && !stored.isEmpty()) return toGrantLines(stored);

        List<BigDecimal> listPrices = rules.singlePrices(items);
        List<GrantLine> lines = MarketingAllocation.lines(product, items, listPrices, total);
        int sort = 0;
        for (GrantLine line : lines) {
            MarketingProductOrderLine row = new MarketingProductOrderLine();
            row.setOrderId(order.getId());
            row.setTopicId(line.topicId);
            row.setListAmount(line.listAmount);
            row.setPayableAmount(line.payableAmount);
            row.setValidityKind(line.validityKind);
            row.setValidDays(line.validDays);
            row.setFixedValidFrom(line.fixedValidFrom);
            row.setFixedValidTo(line.fixedValidTo);
            row.setExploreCapacity(line.exploreCapacity ? 1 : 0);
            row.setSortNo(sort++);
            row.setCreateTime(now);
            orderMapper.insertFrozenLine(row);
        }
        return lines;
    }

    private List<GrantLine> toGrantLines(List<MarketingProductOrderLine> stored) {
        List<GrantLine> lines = new ArrayList<>(stored.size());
        for (MarketingProductOrderLine row : stored) {
            GrantLine line = new GrantLine();
            line.topicId = row.getTopicId();
            line.listAmount = row.getListAmount();
            line.payableAmount = row.getPayableAmount();
            line.validityKind = row.getValidityKind();
            line.validDays = row.getValidDays() == null ? 0 : row.getValidDays();
            line.fixedValidFrom = row.getFixedValidFrom();
            line.fixedValidTo = row.getFixedValidTo();
            line.exploreCapacity = Integer.valueOf(1).equals(row.getExploreCapacity());
            lines.add(line);
        }
        return lines;
    }

    /** 礼品与加座都靠"一次领取转移同一行"；其余履约不签发礼品来源。 */
    private boolean needsGiftSource(Integer fulfillmentKind) {
        return Integer.valueOf(MarketingProductOrder.FULFILLMENT_GIFT).equals(fulfillmentKind)
                || Integer.valueOf(MarketingProductOrder.FULFILLMENT_EXTRA_SEAT).equals(fulfillmentKind);
    }

    /**
     * 积分成交的金额锚：与 M2-F {@code verifiedPayableAmount} 完全同源。
     * 有现金标价的商品用订单现金总额；纯积分商品用 {@link PointsValuePolicy#yuanOf(long)}。
     */
    private BigDecimal pointsAsCash(MarketingProductOrder order) {
        if (order.getTotalCashAmount() != null && order.getTotalCashAmount().signum() > 0) {
            return order.getTotalCashAmount();
        }
        long points = order.getTotalPointsAmount() == null ? 0L : order.getTotalPointsAmount().longValue();
        return pointsValuePolicy.yuanOf(points);
    }

    private void markGrantState(Long orderId, int state, Date now) {
        orderMapper.markGrantState(orderId, state, now);
    }

    // ------------------------------------------------------------------ payment

    private void payWithPoints(MarketingProductOrder order, Date now) {
        int points = order.getTotalPointsAmount() == null ? 0 : order.getTotalPointsAmount();
        if (points <= 0) throw MarketingProductException.badRequest("POINTS_AMOUNT_INVALID", "积分金额不合法");
        UmsMember member = memberMapper.selectUmsMemberById(order.getMemberId());
        long current = member == null || member.getPoint() == null ? 0L : member.getPoint();
        if (current < points) throw MarketingProductException.paymentRequired("POINTS_INSUFFICIENT", "积分不足");
        if (memberMapper.deductPoints(order.getMemberId(), points) != 1) {
            throw MarketingProductException.conflict("POINTS_CONFLICT", "积分不足，请重试");
        }
        UmsMemberPointsDetail detail = new UmsMemberPointsDetail();
        detail.setMemberId(order.getMemberId());
        detail.setCurrentPoints(current);
        detail.setAfterPoints(current - points);
        detail.setChangePoints((long) points);
        detail.setChangeReason("商品购买");
        detail.setEventType(POINTS_EVENT_PURCHASE);
        detail.setEventId(order.getId());
        detail.setChangeType(2L);
        detail.setDelFlag(0);
        detail.setCreateTime(now);
        pointsDetailMapper.insertUmsMemberPointsDetail(detail);
        if (orderMapper.markPointsPaid(order.getId(), detail.getId(), now, now) != 1) {
            throw new IllegalStateException("积分支付状态回写失败");
        }
        order.setStatus(MarketingProductOrder.STATUS_PAID);
        order.setPaymentFactType(MarketingProductOrder.PAYMENT_FACT_POINTS);
        order.setPaymentFactId(detail.getId());
        order.setPaidAt(now);
    }

    // ------------------------------------------------------------------ views

    private Map<String, Object> orderView(MarketingProductOrder order) {
        return orderView(order, null);
    }

    private Map<String, Object> orderView(MarketingProductOrder order, String giftTokenOnce) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderNo", order.getOrderNo());
        out.put("status", order.getStatus());
        out.put("statusLabel", statusLabel(order.getStatus()));
        out.put("productId", order.getProductId());
        out.put("productRevision", order.getProductRevision());
        out.put("fulfillmentKind", order.getFulfillmentKind());
        out.put("fulfillmentState", order.getFulfillmentState());
        out.put("payMode", order.getPayMode());
        out.put("quantity", order.getQuantity());
        out.put("cashAmount", order.getTotalCashAmount());
        out.put("pointsAmount", order.getTotalPointsAmount());
        out.put("currency", order.getCurrency());
        out.put("paymentFactType", order.getPaymentFactType());
        out.put("paymentFactId", order.getPaymentFactId());
        out.put("primaryRegistrationId", order.getPrimaryRegistrationId());
        out.put("primaryRegistrationNo", order.getPrimaryRegistrationNo());
        out.put("registrationIds", parseRegistrationIds(order.getRegistrationIdsJson()));
        out.put("paidAt", order.getPaidAt());
        out.put("refundedAt", order.getRefundedAt());
        out.put("quoteExpiresAt", order.getQuoteExpiresAt());
        out.put("createdAt", order.getCreateTime());
        out.put("claimTokenIssued", giftTokenOnce != null);

        Map<String, Object> payment = new LinkedHashMap<>();
        if (Integer.valueOf(MarketingProductOrder.PAY_MODE_CASH).equals(order.getPayMode())) {
            payment.put("channel", "WECHAT");
            payment.put("outTradeNo", order.getOrderNo());
            payment.put("required", Integer.valueOf(MarketingProductOrder.STATUS_PENDING_CASH)
                    .equals(order.getStatus()));
            payment.put("blockedReason", cashChannel.enabled() ? null : cashChannel.unavailableReason());
        } else {
            payment.put("channel", "POINTS");
            payment.put("paid", Integer.valueOf(MarketingProductOrder.STATUS_PAID).equals(order.getStatus()));
        }
        out.put("payment", payment);

        GiftEntitlement gift = giftMapper.selectByOrderId(order.getId());
        if (gift != null || giftTokenOnce != null) {
            Map<String, Object> giftView = new LinkedHashMap<>();
            if (gift != null) {
                giftView.put("giftId", gift.getId());
                giftView.put("publicId", gift.getPublicId());
                giftView.put("status", gift.getStatus());
                giftView.put("recipientMemberId", gift.getRecipientMemberId());
                giftView.put("claimedMemberId", gift.getClaimedMemberId());
                giftView.put("claimedRegistrationId", gift.getClaimedRegistrationId());
                giftView.put("claimExpiresAt", gift.getClaimExpiresAt());
            }
            if (giftTokenOnce != null) giftView.put("claimToken", giftTokenOnce);
            out.put("gift", giftView);
        }
        return out;
    }

    private Map<String, Object> activationView(MarketingProductOrder order, FulfillmentOutcome outcome) {
        Map<String, Object> out = orderView(order);
        if (outcome != null) {
            out.put("activationRegistrations", outcome.registrationIds);
            out.put("replayed", outcome.replayed);
            out.put("pendingActivation", outcome.pendingActivation);
        }
        return out;
    }

    private String statusLabel(Integer status) {
        if (status == null) return "UNKNOWN";
        return switch (status) {
            case MarketingProductOrder.STATUS_PENDING_CASH -> "PENDING_CASH";
            case MarketingProductOrder.STATUS_PAID -> "PAID";
            case MarketingProductOrder.STATUS_CANCELLED -> "CANCELLED";
            case MarketingProductOrder.STATUS_REFUNDED -> "REFUNDED";
            default -> "UNKNOWN";
        };
    }

    private List<Long> parseRegistrationIds(String json) {
        if (json == null || json.trim().isEmpty()) return new ArrayList<>();
        try {
            return JSON.parseArray(json, Long.class);
        } catch (RuntimeException malformed) {
            return new ArrayList<>();
        }
    }

    // ------------------------------------------------------------------ input helpers

    private MarketingProductOrder lockOwnedOrder(long memberId, String orderNo) {
        if (orderNo == null || orderNo.trim().isEmpty()) {
            throw MarketingProductException.badRequest("ORDER_NO_REQUIRED", "订单号不能为空");
        }
        MarketingProductOrder order = orderMapper.selectByOrderNoForUpdate(orderNo.trim());
        if (order == null || !Long.valueOf(memberId).equals(order.getMemberId())) {
            throw MarketingProductException.notFound("ORDER_NOT_FOUND", "订单不存在");
        }
        return order;
    }

    private void validateRequest(MarketingProductOrderRequest request) {
        if (request == null || request.getProductId() == null) {
            throw MarketingProductException.badRequest("PRODUCT_REQUIRED", "请选择商品");
        }
        if (request.getPayMode() == null
                || (request.getPayMode() != MarketingProductOrder.PAY_MODE_CASH
                && request.getPayMode() != MarketingProductOrder.PAY_MODE_POINTS)) {
            throw MarketingProductException.badRequest("PAY_MODE_INVALID", "支付方式不合法");
        }
        if (request.getRequestId() == null
                || !request.getRequestId().matches("[A-Za-z0-9_-]{1,64}")) {
            throw MarketingProductException.badRequest("REQUEST_ID_INVALID", "请求号不合法");
        }
    }

    private String fingerprint(long memberId, MarketingProductOrderRequest request) {
        int fulfillment = request.getFulfillmentKind() == null
                ? MarketingProductOrder.FULFILLMENT_SELF : request.getFulfillmentKind();
        int quantity = request.getQuantity() == null ? 1 : request.getQuantity();
        return MarketingClaimTokenCodec.sha256Hex(MarketingQuoteSigner.canonical(
                String.valueOf(memberId),
                String.valueOf(request.getProductId()),
                String.valueOf(fulfillment),
                String.valueOf(quantity),
                request.getOriginalOrderNo() == null ? null : request.getOriginalOrderNo().trim(),
                request.getRecipientMemberId() == null ? null : String.valueOf(request.getRecipientMemberId()),
                String.valueOf(request.getPayMode()),
                request.getQuoteExpiresAt() == null ? null : String.valueOf(request.getQuoteExpiresAt())));
    }

    private String snapshot(MarketingProduct product, List<MarketingProductItem> items,
                            int fulfillment, int quantity) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("productId", product.getId());
        snapshot.put("productCode", product.getProductCode());
        snapshot.put("productName", product.getProductName());
        snapshot.put("productKind", product.getProductKind());
        snapshot.put("purchaseKind", product.getPurchaseKind());
        snapshot.put("exploreCapacity", product.getExploreCapacity());
        snapshot.put("revision", product.getRevision());
        snapshot.put("termsVersion", product.getTermsVersion());
        snapshot.put("validityKind", product.getValidityKind());
        snapshot.put("validDays", product.getValidDays());
        snapshot.put("fixedValidFrom", product.getFixedValidFrom());
        snapshot.put("fixedValidTo", product.getFixedValidTo());
        snapshot.put("fulfillmentKind", fulfillment);
        snapshot.put("quantity", quantity);
        // M2-F5：成交时冻结**每份**席位数。M2 的准入行用它 × 已付 quantity 决定 order_num，
        // 缺这个键就 fail-closed 报 SHAPE_SNAPSHOT_MISSING（不回落到 live SKU、不按 1 兜底）。
        // 这里写每份值，不能再乘 quantity —— 否则 2 份 × 2 席会变成 8 个名额。
        snapshot.put("seatCount", product.getSeatCount() == null ? 1 : product.getSeatCount());
        List<Map<String, Object>> itemViews = new ArrayList<>();
        for (MarketingProductItem item : items) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("topicId", item.getTopicId());
            view.put("topicTitle", item.getTopicTitle());
            view.put("contentRevision", item.getContentRevision());
            itemViews.add(view);
        }
        snapshot.put("items", itemViews);
        return JSON.toJSONString(snapshot);
    }

    private String orderNo() {
        return "MKO" + System.currentTimeMillis() + random(6);
    }

    private static String random(int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) out.append(UPPER[RANDOM.nextInt(UPPER.length)]);
        return out.toString();
    }

    private void requireMember(long memberId) {
        if (memberId <= 0) throw MarketingProductException.unauthorized();
    }
}
