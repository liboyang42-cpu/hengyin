package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.MarketingProduct;
import com.chengyinhub.business.domain.MarketingProductItem;
import com.chengyinhub.business.domain.MarketingProductOrder;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.MarketingProductOrderRequest;
import com.chengyinhub.business.exception.MarketingProductException;
import com.chengyinhub.business.domain.dto.MarketingExtraSeatPlan;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.GiftEntitlementMapper;
import com.chengyinhub.business.mapper.MarketingProductMapper;
import com.chengyinhub.business.mapper.MarketingProductOrderMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.mapper.UmsMemberPointsDetailMapper;
import com.chengyinhub.business.service.impl.MarketingProductPurchaseServiceImpl;
import com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService;
import com.chengyinhub.business.service.support.MarketingCashChannel;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.WechatPaymentOperations;
import com.chengyinhub.business.service.support.MarketingProductRules;
import com.chengyinhub.business.service.support.PointsValuePolicy;
import com.chengyinhub.business.service.support.MarketingQuotePayload;
import com.chengyinhub.business.service.support.MarketingQuoteSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import static org.mockito.Mockito.mock;

/** 积分成交/幂等/报价防改价/退款只标营销来源，不写第二资金真源。 */
class MarketingProductPurchaseServiceTest {

    private static final String SECRET = "unit-test-product-secret";
    private static final long MEMBER = 7L;
    private static final long PRODUCT = 10L;
    private static final long EXPIRES_AT = System.currentTimeMillis() + 60_000L;
    private static final long FRIEND = 9L;
    private static final long OTHER_MEMBER = 11L;
    private static final String ORIGINAL_ORDER_NO = "MKO-ORIG";
    private static final long ORDER_ID = 7001L;

    private MarketingProductMapper products;
    private MarketingProductOrderMapper orders;
    private GiftEntitlementMapper gifts;
    private UmsMemberMapper members;
    private UmsMemberPointsDetailMapper points;
    private IGiftEntitlementService giftService;
    private IMarketingProductFulfillmentService fulfillment;
    private IMarketingExtraSeatService extraSeatService;
    private MarketingQuoteSigner signer;
    private MarketingProductPurchaseServiceImpl service;
    private WechatVerifiedPaymentFactService verifiedFacts;
    private MarketingProduct product;
    private MarketingProduct addOn;
    private List<MarketingProductItem> items;
    private final AtomicReference<MarketingProductOrder> inserted = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        products = mock(MarketingProductMapper.class);
        orders = mock(MarketingProductOrderMapper.class);
        gifts = mock(GiftEntitlementMapper.class);
        members = mock(UmsMemberMapper.class);
        points = mock(UmsMemberPointsDetailMapper.class);
        giftService = mock(IGiftEntitlementService.class);
        fulfillment = mock(IMarketingProductFulfillmentService.class);
        extraSeatService = mock(IMarketingExtraSeatService.class);
        FulfillmentOutcome pending = new FulfillmentOutcome();
        pending.pendingActivation = true;
        when(fulfillment.grant(any())).thenReturn(pending);
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        signer = new MarketingQuoteSigner(SECRET);
        IOmsTicketService ticketService = mock(IOmsTicketService.class);
        com.chengyinhub.business.domain.OmsTicket ticket = new com.chengyinhub.business.domain.OmsTicket();
        ticket.setId(77L);
        when(ticketService.selectOmsTicketById(77L)).thenReturn(ticket);
        MarketingProductRules rules = new MarketingProductRules(products, orders, topics, ticketService);
        verifiedFacts = mock(WechatVerifiedPaymentFactService.class);
        service = new MarketingProductPurchaseServiceImpl(products, orders, gifts, members, points,
                rules, PointsValuePolicy.codeDefaults(), signer, new MarketingCashChannel(false), giftService,
                fulfillment, extraSeatService, verifiedFacts, mock(WechatPaymentOperations.class));

        product = product();
        addOn = extraSeatProduct();
        items = Arrays.asList(item(100L), item(101L));
        when(products.selectByIdForUpdate(PRODUCT)).thenReturn(product);
        when(products.selectById(PRODUCT)).thenReturn(product);
        when(products.selectItems(PRODUCT)).thenReturn(items);
        when(orders.selectByMemberRequest(anyLong(), anyString())).thenAnswer(inv -> inserted.get());
        when(orders.selectByOrderNo(anyString())).thenAnswer(inv -> inserted.get());
        when(orders.insert(any())).thenAnswer(inv -> {
            MarketingProductOrder order = inv.getArgument(0);
            order.setId(ORDER_ID);
            inserted.set(order);
            return 1;
        });
        when(orders.markPointsPaid(eq(ORDER_ID), any(), any(), any())).thenReturn(1);
        when(orders.markGrantState(eq(ORDER_ID), anyInt(), any())).thenReturn(1);
        when(orders.selectFrozenLines(anyString())).thenReturn(new ArrayList<>());
        when(orders.insertFrozenLine(any())).thenReturn(1);
        when(orders.markRefunded(eq(ORDER_ID), anyString(), any(), any())).thenReturn(1);
        when(members.selectUmsMemberById(MEMBER)).thenReturn(member(500L));
        when(members.deductPoints(MEMBER, 120)).thenReturn(1);
        when(points.insertUmsMemberPointsDetail(any())).thenAnswer(inv -> {
            com.chengyinhub.business.domain.UmsMemberPointsDetail detail = inv.getArgument(0);
            detail.setId(55L);
            return 1;
        });
    }

    @Test
    void pointsPurchaseMarksPaidAndWaitsForGrantPort() {
        Map<String, Object> result = service.purchase(MEMBER, signed(request(), 120));

        assertEquals(MarketingProductOrder.STATUS_PAID, result.get("status"));
        verify(orders).markGrantState(eq(ORDER_ID),
                eq(MarketingProductOrder.GRANT_AWAITING_ACTIVATION), any());
        assertEquals(120, result.get("pointsAmount"));
        verify(members).deductPoints(MEMBER, 120);
        verify(orders).markPointsPaid(eq(ORDER_ID), eq(55L), any(), any());
        verify(orders).bindRegistrations(eq(ORDER_ID), isNull(), isNull(), isNull(), any());
    }

    @Test
    void orderSnapshotFreezesSeatCountPerUnitSoLiveConfigCannotChangePaidShape() {
        // 2 份 × 每份 2 席：M2 用 seatCount × quantity 得 4 个名额。
        product.setSeatCount(2);
        when(products.selectByIdForUpdate(PRODUCT)).thenReturn(product);
        when(members.deductPoints(MEMBER, 240)).thenReturn(1);
        MarketingProductOrderRequest two = request();
        two.setQuantity(2);
        signed(two, 240);

        service.purchase(MEMBER, two);

        com.alibaba.fastjson2.JSONObject frozen =
                com.alibaba.fastjson2.JSON.parseObject(inserted.get().getProductSnapshotJson());

        // 负控 1：删掉 snapshot 里那行 seatCount，M2-F 的 paidShape() 抛 SHAPE_SNAPSHOT_MISSING，
        //          新单直接发不出（不回落到 live SKU，不按 1 兜底）。
        // 负控 2：写成 seatTotal（每份×份数=4），M2 再乘一次 quantity 会得到 8 —— 多领一倍。
        assertEquals(2, frozen.getIntValue("seatCount"));
        assertEquals(2, frozen.getIntValue("quantity"));
    }

    @Test
    void orderSnapshotSeatCountIsPerUnitForSingleQuantityToo() {
        service.purchase(MEMBER, signed(request(), 120));

        com.alibaba.fastjson2.JSONObject frozen =
                com.alibaba.fastjson2.JSON.parseObject(inserted.get().getProductSnapshotJson());

        assertEquals(1, frozen.getIntValue("seatCount"));
        assertEquals(1, frozen.getIntValue("quantity"));
    }

    @Test
    void replaySameRequestDoesNotDeductTwice() {
        service.purchase(MEMBER, signed(request(), 120));
        service.purchase(MEMBER, signed(request(), 120));

        verify(members, times(1)).deductPoints(MEMBER, 120);
        verify(orders, times(1)).insert(any());
    }

    @Test
    void tamperedQuoteIsRejectedBeforeMoneyMoves() {
        MarketingProductOrderRequest request = signed(request(), 1);

        MarketingProductException rejection = assertThrows(MarketingProductException.class,
                () -> service.purchase(MEMBER, request));

        assertEquals("QUOTE_STALE", rejection.getReasonCode());
        verify(members, never()).deductPoints(anyLong(), anyInt());
        verify(orders, never()).insert(any());
    }

    @Test
    void refundOnlyMarksMarketingSourceAndNeverWritesPoints() {
        MarketingProductOrder paid = paidOrder();
        when(orders.selectByOrderNoForUpdate("MKO-PAID")).thenReturn(paid);
        when(orders.selectByOrderNo("MKO-PAID")).thenReturn(paid);

        service.revokeAfterRefund("MKO-PAID", "REFUND");

        verify(orders).markRefunded(eq(ORDER_ID), eq("REFUND"), any(), any());
        verify(members, never()).addPoints(anyLong(), anyInt());
        verify(points, never()).insertUmsMemberPointsDetail(any());
    }

    @Test
    void firstUseActivationReadsFrozenLineAndIsReplaySafe() {
        MarketingProductOrder paid = paidOrder();
        paid.setRequestId("req1");
        paid.setFulfillmentState(MarketingProductOrder.GRANT_AWAITING_ACTIVATION);
        when(orders.selectPendingActivation(MEMBER, 100L)).thenReturn(paid);
        FulfillmentOutcome activated = new FulfillmentOutcome();
        activated.registrationIds = new ArrayList<>(java.util.Collections.singletonList(4242L));
        when(fulfillment.activateOnFirstUse(eq("req1"), eq(MEMBER), eq(100L), any())).thenReturn(activated);

        Map<String, Object> first = service.activateOnUse(MEMBER, 100L);
        Map<String, Object> replay = service.activateOnUse(MEMBER, 100L);

        assertEquals(java.util.Collections.singletonList(4242L), first.get("activationRegistrations"));
        assertEquals(java.util.Collections.singletonList(4242L), replay.get("activationRegistrations"));
        verify(fulfillment, times(2)).activateOnFirstUse(eq("req1"), eq(MEMBER), eq(100L), any());
    }

    @Test
    void purePointsProductAnchorsPayableOnPointsValuePolicy() {
        MarketingProduct purePoints = product();
        purePoints.setCashPrice(null);
        product = purePoints;
        when(products.selectByIdForUpdate(PRODUCT)).thenReturn(purePoints);
        when(products.selectById(PRODUCT)).thenReturn(purePoints);
        FulfillmentOutcome done = new FulfillmentOutcome();
        done.registrationIds = new ArrayList<>(java.util.Collections.singletonList(1L));
        when(fulfillment.grant(any())).thenReturn(done);

        service.purchase(MEMBER, signed(request(), 120));

        ArgumentCaptor<FulfillmentCommand> command = ArgumentCaptor.forClass(FulfillmentCommand.class);
        verify(fulfillment).grant(command.capture());
        BigDecimal payableSum = BigDecimal.ZERO;
        for (GrantLine line : command.getValue().lines) payableSum = payableSum.add(line.payableAmount);
        // 120 积分 × 0.02 = 2.40，与 M2F verifiedPayableAmount 同一锚点。
        assertEquals(0, payableSum.compareTo(new BigDecimal("2.40")));
    }

    @Test
    void ticketRouteIsRejectedAtPurchase() {
        MarketingProduct group = product();
        group.setFulfillmentRoute(MarketingProduct.ROUTE_TICKET_REGISTRATION);
        group.setTicketId(77L);
        when(products.selectByIdForUpdate(PRODUCT)).thenReturn(group);

        MarketingProductException rejection = assertThrows(MarketingProductException.class,
                () -> service.purchase(MEMBER, signed(request(), 120)));

        assertEquals("USE_REGISTRATION_CHECKOUT", rejection.getReasonCode());
        verify(orders, never()).insert(any());
    }

    @Test
    void paidCallbackRejectsAmountMismatchWithoutSettling() {
        MarketingProductOrder order = paidOrder();
        order.setStatus(MarketingProductOrder.STATUS_PENDING_CASH);
        order.setPayMode(MarketingProductOrder.PAY_MODE_CASH);
        when(orders.selectByOrderNo("MKO-CASH")).thenReturn(order);

        boolean accepted = service.handlePaidOrder("MKO-CASH", true, 1,
                "tx", new com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference(
                        1L, "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"));

        assertFalse(accepted);
        verify(verifiedFacts, never()).requireVerified(any());
    }

    @Test
    void extraSeatPurchaseTakesItsPlanFromAdmissionServiceAndSignsRecipient() {
        when(products.selectByIdForUpdate(PRODUCT)).thenReturn(addOn);
        MarketingExtraSeatPlan plan = extraSeatPlan(FRIEND);
        when(extraSeatService.requirePurchasePlan(anyLong(), any(), any(), any())).thenReturn(plan);

        MarketingProductOrderRequest request = extraSeatRequest(FRIEND);
        signedExtraSeat(request, plan);
        service.purchase(MEMBER, request);

        // 真实 caller：purchase 真的把准入判定交给 plan，而不是自己按 parentOrderNo 放行。
        verify(extraSeatService).requirePurchasePlan(eq(MEMBER), eq(addOn), same(request), eq(items));
        // 裁C：加座是买家名下的第二行（payable=加座实付）+ 同一"领取转移"来源，
        // 不改原单数量、不建第二权益表。
        verify(orders).insert(any());
        verify(giftService).issue(eq(inserted.get()), any());
        assertEquals(ORDER_ID, inserted.get().getOriginalOrderId().longValue());
        assertEquals(FRIEND, inserted.get().getGiftRecipientMemberId().longValue());
        assertEquals(MarketingProductOrder.FULFILLMENT_EXTRA_SEAT,
                inserted.get().getFulfillmentKind().intValue());
    }

    @Test
    void extraSeatPurchaseRejectsQuoteSignedForAnotherRecipient() {
        when(products.selectByIdForUpdate(PRODUCT)).thenReturn(addOn);
        MarketingExtraSeatPlan plan = extraSeatPlan(FRIEND);
        when(extraSeatService.requirePurchasePlan(anyLong(), any(), any(), any())).thenReturn(plan);

        MarketingProductOrderRequest request = extraSeatRequest(OTHER_MEMBER);
        signedExtraSeat(request, plan);

        MarketingProductException rejection = assertThrows(MarketingProductException.class,
                () -> service.purchase(MEMBER, request));

        // 换受赠人即签名不符：客户端不能"付一份钱把这一位加给别人"。
        assertEquals("QUOTE_STALE", rejection.getReasonCode());
        verify(members, never()).deductPoints(anyLong(), anyInt());
        verify(orders, never()).insert(any());
    }

    @Test
    void extraSeatPurchaseRejectsQuantityAboveOneEvenWithValidSignature() {
        when(products.selectByIdForUpdate(PRODUCT)).thenReturn(addOn);
        MarketingExtraSeatPlan plan = extraSeatPlan(FRIEND);
        when(extraSeatService.requirePurchasePlan(anyLong(), any(), any(), any())).thenReturn(plan);

        MarketingProductOrderRequest request = extraSeatRequest(FRIEND);
        request.setQuantity(2);
        signedExtraSeat(request, plan);
        when(extraSeatService.requirePurchasePlan(anyLong(), any(), eq(request), any()))
                .thenThrow(MarketingProductException.badRequest("EXTRA_SEAT_ONE_PER_ORDER",
                        "一份加座只对应一位；多人请分别下单"));

        MarketingProductException rejection = assertThrows(MarketingProductException.class,
                () -> service.purchase(MEMBER, request));

        assertEquals("EXTRA_SEAT_ONE_PER_ORDER", rejection.getReasonCode());
        verify(orders, never()).insert(any());
    }

    @Test
    void giftPurchaseIssuesSourceRegistrationForLaterClaim() {
        MarketingProduct gift = product();
        gift.setGiftAllowed(1);
        product = gift;
        gift.setValidityKind(MarketingProduct.VALIDITY_RELATIVE_DAYS);
        when(products.selectByIdForUpdate(PRODUCT)).thenReturn(gift);
        when(products.selectById(PRODUCT)).thenReturn(gift);
        FulfillmentOutcome done = new FulfillmentOutcome();
        done.registrationIds = new ArrayList<>(java.util.Collections.singletonList(777L));
        when(fulfillment.grant(any())).thenReturn(done);

        MarketingProductOrderRequest request = request();
        request.setFulfillmentKind(MarketingProductOrder.FULFILLMENT_GIFT);
        request.setRecipientMemberId(9L);
        signed(request, 120);
        service.purchase(MEMBER, request);

        assertEquals(777L, inserted.get().getPrimaryRegistrationId().longValue());
        verify(giftService).issue(eq(inserted.get()), any());
    }

    @Test
    void paidCallbackContractFailsClosedWithoutSettling() {
        MarketingProductOrder pending = paidOrder();
        pending.setStatus(MarketingProductOrder.STATUS_PENDING_CASH);
        pending.setPayMode(MarketingProductOrder.PAY_MODE_CASH);
        when(orders.selectByOrderNo("MKO-CASH")).thenReturn(pending);

        WechatVerifiedPaymentFact.Reference ref =
                new com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference(
                        1L, "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");

        assertFalse(service.handlePaidOrder("MKO-CASH", false, 3999, "tx", ref), "非 SUCCESS 不成交");
        assertFalse(service.handlePaidOrder("MKO-UNKNOWN", true, 3999, "tx", ref), "未知单号不成交");
        assertFalse(service.handlePaidOrder("MKO-CASH", true, null, "tx", ref), "缺分额不成交");
        verify(verifiedFacts, never()).requireVerified(any());
        verify(orders, never()).markCashPaid(anyLong(), anyLong(), any(), any());
    }

    @Test
    void prepareCashPaymentStaysClosedUntilRouteEnabled() {
        MarketingProductException rejection = assertThrows(MarketingProductException.class,
                () -> service.prepareCashPayment(MEMBER, "MKO-ANY", null));
        assertEquals("PAYMENT_OWNER_ROUTE_NOT_WIRED", rejection.getReasonCode());
    }

    // ------------------------------------------------------------------ fixtures

    private MarketingProduct product() {
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

    private MarketingProductItem item(long topicId) {
        MarketingProductItem item = new MarketingProductItem();
        item.setProductId(PRODUCT);
        item.setTopicId(topicId);
        item.setTopicTitle("作品" + topicId);
        item.setContentRevision(5L);
        return item;
    }

    private UmsMember member(long point) {
        UmsMember member = new UmsMember();
        member.setId(MEMBER);
        member.setPoint(point);
        return member;
    }

    private MarketingProductOrder paidOrder() {
        MarketingProductOrder order = new MarketingProductOrder();
        order.setId(ORDER_ID);
        order.setOrderNo("MKO-PAID");
        order.setMemberId(MEMBER);
        order.setProductId(PRODUCT);
        order.setStatus(MarketingProductOrder.STATUS_PAID);
        order.setPayMode(MarketingProductOrder.PAY_MODE_POINTS);
        order.setFulfillmentKind(MarketingProductOrder.FULFILLMENT_SELF);
        order.setQuantity(1);
        order.setTotalPointsAmount(120);
        order.setTotalCashAmount(new BigDecimal("39.99"));
        order.setCurrency("CNY");
        return order;
    }

    private MarketingProductOrderRequest request() {
        MarketingProductOrderRequest request = new MarketingProductOrderRequest();
        request.setProductId(PRODUCT);
        request.setPayMode(MarketingProductOrder.PAY_MODE_POINTS);
        request.setQuantity(1);
        request.setFulfillmentKind(MarketingProductOrder.FULFILLMENT_SELF);
        request.setQuoteExpiresAt(EXPIRES_AT);
        request.setRequestId("req1");
        return request;
    }

    private MarketingProductOrderRequest signed(MarketingProductOrderRequest request, int points) {
        int quantity = request.getQuantity() == null ? 1 : request.getQuantity();
        BigDecimal cash = product.getCashPrice() == null ? null
                : product.getCashPrice().multiply(BigDecimal.valueOf(quantity));
        request.setQuoteSign(signer.sign(MarketingQuotePayload.of(MEMBER, product,
                request.getFulfillmentKind(), quantity, null, request.getRecipientMemberId(),
                cash, points, MarketingQuotePayload.seatTotal(product, quantity),
                items, request.getQuoteExpiresAt())));
        return request;
    }

    /** 加座 SKU：route=3 + kind=8，父商品是被加座的普通通行证。 */
    private MarketingProduct extraSeatProduct() {
        MarketingProduct addOn = product();
        addOn.setProductKind(MarketingProduct.KIND_EXTRA_SEAT);
        addOn.setFulfillmentRoute(MarketingProduct.ROUTE_EXTRA_SEAT_GRANT);
        addOn.setParentProductId(PRODUCT);
        addOn.setValidityKind(MarketingProduct.VALIDITY_RELATIVE_DAYS);
        return addOn;
    }

    private MarketingExtraSeatPlan extraSeatPlan(long recipient) {
        MarketingExtraSeatPlan plan = new MarketingExtraSeatPlan();
        plan.setOriginalOrderId(ORDER_ID);
        plan.setOriginalOrderNo(ORIGINAL_ORDER_NO);
        plan.setRecipientMemberId(recipient);
        plan.setOriginalPaidSeats(1);
        plan.setPartyBinding("NOT_APPLICABLE");
        plan.setPartyBindingReason("SELF_PLAY_HAS_NO_TEAM_ROSTER");
        plan.setAdmissionMode("GIFT_SINGLE_SOURCE");
        return plan;
    }

    private MarketingProductOrderRequest extraSeatRequest(long recipient) {
        MarketingProductOrderRequest request = request();
        request.setFulfillmentKind(MarketingProductOrder.FULFILLMENT_EXTRA_SEAT);
        request.setOriginalOrderNo(ORIGINAL_ORDER_NO);
        request.setRecipientMemberId(recipient);
        return request;
    }

    /**
     * 按加座真实载荷签名：商品=加座 SKU、fulfillment=3、原单 id 与受赠人都在签名里。
     * 负控：把 {@code plan} / {@code originalOrderId} 换成别的值，purchase 立刻 QUOTE_STALE。
     */
    private void signedExtraSeat(MarketingProductOrderRequest request, MarketingExtraSeatPlan plan) {
        int quantity = request.getQuantity() == null ? 1 : request.getQuantity();
        BigDecimal cash = addOn.getCashPrice() == null ? null
                : addOn.getCashPrice().multiply(BigDecimal.valueOf(quantity));
        request.setQuoteSign(signer.sign(MarketingQuotePayload.of(MEMBER, addOn,
                MarketingProductOrder.FULFILLMENT_EXTRA_SEAT, quantity,
                plan.getOriginalOrderId(), request.getRecipientMemberId(),
                cash, 120 * quantity, MarketingQuotePayload.seatTotal(addOn, quantity),
                items, request.getQuoteExpiresAt())));
    }
}
