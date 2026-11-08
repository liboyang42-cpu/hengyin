package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.GiftEntitlement;
import com.chengyinhub.business.domain.MarketingProduct;
import com.chengyinhub.business.domain.MarketingProductOrder;
import com.chengyinhub.business.domain.dto.GiftClaimRequest;
import com.chengyinhub.business.exception.MarketingProductException;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.MarketingProductItem;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.GiftEntitlementMapper;
import com.chengyinhub.business.mapper.MarketingProductMapper;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.service.impl.GiftEntitlementServiceImpl;
import com.chengyinhub.business.service.support.MarketingClaimTokenCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 礼品领取：一次 CAS、同人幂等、他人/自领拒绝、端口缺失不伪造准入。 */
class GiftEntitlementServiceTest {

    private static final String SECRET = "unit-test-gift-secret";
    private static final long BUYER = 5L;
    private static final long RECIPIENT = 9L;

    private GiftEntitlementMapper gifts;
    private MarketingProductMapper products;
    private CmsRegistrationMapper registrations;
    private PlayerNodeProgressMapper progress;
    private GiftEntitlementServiceImpl service;

    @BeforeEach
    void setUp() {
        gifts = mock(GiftEntitlementMapper.class);
        products = mock(MarketingProductMapper.class);
        registrations = mock(CmsRegistrationMapper.class);
        progress = mock(PlayerNodeProgressMapper.class);
        service = new GiftEntitlementServiceImpl(gifts, products, registrations, progress, SECRET);
        MarketingProductItem item = new MarketingProductItem();
        item.setProductId(10L);
        item.setTopicId(100L);
        item.setContentRevision(5L);
        when(products.selectItems(10L)).thenReturn(java.util.Collections.singletonList(item));
        when(registrations.selectCmsRegistrationByIdForUpdate(700L)).thenReturn(sourceRegistration());
        when(progress.selectPlayerNodeProgressList(any())).thenReturn(new java.util.ArrayList<>());
        MarketingProduct product = new MarketingProduct();
        product.setId(10L);
        product.setGiftAllowed(1);
        product.setGiftClaimDays(30);
        when(products.selectById(10L)).thenReturn(product);
    }

    @Test
    void issueStoresOnlyHashAndReturnsPlaintextOnce() {
        MarketingProductOrder order = order();

        String token = service.issue(order, new Date());

        org.mockito.ArgumentCaptor<GiftEntitlement> saved =
                org.mockito.ArgumentCaptor.forClass(GiftEntitlement.class);
        verify(gifts).insert(saved.capture());
        assertEquals(MarketingClaimTokenCodec.sha256Hex(token), saved.getValue().getClaimTokenHash());
        assertEquals(GiftEntitlement.STATUS_PENDING, saved.getValue().getStatus().intValue());
        assertEquals(700L, saved.getValue().getSourceRegistrationId().longValue(),
                "礼品来源必须绑定买家已付行，领取才有可转移的准入");
    }

    @Test
    void claimLinksBeneficiaryToSamePaidRegistration() {
        String token = token();
        GiftEntitlement pending = pending(token);
        when(gifts.selectByTokenHashForUpdate(MarketingClaimTokenCodec.sha256Hex(token))).thenReturn(pending);
        when(gifts.claimCas(eq(1L), eq(RECIPIENT), any(), any())).thenReturn(1);
        GiftEntitlement claimed = pending(token);
        claimed.setStatus(GiftEntitlement.STATUS_CLAIMED);
        claimed.setClaimedMemberId(RECIPIENT);
        claimed.setClaimedRegistrationId(700L);
        when(gifts.selectById(1L)).thenReturn(claimed);

        Map<String, Object> result = service.claim(RECIPIENT, request(token));

        assertEquals(GiftEntitlement.STATUS_CLAIMED, result.get("status"));
        assertEquals("LINKED_SAME_REGISTRATION", result.get("admissionState"));
        assertEquals(700L, ((Number) result.get("registrationId")).longValue());
        assertEquals(Boolean.FALSE, result.get("buyerCanPlay"));
        verify(gifts).claimCas(eq(1L), eq(RECIPIENT), any(), any());
        verify(gifts).bindSameRegistration(eq(1L), eq(700L), any());
    }

    @Test
    void claimRejectsGiftWithoutPaidSourceRegistration() {
        String token = token();
        GiftEntitlement pending = pending(token);
        pending.setSourceRegistrationId(null);
        when(gifts.selectByTokenHashForUpdate(MarketingClaimTokenCodec.sha256Hex(token))).thenReturn(pending);

        MarketingProductException rejection = assertThrows(MarketingProductException.class,
                () -> service.claim(RECIPIENT, request(token)));

        assertEquals("GIFT_SOURCE_UNLINKED", rejection.getReasonCode());
        verify(gifts, never()).claimCas(anyLong(), anyLong(), any(), any());
    }

    @Test
    void sameRecipientReplayReturnsOriginalWithoutSecondCas() {
        String token = token();
        GiftEntitlement claimed = pending(token);
        claimed.setStatus(GiftEntitlement.STATUS_CLAIMED);
        claimed.setClaimedMemberId(RECIPIENT);
        when(gifts.selectByTokenHashForUpdate(MarketingClaimTokenCodec.sha256Hex(token))).thenReturn(claimed);

        Map<String, Object> result = service.claim(RECIPIENT, request(token));

        assertEquals(GiftEntitlement.STATUS_CLAIMED, result.get("status"));
        verify(gifts, never()).claimCas(anyLong(), anyLong(), any(), any());
    }

    @Test
    void buyerSelfClaimIsAllowedAndStillSingleAdmission() {
        String token = token();
        when(gifts.selectByTokenHashForUpdate(MarketingClaimTokenCodec.sha256Hex(token)))
                .thenReturn(pending(token));
        when(gifts.claimCas(eq(1L), eq(BUYER), any(), any())).thenReturn(1);
        GiftEntitlement claimed = pending(token);
        claimed.setStatus(GiftEntitlement.STATUS_CLAIMED);
        claimed.setClaimedMemberId(BUYER);
        claimed.setClaimedRegistrationId(700L);
        when(gifts.selectById(1L)).thenReturn(claimed);

        Map<String, Object> result = service.claim(BUYER, request(token));

        assertEquals(GiftEntitlement.STATUS_CLAIMED, result.get("status"));
        assertEquals(700L, ((Number) result.get("registrationId")).longValue());
        verify(gifts).bindSameRegistration(eq(1L), eq(700L), any());
    }

    @Test
    void claimRejectsAlreadyUsedSource() {
        String token = token();
        when(gifts.selectByTokenHashForUpdate(MarketingClaimTokenCodec.sha256Hex(token)))
                .thenReturn(pending(token));
        CmsRegistration started = sourceRegistration();
        started.setFirstSuccessfulScanAt(new Date());
        when(registrations.selectCmsRegistrationByIdForUpdate(700L)).thenReturn(started);

        MarketingProductException rejection = assertThrows(MarketingProductException.class,
                () -> service.claim(RECIPIENT, request(token)));

        assertEquals("GIFT_SOURCE_ALREADY_USED", rejection.getReasonCode());
        verify(gifts, never()).claimCas(anyLong(), anyLong(), any(), any());
    }

    @Test
    void claimRejectsSourceOutsideProductTopics() {
        String token = token();
        when(gifts.selectByTokenHashForUpdate(MarketingClaimTokenCodec.sha256Hex(token)))
                .thenReturn(pending(token));
        CmsRegistration otherTopic = sourceRegistration();
        otherTopic.setOwnerId(999L);
        when(registrations.selectCmsRegistrationByIdForUpdate(700L)).thenReturn(otherTopic);

        MarketingProductException rejection = assertThrows(MarketingProductException.class,
                () -> service.claim(RECIPIENT, request(token)));

        assertEquals("GIFT_TOPIC_NOT_COVERED", rejection.getReasonCode());
    }

    @Test
    void claimRejectsRefundedOrInactiveSource() {
        String token = token();
        when(gifts.selectByTokenHashForUpdate(MarketingClaimTokenCodec.sha256Hex(token)))
                .thenReturn(pending(token));
        CmsRegistration refunded = sourceRegistration();
        refunded.setPaymentStatus(4);
        when(registrations.selectCmsRegistrationByIdForUpdate(700L)).thenReturn(refunded);

        MarketingProductException rejection = assertThrows(MarketingProductException.class,
                () -> service.claim(RECIPIENT, request(token)));

        assertEquals("GIFT_SOURCE_INACTIVE", rejection.getReasonCode());
    }

    @Test
    void anotherRecipientCannotTakeClaimedGift() {
        String token = token();
        GiftEntitlement claimed = pending(token);
        claimed.setStatus(GiftEntitlement.STATUS_CLAIMED);
        claimed.setClaimedMemberId(RECIPIENT);
        when(gifts.selectByTokenHashForUpdate(MarketingClaimTokenCodec.sha256Hex(token))).thenReturn(claimed);

        MarketingProductException rejection = assertThrows(MarketingProductException.class,
                () -> service.claim(11L, request(token)));

        assertEquals("GIFT_ALREADY_CLAIMED", rejection.getReasonCode());
        verify(gifts, never()).claimCas(anyLong(), anyLong(), any(), any());
    }

    // ------------------------------------------------------------------ fixtures

    private String token() {
        return MarketingClaimTokenCodec.issue(
                MarketingClaimTokenCodec.GIFT_PREFIX, "abcdefghijklmnopqrstuvwx", 0L, SECRET);
    }

    private GiftClaimRequest request(String token) {
        GiftClaimRequest request = new GiftClaimRequest();
        request.setToken(token);
        request.setRequestId("claim1");
        return request;
    }

    private GiftEntitlement pending(String token) {
        GiftEntitlement gift = new GiftEntitlement();
        gift.setId(1L);
        gift.setOrderId(50L);
        gift.setProductId(10L);
        gift.setSourceRegistrationId(700L);
        gift.setBuyerMemberId(BUYER);
        gift.setClaimTokenHash(MarketingClaimTokenCodec.sha256Hex(token));
        gift.setStatus(GiftEntitlement.STATUS_PENDING);
        return gift;
    }

    private CmsRegistration sourceRegistration() {
        CmsRegistration row = new CmsRegistration();
        row.setId(700L);
        row.setMemberId(BUYER);
        row.setOwnerType(1L);
        row.setOwnerId(100L);
        row.setPurchaseKind(2);
        row.setPaymentStatus(2);
        row.setRegistrationStatus(2);
        row.setExpiresAt(new Date(System.currentTimeMillis() + 86_400_000L));
        return row;
    }

    private MarketingProductOrder order() {
        MarketingProductOrder order = new MarketingProductOrder();
        order.setId(50L);
        order.setMemberId(BUYER);
        order.setProductId(10L);
        order.setFulfillmentKind(MarketingProductOrder.FULFILLMENT_GIFT);
        order.setStatus(MarketingProductOrder.STATUS_PAID);
        order.setPrimaryRegistrationId(700L);
        order.setGiftRecipientMemberId(RECIPIENT);
        return order;
    }
}
