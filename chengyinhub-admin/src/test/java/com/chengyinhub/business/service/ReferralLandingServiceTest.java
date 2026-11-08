package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.MarketingProduct;
import com.chengyinhub.business.domain.MarketingProductItem;
import com.chengyinhub.business.domain.MarketingReferralCredential;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.ReferralLandingGiftRow;
import com.chengyinhub.business.domain.vo.GiftLandingView;
import com.chengyinhub.business.domain.vo.PublicContentCatalogItemVO;
import com.chengyinhub.business.domain.vo.ReferralLandingView;
import com.chengyinhub.business.mapper.MarketingProductMapper;
import com.chengyinhub.business.mapper.MarketingReferralCredentialMapper;
import com.chengyinhub.business.mapper.ReferralLandingMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.impl.ReferralLandingServiceImpl;
import com.chengyinhub.business.service.support.MarketingClaimTokenCodec;
import com.chengyinhub.business.service.support.ReferralTokenCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 落地页只读契约：
 * <ul>
 *   <li>裸 inviterId / 改签名 / 过期 / 撤销一律不读业务表，也绝不写任何表；</li>
 *   <li>目标必须过既有公开目录/公开活动投影闸；可见也不等于可报名，只回 UNKNOWN 或事实性 CLOSED；</li>
 *   <li>礼品状态（待领/已领/过期/撤销/商品缺失）只按既有事实回报，不做写迁移。</li>
 * </ul>
 *
 * <p>私有/未审核/已删除的可见性判据在既有投影 SQL 内（PublicContentCatalogMapper.catalogPublicGate、
 * CmsActivityMapper.selectPublicCmsActivityById），由既有
 * {@code PublicContentCatalogServiceTest.byIdsKeepsRequestedOrderAndDropsUnpublicIds} 等覆盖；
 * 这里证明落地页对这些空投影一律回 TARGET_UNAVAILABLE，不自己重写一套可见性。
 */
class ReferralLandingServiceTest {

    private static final String SECRET = "unit-test-landing-secret";
    private static final String PUBLIC_ID = "abcdef0123456789abcdef";
    private static final long TOPIC_ID = 42L;
    private static final long ACTIVITY_ID = 88L;
    private static final long PRODUCT_ID = 77L;
    private static final long SHARER_ID = 9L;

    private MarketingReferralCredentialMapper credentials;
    private ReferralLandingMapper landing;
    private UmsMemberMapper members;
    private IPublicContentCatalogService publicCatalog;
    private ICmsActivityService activities;
    private MarketingProductMapper products;
    private ReferralLandingServiceImpl service;

    @BeforeEach
    void setUp() {
        credentials = mock(MarketingReferralCredentialMapper.class);
        landing = mock(ReferralLandingMapper.class);
        members = mock(UmsMemberMapper.class);
        publicCatalog = mock(IPublicContentCatalogService.class);
        activities = mock(ICmsActivityService.class);
        products = mock(MarketingProductMapper.class);
        service = new ReferralLandingServiceImpl(credentials, landing, members, publicCatalog,
                activities, products, SECRET);
    }

    // ------------------------------------------------------------------ referral

    @Test
    void bareInviterIdOrTamperedTokenIsInvalidAndReadsNothing() {
        ReferralLandingView bare = service.describeReferral("7");
        ReferralLandingView tampered = service.describeReferral(referralToken(futureEpochSec()) + "x");

        assertEquals(ReferralLandingView.STATE_INVALID_TOKEN, bare.getState());
        assertEquals(ReferralLandingView.STATE_INVALID_TOKEN, tampered.getState());
        assertNull(bare.getInviter());
        assertNull(tampered.getInviter());
        verifyNoInteractions(credentials, landing, members, publicCatalog, activities, products);
    }

    @Test
    void expiredSignedTokenIsReportedWithoutCredentialLookup() {
        String token = ReferralTokenCodec.issue(PUBLIC_ID, System.currentTimeMillis() / 1000L - 60, SECRET);

        ReferralLandingView view = service.describeReferral(token);

        assertEquals(ReferralLandingView.STATE_TOKEN_EXPIRED, view.getState());
        assertEquals("TOKEN_EXPIRED", view.getReasonCode());
        assertNull(view.getInviter());
        verify(credentials, never()).selectByPublicId(anyString());
        verifyNoInteractions(members, publicCatalog, activities, products, landing);
    }

    @Test
    void revokedCredentialNeverLeaksInviterOrTarget() {
        String token = referralToken(futureEpochSec());
        when(credentials.selectByPublicId(PUBLIC_ID))
                .thenReturn(credential(token, MarketingReferralCredential.STATUS_REVOKED));

        ReferralLandingView view = service.describeReferral(token);

        assertEquals(ReferralLandingView.STATE_TOKEN_REVOKED, view.getState());
        assertNull(view.getInviter());
        assertNull(view.getTarget());
        verify(members, never()).selectUmsMemberById(anyLong());
        verifyNoInteractions(publicCatalog, activities, products, landing);
    }

    @Test
    void validTopicCredentialGivesPublicMinimumAndFactBackedStateOnly() {
        Date expiresAt = new Date(System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1));
        String token = referralToken(expiresAt.getTime() / 1000L);
        MarketingReferralCredential credential = credential(token, MarketingReferralCredential.STATUS_ACTIVE);
        credential.setExpiresAt(expiresAt);
        when(credentials.selectByPublicId(PUBLIC_ID)).thenReturn(credential);
        when(publicCatalog.byIds(Collections.singletonList(TOPIC_ID), null))
                .thenReturn(Collections.singletonList(catalogItem("老城谜案", "/city/330100/quest/42",
                        "ON_SALE", Boolean.FALSE)));
        when(members.selectUmsMemberById(SHARER_ID)).thenReturn(member("阿城", "https://cdn/a.png"));

        ReferralLandingView view = service.describeReferral(token);

        assertEquals(ReferralLandingView.STATE_VALID, view.getState());
        assertEquals("阿城", view.getInviter().getNickname());
        assertEquals("https://cdn/a.png", view.getInviter().getAvatar());
        assertEquals("老城谜案", view.getTarget().getTitle());
        assertEquals("/city/330100/quest/42", view.getTarget().getCanonicalPath());
        assertEquals("share", view.getCampaignKey());
        assertEquals(expiresAt, view.getExpiresAt());
        assertEquals("ON_SALE", view.getSaleState());
        // 公开可见 + ON_SALE 都不证明报名窗口/容量仍开放：只能是 UNKNOWN + 去目标页重校验。
        assertEquals(ReferralLandingView.REGISTRATION_UNKNOWN, view.getRegistrationState());
        assertEquals(ReferralLandingView.ACTION_VIEW_TARGET, view.getNextAction());
        assertEquals("/api/registration/create", view.getEndpoints().getRegistrationCreate());
        assertEquals("/api/marketing/attribution/claim", view.getEndpoints().getClaimAttribution());

        verify(credentials).selectByPublicId(PUBLIC_ID);
        verify(publicCatalog).byIds(Collections.singletonList(TOPIC_ID), null);
        verify(members).selectUmsMemberById(SHARER_ID);
        verifyNoInteractions(landing, products, activities);
        verifyNoMoreInteractions(credentials, publicCatalog, members);
    }

    @Test
    void endedSaleIsTheOnlyFactBackedClosedState() {
        String token = referralToken(futureEpochSec());
        when(credentials.selectByPublicId(PUBLIC_ID))
                .thenReturn(credential(token, MarketingReferralCredential.STATUS_ACTIVE));
        when(publicCatalog.byIds(Collections.singletonList(TOPIC_ID), null))
                .thenReturn(Collections.singletonList(catalogItem("老城谜案", "/city/330100/quest/42",
                        "SALE_ENDED", Boolean.FALSE)));
        when(members.selectUmsMemberById(SHARER_ID)).thenReturn(member("阿城", null));

        ReferralLandingView view = service.describeReferral(token);

        assertEquals(ReferralLandingView.STATE_VALID, view.getState());
        assertEquals(ReferralLandingView.REGISTRATION_CLOSED, view.getRegistrationState());
        assertEquals(ReferralLandingView.ACTION_NONE, view.getNextAction());
    }

    /**
     * 自由探索主题的 catalog 事实可以是 NOT_ON_SALE（未进入 SELLING 或售止缺失）与
     * freeSelfPlay=true 同时成立（自玩价 0 且未过截止）。免费入口开着时不得报"报名关闭"，
     * 更不能把去目标页的引导断掉。
     */
    @Test
    void openFreeEntryIsNeverReportedAsClosedEvenWhenPaidSaleIsNotOnSale() {
        String token = referralToken(futureEpochSec());
        when(credentials.selectByPublicId(PUBLIC_ID))
                .thenReturn(credential(token, MarketingReferralCredential.STATUS_ACTIVE));
        when(publicCatalog.byIds(Collections.singletonList(TOPIC_ID), null))
                .thenReturn(Collections.singletonList(catalogItem("老城谜案", "/city/330100/quest/42",
                        "NOT_ON_SALE", Boolean.TRUE)));
        when(members.selectUmsMemberById(SHARER_ID)).thenReturn(member("阿城", null));

        ReferralLandingView view = service.describeReferral(token);

        assertEquals(ReferralLandingView.STATE_VALID, view.getState());
        assertEquals(Boolean.TRUE, view.getFreeSelfPlay());
        assertEquals(ReferralLandingView.REGISTRATION_UNKNOWN, view.getRegistrationState(),
                "免费自玩入口开着时不能宣称报名关闭");
        assertEquals(ReferralLandingView.ACTION_VIEW_TARGET, view.getNextAction(),
                "仍要引导去目标页走原免费/注册入口");
    }

    /** 同一 NOT_ON_SALE 事实在免费入口关闭时仍是 CLOSED：修复没有把付费停售一并放开。 */
    @Test
    void notOnSaleWithoutFreeEntryStaysClosed() {
        String token = referralToken(futureEpochSec());
        when(credentials.selectByPublicId(PUBLIC_ID))
                .thenReturn(credential(token, MarketingReferralCredential.STATUS_ACTIVE));
        when(publicCatalog.byIds(Collections.singletonList(TOPIC_ID), null))
                .thenReturn(Collections.singletonList(catalogItem("老城谜案", "/city/330100/quest/42",
                        "NOT_ON_SALE", Boolean.FALSE)));
        when(members.selectUmsMemberById(SHARER_ID)).thenReturn(member("阿城", null));

        ReferralLandingView view = service.describeReferral(token);

        assertEquals(ReferralLandingView.REGISTRATION_CLOSED, view.getRegistrationState());
        assertEquals(ReferralLandingView.ACTION_NONE, view.getNextAction());
    }

    @Test
    void privateTopicDroppedByTheExistingPublicGateIsUnavailable() {
        assertTopicUnavailable(
                Collections.<PublicContentCatalogItemVO>emptyList(),
                "俱乐部会员专属主题被 catalogPublicGate 排除");
    }

    @Test
    void unreviewedTopicDroppedByTheExistingPublicGateIsUnavailable() {
        assertTopicUnavailable(
                Collections.<PublicContentCatalogItemVO>emptyList(),
                "未过审/未发布主题被 catalogPublicGate 排除");
    }

    @Test
    void deletedTopicDroppedByTheExistingPublicGateIsUnavailable() {
        assertTopicUnavailable(
                Collections.<PublicContentCatalogItemVO>emptyList(),
                "已删除主题被 catalogPublicGate(del_flag) 排除");
    }

    @Test
    void unpublishedTargetIsUnavailableInsteadOfSharingStaleContent() {
        assertTopicUnavailable(Collections.<PublicContentCatalogItemVO>emptyList(), "普通未公开");
    }

    @Test
    void validActivityCredentialStillProjectsOnlyPublicFields() {
        String token = referralToken(futureEpochSec());
        MarketingReferralCredential credential = credential(token, MarketingReferralCredential.STATUS_ACTIVE);
        credential.setTargetType(MarketingReferralCredential.TARGET_ACTIVITY);
        credential.setTargetId(ACTIVITY_ID);
        when(credentials.selectByPublicId(PUBLIC_ID)).thenReturn(credential);
        when(activities.selectPublicCmsActivityById(ACTIVITY_ID)).thenReturn(activity(ACTIVITY_ID, "夜游市集"));
        when(members.selectUmsMemberById(SHARER_ID)).thenReturn(member("阿城", null));

        ReferralLandingView view = service.describeReferral(token);

        assertEquals(ReferralLandingView.STATE_VALID, view.getState());
        assertEquals("夜游市集", view.getTarget().getTitle());
        assertNull(view.getTarget().getCanonicalPath());
        assertNull(view.getSaleState());
        assertEquals(ReferralLandingView.REGISTRATION_UNKNOWN, view.getRegistrationState());
        assertEquals(ReferralLandingView.ACTION_VIEW_TARGET, view.getNextAction());
        verify(activities).selectPublicCmsActivityById(ACTIVITY_ID);
        verifyNoInteractions(publicCatalog, landing, products);
    }

    @Test
    void privateUnreviewedOrDeletedActivityIsUnavailableNotExposed() {
        String token = referralToken(futureEpochSec());
        MarketingReferralCredential credential = credential(token, MarketingReferralCredential.STATUS_ACTIVE);
        credential.setTargetType(MarketingReferralCredential.TARGET_ACTIVITY);
        credential.setTargetId(ACTIVITY_ID);
        when(credentials.selectByPublicId(PUBLIC_ID)).thenReturn(credential);
        when(activities.selectPublicCmsActivityById(ACTIVITY_ID)).thenReturn(null);

        ReferralLandingView view = service.describeReferral(token);

        assertEquals(ReferralLandingView.STATE_TARGET_UNAVAILABLE, view.getState());
        assertNull(view.getTarget());
        assertNull(view.getInviter());
        verify(members, never()).selectUmsMemberById(anyLong());
        verifyNoInteractions(publicCatalog, products, landing);
    }

    // ------------------------------------------------------------------ gift

    @Test
    void validGiftTokenShowsCoverageAndNeverClaims() {
        String token = MarketingClaimTokenCodec.issue(MarketingClaimTokenCodec.GIFT_PREFIX, PUBLIC_ID, 0L, SECRET);
        when(landing.selectGiftByTokenHash(MarketingClaimTokenCodec.sha256Hex(token)))
                .thenReturn(gift(ReferralLandingGiftRow.STATUS_PENDING, false));
        when(products.selectById(PRODUCT_ID)).thenReturn(product());
        when(products.selectItems(PRODUCT_ID)).thenReturn(Arrays.asList(productItem()));

        GiftLandingView view = service.describeGift(token);

        assertEquals(GiftLandingView.STATE_VALID, view.getState());
        assertEquals("城市包", view.getProduct().getProductName());
        assertEquals(Long.valueOf(5L), view.getProduct().getRevision());
        assertEquals(1, view.getProduct().getItems().size());
        assertEquals(Long.valueOf(TOPIC_ID), view.getProduct().getItems().get(0).getTopicId());
        assertTrue(view.getClaimExpiresAt() != null);
        assertFalse(view.isRecipientBound());
        assertEquals(GiftLandingView.ACTION_CLAIM_GIFT, view.getNextAction());
        assertEquals("/api/marketing/product/gifts/claim", view.getEndpoints().getClaimGift());
        assertTrue(view.getEndpoints().isRequiresLogin());

        verify(landing).selectGiftByTokenHash(MarketingClaimTokenCodec.sha256Hex(token));
        verifyNoMoreInteractions(landing);
        verifyNoInteractions(credentials, members, publicCatalog, activities);
    }

    @Test
    void claimedGiftReportsStateAndClaimTimeOnly() {
        String token = MarketingClaimTokenCodec.issue(MarketingClaimTokenCodec.GIFT_PREFIX, PUBLIC_ID, 0L, SECRET);
        ReferralLandingGiftRow row = gift(ReferralLandingGiftRow.STATUS_CLAIMED, true);
        Date claimedAt = new Date(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(5));
        row.setClaimedAt(claimedAt);
        when(landing.selectGiftByTokenHash(MarketingClaimTokenCodec.sha256Hex(token))).thenReturn(row);
        when(products.selectById(PRODUCT_ID)).thenReturn(product());

        GiftLandingView view = service.describeGift(token);

        assertEquals(GiftLandingView.STATE_CLAIMED, view.getState());
        assertEquals(claimedAt, view.getClaimedAt());
        assertTrue(view.isRecipientBound());
        assertEquals(GiftLandingView.ACTION_NONE, view.getNextAction());
        verify(landing).selectGiftByTokenHash(MarketingClaimTokenCodec.sha256Hex(token));
        verifyNoMoreInteractions(landing);
    }

    @Test
    void expiredClaimWindowIsComputedWithoutWritingState() {
        String token = MarketingClaimTokenCodec.issue(MarketingClaimTokenCodec.GIFT_PREFIX, PUBLIC_ID, 0L, SECRET);
        ReferralLandingGiftRow row = gift(ReferralLandingGiftRow.STATUS_PENDING, false);
        row.setClaimExpiresAt(new Date(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(1)));
        when(landing.selectGiftByTokenHash(MarketingClaimTokenCodec.sha256Hex(token))).thenReturn(row);
        when(products.selectById(PRODUCT_ID)).thenReturn(product());

        GiftLandingView view = service.describeGift(token);

        assertEquals(GiftLandingView.STATE_GIFT_EXPIRED, view.getState());
        assertEquals("GIFT_CLAIM_EXPIRED", view.getReasonCode());
        // 只读：没有 expire()/claimCas() 任何写迁移。
        verify(landing).selectGiftByTokenHash(MarketingClaimTokenCodec.sha256Hex(token));
        verifyNoMoreInteractions(landing);
    }

    @Test
    void codeLevelExpiryWinsBeforeRowLookup() {
        String token = MarketingClaimTokenCodec.issue(MarketingClaimTokenCodec.GIFT_PREFIX, PUBLIC_ID,
                System.currentTimeMillis() / 1000L - 60, SECRET);

        GiftLandingView view = service.describeGift(token);

        assertEquals(GiftLandingView.STATE_TOKEN_EXPIRED, view.getState());
        verify(landing, never()).selectGiftByTokenHash(anyString());
        verifyNoInteractions(products, credentials, members);
    }

    @Test
    void giftTokenAndReferralTokenNeverCrossPrefixes() {
        String giftAsReferral = MarketingClaimTokenCodec.issue(
                MarketingClaimTokenCodec.GIFT_PREFIX, PUBLIC_ID, 0L, SECRET);
        String referralAsGift = referralToken(futureEpochSec());

        assertEquals(ReferralLandingView.STATE_INVALID_TOKEN, service.describeReferral(giftAsReferral).getState());
        assertEquals(GiftLandingView.STATE_INVALID_TOKEN, service.describeGift(referralAsGift).getState());
        verifyNoInteractions(credentials, landing, members, publicCatalog, activities, products);
    }

    @Test
    void revokedGiftAndMissingProductAreReportedWithoutFabrication() {
        String token = MarketingClaimTokenCodec.issue(MarketingClaimTokenCodec.GIFT_PREFIX, PUBLIC_ID, 0L, SECRET);
        when(landing.selectGiftByTokenHash(MarketingClaimTokenCodec.sha256Hex(token)))
                .thenReturn(gift(ReferralLandingGiftRow.STATUS_REVOKED, false));
        when(products.selectById(PRODUCT_ID)).thenReturn(product());
        assertEquals(GiftLandingView.STATE_GIFT_REVOKED, service.describeGift(token).getState());

        when(landing.selectGiftByTokenHash(MarketingClaimTokenCodec.sha256Hex(token)))
                .thenReturn(gift(ReferralLandingGiftRow.STATUS_PENDING, false));
        when(products.selectById(PRODUCT_ID)).thenReturn(null);
        GiftLandingView missing = service.describeGift(token);
        assertEquals(GiftLandingView.STATE_PRODUCT_UNAVAILABLE, missing.getState());
        assertNull(missing.getProduct());
        assertEquals(GiftLandingView.ACTION_NONE, missing.getNextAction());
    }

    @Test
    void unknownGiftRowIsInvalidNotAFabricatedLanding() {
        String token = MarketingClaimTokenCodec.issue(MarketingClaimTokenCodec.GIFT_PREFIX, PUBLIC_ID, 0L, SECRET);
        when(landing.selectGiftByTokenHash(MarketingClaimTokenCodec.sha256Hex(token))).thenReturn(null);

        GiftLandingView view = service.describeGift(token);

        assertEquals(GiftLandingView.STATE_INVALID_TOKEN, view.getState());
        assertNull(view.getProduct());
        verifyNoInteractions(products);
    }

    // ------------------------------------------------------------------ fixtures

    private void assertTopicUnavailable(java.util.List<PublicContentCatalogItemVO> projected, String because) {
        String token = referralToken(futureEpochSec());
        when(credentials.selectByPublicId(PUBLIC_ID))
                .thenReturn(credential(token, MarketingReferralCredential.STATUS_ACTIVE));
        when(publicCatalog.byIds(Collections.singletonList(TOPIC_ID), null)).thenReturn(projected);

        ReferralLandingView view = service.describeReferral(token);

        assertEquals(ReferralLandingView.STATE_TARGET_UNAVAILABLE, view.getState(), because);
        assertNull(view.getTarget());
        assertNull(view.getInviter());
        verify(members, never()).selectUmsMemberById(anyLong());
        verifyNoInteractions(activities, products, landing);
    }

    private static long futureEpochSec() {
        return System.currentTimeMillis() / 1000L + 3600L;
    }

    private static String referralToken(long expiresAtEpochSec) {
        return ReferralTokenCodec.issue(PUBLIC_ID, expiresAtEpochSec, SECRET);
    }

    private static MarketingReferralCredential credential(String token, int status) {
        MarketingReferralCredential credential = new MarketingReferralCredential();
        credential.setId(11L);
        credential.setPublicId(PUBLIC_ID);
        credential.setSharerMemberId(SHARER_ID);
        credential.setCampaignKey("share");
        credential.setTargetType(MarketingReferralCredential.TARGET_TOPIC);
        credential.setTargetId(TOPIC_ID);
        credential.setIssuedAt(new Date(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(1)));
        credential.setExpiresAt(new Date(System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1)));
        credential.setStatus(status);
        credential.setTokenHash(ReferralTokenCodec.sha256Hex(token));
        return credential;
    }

    private static ReferralLandingGiftRow gift(int status, boolean recipientBound) {
        ReferralLandingGiftRow row = new ReferralLandingGiftRow();
        row.setPublicId(PUBLIC_ID);
        row.setProductId(PRODUCT_ID);
        row.setStatus(status);
        row.setRecipientBound(recipientBound);
        row.setClaimExpiresAt(new Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(7)));
        return row;
    }

    private static PublicContentCatalogItemVO catalogItem(String name, String canonicalPath,
                                                          String saleState, Boolean freeSelfPlay) {
        PublicContentCatalogItemVO item = new PublicContentCatalogItemVO();
        item.setId(TOPIC_ID);
        item.setName(name);
        item.setCanonicalPath(canonicalPath);
        item.setSaleState(saleState);
        item.setFreeSelfPlay(freeSelfPlay);
        return item;
    }

    private static CmsActivity activity(Long id, String name) {
        CmsActivity activity = new CmsActivity();
        activity.setId(id);
        activity.setName(name);
        activity.setStatus(1L);
        return activity;
    }

    private static UmsMember member(String nickname, String avatar) {
        UmsMember member = new UmsMember();
        member.setId(SHARER_ID);
        member.setNickname(nickname);
        member.setAvatar(avatar);
        return member;
    }

    private static MarketingProduct product() {
        MarketingProduct product = new MarketingProduct();
        product.setId(PRODUCT_ID);
        product.setProductCode("CITY_BUNDLE_330100");
        product.setProductName("城市包");
        product.setProductKind(2);
        product.setValidityKind(1);
        product.setValidDays(30);
        product.setRevision(5L);
        return product;
    }

    private static MarketingProductItem productItem() {
        MarketingProductItem item = new MarketingProductItem();
        item.setProductId(PRODUCT_ID);
        item.setTopicId(TOPIC_ID);
        item.setTopicTitle("老城谜案");
        item.setContentRevision(9L);
        return item;
    }
}
