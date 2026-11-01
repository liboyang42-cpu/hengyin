package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.SettlementRule;
import com.chengyinhub.business.domain.dto.PricingResult;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.mapper.CoopPerkMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.impl.PricingServiceImpl;
import com.chengyinhub.business.service.impl.TopicSaleFinalizationServiceImpl;
import com.chengyinhub.business.service.support.CommercialReviewStatusSource;
import com.chengyinhub.business.service.support.CommercialTopicPolicy;
import com.chengyinhub.business.service.support.CoopOrderLineupSource;
import com.chengyinhub.business.service.support.MerchantPublishedTopicTransferGate;
import com.chengyinhub.business.service.support.PendingRecruitWorkflowTerminator;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.controller.api.ApiPricingController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SC-FREE-PRICE 验收:自由探索主题(FREE_EXPLORE)在定价阶段「确认终价锁价」。
 *
 * <ul>
 *   <li>锁定快照 = 草稿:selfPlayPrice / 有人带票价与库存 / 费率快照逐字段落库,锁价不改 lifecycle;</li>
 *   <li>缺条件按**原话**拒绝且零写入:非定价阶段、未填票价、必选章无生效供给、低于底价、计酬必亏;</li>
 *   <li>非发布者在控制器层即被拒,不触达定价服务;</li>
 *   <li>玩家报价(活动投影票)与主题供给票价不一致时开售闸拒绝。</li>
 * </ul>
 *
 * <p>底价口径(本类固定阵容):1 家非 club 固定费 15 的合作单 ⇒ maxN=1 ⇒ 保底率 0.05+0.01×1=0.05(取规则行);
 * 平台率 0.12 ⇒ 分母 0.83。self 底价 = ceil(15/0.83)=18.08;guided(带队成本 100/4 人=25)= ceil(40/0.83)=48.20。
 */
class FreeExplorePriceLockAcceptanceTest {

    private static final Long TOPIC_ID = 7701L;
    private static final Long OWNER_ID = 900001L;

    private CmsTopicMapper cmsTopicMapper;
    private CoopOrderMapper coopOrderMapper;
    private OmsTicketMapper ticketMapper;
    private MerchantChapterOfferMapper offerMapper;
    private ISettlementRuleService ruleService;
    private PricingServiceImpl service;

    @BeforeEach
    void setUp() {
        cmsTopicMapper = mock(CmsTopicMapper.class);
        coopOrderMapper = mock(CoopOrderMapper.class);
        ticketMapper = mock(OmsTicketMapper.class);
        offerMapper = mock(MerchantChapterOfferMapper.class);
        ruleService = mock(ISettlementRuleService.class);
        service = new PricingServiceImpl();
        ReflectionTestUtils.setField(service, "cmsTopicMapper", cmsTopicMapper);
        ReflectionTestUtils.setField(service, "coopOrderMapper", coopOrderMapper);
        ReflectionTestUtils.setField(service, "coopPerkMapper", mock(CoopPerkMapper.class));
        ReflectionTestUtils.setField(service, "offerMapper", offerMapper);
        ReflectionTestUtils.setField(service, "settlementRuleService", ruleService);
        ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);

        // 规则行刻意与硬编码兜底(0.10/0.05/0.02/5)不同,证明快照取自规则行而非常量。
        SettlementRule rule = new SettlementRule();
        rule.setPlatformCommissionRate(new BigDecimal("0.12"));
        rule.setGuaranteeBaseRate(new BigDecimal("0.04"));
        rule.setGuaranteeStepRate(new BigDecimal("0.01"));
        rule.setGuaranteeMaxMerchant(3);
        when(ruleService.getActiveRule(0L, "topic")).thenReturn(rule);

        CoopOrder fixed = new CoopOrder();
        fixed.setShareMode(2);
        fixed.setFixedFee(new BigDecimal("15"));
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(TOPIC_ID)).thenReturn(Collections.singletonList(fixed));
        when(offerMapper.countRequiredChaptersWithoutLiveOffer(TOPIC_ID)).thenReturn(0);
        when(ticketMapper.selectOmsTicketList(any(OmsTicket.class))).thenReturn(Collections.emptyList());
    }

    private CmsTopic freeExploreTopic(Integer lifecycle) {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setMemberId(OWNER_ID);
        topic.setProductType(ProductType.FREE_EXPLORE);
        topic.setLifecycle(lifecycle);
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(topic);
        return topic;
    }

    private void assertNoWrite() {
        verify(cmsTopicMapper, never()).updateCmsTopic(any(CmsTopic.class));
        verify(ticketMapper, never()).insertOmsTicket(any(OmsTicket.class));
        verify(ticketMapper, never()).updateOmsTicket(any(OmsTicket.class));
    }

    // ------------------------------------------------------------------ (a) 锁定快照 = 草稿

    @Test
    void confirm_persistsExactlyTheDraftPricesInventoryAndFeeSnapshot_withoutTouchingLifecycle() {
        CmsTopic topic = freeExploreTopic(2);

        service.confirmPrice(TOPIC_ID, new BigDecimal("128.00"), new BigDecimal("66.60"), 37,
                new BigDecimal("100"), 4);

        ArgumentCaptor<CmsTopic> topicWrite = ArgumentCaptor.forClass(CmsTopic.class);
        verify(cmsTopicMapper).updateCmsTopic(topicWrite.capture());
        CmsTopic upd = topicWrite.getValue();
        assertEquals(TOPIC_ID, upd.getId());
        assertEquals(Integer.valueOf(1), upd.getSelfPlay());
        assertEquals(new BigDecimal("66.60"), upd.getSelfPlayPrice(), "无人带终价必须原样落 cms_topic.self_play_price");
        assertEquals(new BigDecimal("0.12"), upd.getFeePlatformRate());
        assertEquals(new BigDecimal("0.04"), upd.getFeeGuaranteeBaseRate());
        assertEquals(new BigDecimal("0.01"), upd.getFeeGuaranteeStepRate());
        assertEquals(Integer.valueOf(3), upd.getFeeGuaranteeMaxMerchant());
        assertNull(upd.getLifecycle(), "锁价只到 PRICING,不得顺手推进 lifecycle");
        assertEquals(Integer.valueOf(2), topic.getLifecycle());
        verify(cmsTopicMapper, never()).finalizeForSale(anyLong(), any(), any());

        ArgumentCaptor<OmsTicket> ticketWrite = ArgumentCaptor.forClass(OmsTicket.class);
        verify(ticketMapper).insertOmsTicket(ticketWrite.capture());
        OmsTicket guided = ticketWrite.getValue();
        assertEquals(new BigDecimal("128.00"), guided.getPrice(), "有人带终价必须原样落 oms_ticket.price");
        assertEquals(Long.valueOf(37), guided.getTotalInventory());
        assertEquals(Long.valueOf(37), guided.getRemainingInventory());
        assertEquals(Long.valueOf(1), guided.getOwnerType());
        assertEquals(TOPIC_ID, guided.getOwnerId());
        assertEquals("guided", guided.getSubType());
        assertEquals(OWNER_ID, guided.getMemberId());
        assertEquals(ProductType.toTicketMode(ProductType.FREE_EXPLORE), guided.getMode(),
                "开售路径插的票 mode 必须跟主题 product_type,不能造混票");
        verify(ticketMapper, never()).updateOmsTicket(any(OmsTicket.class));
    }

    @Test
    void confirm_overwritesExistingGuidedTicketWithDraftPriceAndInventory() {
        freeExploreTopic(2);
        OmsTicket existing = new OmsTicket();
        existing.setId(5501L);
        existing.setPrice(new BigDecimal("999"));
        when(ticketMapper.selectOmsTicketList(any(OmsTicket.class))).thenReturn(Collections.singletonList(existing));
        when(ticketMapper.updateOmsTicket(any(OmsTicket.class))).thenReturn(1);

        service.confirmPrice(TOPIC_ID, new BigDecimal("88.80"), null, 12, new BigDecimal("100"), 4);

        ArgumentCaptor<OmsTicket> ticketWrite = ArgumentCaptor.forClass(OmsTicket.class);
        verify(ticketMapper).updateOmsTicket(ticketWrite.capture());
        assertEquals(Long.valueOf(5501L), ticketWrite.getValue().getId());
        assertEquals(new BigDecimal("88.80"), ticketWrite.getValue().getPrice());
        assertEquals(Long.valueOf(12), ticketWrite.getValue().getTotalInventory());
        assertEquals(Long.valueOf(12), ticketWrite.getValue().getRemainingInventory());
        verify(ticketMapper, never()).insertOmsTicket(any(OmsTicket.class));
        ArgumentCaptor<CmsTopic> topicWrite = ArgumentCaptor.forClass(CmsTopic.class);
        verify(cmsTopicMapper).updateCmsTopic(topicWrite.capture());
        assertNull(topicWrite.getValue().getSelfPlayPrice(), "只填有人带时不得改写自玩价");
    }

    @Test
    void confirm_priceExactlyAtFloorIsAccepted() {
        freeExploreTopic(2);

        service.confirmPrice(TOPIC_ID, new BigDecimal("48.20"), new BigDecimal("18.08"), null,
                new BigDecimal("100"), 4);

        verify(cmsTopicMapper).updateCmsTopic(any(CmsTopic.class));
        ArgumentCaptor<OmsTicket> ticketWrite = ArgumentCaptor.forClass(OmsTicket.class);
        verify(ticketMapper).insertOmsTicket(ticketWrite.capture());
        assertEquals(Long.valueOf(100), ticketWrite.getValue().getTotalInventory(), "未填库存按 100 兜底");
    }

    // ------------------------------------------------------------------ (b) 缺条件原话拒绝 + 零写入

    @Test
    void refuse_notInPricingLifecycle() {
        for (Integer lifecycle : new Integer[] {null, 1, 3}) {
            freeExploreTopic(lifecycle);
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> service.confirmPrice(TOPIC_ID, null, new BigDecimal("66.60"), null, null, null));
            assertEquals("主题未在定价阶段,不能确认终价", e.getMessage(), "lifecycle=" + lifecycle);
        }
        assertNoWrite();
    }

    @Test
    void refuse_noPriceAtAll() {
        freeExploreTopic(2);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.confirmPrice(TOPIC_ID, null, null, 10, null, null));
        assertEquals("请至少填写一种票价", e.getMessage());
        assertNoWrite();
    }

    @Test
    void refuse_requiredChapterWithoutLiveSupply() {
        freeExploreTopic(2);
        when(offerMapper.countRequiredChaptersWithoutLiveOffer(TOPIC_ID)).thenReturn(1);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.confirmPrice(TOPIC_ID, new BigDecimal("128.00"), new BigDecimal("66.60"), 37,
                        new BigDecimal("100"), 4));
        assertEquals("每个必选章至少需要 1 家生效商家供给,暂不能锁价开售", e.getMessage());
        assertNoWrite();
    }

    @Test
    void refuse_selfPriceBelowFloorNamesTheFloor() {
        freeExploreTopic(2);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.confirmPrice(TOPIC_ID, null, new BigDecimal("18.07"), null, null, null));
        assertEquals("无人带票价不能低于底价 18.08", e.getMessage());
        assertNoWrite();
    }

    @Test
    void refuse_guidedPriceBelowFloorNamesTheFloor() {
        freeExploreTopic(2);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.confirmPrice(TOPIC_ID, new BigDecimal("48.19"), new BigDecimal("66.60"), 37,
                        new BigDecimal("100"), 4));
        assertEquals("有人带票价不能低于底价 48.20", e.getMessage());
        assertNoWrite();
    }

    @Test
    void refuse_revshareCostNotBelowPrice() {
        freeExploreTopic(2);
        when(offerMapper.sumMaxPerHeadFeeByRequiredChapters(TOPIC_ID)).thenReturn(new BigDecimal("70"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.confirmPrice(TOPIC_ID, null, new BigDecimal("66.60"), null, null, null));
        assertEquals("无人带票价 66.60 不高于计酬成本上界 70:玩家全部通关时,每个必选章节最贵的计酬商家都要付一次人头费,"
                + "合计 70 ≥ 票价 ⇒ 每张票必亏。请提高票价、降低人头费、或把部分章节改成权益档/引流档。", e.getMessage());
        assertNoWrite();
    }

    @Test
    void preview_requiredChapterWithoutLiveSupply_cannotConfirmWithTheSameReasonAsConfirm() {
        CmsTopic topic = freeExploreTopic(2);
        when(cmsTopicMapper.selectCmsTopicLifecycleById(TOPIC_ID)).thenReturn(topic);
        when(cmsTopicMapper.selectProductTypeById(TOPIC_ID)).thenReturn(ProductType.FREE_EXPLORE);
        when(offerMapper.countRequiredChaptersWithoutLiveOffer(TOPIC_ID)).thenReturn(1);

        PricingResult preview = service.previewTopic(TOPIC_ID, null, null, "self");
        IllegalStateException confirm = assertThrows(IllegalStateException.class,
                () -> service.confirmPrice(TOPIC_ID, null, new BigDecimal("66.60"), null, null, null));

        assertEquals(Boolean.FALSE, preview.getCanConfirm(), "预览与锁价必须同一判据:缺必选章供给时不可确认");
        assertEquals(confirm.getMessage(), preview.getConfirmBlockReason());
        assertEquals("每个必选章至少需要 1 家生效商家供给,暂不能锁价开售", preview.getConfirmBlockReason());
        assertNoWrite();
    }

    @Test
    void preview_pricingStageWithLiveSupply_canConfirmWithoutReason() {
        CmsTopic topic = freeExploreTopic(2);
        when(cmsTopicMapper.selectCmsTopicLifecycleById(TOPIC_ID)).thenReturn(topic);
        when(cmsTopicMapper.selectProductTypeById(TOPIC_ID)).thenReturn(ProductType.FREE_EXPLORE);

        PricingResult preview = service.previewTopic(TOPIC_ID, null, null, "self");

        assertEquals(Boolean.TRUE, preview.getCanConfirm());
        assertNull(preview.getConfirmBlockReason());
    }

    @Test
    void preview_notInPricingLifecycle_reportsTheConfirmReason() {
        CmsTopic topic = freeExploreTopic(3);
        when(cmsTopicMapper.selectCmsTopicLifecycleById(TOPIC_ID)).thenReturn(topic);
        when(cmsTopicMapper.selectProductTypeById(TOPIC_ID)).thenReturn(ProductType.FREE_EXPLORE);

        PricingResult preview = service.previewTopic(TOPIC_ID, null, null, "self");

        assertEquals(Boolean.FALSE, preview.getCanConfirm());
        assertEquals("主题未在定价阶段,不能确认终价", preview.getConfirmBlockReason());
    }

    // ------------------------------------------------------------------ (c) 控制器:归属 + 原话透传

    private ApiPricingController controller(Long caller, IPricingService pricing) {
        return controller(caller, pricing, true);
    }

    private ApiPricingController controller(Long caller, IPricingService pricing, boolean ownerRegistered) {
        ApiPricingController controller = spy(new ApiPricingController());
        com.chengyinhub.business.service.impl.MemberIdentityService identity =
                mock(com.chengyinhub.business.service.impl.MemberIdentityService.class);
        when(identity.isRegistered(OWNER_ID)).thenReturn(ownerRegistered);
        ReflectionTestUtils.setField(controller, "memberIdentityService", identity);
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setMemberId(OWNER_ID);
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);
        ReflectionTestUtils.setField(controller, "pricingService", pricing);
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        doReturn(caller).when(controller).getAppUserId();
        return controller;
    }

    private static Map<String, Object> body() {
        Map<String, Object> body = new HashMap<>();
        body.put("topicId", TOPIC_ID);
        body.put("guidedPrice", "128.00");
        body.put("selfPrice", "66.60");
        body.put("guidedInventory", 37);
        body.put("leadCost", "100");
        body.put("teamSize", 4);
        return body;
    }

    @Test
    void controller_nonOwnerRefusedBeforeAnyServiceCall() {
        IPricingService pricing = mock(IPricingService.class);
        ApiPricingController controller = controller(OWNER_ID + 1, pricing);

        AjaxResult confirm = controller.confirm(body());
        AjaxResult preview = controller.preview(body());

        assertEquals(500, confirm.get(AjaxResult.CODE_TAG));
        assertEquals("仅主题发布者可定价", confirm.get(AjaxResult.MSG_TAG));
        assertEquals("仅主题发布者可定价", preview.get(AjaxResult.MSG_TAG));
        verifyNoInteractions(pricing);
    }

    @Test
    void controller_ownerConfirmPassesDraftThroughUnchanged() {
        IPricingService pricing = mock(IPricingService.class);
        ApiPricingController controller = controller(OWNER_ID, pricing);

        AjaxResult result = controller.confirm(body());

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(pricing).confirmPrice(eq(TOPIC_ID), eq(new BigDecimal("128.00")), eq(new BigDecimal("66.60")),
                eq(37), eq(new BigDecimal("100")), eq(4));
    }

    @Test
    void controller_ownerSeesTheExactRefusalReasonFromTheRealService() {
        freeExploreTopic(2);
        when(offerMapper.countRequiredChaptersWithoutLiveOffer(TOPIC_ID)).thenReturn(2);
        ApiPricingController controller = controller(OWNER_ID, service);

        AjaxResult result = controller.confirm(body());

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("每个必选章至少需要 1 家生效商家供给,暂不能锁价开售", result.get(AjaxResult.MSG_TAG));
        assertNoWrite();
    }

    /**
     * 2026-10-31:招商中的主题可以先不定价(发布时票价 0 ⇒ 免费豁免实名);终价收费时必须补上实名闸,
     * 否则「先发免费、后定收费」绕过发布者实名。
     */
    @Test
    void controller_paidFinalPriceRequiresPublisherIdentity() {
        IPricingService pricing = mock(IPricingService.class);
        ApiPricingController controller = controller(OWNER_ID, pricing, false);

        AjaxResult result = controller.confirm(body());

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("收费主题要先登记发布者实名(姓名与身份证号),再确认终价", result.get(AjaxResult.MSG_TAG));
        verifyNoInteractions(pricing);
    }

    @Test
    void controller_freeFinalPriceStaysExemptFromIdentity() {
        IPricingService pricing = mock(IPricingService.class);
        ApiPricingController controller = controller(OWNER_ID, pricing, false);
        Map<String, Object> free = body();
        free.put("guidedPrice", "0");
        free.put("selfPrice", "0.00");

        AjaxResult result = controller.confirm(free);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(pricing).confirmPrice(eq(TOPIC_ID), eq(new BigDecimal("0")), eq(new BigDecimal("0.00")),
                eq(37), eq(new BigDecimal("100")), eq(4));
    }

    // ------------------------------------------------------------------ (d) 玩家报价 = 供给票价

    @Test
    void saleGate_playerFacingProjectionMustCarryTheLockedPrice() {
        @SuppressWarnings("unchecked")
        ObjectProvider<PendingRecruitWorkflowTerminator> provider = mock(ObjectProvider.class);
        TopicSaleFinalizationServiceImpl finalization = new TopicSaleFinalizationServiceImpl(
                mock(CmsTopicMapper.class), mock(OmsTicketMapper.class), mock(CmsTopicChapterMapper.class),
                mock(MerchantChapterOfferMapper.class), mock(CoopOrderLineupSource.class), provider,
                mock(CommercialTopicPolicy.class), mock(CommercialReviewStatusSource.class),
                mock(MerchantPublishedTopicTransferGate.class));
        OmsTicket locked = new OmsTicket();
        locked.setName("有人带");
        locked.setMode(ProductType.toTicketMode(ProductType.FREE_EXPLORE));
        locked.setSubType("guided");
        locked.setPrice(new BigDecimal("128.00"));
        locked.setTotalInventory(37L);
        OmsTicket projected = new OmsTicket();
        projected.setName("有人带");
        projected.setMode(ProductType.toTicketMode(ProductType.FREE_EXPLORE));
        projected.setSubType("guided");
        projected.setTotalInventory(37L);

        projected.setPrice(new BigDecimal("128")); // 同值不同 scale:玩家报价与锁价一致,放行
        ReflectionTestUtils.invokeMethod(finalization, "assertTicketProjection", locked, projected, new CmsActivity());

        projected.setPrice(new BigDecimal("99.00")); // 玩家看到的价 ≠ 锁定价:开售闸拒绝
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(finalization, "assertTicketProjection", locked, projected, new CmsActivity()));
        assertEquals("SALE_TICKET_PROJECTION_MISMATCH_PRICE", e.getMessage());
    }
}
