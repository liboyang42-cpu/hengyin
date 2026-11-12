package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.MarketingProduct;
import com.chengyinhub.business.domain.MarketingProductItem;
import com.chengyinhub.business.domain.MarketingProductOrder;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.exception.MarketingProductException;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.mapper.MarketingProductMapper;
import com.chengyinhub.business.mapper.MarketingProductOrderMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** 商品可售与加座前置校验；报价与下单共用，避免两套规则漂移。 */
@Component
public class MarketingProductRules {

    private static final int MAX_QUANTITY = 100;

    private final MarketingProductMapper productMapper;
    private final MarketingProductOrderMapper orderMapper;
    private final CmsTopicMapper topicMapper;
    private final IOmsTicketService ticketService;

    public MarketingProductRules(MarketingProductMapper productMapper,
                                 MarketingProductOrderMapper orderMapper,
                                 CmsTopicMapper topicMapper,
                                 IOmsTicketService ticketService) {
        this.productMapper = productMapper;
        this.orderMapper = orderMapper;
        this.topicMapper = topicMapper;
        this.ticketService = ticketService;
    }

    public MarketingProduct requireProduct(Long productId) {
        if (productId == null || productId <= 0) {
            throw MarketingProductException.badRequest("PRODUCT_INVALID", "商品编号不合法");
        }
        MarketingProduct product = productMapper.selectById(productId);
        if (product == null) throw MarketingProductException.notFound("PRODUCT_NOT_FOUND", "商品不存在");
        return product;
    }

    public List<MarketingProductItem> items(Long productId) {
        List<MarketingProductItem> items = productMapper.selectItems(productId);
        return items == null ? new ArrayList<>() : items;
    }

    /** 每个覆盖主题的单买票面（分摊权重与上限）；读取失败返回 null（按权重 1）。 */
    public List<BigDecimal> singlePrices(List<MarketingProductItem> items) {
        List<BigDecimal> prices = new ArrayList<>();
        if (items == null) return prices;
        for (MarketingProductItem item : items) {
            BigDecimal price = null;
            try {
                CmsTopic topic = topicMapper.selectCmsTopicById(item.getTopicId());
                if (topic != null) price = topic.getSelfPlayPrice();
            } catch (RuntimeException unavailable) {
                price = null;
            }
            prices.add(price);
        }
        return prices;
    }

    public void requireSellable(MarketingProduct product, Date now) {
        if (!Integer.valueOf(MarketingProduct.STATUS_ON_SALE).equals(product.getStatus())) {
            throw MarketingProductException.conflict("PRODUCT_NOT_ON_SALE", "商品未在售");
        }
        if (product.getSaleStartAt() != null && product.getSaleStartAt().after(now)) {
            throw MarketingProductException.conflict("SALE_NOT_STARTED", "商品尚未开售");
        }
        if (product.getSaleEndAt() != null && !product.getSaleEndAt().after(now)) {
            throw MarketingProductException.conflict("SALE_ENDED", "商品已停售");
        }
        if (product.getValidityKind() != null
                && product.getValidityKind() == MarketingProduct.VALIDITY_FIXED_WINDOW
                && (product.getFixedValidTo() == null || !product.getFixedValidTo().after(now))) {
            throw MarketingProductException.conflict("PERIOD_ENDED", "本期商品已到期");
        }
        if (product.getProductKind() != null
                && product.getProductKind() == MarketingProduct.KIND_EXTRA_SEAT
                && product.getParentProductId() == null) {
            throw MarketingProductException.conflict("ADD_ON_BASE_MISSING", "加座商品未绑定原商品");
        }
        if (product.getPurchaseKind() == null
                || (product.getPurchaseKind() != 2 && product.getPurchaseKind() != 3)) {
            throw MarketingProductException.conflict("PURCHASE_KIND_INVALID", "商品购买类型配置不合法");
        }
        if (product.occupiesExploreCapacity() && product.getPurchaseKind() != 3) {
            throw MarketingProductException.conflict("EXPLORE_CAPACITY_INVALID",
                    "只有探索票才能占章节容量");
        }
        if (items(product.getId()).isEmpty()) {
            throw MarketingProductException.conflict("PRODUCT_HAS_NO_CONTENT", "商品未绑定任何作品");
        }
        Integer route = product.getFulfillmentRoute();
        if (route == null || (route != MarketingProduct.ROUTE_SELF_PLAY_GRANT
                && route != MarketingProduct.ROUTE_TICKET_REGISTRATION
                && route != MarketingProduct.ROUTE_EXTRA_SEAT_GRANT)) {
            throw MarketingProductException.conflict("FULFILLMENT_ROUTE_INVALID", "商品履约路由配置不合法");
        }
        if (product.usesTicketRegistration()) {
            // 团体/学校：必须绑定真实 oms_ticket，由既有报名/席位链路承载；不另建席位真源。
            if (product.getTicketId() == null || product.getTicketId() <= 0) {
                throw MarketingProductException.conflict("TICKET_BINDING_MISSING", "团体/学校商品未绑定票种");
            }
            if (ticketService.selectOmsTicketById(product.getTicketId()) == null) {
                throw MarketingProductException.conflict("TICKET_BINDING_NOT_FOUND", "绑定的票种不存在");
            }
        } else if (product.getTicketId() != null) {
            throw MarketingProductException.conflict("TICKET_BINDING_UNEXPECTED", "该路由不应绑定票种");
        }
        if (product.isExtraSeatRoute() && product.getParentProductId() == null) {
            throw MarketingProductException.conflict("ADD_ON_BASE_MISSING", "加座商品未绑定原商品");
        }
        if (Integer.valueOf(1).equals(product.getGiftAllowed())
                && product.getValidityKind() != null
                && product.getValidityKind() == MarketingProduct.VALIDITY_ACTIVATE_ON_FIRST_USE) {
            // 礼品按"单行转移"发放：领取前买家行已存在，不能是首次使用型（否则领取时才有行）。
            throw MarketingProductException.conflict("GIFT_REQUIRES_EXISTING_ROW",
                    "礼品商品不能使用“首次使用起算”有效期");
        }
    }

    public boolean isSellable(MarketingProduct product, Date now) {
        try {
            requireSellable(product, now);
            return true;
        } catch (MarketingProductException rejected) {
            return false;
        }
    }

    /**
     * 团体/学校转入既有报名结算所需的真实参数（ownerType/ownerId/ticketId/tickets）。
     * 票种解析不到时仍给出 ticketId 并标 ticketResolved=false，客户端据此报配置缺口而不是猜参数。
     */
    public java.util.Map<String, Object> ticketCheckout(MarketingProduct product, int quantity) {
        OmsTicket ticket = boundTicket(product);
        java.util.Map<String, Object> checkout = new java.util.LinkedHashMap<>();
        checkout.put("quoteEndpoint", "/api/registration/quote");
        checkout.put("createEndpoint", "/api/registration/create");
        // 与 MG（团体/学校）lane 的命名对齐；同值别名，避免两边各写一份端点常量。
        checkout.put("groupQuoteEndpoint", "/api/marketing/group-checkout/quote");
        checkout.put("groupCreateEndpoint", "/api/marketing/group-checkout/create");
        checkout.put("productId", product == null ? null : product.getId());
        checkout.put("ticketId", ticket == null ? (product == null ? null : product.getTicketId()) : ticket.getId());
        if (ticket != null) {
            checkout.put("ownerType", ticket.getOwnerType());
            checkout.put("ownerId", ticket.getOwnerId());
            // ownerType=2 时 ownerId 就是活动 id；场次时间/事实由 MG lane 按此 id 自行解析。
            checkout.put("activityId", Long.valueOf(2L).equals(ticket.getOwnerType()) ? ticket.getOwnerId() : null);
            checkout.put("sessionStartAt", ticket.getStartTime());
            checkout.put("sessionEndAt", ticket.getEndTime());
            checkout.put("signupDeadline", ticket.getSignupDeadline());
            checkout.put("remainingInventory", ticket.getRemainingInventory());
        }
        checkout.put("quantity", quantity);
        java.util.List<java.util.Map<String, Object>> tickets = new java.util.ArrayList<>();
        java.util.Map<String, Object> line = new java.util.LinkedHashMap<>();
        line.put("ticketId", ticket == null ? (product == null ? null : product.getTicketId()) : ticket.getId());
        line.put("count", quantity);
        tickets.add(line);
        checkout.put("tickets", tickets);
        checkout.put("ticketResolved", ticket != null);
        return checkout;
    }

    /** 团体/学校的原票种（报价跳转用）；未绑定返回 null。 */
    public OmsTicket boundTicket(MarketingProduct product) {
        if (product == null || product.getTicketId() == null || product.getTicketId() <= 0) return null;
        return ticketService.selectOmsTicketById(product.getTicketId());
    }

    public void requireQuantity(int quantity) {
        if (quantity < 1 || quantity > MAX_QUANTITY) {
            throw MarketingProductException.badRequest("QUANTITY_INVALID", "数量不合法");
        }
    }

    /** 加座必须挂在本人已付、且商品匹配的原商品订单上；原订单已付金额不改。 */
    public Long requireAddOnBase(long memberId, MarketingProduct product, String originalOrderNo) {
        if (product.getParentProductId() == null) {
            throw MarketingProductException.badRequest("ADD_ON_BASE_MISSING", "加座商品未绑定原商品");
        }
        if (originalOrderNo == null || originalOrderNo.trim().isEmpty()) {
            throw MarketingProductException.badRequest("ORIGINAL_ORDER_REQUIRED", "请提供原订单号");
        }
        MarketingProductOrder original = orderMapper.selectByOrderNo(originalOrderNo.trim());
        if (original == null || !Long.valueOf(memberId).equals(original.getMemberId())) {
            throw MarketingProductException.forbidden("ORIGINAL_ORDER_FORBIDDEN", "原订单不属于你");
        }
        if (!Integer.valueOf(MarketingProductOrder.STATUS_PAID).equals(original.getStatus())) {
            throw MarketingProductException.conflict("ORIGINAL_ORDER_NOT_PAID", "原订单尚未支付");
        }
        if (Integer.valueOf(MarketingProductOrder.FULFILLMENT_GIFT).equals(original.getFulfillmentKind())) {
            throw MarketingProductException.conflict("ORIGINAL_ORDER_IS_GIFT", "礼品订单不能加座");
        }
        if (!product.getParentProductId().equals(original.getProductId())) {
            throw MarketingProductException.conflict("ADD_ON_BASE_MISMATCH", "加座商品与原订单不匹配");
        }
        if (original.getPrimaryRegistrationId() == null) {
            throw MarketingProductException.conflict("ORIGINAL_ADMISSION_MISSING", "原订单尚无原注册准入");
        }
        return original.getId();
    }
}
