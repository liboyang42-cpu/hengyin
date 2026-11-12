package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.GiftEntitlement;
import com.chengyinhub.business.domain.MarketingProduct;
import com.chengyinhub.business.domain.MarketingProductItem;
import com.chengyinhub.business.domain.MarketingProductOrder;
import com.chengyinhub.business.domain.MarketingProductOrderLine;
import com.chengyinhub.business.domain.dto.MarketingExtraSeatOrderRow;
import com.chengyinhub.business.domain.dto.MarketingExtraSeatPlan;
import com.chengyinhub.business.domain.dto.MarketingExtraSeatQuote;
import com.chengyinhub.business.domain.dto.MarketingExtraSeatQuoteRequest;
import com.chengyinhub.business.domain.dto.MarketingProductOrderRequest;
import com.chengyinhub.business.exception.MarketingProductException;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.MarketingExtraSeatMapper;
import com.chengyinhub.business.mapper.MarketingProductOrderMapper;
import com.chengyinhub.business.service.IMarketingExtraSeatService;
import com.chengyinhub.business.service.support.MarketingCashChannel;
import com.chengyinhub.business.service.support.MarketingProductRules;
import com.chengyinhub.business.service.support.MarketingQuotePayload;
import com.chengyinhub.business.service.support.MarketingQuoteSigner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 加座 route3 的准入前置与读面。**不做任何资金动作**：积分扣减、微信验证事实、
 * 原 grant 与礼品签发都在 {@code MarketingProductPurchaseServiceImpl} 的单一成交路径里，
 * 本层只回答"这一位能不能卖、卖给谁"。
 *
 * <p>三条不可退让的边界：
 * <ol>
 *   <li>原单事实只读：本层不改 {@code cms_registration} / {@code marketing_product_order}
 *       的金额、数量、支付事实与归属，也不建平行席位账本；</li>
 *   <li>归属由服务端核对：原单必须属于调用者、原 registration 也必须属于他；客户端传别人的
 *       订单号只会拿到 403/409，不会被"加到别人队里"；</li>
 *   <li>重复加座在原单行锁内判：{@link #requirePurchasePlan} 先
 *       {@code selectByOrderNoForUpdate} 再查该受赠人是否已在本原单加过座。</li>
 * </ol>
 *
 * <p><b>本层没有容量概念</b>：自玩增购不存在"场次座位"这种资源，也读不到任何既有容量配置，
 * 所以 {@code partyCapacity}/{@code seatsRemaining} 恒为 null、{@code partyBinding} 恒为
 * {@code NOT_APPLICABLE}，不做"末位竞争"判定。
 */
@Service
public class MarketingExtraSeatServiceImpl implements IMarketingExtraSeatService {

    /** 与 IMarketingProductCatalogService 同一报价 TTL（10 分钟）；两处必须是同一个数。 */
    private static final long QUOTE_TTL_MILLIS = TimeUnit.MINUTES.toMillis(10);

    /** 一次最多回读多少条"我的加座"。 */
    private static final int MAX_READ_LIMIT = 50;

    /** 自玩域没有可加入的队伍/席位名册：这一位只是独立的第二份准入。 */
    static final String PARTY_BINDING = "NOT_APPLICABLE";
    static final String PARTY_BINDING_REASON = "SELF_PLAY_HAS_NO_TEAM_ROSTER";
    static final String ADMISSION_MODE = "GIFT_SINGLE_SOURCE";

    private final MarketingProductRules rules;
    private final MarketingProductOrderMapper orders;
    private final MarketingExtraSeatMapper extraSeats;
    private final CmsRegistrationMapper registrations;
    private final MarketingQuoteSigner signer;
    private final MarketingCashChannel cashChannel;

    public MarketingExtraSeatServiceImpl(MarketingProductRules rules,
                                         MarketingProductOrderMapper orders,
                                         MarketingExtraSeatMapper extraSeats,
                                         CmsRegistrationMapper registrations,
                                         MarketingQuoteSigner signer,
                                         MarketingCashChannel cashChannel) {
        this.rules = rules;
        this.orders = orders;
        this.extraSeats = extraSeats;
        this.registrations = registrations;
        this.signer = signer;
        this.cashChannel = cashChannel;
    }

    @Override
    @Transactional(readOnly = true)
    public MarketingExtraSeatQuote quote(long callerMemberId, MarketingExtraSeatQuoteRequest request) {
        requireMember(callerMemberId);
        if (request == null || request.getProductId() == null) {
            throw MarketingProductException.badRequest("PRODUCT_REQUIRED", "请选择加座商品");
        }
        MarketingProduct product = rules.requireProduct(request.getProductId());
        Date now = new Date();
        rules.requireSellable(product, now);
        List<MarketingProductItem> items = rules.items(product.getId());

        MarketingExtraSeatPlan plan = plan(callerMemberId, product, request.getOriginalOrderNo(),
                request.getRecipientMemberId(), items, false);

        BigDecimal totalCash = MarketingQuotePayload.cashTotal(product.getCashPrice(), 1);
        Integer totalPoints = MarketingQuotePayload.pointsTotal(product.getPointsPrice(), 1);
        int seatTotal = MarketingQuotePayload.seatTotal(product, 1);
        long expiresAt = now.getTime() + QUOTE_TTL_MILLIS;

        MarketingExtraSeatQuote result = new MarketingExtraSeatQuote();
        result.setProductId(product.getId());
        result.setProductCode(product.getProductCode());
        result.setProductName(product.getProductName());
        result.setProductKind(product.getProductKind());
        result.setProductRevision(product.getRevision());
        result.setFulfillmentKind(MarketingProductOrder.FULFILLMENT_EXTRA_SEAT);
        result.setQuantity(1);
        result.setUnitCashPrice(product.getCashPrice());
        result.setUnitPointsPrice(product.getPointsPrice());
        result.setTotalCashAmount(totalCash);
        result.setTotalPointsAmount(totalPoints);
        result.setCurrency("CNY");
        result.setSeatCount(seatTotal);
        result.setTermsVersion(product.getTermsVersion());
        result.setOriginalOrderNo(plan.getOriginalOrderNo());
        result.setOriginalPaidSeats(plan.getOriginalPaidSeats());
        // 恒为 null：自玩增购没有可读容量（见类注释），客户端不得据此置灰。
        result.setPartyCapacity(plan.getPartyCapacity());
        result.setSeatsRemaining(plan.getSeatsRemaining());
        result.setRecipientMemberId(plan.getRecipientMemberId());
        result.setPartyBinding(plan.getPartyBinding());
        result.setPartyBindingReason(plan.getPartyBindingReason());
        result.setAdmissionMode(plan.getAdmissionMode());
        result.setAdmissionRequiresClaim(Boolean.TRUE);
        result.setQuoteExpiresAt(new Date(expiresAt));

        List<Integer> payModes = new ArrayList<>();
        if (product.getPointsPrice() != null) payModes.add(MarketingProductOrder.PAY_MODE_POINTS);
        if (product.getCashPrice() != null) {
            if (cashChannel.enabled()) payModes.add(MarketingProductOrder.PAY_MODE_CASH);
            else result.setCashUnavailableReason(cashChannel.unavailableReason());
        }
        result.setAvailablePayModes(payModes);

        result.setQuoteSign(signer.sign(canonical(callerMemberId, product, plan.getOriginalOrderId(),
                plan.getRecipientMemberId(), totalCash, totalPoints, seatTotal, items, expiresAt)));
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MarketingExtraSeatPlan requirePurchasePlan(long callerMemberId, MarketingProduct product,
                                                      MarketingProductOrderRequest request,
                                                      List<MarketingProductItem> items) {
        requireMember(callerMemberId);
        if (request == null) throw MarketingProductException.badRequest("PRODUCT_REQUIRED", "请选择加座商品");
        if (request.getProductId() == null || product == null || !request.getProductId().equals(product.getId())) {
            throw MarketingProductException.badRequest("PRODUCT_REQUIRED", "请选择加座商品");
        }
        int quantity = request.getQuantity() == null ? 1 : request.getQuantity();
        if (quantity != 1) {
            throw MarketingProductException.badRequest("EXTRA_SEAT_ONE_PER_ORDER",
                    "一份加座只对应一位；多人请分别下单");
        }
        return plan(callerMemberId, product, request.getOriginalOrderNo(),
                request.getRecipientMemberId(), items, true);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> myExtraSeats(long callerMemberId, int limit) {
        requireMember(callerMemberId);
        int size = limit <= 0 ? 20 : Math.min(limit, MAX_READ_LIMIT);
        List<MarketingExtraSeatOrderRow> rows = extraSeats.selectExtraSeatsByMember(callerMemberId, size);
        List<Map<String, Object>> out = new ArrayList<>();
        if (rows == null) return out;
        for (MarketingExtraSeatOrderRow row : rows) out.add(extraSeatView(row));
        return out;
    }

    // ------------------------------------------------------------------ plan

    /**
     * 加座前置。{@code lockOriginal=true} 时先锁原单行再判重复：
     * "同一受赠人重复加座"与并发下单在同一把锁上串行，只可能有一个成功。
     */
    private MarketingExtraSeatPlan plan(long callerMemberId, MarketingProduct product, String originalOrderNo,
                                        Long recipientMemberId, List<MarketingProductItem> items,
                                        boolean lockOriginal) {
        requireExtraSeatProduct(product);
        requireEagerGrantProduct(product);
        // 复用 M3 的唯一原单校验：本人 / 已付 / 非礼品 / 父商品匹配 / 已有原准入。
        rules.requireAddOnBase(callerMemberId, product, originalOrderNo);

        String orderNo = originalOrderNo.trim();
        MarketingProductOrder original = lockOriginal
                ? orders.selectByOrderNoForUpdate(orderNo) : orders.selectByOrderNo(orderNo);
        requireLockedOriginal(callerMemberId, product, original);

        CmsRegistration source = registrations.selectCmsRegistrationById(original.getPrimaryRegistrationId());
        requireSelfPlaySource(callerMemberId, source);

        if (recipientMemberId == null || recipientMemberId <= 0) {
            throw MarketingProductException.badRequest("EXTRA_SEAT_RECIPIENT_REQUIRED",
                    "请指定这一位加座给谁");
        }
        if (recipientMemberId.longValue() == callerMemberId) {
            throw MarketingProductException.badRequest("EXTRA_SEAT_SELF_RECIPIENT",
                    "加座是第二位玩家的独立准入，不能给自己再买一份");
        }
        // 加座件数不设上限（自玩增购没有座位资源）；只拦"同一位在同一原单重复加座"，
        // 否则会给同一个受赠人产出两份准入。
        if (extraSeats.countPaidExtraSeatForRecipient(original.getId(), recipientMemberId) > 0) {
            throw MarketingProductException.conflict("EXTRA_SEAT_RECIPIENT_ALREADY_BOUND",
                    "这位玩家在该原订单上已有一份加座");
        }

        int paidSeats = source.getOrderNum() == null ? 1 : source.getOrderNum().intValue();
        if (paidSeats <= 0) {
            throw MarketingProductException.conflict("EXTRA_SEAT_ORIGINAL_SEATS_INVALID",
                    "原订单没有已付席位，不能加座");
        }
        List<Long> topicIds = requireCoverageMatchesOriginal(original, items);

        MarketingExtraSeatPlan plan = new MarketingExtraSeatPlan();
        plan.setOriginalOrderId(original.getId());
        plan.setOriginalOrderNo(original.getOrderNo());
        plan.setRecipientMemberId(recipientMemberId);
        plan.setTopicIds(topicIds);
        plan.setOriginalPaidSeats(paidSeats);
        plan.setPartyCapacity(null);
        plan.setSeatsRemaining(null);
        plan.setPartyBinding(PARTY_BINDING);
        plan.setPartyBindingReason(PARTY_BINDING_REASON);
        plan.setAdmissionMode(ADMISSION_MODE);
        return plan;
    }

    /** 原单在加锁后重验一次：锁前锁后任何归属/状态变化都不允许继续。 */
    private void requireLockedOriginal(long callerMemberId, MarketingProduct product,
                                       MarketingProductOrder original) {
        if (original == null || !Long.valueOf(callerMemberId).equals(original.getMemberId())) {
            throw MarketingProductException.forbidden("ORIGINAL_ORDER_FORBIDDEN", "原订单不属于你");
        }
        if (!Integer.valueOf(MarketingProductOrder.STATUS_PAID).equals(original.getStatus())) {
            throw MarketingProductException.conflict("ORIGINAL_ORDER_NOT_PAID", "原订单尚未支付");
        }
        if (!Integer.valueOf(MarketingProductOrder.FULFILLMENT_SELF).equals(original.getFulfillmentKind())) {
            throw MarketingProductException.conflict("ORIGINAL_ORDER_NOT_BASE",
                    "礼品单或加座单不能作为加座的原订单");
        }
        if (product.getParentProductId() == null
                || !product.getParentProductId().equals(original.getProductId())) {
            throw MarketingProductException.conflict("ADD_ON_BASE_MISMATCH", "加座商品与原订单不匹配");
        }
        if (original.getPrimaryRegistrationId() == null) {
            throw MarketingProductException.conflict("ORIGINAL_ADMISSION_MISSING", "原订单尚无原注册准入");
        }
    }

    private void requireSelfPlaySource(long callerMemberId, CmsRegistration source) {
        if (source == null) {
            throw MarketingProductException.conflict("EXTRA_SEAT_ORIGINAL_MISSING", "原订单的准入行不存在");
        }
        if (!Long.valueOf(callerMemberId).equals(source.getMemberId())) {
            throw MarketingProductException.forbidden("EXTRA_SEAT_ORIGINAL_ROLE_INVALID",
                    "只有原订单购买人可以加座");
        }
        if (!Long.valueOf(1L).equals(source.getOwnerType()) || source.getOwnerId() == null) {
            // 活动/场次票走原报名与组队；礼品单源单准入也只在自玩域成立。
            throw MarketingProductException.conflict("EXTRA_SEAT_ORIGINAL_SCOPE_INVALID",
                    "加座只适用于自玩通行证；活动票请走原报名或组队");
        }
        if (!Integer.valueOf(2).equals(source.getPaymentStatus())
                || !Integer.valueOf(2).equals(source.getRegistrationStatus())) {
            throw MarketingProductException.conflict("EXTRA_SEAT_ORIGINAL_INACTIVE",
                    "原通行证已取消、已退款或未支付，不能加座");
        }
        if (source.getExpiresAt() != null && !source.getExpiresAt().after(new Date())) {
            throw MarketingProductException.conflict("EXTRA_SEAT_ORIGINAL_EXPIRED",
                    "原通行证已过期，不能加座");
        }
    }

    /**
     * 加座 SKU 覆盖的主题必须与原单**成交时冻结**的主题集合一致。
     *
     * <p>多主题包自然支持：比对成交时冻结的 {@code marketing_product_order_line}，而不是假定
     * "只有 primary 一行"。少覆盖 = 受赠人拿到的比承诺少（静默少发）；
     * 多覆盖 = 白送未付主题。两种都拒。
     *
     * @return 原单冻结的主题 id（升序，供 plan 回显）
     */
    private List<Long> requireCoverageMatchesOriginal(MarketingProductOrder original,
                                                      List<MarketingProductItem> items) {
        List<MarketingProductOrderLine> frozen = orders.selectFrozenLines(original.getOrderNo());
        if (frozen == null || frozen.isEmpty()) {
            // 冻结行是成交时的权威覆盖；缺失说明原单不是本域成交的，不能猜它的主题。
            throw MarketingProductException.conflict("EXTRA_SEAT_ORIGINAL_LINES_MISSING",
                    "原订单缺少成交冻结内容，无法确认加座覆盖范围");
        }
        Set<Long> paid = new LinkedHashSet<>();
        for (MarketingProductOrderLine line : frozen) {
            if (line == null || line.getTopicId() == null) {
                throw MarketingProductException.conflict("EXTRA_SEAT_ORIGINAL_LINES_MISSING",
                        "原订单冻结内容不完整，无法确认加座覆盖范围");
            }
            paid.add(line.getTopicId());
        }
        Set<Long> sold = new LinkedHashSet<>();
        for (MarketingProductItem item : items) {
            if (item == null || item.getTopicId() == null) {
                throw MarketingProductException.conflict("EXTRA_SEAT_TOPIC_MISSING",
                        "加座商品内容不完整，无法确认覆盖范围");
            }
            sold.add(item.getTopicId());
        }
        if (!paid.equals(sold)) {
            throw MarketingProductException.conflict("EXTRA_SEAT_TOPIC_MISMATCH",
                    "加座内容必须与原订单已购内容完全一致");
        }
        return new ArrayList<>(paid);
    }

    private void requireExtraSeatProduct(MarketingProduct product) {
        if (!product.isExtraSeatRoute()
                || product.getProductKind() == null
                || product.getProductKind() != MarketingProduct.KIND_EXTRA_SEAT) {
            throw MarketingProductException.badRequest("EXTRA_SEAT_PRODUCT_REQUIRED",
                    "该商品不是加座商品（需 fulfillment_route=3 且 product_kind=8）");
        }
        if (product.getSeatCount() != null && product.getSeatCount() != 1) {
            // 一份加座恒等于一位；允许多席位会让一行 order_num 对应多个领取人，
            // 而礼品单源单准入只能转给一个人（会静默少发）。
            throw MarketingProductException.conflict("EXTRA_SEAT_SKU_INVALID",
                    "加座商品必须配置 seat_count=1");
        }
    }

    /**
     * 首次使用型加座在成交时**不建可玩行**（M2-F 语义），因此礼品没有可转的已付源行。
     * 这里直接拒绝，而不是签发一张永远领不了的码。
     */
    private void requireEagerGrantProduct(MarketingProduct product) {
        if (product.getValidityKind() != null
                && product.getValidityKind() == MarketingProduct.VALIDITY_ACTIVATE_ON_FIRST_USE) {
            throw MarketingProductException.conflict("EXTRA_SEAT_ACTIVATION_UNSUPPORTED",
                    "加座不支持「首次使用才生效」的商品；请改用购买即生效的加座 SKU");
        }
    }

    // ------------------------------------------------------------------ views

    private Map<String, Object> extraSeatView(MarketingExtraSeatOrderRow row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderNo", row.getOrderNo());
        out.put("status", row.getStatus());
        out.put("statusLabel", statusLabel(row.getStatus()));
        out.put("productId", row.getProductId());
        out.put("productRevision", row.getProductRevision());
        out.put("fulfillmentKind", row.getFulfillmentKind());
        out.put("fulfillmentState", row.getFulfillmentState());
        out.put("payMode", row.getPayMode());
        out.put("cashAmount", row.getTotalCashAmount());
        out.put("pointsAmount", row.getTotalPointsAmount());
        out.put("paymentFactType", row.getPaymentFactType());
        out.put("primaryRegistrationId", row.getPrimaryRegistrationId());
        out.put("recipientMemberId", row.getRecipientMemberId());
        out.put("originalOrderNo", row.getOriginalOrderNo());
        out.put("partyBinding", PARTY_BINDING);
        out.put("partyBindingReason", PARTY_BINDING_REASON);
        out.put("admissionMode", ADMISSION_MODE);
        out.put("paidAt", row.getPaidAt());
        out.put("refundedAt", row.getRefundedAt());
        out.put("createdAt", row.getCreateTime());

        if (row.getGiftId() == null) {
            // 已付却没有礼品来源:说明发放链路不完整，如实暴露，不冒充"待领取"。
            out.put("admissionState", Integer.valueOf(MarketingProductOrder.STATUS_PAID)
                    .equals(row.getStatus()) ? "PENDING_GIFT_ISSUE" : "NOT_GRANTED");
            return out;
        }
        Map<String, Object> giftView = new LinkedHashMap<>();
        giftView.put("giftId", row.getGiftId());
        giftView.put("publicId", row.getGiftPublicId());
        giftView.put("status", row.getGiftStatus());
        giftView.put("recipientMemberId", row.getGiftRecipientMemberId());
        giftView.put("claimedMemberId", row.getGiftClaimedMemberId());
        giftView.put("claimedRegistrationId", row.getGiftClaimedRegistrationId());
        giftView.put("claimExpiresAt", row.getGiftClaimExpiresAt());
        out.put("gift", giftView);
        out.put("admissionState", Integer.valueOf(GiftEntitlement.STATUS_CLAIMED).equals(row.getGiftStatus())
                ? "LINKED_SAME_REGISTRATION" : "PENDING_CLAIM");
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

    // ------------------------------------------------------------------ helpers

    /** 与 M3 下单路径同一份载荷：任一项变化签名即失效（含 fulfilledKind=3 与原单 id）。 */
    private String canonical(long callerMemberId, MarketingProduct product, Long originalOrderId,
                             Long recipientMemberId, BigDecimal totalCash, Integer totalPoints,
                             int seatTotal, List<MarketingProductItem> items, long expiresAt) {
        return MarketingQuotePayload.of(callerMemberId, product,
                MarketingProductOrder.FULFILLMENT_EXTRA_SEAT, 1, originalOrderId, recipientMemberId,
                totalCash, totalPoints, seatTotal, items, expiresAt);
    }

    private void requireMember(long memberId) {
        if (memberId <= 0) throw MarketingProductException.unauthorized();
    }
}
