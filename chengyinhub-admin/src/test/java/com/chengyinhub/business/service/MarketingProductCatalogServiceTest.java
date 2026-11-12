package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.MarketingProduct;
import com.chengyinhub.business.domain.MarketingProductItem;
import com.chengyinhub.business.domain.MarketingProductOrder;
import com.chengyinhub.business.domain.dto.MarketingProductQuoteRequest;
import com.chengyinhub.business.domain.dto.MarketingProductQuoteResult;
import com.chengyinhub.business.exception.MarketingProductException;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.MarketingProductMapper;
import com.chengyinhub.business.mapper.MarketingProductOrderMapper;
import com.chengyinhub.business.service.impl.MarketingProductCatalogServiceImpl;
import com.chengyinhub.business.service.support.MarketingCashChannel;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.support.MarketingProductRules;
import com.chengyinhub.business.service.support.MarketingQuotePayload;
import com.chengyinhub.business.service.support.MarketingQuoteSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 报价契约：kind→PurchaseKind 映射、章节容量旗标、服务端总价与签名。 */
class MarketingProductCatalogServiceTest {

    private static final String SECRET = "unit-test-product-secret";
    private static final long MEMBER = 7L;
    private static final long PRODUCT = 10L;

    private MarketingProductMapper products;
    private MarketingProductOrderMapper orders;
    private CmsTopicMapper topics;
    private MarketingQuoteSigner signer;
    private IOmsTicketService ticketService;
    private MarketingProductCatalogServiceImpl service;

    @BeforeEach
    void setUp() {
        products = mock(MarketingProductMapper.class);
        orders = mock(MarketingProductOrderMapper.class);
        topics = mock(CmsTopicMapper.class);
        signer = new MarketingQuoteSigner(SECRET);
        ticketService = mock(IOmsTicketService.class);
        MarketingProductRules rules = new MarketingProductRules(products, orders, topics, ticketService);
        service = new MarketingProductCatalogServiceImpl(products, rules, signer,
                new MarketingCashChannel(false));
        when(products.selectById(PRODUCT)).thenReturn(passProduct());
        when(products.selectItems(PRODUCT)).thenReturn(Arrays.asList(item(100L, 5L), item(101L, 6L)));
        CmsTopic topic = new CmsTopic();
        topic.setId(100L);
        topic.setSelfPlayPrice(new BigDecimal("19.99"));
        when(topics.selectCmsTopicById(100L)).thenReturn(topic);
    }

    @Test
    void passQuotesSelfPlayKindWithoutExploreCapacity() {
        MarketingProductQuoteResult quote = service.quote(MEMBER, request());

        assertEquals(2, quote.getPurchaseKind().intValue());
        assertFalse(quote.getExploreCapacity());
        assertEquals(120, quote.getTotalPointsAmount().intValue());
        assertEquals(new BigDecimal("39.99"), quote.getTotalCashAmount());
        assertNotNull(quote.getQuoteSign());
        String canonical = MarketingQuotePayload.of(MEMBER, passProduct(),
                MarketingProductOrder.FULFILLMENT_SELF, 1, null, null,
                quote.getTotalCashAmount(), quote.getTotalPointsAmount(), 1,
                products.selectItems(PRODUCT), quote.getQuoteExpiresAt().getTime());
        assertTrue(signer.matches(canonical, quote.getQuoteSign()));
    }

    @Test
    void exploreProductMustDeclarePurchaseKindThree() {
        MarketingProduct invalid = passProduct();
        invalid.setPurchaseKind(2);
        invalid.setExploreCapacity(1);
        when(products.selectById(PRODUCT)).thenReturn(invalid);

        assertReason("EXPLORE_CAPACITY_INVALID", () -> service.quote(MEMBER, request()));
    }

    @Test
    void giftRejectsSelfRecipient() {
        MarketingProduct giftProduct = passProduct();
        giftProduct.setGiftAllowed(1);
        giftProduct.setValidityKind(MarketingProduct.VALIDITY_RELATIVE_DAYS);
        when(products.selectById(PRODUCT)).thenReturn(giftProduct);
        MarketingProductQuoteRequest self = request();
        self.setFulfillmentKind(MarketingProductOrder.FULFILLMENT_GIFT);
        self.setRecipientMemberId(MEMBER);
        assertReason("GIFT_SELF_RECIPIENT", () -> service.quote(MEMBER, self));
    }

    @Test
    void addOnRequiresAdmissionOnOriginalOrder() {
        MarketingProduct addOn = passProduct();
        addOn.setProductKind(MarketingProduct.KIND_EXTRA_SEAT);
        addOn.setParentProductId(PRODUCT);
        when(products.selectById(PRODUCT)).thenReturn(addOn);

        MarketingProductOrder paid = new MarketingProductOrder();
        paid.setId(500L);
        paid.setMemberId(MEMBER);
        paid.setProductId(PRODUCT);
        paid.setStatus(MarketingProductOrder.STATUS_PAID);
        when(orders.selectByOrderNo("MKO-ORIG")).thenReturn(paid);

        MarketingProductQuoteRequest addOnRequest = request();
        addOnRequest.setFulfillmentKind(MarketingProductOrder.FULFILLMENT_EXTRA_SEAT);
        addOnRequest.setOriginalOrderNo("MKO-ORIG");
        assertReason("ORIGINAL_ADMISSION_MISSING", () -> service.quote(MEMBER, addOnRequest));
    }

    @Test
    void extraSeatRouteRequiresARecipientSoTheOtherPlayerIsPinned() {
        stubPaidExtraSeatBase();
        MarketingProductQuoteRequest addOnRequest = extraSeatRequest();
        addOnRequest.setRecipientMemberId(null);

        assertReason("EXTRA_SEAT_RECIPIENT_REQUIRED", () -> service.quote(MEMBER, addOnRequest));
    }

    @Test
    void extraSeatRouteRejectsBuyingForYourself() {
        stubPaidExtraSeatBase();
        MarketingProductQuoteRequest addOnRequest = extraSeatRequest();
        addOnRequest.setRecipientMemberId(MEMBER);

        // 加座是第二位玩家的独立准入；给自己再买一份只会多付一份钱。
        assertReason("EXTRA_SEAT_SELF_RECIPIENT", () -> service.quote(MEMBER, addOnRequest));
    }

    @Test
    void extraSeatRouteIsOneSeatPerOrderAndQuotesPayModes() {
        stubPaidExtraSeatBase();
        MarketingProductQuoteRequest two = extraSeatRequest();
        two.setQuantity(2);
        assertReason("EXTRA_SEAT_ONE_PER_ORDER", () -> service.quote(MEMBER, two));

        MarketingProductQuoteResult quote = service.quote(MEMBER, extraSeatRequest());

        // 负控：route3 分支漏掉 payModes 时这里会是空列表（前端拿不到可支付方式）。
        assertEquals(java.util.Collections.singletonList(MarketingProductOrder.PAY_MODE_POINTS),
                quote.getAvailablePayModes());
        // 现金通道默认关闭：只说明原因，不冒充可用。
        assertEquals("PAYMENT_OWNER_ROUTE_NOT_WIRED", quote.getCashUnavailableReason());
        assertEquals("EXTRA_SEAT_REQUIRES_ORIGINAL_ORDER", quote.getPurchaseRouteReason());
    }

    /** 加座 SKU + 一张属于本人的已付原单（含原准入）。 */
    private void stubPaidExtraSeatBase() {
        MarketingProduct addOn = passProduct();
        addOn.setProductKind(MarketingProduct.KIND_EXTRA_SEAT);
        addOn.setFulfillmentRoute(MarketingProduct.ROUTE_EXTRA_SEAT_GRANT);
        addOn.setParentProductId(PRODUCT);
        addOn.setValidityKind(MarketingProduct.VALIDITY_RELATIVE_DAYS);
        when(products.selectById(PRODUCT)).thenReturn(addOn);

        MarketingProductOrder paid = new MarketingProductOrder();
        paid.setId(500L);
        paid.setOrderNo("MKO-ORIG");
        paid.setMemberId(MEMBER);
        paid.setProductId(PRODUCT);
        paid.setStatus(MarketingProductOrder.STATUS_PAID);
        paid.setFulfillmentKind(MarketingProductOrder.FULFILLMENT_SELF);
        paid.setPrimaryRegistrationId(321L);
        when(orders.selectByOrderNo("MKO-ORIG")).thenReturn(paid);
    }

    private MarketingProductQuoteRequest extraSeatRequest() {
        MarketingProductQuoteRequest addOnRequest = request();
        addOnRequest.setFulfillmentKind(MarketingProductOrder.FULFILLMENT_EXTRA_SEAT);
        addOnRequest.setOriginalOrderNo("MKO-ORIG");
        addOnRequest.setRecipientMemberId(9L);
        return addOnRequest;
    }

    @Test
    void ticketRouteIsQuotedAsRegistrationCheckoutOnly() {
        MarketingProduct group = passProduct();
        group.setProductKind(MarketingProduct.KIND_GROUP_TEAM);
        group.setFulfillmentRoute(MarketingProduct.ROUTE_TICKET_REGISTRATION);
        group.setTicketId(77L);
        when(products.selectById(PRODUCT)).thenReturn(group);
        com.chengyinhub.business.domain.OmsTicket ticket = new com.chengyinhub.business.domain.OmsTicket();
        ticket.setId(77L);
        ticket.setOwnerType(2L);
        ticket.setOwnerId(555L);
        when(ticketService.selectOmsTicketById(77L)).thenReturn(ticket);

        MarketingProductQuoteResult quote = service.quote(MEMBER, request());

        assertEquals(MarketingProduct.ROUTE_TICKET_REGISTRATION, quote.getPurchaseRoute().intValue());
        assertEquals("USE_REGISTRATION_CHECKOUT", quote.getPurchaseRouteReason());
        assertTrue(quote.getAvailablePayModes().isEmpty());
        // 必须给原报名结算的真实标识，不能只回一个固定 409。
        assertEquals(2L, ((Number) quote.getCheckout().get("ownerType")).longValue());
        assertEquals(555L, ((Number) quote.getCheckout().get("ownerId")).longValue());
        assertEquals(77L, ((Number) quote.getCheckout().get("ticketId")).longValue());
        assertEquals("/api/registration/quote", quote.getCheckout().get("quoteEndpoint"));
        assertTrue(Boolean.TRUE.equals(quote.getCheckout().get("ticketResolved")));
    }

    @Test
    void giftProductCannotUseFirstUseValidity() {
        MarketingProduct giftProduct = passProduct();
        giftProduct.setGiftAllowed(1);
        when(products.selectById(PRODUCT)).thenReturn(giftProduct);
        assertReason("GIFT_REQUIRES_EXISTING_ROW", () -> service.quote(MEMBER, request()));
    }

    // ------------------------------------------------------------------ fixtures

    private MarketingProduct passProduct() {
        MarketingProduct product = new MarketingProduct();
        product.setId(PRODUCT);
        product.setProductCode("PASS-CITY-7D");
        product.setProductName("城市 7 日通行证");
        product.setProductKind(MarketingProduct.KIND_TIME_LIMITED_PASS);
        product.setPurchaseKind(2);
        product.setExploreCapacity(0);
        product.setFulfillmentRoute(MarketingProduct.ROUTE_SELF_PLAY_GRANT);
        product.setRevision(3L);
        product.setCashPrice(new BigDecimal("39.99"));
        product.setPointsPrice(120);
        product.setValidityKind(MarketingProduct.VALIDITY_ACTIVATE_ON_FIRST_USE);
        product.setValidDays(7);
        product.setSeatCount(1);
        product.setGiftAllowed(0);
        product.setStatus(MarketingProduct.STATUS_ON_SALE);
        product.setTermsVersion("v1");
        return product;
    }

    private MarketingProductItem item(long topicId, long revision) {
        MarketingProductItem item = new MarketingProductItem();
        item.setProductId(PRODUCT);
        item.setTopicId(topicId);
        item.setTopicTitle("作品" + topicId);
        item.setContentRevision(revision);
        return item;
    }

    private MarketingProductQuoteRequest request() {
        MarketingProductQuoteRequest request = new MarketingProductQuoteRequest();
        request.setProductId(PRODUCT);
        request.setQuantity(1);
        request.setFulfillmentKind(MarketingProductOrder.FULFILLMENT_SELF);
        return request;
    }

    private void assertReason(String reason, Runnable action) {
        MarketingProductException rejection = assertThrows(MarketingProductException.class, action::run);
        assertEquals(reason, rejection.getReasonCode());
    }
}
