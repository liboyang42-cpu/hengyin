package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.MarketingOffer;
import com.chengyinhub.business.domain.MarketingOfferItem;
import com.chengyinhub.business.domain.MarketingPromotion;
import com.chengyinhub.business.domain.MarketingTier;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.exception.MarketingPromotionException;
import com.chengyinhub.business.mapper.MarketingOfferMapper;
import com.chengyinhub.business.mapper.MarketingPromotionMapper;
import com.chengyinhub.business.mapper.MarketingTierMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 促销配置契约:坏配置一律拒写、跨主体票不得组包、写操作必须过对象级范围校验。
 *
 * <p>权限负例在这里是"服务层"的证明;HTTP 层的 403 仍由 {@code @PreAuthorize} 负责,
 * 两者都必须成立 —— 只有菜单权限不足以改别人主题的促销。
 */
class MarketingPromotionConfigServiceImplTest {

    private static final Long TOPIC_ID = 500L;
    private static final Long ACTIVITY_ID = 200L;
    private static final Long TICKET_ID = 11L;

    private MarketingOfferMapper offerMapper;
    private MarketingTierMapper tierMapper;
    private MarketingPromotionMapper promotionMapper;
    private OmsTicketMapper omsTicketMapper;
    private ICmsActivityService activityService;
    private ITopicOperationsScopeAuthorizer scopeAuthorizer;
    private MarketingPromotionConfigServiceImpl service;

    @BeforeEach
    void setUp() {
        offerMapper = mock(MarketingOfferMapper.class);
        tierMapper = mock(MarketingTierMapper.class);
        promotionMapper = mock(MarketingPromotionMapper.class);
        omsTicketMapper = mock(OmsTicketMapper.class);
        activityService = mock(ICmsActivityService.class);
        scopeAuthorizer = mock(ITopicOperationsScopeAuthorizer.class);
        service = new MarketingPromotionConfigServiceImpl(offerMapper, tierMapper, promotionMapper,
                omsTicketMapper, activityService, scopeAuthorizer);
        when(scopeAuthorizer.canEditTopic(TOPIC_ID)).thenReturn(true);
        when(scopeAuthorizer.hasGlobalTopicScope()).thenReturn(true);
        when(omsTicketMapper.selectOmsTicketById(TICKET_ID)).thenReturn(ticket(TOPIC_ID));
        when(offerMapper.insertOffer(any())).thenReturn(1);
        when(tierMapper.insertTier(any())).thenReturn(1);
        when(promotionMapper.insertPromotion(any())).thenReturn(1);
    }

    // ------------------------------------------------------------------ 组合包 / 礼品

    @Test
    void comboPriceAboveTheListSumIsRejected() {
        MarketingPromotionException rejection = assertThrows(MarketingPromotionException.class,
                () -> service.createOffer(combo("260.00"), items(TICKET_ID, 1, "100.00")));
        assertEquals("OFFER_PRICE_ABOVE_LIST", rejection.getReasonCode());
        verify(offerMapper, never()).insertOffer(any());
    }

    @Test
    void comboMemberFromAnotherOwnerIsRejected() {
        when(omsTicketMapper.selectOmsTicketById(TICKET_ID)).thenReturn(ticket(TOPIC_ID + 1));

        MarketingPromotionException rejection = assertThrows(MarketingPromotionException.class,
                () -> service.createOffer(combo("150.00"), items(TICKET_ID, 2, "100.00")));
        assertEquals("TICKET_SCOPE_MISMATCH", rejection.getReasonCode());
    }

    @Test
    void writingWithoutTopicScopeIsForbidden() {
        when(scopeAuthorizer.canEditTopic(TOPIC_ID)).thenReturn(false);

        MarketingPromotionException rejection = assertThrows(MarketingPromotionException.class,
                () -> service.createOffer(combo("150.00"), items(TICKET_ID, 2, "100.00")));
        assertEquals("SCOPE_FORBIDDEN", rejection.getReasonCode());
        verify(offerMapper, never()).insertOffer(any());
    }

    @Test
    void duplicatedOfferCodeIsRejectedBeforeTheDatabase() {
        when(offerMapper.countByCode("DUO-COMBO")).thenReturn(1);

        MarketingPromotionException rejection = assertThrows(MarketingPromotionException.class,
                () -> service.createOffer(combo("150.00"), items(TICKET_ID, 2, "100.00")));
        assertEquals("OFFER_CODE_TAKEN", rejection.getReasonCode());
    }

    @Test
    void createOfferComputesListAmountOnTheServerAndStartsAtVersionOne() {
        service.createOffer(combo("150.00"), items(TICKET_ID, 2, "100.00"));

        ArgumentCaptor<MarketingOffer> captor = ArgumentCaptor.forClass(MarketingOffer.class);
        verify(offerMapper).insertOffer(captor.capture());
        MarketingOffer saved = captor.getValue();
        assertEquals(0, new BigDecimal("200.00").compareTo(saved.getListAmount()),
                "listAmount 必须由成员现价算出,不采信入参");
        assertEquals(Integer.valueOf(1), saved.getRuleVersion());
        assertEquals(Integer.valueOf(0), saved.getDelFlag());
        verify(offerMapper).insertItem(any(MarketingOfferItem.class));
    }

    @Test
    void updateOfferBumpsTheRuleVersionAndKeepsItsIdentity() {
        MarketingOffer existing = combo("150.00");
        existing.setId(70L);
        existing.setRuleVersion(4);
        existing.setOwnerType(1);
        existing.setOwnerId(TOPIC_ID);
        when(offerMapper.selectOfferById(70L)).thenReturn(existing);
        when(offerMapper.updateOffer(any())).thenReturn(1);

        MarketingOffer request = combo("140.00");
        request.setId(70L);
        request.setOfferCode("HIJACKED");
        request.setOwnerId(TOPIC_ID + 1);
        service.updateOffer(request, items(TICKET_ID, 2, "100.00"));

        ArgumentCaptor<MarketingOffer> captor = ArgumentCaptor.forClass(MarketingOffer.class);
        verify(offerMapper).updateOffer(captor.capture());
        assertEquals("DUO-COMBO", captor.getValue().getOfferCode(), "业务码不可通过更新改身份");
        assertEquals(Long.valueOf(TOPIC_ID), captor.getValue().getOwnerId(), "归属不可改");
        assertEquals(Integer.valueOf(5), captor.getValue().getRuleVersion(), "每次改配置必须自增版本");
        verify(offerMapper).deleteItemsByOfferId(70L);
    }

    @Test
    void nonComboOfferKindIsRejected() {
        // 礼品独立售价归 M3 礼品 SKU;本域只允许组合包,别的 kind 一律拒写。
        MarketingOffer bad = combo("130.00");
        bad.setOfferKind(Integer.valueOf(2));

        MarketingPromotionException rejection = assertThrows(MarketingPromotionException.class,
                () -> service.createOffer(bad, items(TICKET_ID, 1, "100.00")));
        assertEquals("OFFER_KIND_INVALID", rejection.getReasonCode());
    }

    // ------------------------------------------------------------------ 阶梯

    @Test
    void overlappingTierRangeIsRejected() {
        MarketingTier existing = tier(1, 3);
        existing.setId(9L);
        existing.setTicketId(TICKET_ID);
        when(tierMapper.selectTierList(any())).thenReturn(Arrays.asList(existing));

        MarketingPromotionException rejection = assertThrows(MarketingPromotionException.class,
                () -> service.createTier(tier(2, 4)));
        assertEquals("TIER_RANGE_OVERLAP", rejection.getReasonCode());
    }

    @Test
    void wildcardTierRangeAlsoConflictsWithSpecificTicketTiers() {
        MarketingTier existing = tier(1, 5);
        existing.setId(9L);
        existing.setTicketId(TICKET_ID);
        when(tierMapper.selectTierList(any())).thenReturn(Arrays.asList(existing));

        MarketingTier candidate = tier(4, 6);
        candidate.setTicketId(MarketingTier.TICKET_ALL);

        MarketingPromotionException rejection = assertThrows(MarketingPromotionException.class,
                () -> service.createTier(candidate));
        assertEquals("TIER_RANGE_OVERLAP", rejection.getReasonCode());
    }

    @Test
    void tierEndBelowStartIsRejected() {
        MarketingPromotionException rejection = assertThrows(MarketingPromotionException.class,
                () -> service.createTier(tier(5, 3)));
        assertEquals("TIER_RANGE_INVALID", rejection.getReasonCode());
    }

    @Test
    void tierCannotReferenceATicketOfAnotherOwner() {
        when(omsTicketMapper.selectOmsTicketById(TICKET_ID)).thenReturn(ticket(TOPIC_ID + 1));

        MarketingPromotionException rejection = assertThrows(MarketingPromotionException.class,
                () -> service.createTier(tier(1, 3)));
        assertEquals("TICKET_SCOPE_MISMATCH", rejection.getReasonCode());
    }

    @Test
    void createTierStartsAtVersionOneAndDefaultsToDisabled() {
        service.createTier(tier(1, 3));

        ArgumentCaptor<MarketingTier> captor = ArgumentCaptor.forClass(MarketingTier.class);
        verify(tierMapper).insertTier(captor.capture());
        assertEquals(Integer.valueOf(1), captor.getValue().getRuleVersion());
        assertEquals(Integer.valueOf(0), captor.getValue().getStatus());
    }

    @Test
    void reCreatingASoftDeletedTierRevivesThatRowInsteadOfInsertingASecondOne() {
        // uk_tier_range 不含 del_flag:软删后同配置重建必须复活原行,不能 insert(会 1062),
        // 也不能因为"删过"就把规则版本重置回 1。
        MarketingTier deleted = tier(1, 3);
        deleted.setId(88L);
        deleted.setDelFlag(2);
        deleted.setRuleVersion(4);
        when(tierMapper.selectTierByNaturalKeyIncludingDeleted(1, TOPIC_ID, TICKET_ID, 1))
                .thenReturn(deleted);
        when(tierMapper.reactivateTier(any())).thenReturn(1);

        MarketingTier request = tier(1, 3);
        request.setUnitPrice(new BigDecimal("85.00"));

        int rows = service.createTier(request);

        assertEquals(1, rows);
        ArgumentCaptor<MarketingTier> captor = ArgumentCaptor.forClass(MarketingTier.class);
        verify(tierMapper).reactivateTier(captor.capture());
        assertEquals(Long.valueOf(88L), captor.getValue().getId(), "复活的是原行,不是新行");
        assertEquals(Integer.valueOf(5), captor.getValue().getRuleVersion(), "版本继续递增,不重置为 1");
        assertEquals(0, new BigDecimal("85.00").compareTo(captor.getValue().getUnitPrice()),
                "复活的行走本次提交的配置,不是旧值");
        verify(tierMapper, never()).insertTier(any());
    }

    @Test
    void revivingATierChecksTheOwnershipOfTheStoredRow() {
        // 复活的必须是调用者有权管理的 owner:归属按旧行再校验,不因为业务键里带了 owner 就跳过。
        MarketingTier foreign = tier(1, 3);
        foreign.setId(89L);
        foreign.setOwnerId(TOPIC_ID + 1);
        foreign.setDelFlag(2);
        when(tierMapper.selectTierByNaturalKeyIncludingDeleted(1, TOPIC_ID, TICKET_ID, 1))
                .thenReturn(foreign);
        when(scopeAuthorizer.canEditTopic(TOPIC_ID + 1)).thenReturn(false);

        MarketingPromotionException rejection = assertThrows(MarketingPromotionException.class,
                () -> service.createTier(tier(1, 3)));

        assertEquals("SCOPE_FORBIDDEN", rejection.getReasonCode());
        verify(tierMapper, never()).reactivateTier(any());
        verify(tierMapper, never()).insertTier(any());
    }

    @Test
    void losingTheConcurrentReviveRaceIsAConflictNotASecondRow() {
        MarketingTier deleted = tier(1, 3);
        deleted.setId(90L);
        deleted.setDelFlag(2);
        deleted.setRuleVersion(1);
        when(tierMapper.selectTierByNaturalKeyIncludingDeleted(1, TOPIC_ID, TICKET_ID, 1))
                .thenReturn(deleted);
        // 并发请求先复活了同一行 ⇒ 这里的条件更新命中 0 行
        when(tierMapper.reactivateTier(any())).thenReturn(0);

        MarketingPromotionException rejection = assertThrows(MarketingPromotionException.class,
                () -> service.createTier(tier(1, 3)));

        assertEquals("TIER_RANGE_OVERLAP", rejection.getReasonCode());
        verify(tierMapper, never()).insertTier(any());
    }

    // ------------------------------------------------------------------ 触发式优惠

    @Test
    void promotionWithoutAnExplicitWindowIsRejected() {
        MarketingPromotion promotion = promotion();
        promotion.setWindowHours(null);

        MarketingPromotionException rejection = assertThrows(MarketingPromotionException.class,
                () -> service.createPromotion(promotion));
        assertEquals("PROMO_WINDOW_REQUIRED", rejection.getReasonCode());
    }

    @Test
    void rateAboveOneIsRejected() {
        MarketingPromotion promotion = promotion();
        promotion.setDiscountType(MarketingPromotion.DISCOUNT_RATE);
        promotion.setDiscountValue(new BigDecimal("1.5"));

        MarketingPromotionException rejection = assertThrows(MarketingPromotionException.class,
                () -> service.createPromotion(promotion));
        assertEquals("PROMO_DISCOUNT_INVALID", rejection.getReasonCode());
    }

    @Test
    void completionPromotionMustBeBoundToATopic() {
        MarketingPromotion promotion = promotion();
        promotion.setScopeType(MarketingPromotion.SCOPE_ALL);
        promotion.setScopeId(null);

        MarketingPromotionException rejection = assertThrows(MarketingPromotionException.class,
                () -> service.createPromotion(promotion));
        assertEquals("PROMO_SCOPE_REQUIRED", rejection.getReasonCode());
    }

    @Test
    void globalPromotionRequiresPlatformScope() {
        MarketingPromotion promotion = firstQuestPromotion();
        when(scopeAuthorizer.hasGlobalTopicScope()).thenReturn(false);

        MarketingPromotionException rejection = assertThrows(MarketingPromotionException.class,
                () -> service.createPromotion(promotion));
        assertEquals("SCOPE_FORBIDDEN", rejection.getReasonCode());
    }

    @Test
    void activityScopedPromotionIsAuthorisedThroughItsTopic() {
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY_ID);
        activity.setTopicId(TOPIC_ID);
        when(activityService.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activity);

        MarketingPromotion promotion = promotion();
        promotion.setTriggerKind(MarketingPromotion.TRIGGER_FIRST_QUEST);
        promotion.setScopeType(MarketingPromotion.SCOPE_ACTIVITY);
        promotion.setScopeId(ACTIVITY_ID);
        service.createPromotion(promotion);

        verify(promotionMapper).insertPromotion(any(MarketingPromotion.class));
    }

    @Test
    void createPromotionDefaultsStackGroupAndStartsAtVersionOne() {
        service.createPromotion(promotion());

        ArgumentCaptor<MarketingPromotion> captor = ArgumentCaptor.forClass(MarketingPromotion.class);
        verify(promotionMapper).insertPromotion(captor.capture());
        assertEquals("DEFAULT", captor.getValue().getStackGroup());
        assertEquals(Integer.valueOf(1), captor.getValue().getRuleVersion());
        assertEquals(Integer.valueOf(0), captor.getValue().getExclusiveFlag());
        assertEquals(Integer.valueOf(1), captor.getValue().getMaxUsesPerMember());
    }

    @Test
    void updatingAGlobalPromotionRequiresTheGlobalScopeEvenWhenTheNewScopeIsOwned() {
        // 只在 normalize 里校验「入参 scope」是不够的:把别人的全局促销 id 填进 PUT,
        // 再把 scope 改成自己能编辑的主题,就能把它改挂到自己名下。
        MarketingPromotion existing = promotion();
        existing.setId(9L);
        existing.setScopeType(MarketingPromotion.SCOPE_ALL);
        existing.setScopeId(null);
        existing.setRuleVersion(2);
        when(promotionMapper.selectPromotionById(9L)).thenReturn(existing);
        when(scopeAuthorizer.hasGlobalTopicScope()).thenReturn(false);

        MarketingPromotion incoming = promotion();
        incoming.setId(9L);
        MarketingPromotionException rejection = assertThrows(MarketingPromotionException.class,
                () -> service.updatePromotion(incoming));

        assertEquals("SCOPE_FORBIDDEN", rejection.getReasonCode());
        verify(promotionMapper, never()).updatePromotion(any(MarketingPromotion.class));
    }

    // ------------------------------------------------------------------ helpers

    private MarketingOffer combo(String price) {
        MarketingOffer offer = new MarketingOffer();
        offer.setOfferCode("DUO-COMBO");
        offer.setOfferKind(MarketingOffer.KIND_COMBO);
        offer.setName("双人组合");
        offer.setOwnerType(1);
        offer.setOwnerId(TOPIC_ID);
        offer.setPriceAmount(new BigDecimal(price));
        offer.setStatus(MarketingOffer.STATUS_DISABLED);
        return offer;
    }

    private MarketingOfferItem item(Long ticketId, int quantity, String unitPrice) {
        MarketingOfferItem item = new MarketingOfferItem();
        item.setItemType(MarketingOfferItem.ITEM_TICKET);
        item.setItemId(ticketId);
        item.setQuantity(quantity);
        item.setUnitListAmount(new BigDecimal(unitPrice));
        return item;
    }

    private ArrayList<MarketingOfferItem> items(Long ticketId, int quantity, String unitPrice) {
        return new ArrayList<>(Arrays.asList(item(ticketId, quantity, unitPrice)));
    }

    private OmsTicket ticket(Long ownerId) {
        OmsTicket ticket = new OmsTicket();
        ticket.setId(TICKET_ID);
        ticket.setOwnerType(1L);
        ticket.setOwnerId(ownerId);
        ticket.setDelFlag(0);
        ticket.setPrice(new BigDecimal("100.00"));
        return ticket;
    }

    private MarketingTier tier(int min, Integer max) {
        MarketingTier tier = new MarketingTier();
        tier.setOwnerType(1);
        tier.setOwnerId(TOPIC_ID);
        tier.setTicketId(TICKET_ID);
        tier.setMinQuantity(min);
        tier.setMaxQuantity(max);
        tier.setUnitPrice(new BigDecimal("90.00"));
        return tier;
    }

    private MarketingPromotion promotion() {
        MarketingPromotion promotion = new MarketingPromotion();
        promotion.setPromoCode("AFTER48");
        promotion.setName("完局限时");
        promotion.setTriggerKind(MarketingPromotion.TRIGGER_TOPIC_COMPLETION);
        promotion.setScopeType(MarketingPromotion.SCOPE_TOPIC);
        promotion.setScopeId(TOPIC_ID);
        promotion.setDiscountType(MarketingPromotion.DISCOUNT_FIXED_AMOUNT);
        promotion.setDiscountValue(new BigDecimal("30.00"));
        promotion.setWindowHours(48);
        return promotion;
    }

    private MarketingPromotion firstQuestPromotion() {
        MarketingPromotion promotion = promotion();
        promotion.setPromoCode("FIRST_QUEST");
        promotion.setTriggerKind(MarketingPromotion.TRIGGER_FIRST_QUEST);
        promotion.setScopeType(MarketingPromotion.SCOPE_ALL);
        promotion.setScopeId(null);
        return promotion;
    }
}
