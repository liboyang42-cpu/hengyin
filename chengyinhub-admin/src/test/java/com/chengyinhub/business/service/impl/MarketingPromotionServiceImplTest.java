package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.MarketingOffer;
import com.chengyinhub.business.domain.MarketingOfferItem;
import com.chengyinhub.business.domain.MarketingOrderAdjustment;
import com.chengyinhub.business.domain.MarketingPromotion;
import com.chengyinhub.business.domain.MarketingPromotionRedemption;
import com.chengyinhub.business.domain.PlayerBadge;
import com.chengyinhub.business.domain.dto.MarketingExclusion;
import com.chengyinhub.business.domain.dto.MarketingPromotionQuote;
import com.chengyinhub.business.domain.dto.MarketingPromotionRequest;
import com.chengyinhub.business.domain.vo.MarketingOfferCandidateView;
import com.chengyinhub.business.domain.vo.MarketingPromotionCandidateView;
import com.chengyinhub.business.exception.MarketingPromotionException;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.MarketingOfferMapper;
import com.chengyinhub.business.mapper.MarketingOrderAdjustmentMapper;
import com.chengyinhub.business.mapper.MarketingPromotionMapper;
import com.chengyinhub.business.mapper.MarketingPromotionRedemptionMapper;
import com.chengyinhub.business.mapper.MarketingTierMapper;
import com.chengyinhub.business.mapper.PlayerBadgeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 促销服务契约:资格只认服务端通关事实、一次性语义、退款撤销不删行、组合包逐项匹配。
 *
 * <p>注:本测试不证明 InnoDB 唯一键竞争与事务回滚 —— 那是 MySqlIT 的事。
 * 这里证明的是"同一份事实输入,判定的理由码与金额是确定的"。
 */
class MarketingPromotionServiceImplTest {

    private static final Long MEMBER = 88L;
    private static final Long TOPIC_ID = 500L;
    private static final Long ACTIVITY_ID = 200L;
    private static final Long TICKET_ID = 11L;

    private MarketingOfferMapper offerMapper;
    private MarketingTierMapper tierMapper;
    private MarketingPromotionMapper promotionMapper;
    private MarketingPromotionRedemptionMapper redemptionMapper;
    private MarketingOrderAdjustmentMapper adjustmentMapper;
    private PlayerBadgeMapper playerBadgeMapper;
    private CmsActivityMapper activityMapper;
    private MarketingPromotionServiceImpl service;

    @BeforeEach
    void setUp() {
        offerMapper = mock(MarketingOfferMapper.class);
        tierMapper = mock(MarketingTierMapper.class);
        promotionMapper = mock(MarketingPromotionMapper.class);
        redemptionMapper = mock(MarketingPromotionRedemptionMapper.class);
        adjustmentMapper = mock(MarketingOrderAdjustmentMapper.class);
        playerBadgeMapper = mock(PlayerBadgeMapper.class);
        activityMapper = mock(CmsActivityMapper.class);
        service = new MarketingPromotionServiceImpl(offerMapper, tierMapper, promotionMapper,
                redemptionMapper, adjustmentMapper, playerBadgeMapper, activityMapper);
        when(tierMapper.selectActiveByScope(any(), any(), any())).thenReturn(new ArrayList<>());
        when(promotionMapper.selectActiveByScope(any(), any(), any())).thenReturn(new ArrayList<>());
    }

    // ------------------------------------------------------------------ 完局限时

    @Test
    void completionPromotionInsideWindowReducesTheQuote() {
        when(promotionMapper.selectActiveByScope(any(), any(), any()))
                .thenReturn(Arrays.asList(completionPromotion("FIRST48", 48, "30.00")));
        when(playerBadgeMapper.selectOne(eq(MEMBER), eq("TOPIC_FINISH_" + TOPIC_ID), eq(TOPIC_ID)))
                .thenReturn(badge(900L, hoursAgo(1)));
        when(redemptionMapper.countByFact(any(), any(), any())).thenReturn(0);
        when(redemptionMapper.countByMember(any(), any())).thenReturn(0);

        MarketingPromotionQuote quote = service.quote(topicRequest(lines(line(TICKET_ID, 2, "100.00"))), new Date());

        assertMoney("200.00", quote.getListAmount());
        assertMoney("30.00", quote.getPromotionDiscountAmount());
        assertMoney("170.00", quote.getPayableAmount());
        assertEquals(Collections.singletonList("FIRST48"), quote.getPromotionCodes());
        assertEquals(Long.valueOf(900L), quote.getAdjustments().get(0).getSourceFactId());
    }

    @Test
    void completionPromotionOutsideWindowIsExcludedWithItsOwnReason() {
        when(promotionMapper.selectActiveByScope(any(), any(), any()))
                .thenReturn(Arrays.asList(completionPromotion("FIRST48", 48, "30.00")));
        when(playerBadgeMapper.selectOne(any(), any(), any())).thenReturn(badge(900L, hoursAgo(72)));
        when(redemptionMapper.countByFact(any(), any(), any())).thenReturn(0);
        when(redemptionMapper.countByMember(any(), any())).thenReturn(0);

        MarketingPromotionQuote quote = service.quote(topicRequest(lines(line(TICKET_ID, 1, "100.00"))), new Date());

        assertMoney("100.00", quote.getPayableAmount());
        assertExcluded(quote, MarketingExclusion.TRIGGER_WINDOW_EXPIRED);
    }

    @Test
    void missingCompletionFactKeepsTheFullPrice() {
        when(promotionMapper.selectActiveByScope(any(), any(), any()))
                .thenReturn(Arrays.asList(completionPromotion("FIRST48", 48, "30.00")));
        when(playerBadgeMapper.selectOne(any(), any(), any())).thenReturn(null);

        MarketingPromotionQuote quote = service.quote(topicRequest(lines(line(TICKET_ID, 1, "100.00"))), new Date());

        assertMoney("100.00", quote.getPayableAmount());
        assertExcluded(quote, MarketingExclusion.TRIGGER_FACT_MISSING);
    }

    @Test
    void alreadyRedeemedFactBlocksASecondDiscount() {
        when(promotionMapper.selectActiveByScope(any(), any(), any()))
                .thenReturn(Arrays.asList(completionPromotion("FIRST48", 48, "30.00")));
        when(playerBadgeMapper.selectOne(any(), any(), any())).thenReturn(badge(900L, hoursAgo(1)));
        when(redemptionMapper.countByFact(any(), any(), any())).thenReturn(1);

        MarketingPromotionQuote quote = service.quote(topicRequest(lines(line(TICKET_ID, 1, "100.00"))), new Date());

        assertMoney("100.00", quote.getPayableAmount());
        assertExcluded(quote, MarketingExclusion.ALREADY_USED);
    }

    @Test
    void memberLimitBlocksFurtherUseEvenForANewFact() {
        MarketingPromotion promotion = completionPromotion("FIRST48", 48, "30.00");
        promotion.setMaxUsesPerMember(1);
        when(promotionMapper.selectActiveByScope(any(), any(), any())).thenReturn(Arrays.asList(promotion));
        when(playerBadgeMapper.selectOne(any(), any(), any())).thenReturn(badge(901L, hoursAgo(1)));
        when(redemptionMapper.countByFact(any(), any(), any())).thenReturn(0);
        when(redemptionMapper.countByMember(any(), any())).thenReturn(1);

        MarketingPromotionQuote quote = service.quote(topicRequest(lines(line(TICKET_ID, 1, "100.00"))), new Date());

        assertMoney("100.00", quote.getPayableAmount());
        assertExcluded(quote, MarketingExclusion.MEMBER_LIMIT_REACHED);
    }

    @Test
    void completionPromotionWithoutPositiveWindowIsNotUsable() {
        when(promotionMapper.selectActiveByScope(any(), any(), any()))
                .thenReturn(Arrays.asList(completionPromotion("BAD", 0, "30.00")));
        when(playerBadgeMapper.selectOne(any(), any(), any())).thenReturn(badge(900L, hoursAgo(1)));

        MarketingPromotionQuote quote = service.quote(topicRequest(lines(line(TICKET_ID, 1, "100.00"))), new Date());

        assertMoney("100.00", quote.getPayableAmount());
        assertExcluded(quote, MarketingExclusion.PROMO_WINDOW_UNCONFIGURED);
    }

    // ------------------------------------------------------------------ 首关

    @Test
    void firstQuestPromotionUsesTheEarliestCompletionFact() {
        MarketingPromotion promotion = new MarketingPromotion();
        promotion.setId(2L);
        promotion.setPromoCode("FIRST_QUEST");
        promotion.setName("首关后优惠");
        promotion.setTriggerKind(MarketingPromotion.TRIGGER_FIRST_QUEST);
        promotion.setScopeType(MarketingPromotion.SCOPE_ALL);
        promotion.setDiscountType(MarketingPromotion.DISCOUNT_FIXED_AMOUNT);
        promotion.setDiscountValue(new BigDecimal("15.00"));
        promotion.setWindowHours(72);
        promotion.setMaxUsesPerMember(1);
        promotion.setStatus(MarketingPromotion.STATUS_ENABLED);
        promotion.setRuleVersion(4);
        promotion.setStackGroup("FIRST_QUEST");
        when(promotionMapper.selectActiveByScope(any(), any(), any())).thenReturn(Arrays.asList(promotion));
        PlayerBadge recent = badge(880L, hoursAgo(2));
        PlayerBadge earliest = badge(700L, hoursAgo(10));
        when(playerBadgeMapper.selectListByMember(MEMBER)).thenReturn(Arrays.asList(recent, earliest));
        when(redemptionMapper.countByFact(any(), any(), any())).thenReturn(0);
        when(redemptionMapper.countByMember(any(), any())).thenReturn(0);

        MarketingPromotionQuote quote = service.quote(topicRequest(lines(line(TICKET_ID, 1, "100.00"))), new Date());

        assertMoney("85.00", quote.getPayableAmount());
        assertEquals(Long.valueOf(700L), quote.getAdjustments().get(0).getSourceFactId(),
                "首关窗口必须从最早一次通关起算,不是最近一次");
    }

    @Test
    void firstQuestPromotionExpiresFromTheEarliestCompletion() {
        MarketingPromotion promotion = new MarketingPromotion();
        promotion.setId(3L);
        promotion.setPromoCode("FIRST_QUEST");
        promotion.setName("首关后优惠");
        promotion.setTriggerKind(MarketingPromotion.TRIGGER_FIRST_QUEST);
        promotion.setScopeType(MarketingPromotion.SCOPE_ALL);
        promotion.setDiscountType(MarketingPromotion.DISCOUNT_FIXED_AMOUNT);
        promotion.setDiscountValue(new BigDecimal("15.00"));
        promotion.setWindowHours(24);
        promotion.setStatus(MarketingPromotion.STATUS_ENABLED);
        promotion.setRuleVersion(1);
        promotion.setMaxUsesPerMember(1);
        promotion.setStackGroup("FIRST_QUEST");
        when(promotionMapper.selectActiveByScope(any(), any(), any())).thenReturn(Arrays.asList(promotion));
        when(playerBadgeMapper.selectListByMember(MEMBER))
                .thenReturn(Arrays.asList(badge(700L, hoursAgo(48))));

        MarketingPromotionQuote quote = service.quote(topicRequest(lines(line(TICKET_ID, 1, "100.00"))), new Date());

        assertMoney("100.00", quote.getPayableAmount());
        assertExcluded(quote, MarketingExclusion.TRIGGER_WINDOW_EXPIRED);
    }

    // ------------------------------------------------------------------ 组合包

    @Test
    void comboOfferAppliesWhenCartMatchesTheConfiguredItems() {
        when(offerMapper.selectEnabledByCode("COMBO-DUO")).thenReturn(comboOffer("150.00"));
        when(offerMapper.selectItemsByOfferId(70L)).thenReturn(Arrays.asList(item(TICKET_ID, 2)));
        when(adjustmentMapper.countByOfferAndMember(70L, MEMBER)).thenReturn(0);

        MarketingPromotionRequest request = topicRequest(lines(line(TICKET_ID, 2, "100.00")));
        request.setOfferCode("COMBO-DUO");
        MarketingPromotionQuote quote = service.quote(request, new Date());

        assertMoney("150.00", quote.getPayableAmount());
        assertEquals("COMBO-DUO", quote.getOfferCode());
    }

    @Test
    void comboOfferIsIgnoredWhenTheCartDiffers() {
        when(offerMapper.selectEnabledByCode("COMBO-DUO")).thenReturn(comboOffer("150.00"));
        when(offerMapper.selectItemsByOfferId(70L)).thenReturn(Arrays.asList(item(TICKET_ID, 3)));
        when(adjustmentMapper.countByOfferAndMember(70L, MEMBER)).thenReturn(0);

        MarketingPromotionRequest request = topicRequest(lines(line(TICKET_ID, 2, "100.00")));
        request.setOfferCode("COMBO-DUO");
        MarketingPromotionQuote quote = service.quote(request, new Date());

        assertMoney("200.00", quote.getPayableAmount());
        assertNull(quote.getOfferId());
        assertExcluded(quote, MarketingExclusion.OFFER_ITEMS_MISMATCH);
    }

    @Test
    void comboOfferForAnotherScopeIsIgnored() {
        MarketingOffer offer = comboOffer("150.00");
        offer.setOwnerId(TOPIC_ID + 1);
        when(offerMapper.selectEnabledByCode("COMBO-DUO")).thenReturn(offer);

        MarketingPromotionRequest request = topicRequest(lines(line(TICKET_ID, 1, "100.00")));
        request.setOfferCode("COMBO-DUO");
        MarketingPromotionQuote quote = service.quote(request, new Date());

        assertMoney("100.00", quote.getPayableAmount());
        assertExcluded(quote, MarketingExclusion.OFFER_SCOPE_MISMATCH);
    }

    @Test
    void offerOverTheMemberLimitIsIgnored() {
        when(offerMapper.selectEnabledByCode("COMBO-DUO")).thenReturn(comboOffer("150.00"));
        when(adjustmentMapper.countByOfferAndMember(70L, MEMBER)).thenReturn(1);

        MarketingPromotionRequest request = topicRequest(lines(line(TICKET_ID, 2, "100.00")));
        request.setOfferCode("COMBO-DUO");
        MarketingPromotionQuote quote = service.quote(request, new Date());

        assertMoney("200.00", quote.getPayableAmount());
        assertExcluded(quote, MarketingExclusion.OFFER_MEMBER_LIMIT);
    }

    @Test
    void unknownOfferCodeIsReportedNotSilentlyPriced() {
        when(offerMapper.selectEnabledByCode("NOPE")).thenReturn(null);

        MarketingPromotionRequest request = topicRequest(lines(line(TICKET_ID, 1, "100.00")));
        request.setOfferCode("NOPE");
        MarketingPromotionQuote quote = service.quote(request, new Date());

        assertMoney("100.00", quote.getPayableAmount());
        assertExcluded(quote, MarketingExclusion.OFFER_NOT_FOUND);
    }

    // ------------------------------------------------------------------ 快照 / 撤销

    @Test
    void commitAppliedWritesSnapshotAndRedemption() {
        when(offerMapper.selectEnabledByCode("COMBO-DUO")).thenReturn(comboOffer("150.00"));
        when(offerMapper.selectItemsByOfferId(70L)).thenReturn(Arrays.asList(item(TICKET_ID, 2)));
        when(adjustmentMapper.countByOfferAndMember(70L, MEMBER)).thenReturn(0);
        when(offerMapper.selectOfferByIdForUpdate(70L)).thenReturn(comboOffer("150.00"));
        when(promotionMapper.selectActiveByScope(any(), any(), any()))
                .thenReturn(Arrays.asList(completionPromotion("FIRST48", 48, "10.00")));
        when(playerBadgeMapper.selectOne(any(), any(), any())).thenReturn(badge(900L, hoursAgo(1)));
        when(redemptionMapper.countByFact(any(), any(), any())).thenReturn(0);
        when(redemptionMapper.countByMember(any(), any())).thenReturn(0);

        MarketingPromotionRequest request = topicRequest(lines(line(TICKET_ID, 2, "100.00")));
        request.setOfferCode("COMBO-DUO");
        MarketingPromotionQuote quote = service.quote(request, new Date());
        service.commitApplied(quote, 1, 555L, new Date());

        ArgumentCaptor<MarketingOrderAdjustment> snapshot = ArgumentCaptor.forClass(MarketingOrderAdjustment.class);
        verify(adjustmentMapper).insertAdjustment(snapshot.capture());
        MarketingOrderAdjustment row = snapshot.getValue();
        assertEquals(Long.valueOf(555L), row.getOrderId());
        assertEquals(Long.valueOf(MEMBER), row.getMemberId());
        assertEquals(Integer.valueOf(1), row.getOwnerType());
        assertEquals(Long.valueOf(TOPIC_ID), row.getOwnerId());
        assertMoney("200.00", row.getListAmount());
        // 200 分买 → 组合包 150 → 完局限时再减 10 → 应付 140
        assertMoney("140.00", row.getPayableAmount());
        assertNotNull(row.getAppliedJson());
        assertTrue(row.getAppliedJson().contains("COMBO-DUO"));
        assertEquals(quote.getMarketingSign(), row.getQuoteFingerprint());

        // 一次性事实:只对触发式促销写一行,组合包不写使用事实
        ArgumentCaptor<MarketingPromotionRedemption> redemption =
                ArgumentCaptor.forClass(MarketingPromotionRedemption.class);
        verify(redemptionMapper, times(1)).insertRedemption(redemption.capture());
        assertEquals(Long.valueOf(1L), redemption.getValue().getPromotionId());
        assertEquals(Long.valueOf(900L), redemption.getValue().getSourceFactId());
        assertEquals(Long.valueOf(MEMBER), redemption.getValue().getMemberId());
        assertEquals(Integer.valueOf(MarketingPromotionRedemption.STATUS_USED), redemption.getValue().getStatus());
    }

    @Test
    void commitAppliedWithoutAnyPromotionWritesNothing() {
        MarketingPromotionQuote quote = service.quote(topicRequest(lines(line(TICKET_ID, 1, "100.00"))), new Date());
        service.commitApplied(quote, 1, 556L, new Date());

        verify(adjustmentMapper, never()).insertAdjustment(any());
        verify(redemptionMapper, never()).insertRedemption(any());
    }

    @Test
    void commitAppliedRejectsTheOfferWhenTheMemberLimitIsAlreadyReachedUnderLock() {
        // 报价时看到 0 次、建单时已被另一笔订单占用:锁后重读必须拦住,不能落第二份使用事实。
        when(offerMapper.selectEnabledByCode("COMBO-DUO")).thenReturn(comboOffer("150.00"));
        when(offerMapper.selectItemsByOfferId(70L)).thenReturn(Arrays.asList(item(TICKET_ID, 2)));
        when(adjustmentMapper.countByOfferAndMember(70L, MEMBER)).thenReturn(0);

        MarketingPromotionRequest request = topicRequest(lines(line(TICKET_ID, 2, "100.00")));
        request.setOfferCode("COMBO-DUO");
        MarketingPromotionQuote quote = service.quote(request, new Date());

        // 并发胜出的那笔已提交 ⇒ 建单事务内锁后重读为 1
        when(offerMapper.selectOfferByIdForUpdate(70L)).thenReturn(comboOffer("150.00"));
        when(adjustmentMapper.countByOfferAndMemberForUpdate(70L, MEMBER)).thenReturn(1);

        MarketingPromotionException rejection = assertThrows(MarketingPromotionException.class,
                () -> service.commitApplied(quote, 1, 557L, new Date()));

        assertEquals(MarketingExclusion.OFFER_MEMBER_LIMIT, rejection.getReasonCode());
        verify(adjustmentMapper, never()).insertAdjustment(any());
        verify(redemptionMapper, never()).insertRedemption(any());
    }

    @Test
    void commitAppliedOnReplayReturnsBeforeLockingOrConsumingAgain() {
        when(offerMapper.selectEnabledByCode("COMBO-DUO")).thenReturn(comboOffer("150.00"));
        when(offerMapper.selectItemsByOfferId(70L)).thenReturn(Arrays.asList(item(TICKET_ID, 2)));
        when(offerMapper.selectOfferByIdForUpdate(70L)).thenReturn(comboOffer("150.00"));
        when(adjustmentMapper.countByOfferAndMemberForUpdate(70L, MEMBER)).thenReturn(0);

        MarketingPromotionRequest request = topicRequest(lines(line(TICKET_ID, 2, "100.00")));
        request.setOfferCode("COMBO-DUO");
        MarketingPromotionQuote quote = service.quote(request, new Date());

        // 同一 (orderType, orderId) 已有成交快照 = 这单已经用过这份优惠
        when(adjustmentMapper.selectByOrder(1, 558L)).thenReturn(new MarketingOrderAdjustment());

        service.commitApplied(quote, 1, 558L, new Date());

        verify(offerMapper, never()).selectOfferByIdForUpdate(any());
        verify(adjustmentMapper, never()).insertAdjustment(any());
    }

    @Test
    void revokeForOrderOnlyChangesStatus() {
        service.revokeForOrder(1, 555L, new Date());

        verify(redemptionMapper).revokeByOrder(eq(1), eq(555L),
                eq(MarketingPromotionRedemption.STATUS_REVOKED_BY_REFUND), any(Date.class));
        verify(redemptionMapper, never()).insertRedemption(any());
    }

    @Test
    void readOrderAdjustmentDelegatesToTheSnapshot() {
        MarketingOrderAdjustment row = new MarketingOrderAdjustment();
        row.setOrderId(555L);
        when(adjustmentMapper.selectByOrder(1, 555L)).thenReturn(row);

        assertEquals(row, service.readOrderAdjustment(1, 555L));
        assertNull(service.readOrderAdjustment(null, 555L));
    }

    // ------------------------------------------------------------------ 投影

    @Test
    void offerCatalogMarksMemberLimitAsUnavailable() {
        when(offerMapper.selectActiveByScope(eq(1), eq(TOPIC_ID), any()))
                .thenReturn(Arrays.asList(comboOffer("150.00")));
        when(offerMapper.selectItemsByOfferId(70L)).thenReturn(Arrays.asList(item(TICKET_ID, 2)));
        when(adjustmentMapper.countByOfferAndMember(70L, MEMBER)).thenReturn(1);

        List<MarketingOfferCandidateView> views = service.listOffers(MEMBER, 1, TOPIC_ID, new Date());

        assertEquals(1, views.size());
        assertFalse(views.get(0).isAvailable());
        assertEquals(MarketingExclusion.OFFER_MEMBER_LIMIT, views.get(0).getReasonCode());
        assertEquals(1, views.get(0).getItems().size());
    }

    @Test
    void promotionCatalogMarksIneligibleWithReason() {
        when(promotionMapper.selectActiveByScope(eq(1), eq(TOPIC_ID), any()))
                .thenReturn(Arrays.asList(completionPromotion("FIRST48", 48, "30.00")));
        when(playerBadgeMapper.selectOne(any(), any(), any())).thenReturn(null);

        List<MarketingPromotionCandidateView> views = service.listPromotions(MEMBER, 1, TOPIC_ID, new Date());

        assertEquals(1, views.size());
        assertFalse(views.get(0).isEligible());
        assertEquals(MarketingExclusion.TRIGGER_FACT_MISSING, views.get(0).getReasonCode());
        assertEquals(Integer.valueOf(48), views.get(0).getWindowHours());
    }

    @Test
    void quoteRequiresALoggedInMember() {
        MarketingPromotionRequest request = topicRequest(lines(line(TICKET_ID, 1, "100.00")));
        request.setMemberId(null);
        MarketingPromotionException rejection = assertThrows(MarketingPromotionException.class,
                () -> service.quote(request, new Date()));
        assertEquals("LOGIN_REQUIRED", rejection.getReasonCode());
    }

    @Test
    void activityScopedQuoteIsSupported() {
        MarketingPromotionRequest request = new MarketingPromotionRequest();
        request.setMemberId(MEMBER);
        request.setOwnerType(2);
        request.setOwnerId(ACTIVITY_ID);
        request.setLines(lines(line(TICKET_ID, 1, "100.00")));

        MarketingPromotionQuote quote = service.quote(request, new Date());

        assertMoney("100.00", quote.getPayableAmount());
        assertEquals(Integer.valueOf(2), quote.getOwnerType());
        assertEquals(ACTIVITY_ID, quote.getOwnerId());
    }

    @Test
    void activityQuoteAlsoSeesItsTopicsCompletionPromotion() {
        // 真实售票路径报价对象是活动(owner_type=2),通关事实却记在活动所属主题上。
        // 只按活动 scope 查配置,主题级完局限时永远进不了候选 —— 机制等于没接线。
        when(activityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activityWithTopic(TOPIC_ID));
        when(promotionMapper.selectActiveByScope(eq(2), eq(ACTIVITY_ID), any()))
                .thenReturn(new ArrayList<>());
        when(promotionMapper.selectActiveByScope(eq(1), eq(TOPIC_ID), any()))
                .thenReturn(Arrays.asList(completionPromotion("FIRST48", 48, "30.00")));
        when(playerBadgeMapper.selectOne(any(), any(), any())).thenReturn(badge(900L, hoursAgo(1)));
        when(redemptionMapper.countByFact(any(), any(), any())).thenReturn(0);
        when(redemptionMapper.countByMember(any(), any())).thenReturn(0);

        MarketingPromotionQuote quote = service.quote(
                activityRequest(lines(line(TICKET_ID, 1, "100.00"))), new Date());

        assertMoney("70.00", quote.getPayableAmount());
        assertEquals(Collections.singletonList("FIRST48"), quote.getPromotionCodes());
    }

    @Test
    void globalPromotionReturnedByBothQueriesAppliesOnlyOnce() {
        MarketingPromotion global = new MarketingPromotion();
        global.setId(4L);
        global.setPromoCode("FIRST_QUEST");
        global.setName("首关后优惠");
        global.setTriggerKind(MarketingPromotion.TRIGGER_FIRST_QUEST);
        global.setScopeType(MarketingPromotion.SCOPE_ALL);
        global.setDiscountType(MarketingPromotion.DISCOUNT_FIXED_AMOUNT);
        global.setDiscountValue(new BigDecimal("20.00"));
        global.setWindowHours(72);
        global.setMaxUsesPerMember(1);
        global.setStatus(MarketingPromotion.STATUS_ENABLED);
        global.setRuleVersion(1);
        global.setStackGroup("FIRST_QUEST");
        when(activityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activityWithTopic(TOPIC_ID));
        when(promotionMapper.selectActiveByScope(eq(2), eq(ACTIVITY_ID), any()))
                .thenReturn(Arrays.asList(global));
        when(promotionMapper.selectActiveByScope(eq(1), eq(TOPIC_ID), any()))
                .thenReturn(Arrays.asList(global));
        when(playerBadgeMapper.selectListByMember(MEMBER))
                .thenReturn(Arrays.asList(badge(700L, hoursAgo(2))));
        when(redemptionMapper.countByFact(any(), any(), any())).thenReturn(0);
        when(redemptionMapper.countByMember(any(), any())).thenReturn(0);

        MarketingPromotionQuote quote = service.quote(
                activityRequest(lines(line(TICKET_ID, 1, "100.00"))), new Date());

        assertMoney("20.00", quote.getPromotionDiscountAmount());
        assertEquals(1, quote.getPromotionIds().size(), "两次查询返回同一条全局促销只能算一次");
    }

    private MarketingPromotionRequest activityRequest(List<MarketingPromotionRequest.Line> lines) {
        MarketingPromotionRequest request = new MarketingPromotionRequest();
        request.setMemberId(MEMBER);
        request.setOwnerType(2);
        request.setOwnerId(ACTIVITY_ID);
        request.setLines(lines);
        return request;
    }

    // ------------------------------------------------------------------ helpers

    private MarketingPromotionRequest topicRequest(List<MarketingPromotionRequest.Line> lines) {
        MarketingPromotionRequest request = new MarketingPromotionRequest();
        request.setMemberId(MEMBER);
        request.setOwnerType(1);
        request.setOwnerId(TOPIC_ID);
        request.setLines(lines);
        return request;
    }

    private List<MarketingPromotionRequest.Line> lines(MarketingPromotionRequest.Line... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    private MarketingPromotionRequest.Line line(Long ticketId, int quantity, String unitPrice) {
        return new MarketingPromotionRequest.Line(ticketId, quantity, new BigDecimal(unitPrice));
    }

    private CmsActivity activityWithTopic(Long topicId) {
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY_ID);
        activity.setTopicId(topicId);
        return activity;
    }

    private MarketingPromotion completionPromotion(String code, int windowHours, String value) {
        MarketingPromotion promotion = new MarketingPromotion();
        promotion.setId(1L);
        promotion.setPromoCode(code);
        promotion.setName("完局限时");
        promotion.setTriggerKind(MarketingPromotion.TRIGGER_TOPIC_COMPLETION);
        promotion.setScopeType(MarketingPromotion.SCOPE_TOPIC);
        promotion.setScopeId(TOPIC_ID);
        promotion.setDiscountType(MarketingPromotion.DISCOUNT_FIXED_AMOUNT);
        promotion.setDiscountValue(new BigDecimal(value));
        promotion.setWindowHours(windowHours);
        promotion.setMaxUsesPerMember(1);
        promotion.setStatus(MarketingPromotion.STATUS_ENABLED);
        promotion.setRuleVersion(2);
        promotion.setStackGroup(code);
        return promotion;
    }

    private MarketingOffer comboOffer(String price) {
        MarketingOffer offer = new MarketingOffer();
        offer.setId(70L);
        offer.setOfferCode("COMBO-DUO");
        offer.setOfferKind(MarketingOffer.KIND_COMBO);
        offer.setName("打包优惠");
        offer.setDescription("两张一起买");
        offer.setOwnerType(1);
        offer.setOwnerId(TOPIC_ID);
        offer.setPriceAmount(new BigDecimal(price));
        offer.setListAmount(new BigDecimal("200.00"));
        offer.setStatus(MarketingOffer.STATUS_ENABLED);
        offer.setMaxPerMember(1);
        offer.setRuleVersion(3);
        return offer;
    }

    private MarketingOfferItem item(Long ticketId, int quantity) {
        MarketingOfferItem item = new MarketingOfferItem();
        item.setOfferId(70L);
        item.setItemType(MarketingOfferItem.ITEM_TICKET);
        item.setItemId(ticketId);
        item.setQuantity(quantity);
        item.setUnitListAmount(new BigDecimal("100.00"));
        return item;
    }

    private PlayerBadge badge(Long id, Date obtainTime) {
        PlayerBadge badge = new PlayerBadge();
        badge.setId(id);
        badge.setMemberId(MEMBER);
        badge.setBadgeCode("TOPIC_FINISH_" + TOPIC_ID);
        badge.setSourceId(TOPIC_ID);
        badge.setObtainTime(obtainTime);
        return badge;
    }

    private Date hoursAgo(int hours) {
        return new Date(System.currentTimeMillis() - hours * 3_600_000L);
    }

    private void assertExcluded(MarketingPromotionQuote quote, String reasonCode) {
        for (MarketingExclusion exclusion : quote.getExclusions()) {
            if (reasonCode.equals(exclusion.getReasonCode())) {
                return;
            }
        }
        throw new AssertionError("缺少排除原因 " + reasonCode);
    }

    private void assertMoney(String expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "期望 " + expected + " 实际 " + actual);
    }
}
