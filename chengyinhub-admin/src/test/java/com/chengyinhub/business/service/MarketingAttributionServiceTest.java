package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.MarketingAttribution;
import com.chengyinhub.business.domain.MarketingReferralCredential;
import com.chengyinhub.business.domain.dto.AttributionClaimRequest;
import com.chengyinhub.business.domain.dto.AttributionClaimResult;
import com.chengyinhub.business.domain.dto.ReferralTokenIssueRequest;
import com.chengyinhub.business.domain.dto.ReferralTokenView;
import com.chengyinhub.business.exception.MarketingAttributionException;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.MarketingAttributionMapper;
import com.chengyinhub.business.mapper.MarketingReferralCredentialMapper;
import com.chengyinhub.business.service.impl.MarketingAttributionServiceImpl;
import com.chengyinhub.business.service.support.ReferralTokenCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 归因服务契约:只读订单、只写归因,拒绝语义可区分。
 *
 * <p>注:本测试不证明 InnoDB 事务回滚,也不证明并发唯一键竞争 —— 那是 MySqlIT 的事。
 */
class MarketingAttributionServiceTest {

    private static final String SECRET = "unit-test-referral-secret";
    private static final long PLAYER = 7L;
    private static final long SHARER = 9L;
    private static final long TOPIC_ID = 42L;
    private static final long REGISTRATION_ID = 555L;

    private MarketingReferralCredentialMapper credentials;
    private MarketingAttributionMapper attributions;
    private CmsRegistrationMapper registrations;
    private ICmsTopicService topics;
    private ICmsActivityService activities;
    private MarketingAttributionServiceImpl service;

    @BeforeEach
    void setUp() {
        credentials = mock(MarketingReferralCredentialMapper.class);
        attributions = mock(MarketingAttributionMapper.class);
        registrations = mock(CmsRegistrationMapper.class);
        topics = mock(ICmsTopicService.class);
        activities = mock(ICmsActivityService.class);
        service = new MarketingAttributionServiceImpl(credentials, attributions, registrations,
                topics, activities, SECRET);

        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setStatus(1);
        when(topics.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);
    }

    // ------------------------------------------------------------------ 签发

    @Test
    void issueTokenStoresOnlyDigestAndReturnsPlaintextOnce() {
        when(credentials.insertCredential(any())).thenReturn(1);

        ReferralTokenView view = service.issueToken(SHARER, issueRequest(TOPIC_ID, null, null));

        assertNotNull(view.getToken());
        assertEquals(TOPIC_ID, view.getTargetId());
        assertEquals("share", view.getCampaignKey());

        ArgumentCaptor<MarketingReferralCredential> saved =
                ArgumentCaptor.forClass(MarketingReferralCredential.class);
        verify(credentials).insertCredential(saved.capture());
        MarketingReferralCredential row = saved.getValue();
        assertEquals(SHARER, row.getSharerMemberId());
        assertEquals(MarketingReferralCredential.STATUS_ACTIVE, row.getStatus().intValue());
        assertEquals(ReferralTokenCodec.sha256Hex(view.getToken()), row.getTokenHash());
        assertNotEquals(view.getToken(), row.getTokenHash());
        assertTrue(ReferralTokenCodec.verify(view.getToken(),
                row.getExpiresAt().getTime() / 1000L - 1, SECRET).valid);
    }

    @Test
    void issueTokenRejectsUnpublishedTargetAndBadCampaignOrTtl() {
        CmsTopic draft = new CmsTopic();
        draft.setId(TOPIC_ID);
        draft.setStatus(0);
        when(topics.selectCmsTopicById(TOPIC_ID)).thenReturn(draft);
        assertReason("TARGET_NOT_FOUND", () -> service.issueToken(SHARER, issueRequest(TOPIC_ID, null, null)));

        CmsTopic published = new CmsTopic();
        published.setId(TOPIC_ID);
        published.setStatus(1);
        when(topics.selectCmsTopicById(TOPIC_ID)).thenReturn(published);

        assertReason("CAMPAIGN_INVALID", () -> service.issueToken(SHARER, issueRequest(TOPIC_ID, "Spring Sale!", null)));
        assertReason("TTL_INVALID", () -> service.issueToken(SHARER, issueRequest(TOPIC_ID, null, 10)));
        assertReason("TTL_INVALID", () -> service.issueToken(SHARER, issueRequest(TOPIC_ID, null, 400 * 24 * 3600)));
        assertReason("TARGET_INVALID", () -> service.issueToken(SHARER, issueRequest(0L, null, null)));
        assertReason("TARGET_INVALID", () -> service.issueToken(SHARER, issueRequestWithType(3, TOPIC_ID)));
        verify(credentials, never()).insertCredential(any());
    }

    @Test
    void issueTokenRequiresLogin() {
        assertReason("LOGIN_REQUIRED", () -> service.issueToken(0L, issueRequest(TOPIC_ID, null, null)));
    }

    // ------------------------------------------------------------------ 归因

    @Test
    void claimWritesExactlyOneAttributionSnapshot() {
        Pair pair = credentialAndToken(null);
        when(registrations.selectCmsRegistrationById(REGISTRATION_ID)).thenReturn(registration(PLAYER));
        when(attributions.selectByOrder(MarketingAttribution.ORDER_REGISTRATION, REGISTRATION_ID)).thenReturn(null);
        when(attributions.insertAttribution(any())).thenReturn(1);

        AttributionClaimResult result = service.claim(PLAYER, claimRequest(REGISTRATION_ID, pair.token));

        assertFalse(result.isReplayed());
        assertEquals("share", result.getCampaignKey());
        assertEquals(SHARER, result.getSharerMemberId());

        ArgumentCaptor<MarketingAttribution> saved = ArgumentCaptor.forClass(MarketingAttribution.class);
        verify(attributions).insertAttribution(saved.capture());
        MarketingAttribution row = saved.getValue();
        assertEquals(MarketingAttribution.ORDER_REGISTRATION, row.getOrderType().intValue());
        assertEquals(REGISTRATION_ID, row.getOrderId());
        assertEquals(PLAYER, row.getClaimedMemberId());
        assertEquals(SHARER, row.getSharerMemberId());
        assertEquals(64, row.getSnapshotHash().length());
    }

    @Test
    void claimingTwiceWithSameTokenIsIdempotentAndWritesNothingNew() {
        Pair pair = credentialAndToken(null);
        when(registrations.selectCmsRegistrationById(REGISTRATION_ID)).thenReturn(registration(PLAYER));
        MarketingAttribution existing = new MarketingAttribution();
        existing.setOrderType(MarketingAttribution.ORDER_REGISTRATION);
        existing.setOrderId(REGISTRATION_ID);
        existing.setCredentialId(pair.credential.getId());
        existing.setCampaignKey("share");
        existing.setSharerMemberId(SHARER);
        existing.setCreateTime(new Date());
        when(attributions.selectByOrder(MarketingAttribution.ORDER_REGISTRATION, REGISTRATION_ID))
                .thenReturn(existing);

        AttributionClaimResult result = service.claim(PLAYER, claimRequest(REGISTRATION_ID, pair.token));

        assertTrue(result.isReplayed());
        verify(attributions, never()).insertAttribution(any());
    }

    @Test
    void aDifferentSourceCanNeverRewriteAnExistingAttribution() {
        Pair pair = credentialAndToken(null);
        when(registrations.selectCmsRegistrationById(REGISTRATION_ID)).thenReturn(registration(PLAYER));
        MarketingAttribution existing = new MarketingAttribution();
        existing.setCredentialId(pair.credential.getId() + 1000);
        existing.setOrderType(MarketingAttribution.ORDER_REGISTRATION);
        existing.setOrderId(REGISTRATION_ID);
        when(attributions.selectByOrder(MarketingAttribution.ORDER_REGISTRATION, REGISTRATION_ID))
                .thenReturn(existing);

        assertReason("ATTRIBUTION_CONFLICT",
                () -> service.claim(PLAYER, claimRequest(REGISTRATION_ID, pair.token)));
        verify(attributions, never()).insertAttribution(any());
    }

    @Test
    void tamperedExpiredAndRevokedTokensAreRejected() {
        Pair pair = credentialAndToken(null);
        String[] parts = pair.token.split("\\.");
        String flipped = parts[3].charAt(0) == 'A' ? "B" : "A";
        String tampered = parts[0] + "." + parts[1] + "." + parts[2] + "." + flipped + parts[3].substring(1);
        assertReason("INVALID_TOKEN", () -> service.claim(PLAYER, claimRequest(REGISTRATION_ID, tampered)));

        String expired = ReferralTokenCodec.issue(pair.credential.getPublicId(),
                System.currentTimeMillis() / 1000L - 60, SECRET);
        assertReason("TOKEN_EXPIRED", () -> service.claim(PLAYER, claimRequest(REGISTRATION_ID, expired)));

        Pair revoked = credentialAndToken(credential ->
                credential.setStatus(MarketingReferralCredential.STATUS_REVOKED));
        assertReason("TOKEN_REVOKED", () -> service.claim(PLAYER, claimRequest(REGISTRATION_ID, revoked.token)));
    }

    @Test
    void unknownOrForeignOrdersAreRejectedBeforeAnyWrite() {
        Pair pair = credentialAndToken(null);
        when(registrations.selectCmsRegistrationById(REGISTRATION_ID)).thenReturn(null);
        assertReason("ORDER_NOT_FOUND", () -> service.claim(PLAYER, claimRequest(REGISTRATION_ID, pair.token)));

        when(registrations.selectCmsRegistrationById(REGISTRATION_ID)).thenReturn(registration(PLAYER + 1));
        assertReason("ORDER_NOT_OWNED", () -> service.claim(PLAYER, claimRequest(REGISTRATION_ID, pair.token)));

        verify(attributions, never()).insertAttribution(any());
    }

    @Test
    void tokenTargetMustMatchThePurchasedProduct() {
        Pair pair = credentialAndToken(null);
        CmsRegistration otherProduct = registration(PLAYER);
        otherProduct.setOwnerId(TOPIC_ID + 1);
        when(registrations.selectCmsRegistrationById(REGISTRATION_ID)).thenReturn(otherProduct);

        assertReason("TARGET_MISMATCH", () -> service.claim(PLAYER, claimRequest(REGISTRATION_ID, pair.token)));
        verify(attributions, never()).insertAttribution(any());
    }

    @Test
    void aSharerCannotAttributeTheirOwnPurchase() {
        // 分享者 = 买家:自推自买只造假数据,将来接分佣就是第一号作弊路径,直接拒。
        Pair pair = credentialAndToken(null);
        when(registrations.selectCmsRegistrationById(REGISTRATION_ID)).thenReturn(registration(SHARER));

        assertReason("SELF_REFERRAL", () -> service.claim(SHARER, claimRequest(REGISTRATION_ID, pair.token)));
        verify(attributions, never()).insertAttribution(any());
    }

    @Test
    void cancelledExpiredAndRefundedOrdersAreNotAttributable() {
        Pair pair = credentialAndToken(null);

        CmsRegistration cancelled = registration(PLAYER);
        cancelled.setRegistrationStatus(3);
        when(registrations.selectCmsRegistrationById(REGISTRATION_ID)).thenReturn(cancelled);
        assertReason("ORDER_NOT_ATTRIBUTABLE",
                () -> service.claim(PLAYER, claimRequest(REGISTRATION_ID, pair.token)));

        CmsRegistration expired = registration(PLAYER);
        expired.setRegistrationStatus(4);
        when(registrations.selectCmsRegistrationById(REGISTRATION_ID)).thenReturn(expired);
        assertReason("ORDER_NOT_ATTRIBUTABLE",
                () -> service.claim(PLAYER, claimRequest(REGISTRATION_ID, pair.token)));

        CmsRegistration refunded = registration(PLAYER);
        refunded.setRegistrationStatus(2);
        refunded.setPaymentStatus(4);
        when(registrations.selectCmsRegistrationById(REGISTRATION_ID)).thenReturn(refunded);
        assertReason("ORDER_NOT_ATTRIBUTABLE",
                () -> service.claim(PLAYER, claimRequest(REGISTRATION_ID, pair.token)));

        verify(attributions, never()).insertAttribution(any());
    }


    @Test
    void orderCreatedBeforeTheTokenIsNotRetroAttributed() {
        Pair pair = credentialAndToken(null);
        CmsRegistration before = registration(PLAYER);
        before.setCreateTime(new Date(pair.credential.getIssuedAt().getTime() - 3_600_000L));
        when(registrations.selectCmsRegistrationById(REGISTRATION_ID)).thenReturn(before);

        assertReason("ORDER_OUTSIDE_TOKEN_WINDOW",
                () -> service.claim(PLAYER, claimRequest(REGISTRATION_ID, pair.token)));
        verify(attributions, never()).insertAttribution(any());
    }

    @Test
    void claimNeverMutatesTheOrderItAttributes() {
        Pair pair = credentialAndToken(null);
        when(registrations.selectCmsRegistrationById(REGISTRATION_ID)).thenReturn(registration(PLAYER));
        when(attributions.selectByOrder(MarketingAttribution.ORDER_REGISTRATION, REGISTRATION_ID)).thenReturn(null);
        when(attributions.insertAttribution(any())).thenReturn(1);

        service.claim(PLAYER, claimRequest(REGISTRATION_ID, pair.token));

        // 归因不报价、不改锁价/费率/支付状态:对订单表只允许一次只读查询。
        verify(registrations, times(1)).selectCmsRegistrationById(REGISTRATION_ID);
        verify(registrations, never()).updateCmsRegistration(any());
        verifyNoMoreInteractions(registrations);
    }

    @Test
    void claimRejectsUnsupportedOrderTypesAndMissingIdentifiers() {
        Pair pair = credentialAndToken(null);
        assertReason("UNSUPPORTED_ORDER_TYPE", () -> service.claim(PLAYER, claimRequestWithType(2, REGISTRATION_ID, pair.token)));
        assertReason("ORDER_REQUIRED", () -> service.claim(PLAYER, claimRequest(0L, pair.token)));
        assertReason("TOKEN_REQUIRED", () -> service.claim(PLAYER, claimRequest(REGISTRATION_ID, " ")));
        assertReason("LOGIN_REQUIRED", () -> service.claim(0L, claimRequest(REGISTRATION_ID, pair.token)));
        verify(registrations, never()).selectCmsRegistrationById(anyLong());
    }

    // ------------------------------------------------------------------ helpers

    private interface CredentialCustomizer {
        void apply(MarketingReferralCredential credential);
    }

    private record Pair(MarketingReferralCredential credential, String token) {
    }

    private Pair credentialAndToken(CredentialCustomizer customizer) {
        String publicId = "abcdef0123456789abcdef";
        long exp = System.currentTimeMillis() / 1000L + 3600L;
        String token = ReferralTokenCodec.issue(publicId, exp, SECRET);

        MarketingReferralCredential credential = new MarketingReferralCredential();
        credential.setId(5L);
        credential.setPublicId(publicId);
        credential.setSharerMemberId(SHARER);
        credential.setCampaignKey("share");
        credential.setTargetType(MarketingReferralCredential.TARGET_TOPIC);
        credential.setTargetId(TOPIC_ID);
        credential.setIssuedAt(new Date(System.currentTimeMillis() - 60_000L));
        credential.setExpiresAt(new Date(exp * 1000L));
        credential.setStatus(MarketingReferralCredential.STATUS_ACTIVE);
        credential.setTokenHash(ReferralTokenCodec.sha256Hex(token));
        if (customizer != null) {
            customizer.apply(credential);
        }
        when(credentials.selectByPublicId(publicId)).thenReturn(credential);
        return new Pair(credential, token);
    }

    private CmsRegistration registration(long memberId) {
        CmsRegistration registration = new CmsRegistration();
        registration.setId(REGISTRATION_ID);
        registration.setMemberId(memberId);
        registration.setOwnerType(1L);
        registration.setOwnerId(TOPIC_ID);
        registration.setCreateTime(new Date());
        return registration;
    }

    private ReferralTokenIssueRequest issueRequest(Long targetId, String campaign, Integer ttl) {
        ReferralTokenIssueRequest request = new ReferralTokenIssueRequest();
        request.setTargetType(MarketingReferralCredential.TARGET_TOPIC);
        request.setTargetId(targetId);
        request.setCampaignKey(campaign);
        request.setTtlSeconds(ttl);
        return request;
    }

    private ReferralTokenIssueRequest issueRequestWithType(int targetType, Long targetId) {
        ReferralTokenIssueRequest request = issueRequest(targetId, null, null);
        request.setTargetType(targetType);
        return request;
    }

    private AttributionClaimRequest claimRequest(Long orderId, String token) {
        return claimRequestWithType(MarketingAttribution.ORDER_REGISTRATION, orderId, token);
    }

    private AttributionClaimRequest claimRequestWithType(int orderType, Long orderId, String token) {
        AttributionClaimRequest request = new AttributionClaimRequest();
        request.setOrderType(orderType);
        request.setOrderId(orderId);
        request.setToken(token);
        return request;
    }

    private void assertReason(String expected, Runnable call) {
        MarketingAttributionException rejection =
                assertThrows(MarketingAttributionException.class, call::run);
        assertEquals(expected, rejection.getReasonCode());
    }
}
