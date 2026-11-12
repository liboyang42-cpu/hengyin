package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.MarketingProduct;
import com.chengyinhub.business.domain.MarketingProductItem;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.dto.MarketingGroupCheckoutCreateRequest;
import com.chengyinhub.business.domain.dto.MarketingGroupCheckoutQuoteRequest;
import com.chengyinhub.business.domain.dto.MarketingGroupCheckoutQuoteResult;
import com.chengyinhub.business.domain.dto.MarketingGroupCheckoutResult;
import com.chengyinhub.business.domain.dto.RegistrationQuoteResult;
import com.chengyinhub.business.domain.dto.RegistrationRequest;
import com.chengyinhub.business.exception.MarketingProductException;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.MarketingProductMapper;
import com.chengyinhub.business.mapper.MarketingProductOrderMapper;
import com.chengyinhub.business.service.impl.MarketingGroupCheckoutServiceImpl;
import com.chengyinhub.business.service.support.MarketingProductRules;
import com.chengyinhub.common.config.ComplianceProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * MG 直接单测：商品→活动票种/主题/场次解析，报价与建单原样委托既有报名服务。
 *
 * <p>这里的 Mockito 只替代外部边界（目录/票/活动/原结算服务）；一旦真实 DB/HTTP 未跑，
 * 报告必须标 NOT_RUN，不能把本测试当端到端完成证明。
 */
class MarketingGroupCheckoutServiceTest {

    private static final long MEMBER = 7L;
    private static final long PRODUCT = 10L;
    private static final long ACTIVITY = 900L;
    private static final long TOPIC = 100L;
    private static final long TICKET = 88L;
    private static final String CONSENT_DOC = "activity_host_data_sharing";
    private static final String CONSENT_SCENE = "activity_signup";
    private static final String CONSENT_VERSION = "v1";

    private MarketingProductMapper products;
    private MarketingProductOrderMapper orders;
    private CmsTopicMapper topics;
    private IOmsTicketService tickets;
    private ICmsActivityService activities;
    private IRegistrationCheckoutService checkout;
    private IConsentRecordService consents;
    private ComplianceProperties compliance;
    private MarketingGroupCheckoutServiceImpl service;

    @BeforeEach
    void setUp() {
        products = mock(MarketingProductMapper.class);
        orders = mock(MarketingProductOrderMapper.class);
        topics = mock(CmsTopicMapper.class);
        tickets = mock(IOmsTicketService.class);
        activities = mock(ICmsActivityService.class);
        checkout = mock(IRegistrationCheckoutService.class);
        consents = mock(IConsentRecordService.class);
        compliance = new ComplianceProperties();
        compliance.getDocVersions().put(CONSENT_DOC, CONSENT_VERSION);

        MarketingProductRules rules = new MarketingProductRules(products, orders, topics, tickets);
        service = new MarketingGroupCheckoutServiceImpl(
                rules, activities, checkout, consents, compliance);

        when(products.selectById(PRODUCT)).thenReturn(product());
        when(products.selectItems(PRODUCT)).thenReturn(Collections.singletonList(item(TOPIC)));
        when(tickets.selectOmsTicketById(TICKET)).thenReturn(ticket());
        when(activities.selectCmsActivityById(ACTIVITY)).thenReturn(activity(TOPIC));
        when(consents.hasCurrentAgreement(MEMBER, CONSENT_DOC, CONSENT_SCENE, CONSENT_VERSION))
                .thenReturn(true);
    }

    @Test
    void quoteResolvesProductToRealActivityTicketAndReusesOriginalQuote() {
        RegistrationQuoteResult original = new RegistrationQuoteResult();
        original.setPayAmount(new BigDecimal("90.00"));
        original.setQuoteSign("quote-sign");
        when(checkout.quote(eq(MEMBER), any(RegistrationRequest.class))).thenReturn(original);

        MarketingGroupCheckoutQuoteResult result = service.quote(MEMBER, quoteRequest(3));

        ArgumentCaptor<RegistrationRequest> captor = ArgumentCaptor.forClass(RegistrationRequest.class);
        verify(checkout).quote(eq(MEMBER), captor.capture());
        RegistrationRequest sent = captor.getValue();
        assertEquals(2, sent.getOwnerType().intValue());
        assertEquals(ACTIVITY, sent.getOwnerId().longValue());
        assertNull(sent.getTicketId());
        assertEquals(1, sent.getTickets().size());
        assertEquals(TICKET, sent.getTickets().get(0).getTicketId().longValue());
        assertEquals(3, sent.getTickets().get(0).getCount().intValue());
        assertEquals(0, sent.getIsUsePoint().intValue());
        assertNull(sent.getRealName());
        assertNull(sent.getRequestId());

        assertEquals(2, result.getPurchaseRoute().intValue());
        assertEquals("USE_REGISTRATION_CHECKOUT", result.getPurchaseRouteReason());
        assertEquals("REGISTRATION_CHECKOUT", result.getAmountAuthority());
        assertEquals(ACTIVITY, result.getActivityId().longValue());
        assertEquals(TOPIC, result.getTopicId().longValue());
        assertEquals(TICKET, result.getTicketId().longValue());
        assertEquals(10L, result.getRemainingInventory().longValue());
        assertEquals("quote-sign", result.getQuote().getQuoteSign());
        assertEquals("/api/marketing/group-checkout/quote", result.getQuoteEndpoint());
        assertEquals("/api/marketing/group-checkout/create", result.getCreateEndpoint());
    }

    @Test
    void catalogPriceIsNotUsedAsPayableAmount() {
        MarketingProduct expensiveCatalog = product();
        expensiveCatalog.setCashPrice(new BigDecimal("999.00"));
        when(products.selectById(PRODUCT)).thenReturn(expensiveCatalog);
        RegistrationQuoteResult original = new RegistrationQuoteResult();
        original.setPayAmount(new BigDecimal("90.00"));
        original.setQuoteSign("quote-sign");
        when(checkout.quote(eq(MEMBER), any(RegistrationRequest.class))).thenReturn(original);

        MarketingGroupCheckoutQuoteResult result = service.quote(MEMBER, quoteRequest(3));

        assertEquals(new BigDecimal("999.00"), result.getCatalogCashPrice());
        assertEquals(new BigDecimal("90.00"), result.getQuote().getPayAmount());
    }

    @Test
    void configuredSeatCountDoesNotMultiplyPurchasedTickets() {
        MarketingProduct seated = product();
        seated.setSeatCount(5);
        when(products.selectById(PRODUCT)).thenReturn(seated);
        when(checkout.quote(eq(MEMBER), any(RegistrationRequest.class)))
                .thenReturn(new RegistrationQuoteResult());

        service.quote(MEMBER, quoteRequest(2));

        ArgumentCaptor<RegistrationRequest> captor = ArgumentCaptor.forClass(RegistrationRequest.class);
        verify(checkout).quote(eq(MEMBER), captor.capture());
        assertEquals(2, captor.getValue().getTickets().get(0).getCount().intValue());
    }

    @Test
    void crossProductTicketIsRejectedBeforeOriginalQuote() {
        when(activities.selectCmsActivityById(ACTIVITY)).thenReturn(activity(999L));

        MarketingProductException rejected = assertReason("GROUP_TICKET_PRODUCT_MISMATCH",
                () -> service.quote(MEMBER, quoteRequest(1)));

        assertEquals(403, rejected.getHttpStatus());
        verifyNoInteractions(checkout);
    }

    @Test
    void topicOwnedTicketIsRejectedBecauseRegistrationWouldBecomeSelfPlayPass() {
        OmsTicket topicTicket = ticket();
        topicTicket.setOwnerType(1L);
        topicTicket.setOwnerId(TOPIC);
        when(tickets.selectOmsTicketById(TICKET)).thenReturn(topicTicket);

        assertReason("GROUP_TICKET_OWNER_UNSUPPORTED", () -> service.quote(MEMBER, quoteRequest(1)));

        verify(activities, never()).selectCmsActivityById(anyLong());
        verifyNoInteractions(checkout);
    }

    @Test
    void activityWithoutTopicIsRejected() {
        when(activities.selectCmsActivityById(ACTIVITY)).thenReturn(activity(null));

        assertReason("GROUP_ACTIVITY_TOPIC_MISSING", () -> service.quote(MEMBER, quoteRequest(1)));

        verifyNoInteractions(checkout);
    }

    @Test
    void missingActivityIsRejected() {
        when(activities.selectCmsActivityById(ACTIVITY)).thenReturn(null);

        assertReason("GROUP_ACTIVITY_NOT_FOUND", () -> service.quote(MEMBER, quoteRequest(1)));

        verifyNoInteractions(checkout);
    }

    @Test
    void nonGroupProductKindIsRejected() {
        MarketingProduct pass = product();
        pass.setProductKind(MarketingProduct.KIND_CITY_BUNDLE);
        when(products.selectById(PRODUCT)).thenReturn(pass);

        assertReason("GROUP_PRODUCT_KIND_INVALID", () -> service.quote(MEMBER, quoteRequest(1)));

        verifyNoInteractions(checkout);
    }

    @Test
    void nonTicketRouteIsRejected() {
        MarketingProduct selfPlay = product();
        selfPlay.setFulfillmentRoute(MarketingProduct.ROUTE_SELF_PLAY_GRANT);
        selfPlay.setTicketId(null);
        when(products.selectById(PRODUCT)).thenReturn(selfPlay);

        assertReason("GROUP_PRODUCT_ROUTE_INVALID", () -> service.quote(MEMBER, quoteRequest(1)));

        verifyNoInteractions(checkout);
    }

    @Test
    void groupProductWithExplorePurchaseKindIsRejected() {
        MarketingProduct misconfigured = product();
        misconfigured.setPurchaseKind(3);
        when(products.selectById(PRODUCT)).thenReturn(misconfigured);

        assertReason("GROUP_PURCHASE_KIND_INVALID", () -> service.quote(MEMBER, quoteRequest(1)));

        verifyNoInteractions(checkout);
    }

    @Test
    void quantityOutsideOriginalBoundsIsRejected() {
        assertReason("QUANTITY_INVALID", () -> service.quote(MEMBER, quoteRequest(101)));
        assertReason("QUANTITY_INVALID", () -> service.quote(MEMBER, quoteRequest(0)));

        verifyNoInteractions(checkout);
    }

    @Test
    void originalQuoteRejectionForExpiredSessionPropagatesUnchanged() {
        RegistrationCheckoutException rejection =
                new RegistrationCheckoutException(409, "该场次已开始，不能再报名");
        when(checkout.quote(eq(MEMBER), any(RegistrationRequest.class))).thenThrow(rejection);

        assertSame(rejection, assertThrows(RegistrationCheckoutException.class,
                () -> service.quote(MEMBER, quoteRequest(1))));
    }

    @Test
    void createForwardsOriginalRegistrationFactsAndQuoteSign() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("registrationId", 555L);
        data.put("registrationIds", List.of(555L));
        data.put("payableAmount", new BigDecimal("90.00"));
        data.put("payParams", Map.of("timeStamp", "1"));
        when(checkout.createOrReplay(eq(MEMBER), any(RegistrationRequest.class)))
                .thenReturn(new RegistrationCheckoutResult("报名创建成功", data));

        MarketingGroupCheckoutResult result = service.create(MEMBER, createRequest());

        ArgumentCaptor<RegistrationRequest> captor = ArgumentCaptor.forClass(RegistrationRequest.class);
        verify(checkout).createOrReplay(eq(MEMBER), captor.capture());
        RegistrationRequest sent = captor.getValue();
        assertEquals(2, sent.getOwnerType().intValue());
        assertEquals(ACTIVITY, sent.getOwnerId().longValue());
        assertEquals("req-1", sent.getRequestId());
        assertEquals("quote-sign", sent.getQuoteSign());
        assertEquals("marketing-sign", sent.getMarketingSign());
        assertEquals("张三", sent.getRealName());
        assertEquals("13800000000", sent.getPhone());
        assertEquals("app", sent.getPayChannel());
        assertEquals(77L, sent.getSourceClubId().longValue());
        assertEquals("grp-channel", sent.getSourceChannelCode());
        assertEquals(2, sent.getTickets().get(0).getCount().intValue());

        assertEquals(555L, result.getRegistrationId().longValue());
        assertEquals(List.of(555L), result.getRegistrationIds());
        assertEquals("报名创建成功", result.getMessage());
        assertEquals(ACTIVITY, result.getActivityId().longValue());
        assertEquals(TOPIC, result.getTopicId().longValue());
        assertEquals(TICKET, result.getTicketId().longValue());
        assertEquals(new BigDecimal("90.00"), result.getRegistration().get("payableAmount"));
    }

    @Test
    void duplicateRequestIdReturnsTheOriginalRegistrationFacts() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("registrationId", 555L);
        data.put("registrationIds", List.of(555L));
        when(checkout.createOrReplay(eq(MEMBER), any(RegistrationRequest.class)))
                .thenReturn(new RegistrationCheckoutResult("报名创建成功", data));

        MarketingGroupCheckoutResult first = service.create(MEMBER, createRequest());
        MarketingGroupCheckoutResult replay = service.create(MEMBER, createRequest());

        assertEquals(555L, first.getRegistrationId().longValue());
        assertEquals(555L, replay.getRegistrationId().longValue());
        ArgumentCaptor<RegistrationRequest> captor = ArgumentCaptor.forClass(RegistrationRequest.class);
        verify(checkout, times(2)).createOrReplay(eq(MEMBER), captor.capture());
        assertEquals("req-1", captor.getAllValues().get(0).getRequestId());
        assertEquals("req-1", captor.getAllValues().get(1).getRequestId());
    }

    @Test
    void changedPriceOnCreateIsRejectedByOriginalSignCheck() {
        RegistrationCheckoutException rejection =
                new RegistrationCheckoutException(409, "价格已更新，请重新确认");
        when(checkout.createOrReplay(eq(MEMBER), any(RegistrationRequest.class))).thenThrow(rejection);

        assertSame(rejection, assertThrows(RegistrationCheckoutException.class,
                () -> service.create(MEMBER, createRequest())));
    }

    @Test
    void createWithoutQuoteSignIsRejectedBeforeOriginalCheckout() {
        MarketingGroupCheckoutCreateRequest request = createRequest();
        request.setQuoteSign("  ");

        assertReason("QUOTE_SIGN_REQUIRED", () -> service.create(MEMBER, request));

        verifyNoInteractions(checkout);
    }

    @Test
    void createWithoutRequestIdIsRejectedBeforeOriginalCheckout() {
        MarketingGroupCheckoutCreateRequest request = createRequest();
        request.setRequestId("");

        assertReason("REQUEST_ID_REQUIRED", () -> service.create(MEMBER, request));

        verifyNoInteractions(checkout);
    }

    @Test
    void createWithOversizedRequestIdIsRejectedBeforeOriginalCheckout() {
        MarketingGroupCheckoutCreateRequest request = createRequest();
        request.setRequestId("r".repeat(65));

        assertReason("REQUEST_ID_INVALID", () -> service.create(MEMBER, request));

        verifyNoInteractions(checkout);
    }

    @Test
    void createWithoutCurrentSignupConsentIsRejectedBeforeOriginalCheckout() {
        when(consents.hasCurrentAgreement(MEMBER, CONSENT_DOC, CONSENT_SCENE, CONSENT_VERSION))
                .thenReturn(false);

        assertReason("SIGNUP_DATA_SHARING_CONSENT_REQUIRED", () -> service.create(MEMBER, createRequest()));

        verifyNoInteractions(checkout);
    }

    @Test
    void createWithoutRealNameIsRejectedBeforeOriginalCheckout() {
        MarketingGroupCheckoutCreateRequest request = createRequest();
        request.setRealName(" ");

        assertReason("REAL_NAME_REQUIRED", () -> service.create(MEMBER, request));

        verifyNoInteractions(checkout);
    }

    @Test
    void createWithoutPhoneIsRejectedBeforeOriginalCheckout() {
        MarketingGroupCheckoutCreateRequest request = createRequest();
        request.setPhone(null);

        assertReason("PHONE_REQUIRED", () -> service.create(MEMBER, request));

        verifyNoInteractions(checkout);
    }

    private MarketingProductException assertReason(String reasonCode, Executable action) {
        MarketingProductException rejected = assertThrows(MarketingProductException.class, action);
        assertEquals(reasonCode, rejected.getReasonCode());
        return rejected;
    }

    private MarketingGroupCheckoutQuoteRequest quoteRequest(int quantity) {
        MarketingGroupCheckoutQuoteRequest request = new MarketingGroupCheckoutQuoteRequest();
        request.setProductId(PRODUCT);
        request.setQuantity(quantity);
        request.setIsUsePoint(0);
        return request;
    }

    private MarketingGroupCheckoutCreateRequest createRequest() {
        MarketingGroupCheckoutCreateRequest request = new MarketingGroupCheckoutCreateRequest();
        request.setProductId(PRODUCT);
        request.setQuantity(2);
        request.setIsUsePoint(0);
        request.setRealName("张三");
        request.setPhone("13800000000");
        request.setEmail("a@example.com");
        request.setRequestId("req-1");
        request.setQuoteSign("quote-sign");
        request.setMarketingSign("marketing-sign");
        request.setPayChannel("app");
        request.setSourceClubId(77L);
        request.setSourceChannelCode("grp-channel");
        return request;
    }

    private MarketingProduct product() {
        MarketingProduct product = new MarketingProduct();
        product.setId(PRODUCT);
        product.setProductCode("GROUP-DEMO");
        product.setProductName("团建五人");
        product.setProductKind(MarketingProduct.KIND_GROUP_TEAM);
        product.setPurchaseKind(2);
        product.setExploreCapacity(0);
        product.setFulfillmentRoute(MarketingProduct.ROUTE_TICKET_REGISTRATION);
        product.setTicketId(TICKET);
        product.setCashPrice(new BigDecimal("150.00"));
        product.setPointsPrice(1200);
        product.setValidityKind(MarketingProduct.VALIDITY_NONE);
        product.setSeatCount(5);
        product.setStatus(MarketingProduct.STATUS_ON_SALE);
        product.setRevision(3L);
        product.setTermsVersion("terms-v1");
        return product;
    }

    private MarketingProductItem item(Long topicId) {
        MarketingProductItem item = new MarketingProductItem();
        item.setProductId(PRODUCT);
        item.setTopicId(topicId);
        item.setContentRevision(1L);
        return item;
    }

    private OmsTicket ticket() {
        OmsTicket ticket = new OmsTicket();
        ticket.setId(TICKET);
        ticket.setOwnerType(2L);
        ticket.setOwnerId(ACTIVITY);
        ticket.setPrice(new BigDecimal("30.00"));
        ticket.setRemainingInventory(10L);
        long now = System.currentTimeMillis();
        ticket.setStartTime(new Date(now + 86_400_000L));
        ticket.setEndTime(new Date(now + 172_800_000L));
        ticket.setSignupDeadline(new Date(now + 86_400_000L));
        ticket.setMode(1);
        ticket.setDelFlag(0);
        return ticket;
    }

    private CmsActivity activity(Long topicId) {
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setTopicId(topicId);
        activity.setDelFlag(0);
        return activity;
    }
}
