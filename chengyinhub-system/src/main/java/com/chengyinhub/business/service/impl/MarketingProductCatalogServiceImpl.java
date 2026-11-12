package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.MarketingProduct;
import com.chengyinhub.business.domain.MarketingProductItem;
import com.chengyinhub.business.domain.MarketingProductOrder;
import com.chengyinhub.business.domain.PassBundleView;
import com.chengyinhub.business.domain.dto.MarketingProductQuoteRequest;
import com.chengyinhub.business.domain.dto.MarketingProductQuoteResult;
import com.chengyinhub.business.exception.MarketingProductException;
import com.chengyinhub.business.mapper.MarketingProductMapper;
import com.chengyinhub.business.service.IMarketingProductCatalogService;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** 可售读面与报价。只读：不扣积分、不建单、不发权益。 */
@Service
public class MarketingProductCatalogServiceImpl implements IMarketingProductCatalogService {

    private static final long QUOTE_TTL_MILLIS = TimeUnit.MINUTES.toMillis(10);

    private final MarketingProductMapper productMapper;
    private final MarketingProductRules rules;
    private final MarketingQuoteSigner signer;
    private final MarketingCashChannel cashChannel;

    public MarketingProductCatalogServiceImpl(MarketingProductMapper productMapper,
                                              MarketingProductRules rules,
                                              MarketingQuoteSigner signer,
                                              MarketingCashChannel cashChannel) {
        this.productMapper = productMapper;
        this.rules = rules;
        this.signer = signer;
        this.cashChannel = cashChannel;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PassBundleView> listSellable(String city, Integer kind, int limit) {
        int size = limit <= 0 ? 20 : Math.min(limit, 100);
        List<MarketingProduct> products = productMapper.selectSellable(
                city == null || city.trim().isEmpty() ? null : city.trim(), kind, new Date(), size);
        if (products == null || products.isEmpty()) return new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        for (MarketingProduct product : products) ids.add(product.getId());
        Map<Long, List<MarketingProductItem>> itemsByProduct = groupItems(
                productMapper.selectItemsByProductIds(ids));
        Date now = new Date();
        List<PassBundleView> out = new ArrayList<>();
        for (MarketingProduct product : products) {
            List<MarketingProductItem> items = itemsByProduct.getOrDefault(product.getId(), new ArrayList<>());
            if (!items.isEmpty() && rules.isSellable(product, now)) out.add(view(product, items));
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public PassBundleView detail(Long productId) {
        MarketingProduct product = rules.requireProduct(productId);
        return view(product, rules.items(product.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public MarketingProductQuoteResult quote(long callerMemberId, MarketingProductQuoteRequest request) {
        requireMember(callerMemberId);
        if (request == null || request.getProductId() == null) {
            throw MarketingProductException.badRequest("PRODUCT_REQUIRED", "请选择商品");
        }
        MarketingProduct product = rules.requireProduct(request.getProductId());
        Date now = new Date();
        rules.requireSellable(product, now);
        List<MarketingProductItem> items = rules.items(product.getId());

        int fulfillment = request.getFulfillmentKind() == null
                ? MarketingProductOrder.FULFILLMENT_SELF : request.getFulfillmentKind();
        int quantity = request.getQuantity() == null ? 1 : request.getQuantity();
        rules.requireQuantity(quantity);

        Long originalOrderId = null;
        Long extraSeatRecipient = null;
        if (fulfillment == MarketingProductOrder.FULFILLMENT_EXTRA_SEAT) {
            originalOrderId = rules.requireAddOnBase(callerMemberId, product, request.getOriginalOrderNo());
            // 加座必须指名这一位给谁：受赠人参与签名，下单不能改成别人；
            // 也让服务端能拦"同一人在同一原单上重复加座"。
            extraSeatRecipient = request.getRecipientMemberId();
            if (extraSeatRecipient == null || extraSeatRecipient <= 0) {
                throw MarketingProductException.badRequest("EXTRA_SEAT_RECIPIENT_REQUIRED",
                        "请指定这一位加座给谁");
            }
            if (extraSeatRecipient.longValue() == callerMemberId) {
                throw MarketingProductException.badRequest("EXTRA_SEAT_SELF_RECIPIENT",
                        "加座是第二位玩家的独立准入，不能给自己再买一份");
            }
            if (quantity != 1) {
                throw MarketingProductException.badRequest("EXTRA_SEAT_ONE_PER_ORDER",
                        "一份加座只对应一位；多人请分别下单");
            }
        } else if (product.getProductKind() != null
                && product.getProductKind() == MarketingProduct.KIND_EXTRA_SEAT) {
            throw MarketingProductException.badRequest("FULFILLMENT_INVALID", "加座商品必须走加座履约");
        }
        if (product.getProductKind() != null
                && product.getProductKind() == MarketingProduct.KIND_GIFT_PASS
                && fulfillment != MarketingProductOrder.FULFILLMENT_GIFT) {
            throw MarketingProductException.badRequest("GIFT_FULFILLMENT_REQUIRED", "礼品商品必须走赠送履约");
        }

        Long recipient = extraSeatRecipient;
        String giftClaimMode = null;
        if (fulfillment == MarketingProductOrder.FULFILLMENT_GIFT) {
            if (!Integer.valueOf(1).equals(product.getGiftAllowed())) {
                throw MarketingProductException.badRequest("GIFT_NOT_ALLOWED", "该商品不支持赠送");
            }
            if (quantity != 1) {
                throw MarketingProductException.badRequest("GIFT_QUANTITY_INVALID", "礼品一次只能送一份");
            }
            recipient = request.getRecipientMemberId();
            if (recipient != null && recipient.longValue() == callerMemberId) {
                throw MarketingProductException.badRequest("GIFT_SELF_RECIPIENT", "不能把礼品送给自己");
            }
            giftClaimMode = recipient == null ? "CODE" : "PREBOUND";
        }

        BigDecimal totalCash = MarketingQuotePayload.cashTotal(product.getCashPrice(), quantity);
        Integer totalPoints = MarketingQuotePayload.pointsTotal(product.getPointsPrice(), quantity);
        int seatTotal = MarketingQuotePayload.seatTotal(product, quantity);
        long expiresAt = now.getTime() + QUOTE_TTL_MILLIS;

        MarketingProductQuoteResult result = new MarketingProductQuoteResult();
        result.setQuoteExpiresAt(new Date(expiresAt));
        result.setProductId(product.getId());
        result.setProductCode(product.getProductCode());
        result.setProductName(product.getProductName());
        result.setProductKind(product.getProductKind());
        result.setProductRevision(product.getRevision());
        result.setPurchaseKind(product.getPurchaseKind());
        result.setExploreCapacity(product.occupiesExploreCapacity());
        result.setPurchaseRoute(product.getFulfillmentRoute());
        result.setFulfillmentKind(fulfillment);
        result.setQuantity(quantity);
        result.setUnitCashPrice(product.getCashPrice());
        result.setUnitPointsPrice(product.getPointsPrice());
        result.setTotalCashAmount(totalCash);
        result.setTotalPointsAmount(totalPoints);
        result.setCurrency("CNY");
        result.setPointsCurrency("POINTS");
        result.setValidityKind(product.getValidityKind());
        result.setValidDays(product.getValidDays());
        result.setFixedValidFrom(product.getFixedValidFrom());
        result.setFixedValidTo(product.getFixedValidTo());
        result.setActivatesOnFirstUse(product.getValidityKind() != null
                && product.getValidityKind() == MarketingProduct.VALIDITY_ACTIVATE_ON_FIRST_USE);
        result.setSeatCount(seatTotal);
        result.setTermsVersion(product.getTermsVersion());
        result.setGiftClaimMode(giftClaimMode);
        result.setItems(itemViews(items));

        List<Integer> payModes = new ArrayList<>();
        if (product.usesTicketRegistration()) {
            // 团体/学校：价格仅目录展示，真实收款走既有报名结算（tickets[]），本域不出可成交按钮。
            // 这里回报原票种与所有者范围，客户端可直接跳原报价/建单，不需要猜参数。
            result.setPurchaseRouteReason("USE_REGISTRATION_CHECKOUT");
            result.setCheckout(rules.ticketCheckout(product, quantity));
        } else if (product.isExtraSeatRoute()) {
            // 加座 = 独立增购一份一人准入（原单不改）；与礼品同一"领取转移"路径。
            // 支付方式与普通商品一致（此前 route3 恒返回空列表 = 前端无按钮）。
            result.setPurchaseRouteReason("EXTRA_SEAT_REQUIRES_ORIGINAL_ORDER");
            if (product.getPointsPrice() != null) payModes.add(MarketingProductOrder.PAY_MODE_POINTS);
            if (product.getCashPrice() != null) {
                if (cashChannel.enabled()) payModes.add(MarketingProductOrder.PAY_MODE_CASH);
                else result.setCashUnavailableReason(cashChannel.unavailableReason());
            }
        } else {
            if (product.getPointsPrice() != null) payModes.add(MarketingProductOrder.PAY_MODE_POINTS);
            if (product.getCashPrice() != null) {
                if (cashChannel.enabled()) payModes.add(MarketingProductOrder.PAY_MODE_CASH);
                else result.setCashUnavailableReason(cashChannel.unavailableReason());
            }
        }
        result.setAvailablePayModes(payModes);
        result.setQuoteSign(signer.sign(MarketingQuotePayload.of(callerMemberId, product, fulfillment,
                quantity, originalOrderId, recipient, totalCash, totalPoints, seatTotal, items, expiresAt)));
        return result;
    }

    // ------------------------------------------------------------------ helpers

    private Map<Long, List<MarketingProductItem>> groupItems(List<MarketingProductItem> items) {
        Map<Long, List<MarketingProductItem>> grouped = new LinkedHashMap<>();
        if (items == null) return grouped;
        for (MarketingProductItem item : items) {
            grouped.computeIfAbsent(item.getProductId(), key -> new ArrayList<>()).add(item);
        }
        return grouped;
    }

    private PassBundleView view(MarketingProduct product, List<MarketingProductItem> items) {
        PassBundleView view = new PassBundleView();
        view.setProductId(product.getId());
        view.setProductCode(product.getProductCode());
        view.setProductName(product.getProductName());
        view.setProductKind(product.getProductKind());
        view.setPurchaseKind(product.getPurchaseKind());
        view.setExploreCapacity(product.occupiesExploreCapacity());
        view.setPurchaseRoute(product.getFulfillmentRoute());
        view.setSaleCity(product.getSaleCity());
        view.setRevision(product.getRevision());
        view.setTermsVersion(product.getTermsVersion());
        view.setCashPrice(product.getCashPrice());
        view.setPointsPrice(product.getPointsPrice());
        view.setValidityKind(product.getValidityKind());
        view.setValidDays(product.getValidDays());
        view.setFixedValidFrom(product.getFixedValidFrom());
        view.setFixedValidTo(product.getFixedValidTo());
        view.setSeatCount(product.getSeatCount());
        view.setGiftAllowed(product.getGiftAllowed());
        view.setSaleStatus(product.getStatus());
        view.setSaleStartAt(product.getSaleStartAt());
        view.setSaleEndAt(product.getSaleEndAt());
        view.setItems(itemViews(items));
        return view;
    }

    private List<PassBundleView.Item> itemViews(List<MarketingProductItem> items) {
        List<PassBundleView.Item> out = new ArrayList<>();
        for (MarketingProductItem item : items) {
            out.add(new PassBundleView.Item(item.getTopicId(), item.getTopicTitle(), item.getContentRevision()));
        }
        return out;
    }

    private void requireMember(long memberId) {
        if (memberId <= 0) throw MarketingProductException.unauthorized();
    }
}
