package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.MarketingProduct;
import com.chengyinhub.business.domain.MarketingProductItem;
import com.chengyinhub.business.domain.MarketingProductOrder;
import com.chengyinhub.business.domain.MarketingProductOrderLine;
import com.chengyinhub.business.domain.dto.MarketingExtraSeatPlan;
import com.chengyinhub.business.domain.dto.MarketingExtraSeatQuote;
import com.chengyinhub.business.domain.dto.MarketingExtraSeatQuoteRequest;
import com.chengyinhub.business.domain.dto.MarketingProductOrderRequest;
import com.chengyinhub.business.exception.MarketingProductException;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.MarketingExtraSeatMapper;
import com.chengyinhub.business.mapper.MarketingProductMapper;
import com.chengyinhub.business.mapper.MarketingProductOrderMapper;
import com.chengyinhub.business.service.impl.MarketingExtraSeatServiceImpl;
import com.chengyinhub.business.service.support.MarketingCashChannel;
import com.chengyinhub.business.service.support.MarketingProductRules;
import com.chengyinhub.business.service.support.MarketingQuotePayload;
import com.chengyinhub.business.service.support.MarketingQuoteSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 加座 route3 准入闸。每条用例都对应一个真实的越权/错发路径，
 * 负控方式写在用例注释里：把被测守卫删掉，该用例必须变红。
 *
 * <p><b>本类不测容量</b>：自玩增购没有座位资源，服务层也没有任何容量判定
 * （见 {@code MarketingExtraSeatServiceImpl} 类注释）。这里显式断言
 * {@code partyCapacity}/{@code seatsRemaining} 恒为 null，防止有人再把活动组队配置
 * 当自玩上限塞回来。
 */
class MarketingExtraSeatServiceTest {

    private static final String SECRET = "unit-test-extra-seat-secret";
    private static final long BUYER = 7L;
    private static final long FRIEND = 9L;
    private static final long OTHER = 11L;
    private static final long TOPIC = 100L;
    private static final long TOPIC_B = 101L;
    private static final long ADDON_PRODUCT = 20L;
    private static final long BASE_PRODUCT = 10L;
    private static final long ORIGINAL_ORDER_ID = 900L;
    private static final long ORIGINAL_REGISTRATION_ID = 321L;
    private static final String ORIGINAL_ORDER_NO = "MKO-ORIG";

    private MarketingProductMapper products;
    private MarketingProductOrderMapper orders;
    private MarketingExtraSeatMapper extraSeats;
    private CmsRegistrationMapper registrations;
    private MarketingExtraSeatServiceImpl service;

    private MarketingProduct addOn;
    private List<MarketingProductItem> items;
    private MarketingProductOrder original;
    private CmsRegistration source;

    @BeforeEach
    void setUp() {
        products = mock(MarketingProductMapper.class);
        orders = mock(MarketingProductOrderMapper.class);
        extraSeats = mock(MarketingExtraSeatMapper.class);
        registrations = mock(CmsRegistrationMapper.class);
        MarketingProductRules rules = new MarketingProductRules(products, orders,
                mock(CmsTopicMapper.class), mock(IOmsTicketService.class));
        service = new MarketingExtraSeatServiceImpl(rules, orders, extraSeats, registrations,
                new MarketingQuoteSigner(SECRET), new MarketingCashChannel(false));

        addOn = addOnProduct();
        items = Collections.singletonList(item(TOPIC));
        original = paidOriginal();
        source = selfPlaySource();

        when(products.selectById(ADDON_PRODUCT)).thenReturn(addOn);
        when(products.selectItems(ADDON_PRODUCT)).thenReturn(items);
        when(orders.selectByOrderNo(ORIGINAL_ORDER_NO)).thenReturn(original);
        when(orders.selectByOrderNoForUpdate(ORIGINAL_ORDER_NO)).thenReturn(original);
        when(orders.selectFrozenLines(ORIGINAL_ORDER_NO)).thenReturn(frozen(TOPIC));
        when(registrations.selectCmsRegistrationById(ORIGINAL_REGISTRATION_ID)).thenReturn(source);
        when(extraSeats.countPaidExtraSeatForRecipient(anyLong(), anyLong())).thenReturn(0);
    }

    // ---------------------------------------------------------------- happy path

    @Test
    void extraSeatPlanBindsOriginalOrderForSecondPlayerWithoutParty() {
        MarketingExtraSeatPlan plan = service.requirePurchasePlan(BUYER, addOn, orderRequest(FRIEND), items);

        assertEquals(ORIGINAL_ORDER_ID, plan.getOriginalOrderId().longValue());
        assertEquals(FRIEND, plan.getRecipientMemberId().longValue());
        assertEquals(Collections.singletonList(TOPIC), plan.getTopicIds());
        assertEquals(1, plan.getOriginalPaidSeats().intValue());
        // 自玩域没有队伍名册：不允许宣称"加入原队"。
        assertEquals("NOT_APPLICABLE", plan.getPartyBinding());
        assertEquals("SELF_PLAY_HAS_NO_TEAM_ROSTER", plan.getPartyBindingReason());
    }

    @Test
    void capacityFieldsAreAlwaysNullBecauseSelfPlayHasNoSeatResource() {
        MarketingExtraSeatPlan plan = service.requirePurchasePlan(BUYER, addOn, orderRequest(FRIEND), items);
        MarketingExtraSeatQuote quote = service.quote(BUYER, quoteRequest(FRIEND));

        // 负控：把 cms_topic.team_max_members 当自玩上限塞回来，这两行立刻变红，
        // 且会让第二位朋友的正常增购在 4 人配置下被误拒。
        assertNull(plan.getPartyCapacity());
        assertNull(plan.getSeatsRemaining());
        assertNull(quote.getPartyCapacity());
        assertNull(quote.getSeatsRemaining());
    }

    @Test
    void quoteSignsRecipientAndOriginalOrderSoPurchaseCannotSwapThem() {
        MarketingExtraSeatQuote quote = service.quote(BUYER, quoteRequest(FRIEND));

        assertNotNull(quote.getQuoteSign());
        assertEquals(1, quote.getQuantity().intValue());
        assertEquals(FRIEND, quote.getRecipientMemberId().longValue());
        assertEquals("NOT_APPLICABLE", quote.getPartyBinding());

        String canonical = MarketingQuotePayload.of(BUYER, addOn,
                MarketingProductOrder.FULFILLMENT_EXTRA_SEAT, 1, ORIGINAL_ORDER_ID, FRIEND,
                quote.getTotalCashAmount(), quote.getTotalPointsAmount(), quote.getSeatCount(), items,
                quote.getQuoteExpiresAt().getTime());
        assertTrue(new MarketingQuoteSigner(SECRET).matches(canonical, quote.getQuoteSign()));

        // 换成别人签名即失效：客户端不能"付一份钱加到别人队里"。
        String swapped = MarketingQuotePayload.of(BUYER, addOn,
                MarketingProductOrder.FULFILLMENT_EXTRA_SEAT, 1, ORIGINAL_ORDER_ID, OTHER,
                quote.getTotalCashAmount(), quote.getTotalPointsAmount(), quote.getSeatCount(), items,
                quote.getQuoteExpiresAt().getTime());
        assertTrue(!new MarketingQuoteSigner(SECRET).matches(swapped, quote.getQuoteSign()));
    }

    // ---------------------------------------------------------------- 多主题覆盖

    @Test
    void multiTopicAddOnIsAcceptedWhenItMatchesTheOriginalFrozenLines() {
        // 原单买了两个主题 ⇒ 加座必须也能给这一位两个主题（不是只给第一个）。
        items = Arrays.asList(item(TOPIC), item(TOPIC_B));
        when(products.selectItems(ADDON_PRODUCT)).thenReturn(items);
        when(orders.selectFrozenLines(ORIGINAL_ORDER_NO)).thenReturn(frozen(TOPIC, TOPIC_B));

        MarketingExtraSeatPlan plan = service.requirePurchasePlan(BUYER, addOn, orderRequest(FRIEND), items);

        assertEquals(Arrays.asList(TOPIC, TOPIC_B), plan.getTopicIds());
    }

    @Test
    void addOnCoveringFewerTopicsThanTheOriginalOrderIsRejected() {
        when(orders.selectFrozenLines(ORIGINAL_ORDER_NO)).thenReturn(frozen(TOPIC, TOPIC_B));

        MarketingProductException rejection = assertRejected(
                () -> service.requirePurchasePlan(BUYER, addOn, orderRequest(FRIEND), items));

        // 少覆盖 = 受赠人拿到的比承诺少（静默少发）。
        assertEquals("EXTRA_SEAT_TOPIC_MISMATCH", rejection.getReasonCode());
    }

    @Test
    void addOnCoveringExtraTopicsIsRejected() {
        items = Arrays.asList(item(TOPIC), item(TOPIC_B));
        when(products.selectItems(ADDON_PRODUCT)).thenReturn(items);

        MarketingProductException rejection = assertRejected(
                () -> service.requirePurchasePlan(BUYER, addOn, orderRequest(FRIEND), items));

        // 多覆盖 = 白送未付主题。
        assertEquals("EXTRA_SEAT_TOPIC_MISMATCH", rejection.getReasonCode());
    }

    @Test
    void addOnCoveringAnotherTopicEntirelyIsRejected() {
        when(orders.selectFrozenLines(ORIGINAL_ORDER_NO)).thenReturn(frozen(TOPIC_B));

        MarketingProductException rejection = assertRejected(
                () -> service.requirePurchasePlan(BUYER, addOn, orderRequest(FRIEND), items));

        assertEquals("EXTRA_SEAT_TOPIC_MISMATCH", rejection.getReasonCode());
    }

    @Test
    void originalOrderWithoutFrozenLinesIsRejectedRatherThanGuessed() {
        when(orders.selectFrozenLines(ORIGINAL_ORDER_NO)).thenReturn(Collections.emptyList());

        MarketingProductException rejection = assertRejected(
                () -> service.requirePurchasePlan(BUYER, addOn, orderRequest(FRIEND), items));

        assertEquals("EXTRA_SEAT_ORIGINAL_LINES_MISSING", rejection.getReasonCode());
    }

    @Test
    void addOnWithoutMatchingTopicsIsRejected() {
        items = Collections.emptyList();
        when(products.selectItems(ADDON_PRODUCT)).thenReturn(items);

        MarketingProductException rejection = assertRejected(
                () -> service.requirePurchasePlan(BUYER, addOn, orderRequest(FRIEND), items));

        assertEquals("EXTRA_SEAT_TOPIC_MISMATCH", rejection.getReasonCode());
    }

    // ---------------------------------------------------------------- 越权 / 归属

    @Test
    void originalOrderOfAnotherMemberIsForbidden() {
        original.setMemberId(OTHER);

        MarketingProductException rejection = assertRejected(
                () -> service.requirePurchasePlan(BUYER, addOn, orderRequest(FRIEND), items));

        assertEquals("ORIGINAL_ORDER_FORBIDDEN", rejection.getReasonCode());
        assertEquals(403, rejection.getHttpStatus());
    }

    @Test
    void originalRegistrationOwnedByAnotherMemberIsForbidden() {
        // 原营销单写着买家，但权威准入行属于别人（数据被改过）：不按营销单放行。
        source.setMemberId(OTHER);

        MarketingProductException rejection = assertRejected(
                () -> service.requirePurchasePlan(BUYER, addOn, orderRequest(FRIEND), items));

        assertEquals("EXTRA_SEAT_ORIGINAL_ROLE_INVALID", rejection.getReasonCode());
        assertEquals(403, rejection.getHttpStatus());
    }

    @Test
    void activityTicketOriginalScopeIsRejected() {
        // 活动/场次票走原报名与组队；把它当加座原单会绕过场次票务。
        source.setOwnerType(2L);
        source.setOwnerId(555L);

        MarketingProductException rejection = assertRejected(
                () -> service.requirePurchasePlan(BUYER, addOn, orderRequest(FRIEND), items));

        assertEquals("EXTRA_SEAT_ORIGINAL_SCOPE_INVALID", rejection.getReasonCode());
    }

    // ---------------------------------------------------------------- 原单状态

    @Test
    void unpaidOriginalOrderNeverGrants() {
        original.setStatus(MarketingProductOrder.STATUS_PENDING_CASH);

        MarketingProductException rejection = assertRejected(
                () -> service.requirePurchasePlan(BUYER, addOn, orderRequest(FRIEND), items));

        assertEquals("ORIGINAL_ORDER_NOT_PAID", rejection.getReasonCode());
        verify(extraSeats, never()).countPaidExtraSeatForRecipient(anyLong(), anyLong());
    }

    @Test
    void refundedOriginalRegistrationNeverGrants() {
        source.setPaymentStatus(4);
        source.setRegistrationStatus(3);

        MarketingProductException rejection = assertRejected(
                () -> service.requirePurchasePlan(BUYER, addOn, orderRequest(FRIEND), items));

        assertEquals("EXTRA_SEAT_ORIGINAL_INACTIVE", rejection.getReasonCode());
    }

    @Test
    void expiredOriginalPassNeverGrants() {
        source.setExpiresAt(new Date(System.currentTimeMillis() - 60_000L));

        MarketingProductException rejection = assertRejected(
                () -> service.requirePurchasePlan(BUYER, addOn, orderRequest(FRIEND), items));

        assertEquals("EXTRA_SEAT_ORIGINAL_EXPIRED", rejection.getReasonCode());
    }

    @Test
    void giftOrderCannotBeUsedAsExtraSeatBase() {
        original.setFulfillmentKind(MarketingProductOrder.FULFILLMENT_GIFT);

        MarketingProductException rejection = assertRejected(
                () -> service.requirePurchasePlan(BUYER, addOn, orderRequest(FRIEND), items));

        assertEquals("ORIGINAL_ORDER_IS_GIFT", rejection.getReasonCode());
    }

    @Test
    void addOnMustMatchOriginalProduct() {
        addOn.setParentProductId(BASE_PRODUCT + 1);

        MarketingProductException rejection = assertRejected(
                () -> service.requirePurchasePlan(BUYER, addOn, orderRequest(FRIEND), items));

        assertEquals("ADD_ON_BASE_MISMATCH", rejection.getReasonCode());
    }

    // ---------------------------------------------------------------- 重复与单笔一位

    @Test
    void recipientIsRequiredBecauseCodeClaimCannotStopDoubleSeat() {
        MarketingProductException rejection = assertRejected(
                () -> service.requirePurchasePlan(BUYER, addOn, orderRequest(null), items));

        assertEquals("EXTRA_SEAT_RECIPIENT_REQUIRED", rejection.getReasonCode());
    }

    @Test
    void buyerCannotBuyExtraSeatForHimself() {
        MarketingProductException rejection = assertRejected(
                () -> service.requirePurchasePlan(BUYER, addOn, orderRequest(BUYER), items));

        assertEquals("EXTRA_SEAT_SELF_RECIPIENT", rejection.getReasonCode());
    }

    @Test
    void sameRecipientCannotTakeSecondExtraSeatOnSameOriginalOrder() {
        when(extraSeats.countPaidExtraSeatForRecipient(ORIGINAL_ORDER_ID, FRIEND)).thenReturn(1);

        MarketingProductException rejection = assertRejected(
                () -> service.requirePurchasePlan(BUYER, addOn, orderRequest(FRIEND), items));

        assertEquals("EXTRA_SEAT_RECIPIENT_ALREADY_BOUND", rejection.getReasonCode());
    }

    @Test
    void differentRecipientsCanEachTakeOneExtraSeatOnTheSameOriginalOrder() {
        // 加座件数不设上限：不同朋友各买一份是正常商业行为（N 位 = N 笔独立付款）。
        when(extraSeats.countPaidExtraSeatForRecipient(ORIGINAL_ORDER_ID, FRIEND)).thenReturn(0);
        when(extraSeats.countPaidExtraSeatForRecipient(ORIGINAL_ORDER_ID, OTHER)).thenReturn(0);

        MarketingExtraSeatPlan first = service.requirePurchasePlan(BUYER, addOn, orderRequest(FRIEND), items);
        MarketingExtraSeatPlan second = service.requirePurchasePlan(BUYER, addOn, orderRequest(OTHER), items);

        assertEquals(FRIEND, first.getRecipientMemberId().longValue());
        assertEquals(OTHER, second.getRecipientMemberId().longValue());
        // 负控：把 team_max_members 当上限塞回来，第二单会在 2 人配置下被误拒。
        assertNull(second.getPartyCapacity());
    }

    @Test
    void oneOrderIsAlwaysOneSeat() {
        MarketingProductOrderRequest request = orderRequest(FRIEND);
        request.setQuantity(2);

        MarketingProductException rejection = assertRejected(
                () -> service.requirePurchasePlan(BUYER, addOn, request, items));

        assertEquals("EXTRA_SEAT_ONE_PER_ORDER", rejection.getReasonCode());
    }

    @Test
    void multiSeatAddOnSkuIsRejected() {
        addOn.setSeatCount(2);
        when(products.selectById(ADDON_PRODUCT)).thenReturn(addOn);

        MarketingProductException rejection = assertRejected(
                () -> service.requirePurchasePlan(BUYER, addOn, orderRequest(FRIEND), items));

        assertEquals("EXTRA_SEAT_SKU_INVALID", rejection.getReasonCode());
    }

    @Test
    void activateOnFirstUseAddOnIsRejectedSoNoUnclaimableGiftIsIssued() {
        addOn.setValidityKind(MarketingProduct.VALIDITY_ACTIVATE_ON_FIRST_USE);
        when(products.selectById(ADDON_PRODUCT)).thenReturn(addOn);

        MarketingProductException rejection = assertRejected(
                () -> service.requirePurchasePlan(BUYER, addOn, orderRequest(FRIEND), items));

        assertEquals("EXTRA_SEAT_ACTIVATION_UNSUPPORTED", rejection.getReasonCode());
    }

    @Test
    void nonAddOnProductIsRejectedByRoute() {
        MarketingProduct plain = addOnProduct();
        plain.setProductKind(MarketingProduct.KIND_SINGLE_QUEST);
        plain.setFulfillmentRoute(MarketingProduct.ROUTE_SELF_PLAY_GRANT);
        when(products.selectById(ADDON_PRODUCT)).thenReturn(plain);

        MarketingProductException rejection = assertRejected(
                () -> service.requirePurchasePlan(BUYER, plain, orderRequest(FRIEND), items));

        assertEquals("EXTRA_SEAT_PRODUCT_REQUIRED", rejection.getReasonCode());
    }

    @Test
    void originalWithoutPaidSeatsIsRejected() {
        source.setOrderNum(0L);

        MarketingProductException rejection = assertRejected(
                () -> service.requirePurchasePlan(BUYER, addOn, orderRequest(FRIEND), items));

        assertEquals("EXTRA_SEAT_ORIGINAL_SEATS_INVALID", rejection.getReasonCode());
    }

    // ---------------------------------------------------------------- fixtures

    private MarketingProductException assertRejected(org.junit.jupiter.api.function.Executable call) {
        return assertThrows(MarketingProductException.class, call);
    }

    private MarketingExtraSeatQuoteRequest quoteRequest(Long recipient) {
        MarketingExtraSeatQuoteRequest request = new MarketingExtraSeatQuoteRequest();
        request.setProductId(ADDON_PRODUCT);
        request.setOriginalOrderNo(ORIGINAL_ORDER_NO);
        request.setRecipientMemberId(recipient);
        return request;
    }

    private MarketingProductOrderRequest orderRequest(Long recipient) {
        MarketingProductOrderRequest request = new MarketingProductOrderRequest();
        request.setProductId(ADDON_PRODUCT);
        request.setFulfillmentKind(MarketingProductOrder.FULFILLMENT_EXTRA_SEAT);
        request.setOriginalOrderNo(ORIGINAL_ORDER_NO);
        request.setRecipientMemberId(recipient);
        request.setQuantity(1);
        request.setPayMode(MarketingProductOrder.PAY_MODE_POINTS);
        request.setRequestId("req-extra-seat");
        return request;
    }

    private MarketingProduct addOnProduct() {
        MarketingProduct product = new MarketingProduct();
        product.setId(ADDON_PRODUCT);
        product.setProductCode("EXTRA-SEAT-BASE");
        product.setProductName("加座");
        product.setProductKind(MarketingProduct.KIND_EXTRA_SEAT);
        product.setPurchaseKind(2);
        product.setExploreCapacity(0);
        product.setFulfillmentRoute(MarketingProduct.ROUTE_EXTRA_SEAT_GRANT);
        product.setParentProductId(BASE_PRODUCT);
        product.setValidityKind(MarketingProduct.VALIDITY_RELATIVE_DAYS);
        product.setValidDays(30);
        product.setSeatCount(1);
        product.setCashPrice(new BigDecimal("6.00"));
        product.setPointsPrice(300);
        product.setStatus(MarketingProduct.STATUS_ON_SALE);
        product.setTermsVersion("v1");
        product.setRevision(1L);
        return product;
    }

    private MarketingProductItem item(long topicId) {
        MarketingProductItem item = new MarketingProductItem();
        item.setProductId(ADDON_PRODUCT);
        item.setTopicId(topicId);
        item.setTopicTitle("主题" + topicId);
        item.setContentRevision(3L);
        return item;
    }

    /** 原单成交时冻结的分摊行——加座覆盖必须与它逐主题相等。 */
    private List<MarketingProductOrderLine> frozen(long... topicIds) {
        List<MarketingProductOrderLine> lines = new java.util.ArrayList<>();
        int sort = 0;
        for (long topicId : topicIds) {
            MarketingProductOrderLine line = new MarketingProductOrderLine();
            line.setOrderId(ORIGINAL_ORDER_ID);
            line.setTopicId(topicId);
            line.setListAmount(new BigDecimal("39.99"));
            line.setPayableAmount(new BigDecimal("39.99"));
            line.setValidityKind(1);
            line.setValidDays(7);
            line.setExploreCapacity(0);
            line.setSortNo(sort++);
            lines.add(line);
        }
        return lines;
    }

    private MarketingProductOrder paidOriginal() {
        MarketingProductOrder order = new MarketingProductOrder();
        order.setId(ORIGINAL_ORDER_ID);
        order.setOrderNo(ORIGINAL_ORDER_NO);
        order.setMemberId(BUYER);
        order.setProductId(BASE_PRODUCT);
        order.setFulfillmentKind(MarketingProductOrder.FULFILLMENT_SELF);
        order.setStatus(MarketingProductOrder.STATUS_PAID);
        order.setPrimaryRegistrationId(ORIGINAL_REGISTRATION_ID);
        order.setPayMode(MarketingProductOrder.PAY_MODE_POINTS);
        order.setQuantity(1);
        return order;
    }

    private CmsRegistration selfPlaySource() {
        CmsRegistration row = new CmsRegistration();
        row.setId(ORIGINAL_REGISTRATION_ID);
        row.setMemberId(BUYER);
        row.setOwnerType(1L);
        row.setOwnerId(TOPIC);
        row.setOrderNum(1L);
        row.setPaymentStatus(2);
        row.setRegistrationStatus(2);
        row.setPurchaseKind(2);
        return row;
    }
}
